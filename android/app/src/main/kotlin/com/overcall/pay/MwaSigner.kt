package com.overcall.pay

import android.net.Uri
import android.util.Log
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.publickey.SolanaPublicKey

/**
 * Mobile Wallet Adapter wrapper. Exposes the operations OverCall actually
 * needs — connect, sign+send — and hides the verbose adapter API.
 *
 * Connection model: `transact { authResult -> ... }` is a single round-trip
 * that pops the wallet picker UI, signs whatever you ask for inside the
 * lambda, and returns. No long-lived "session" object — each high-level
 * operation re-invokes transact.
 *
 * The methods take an [ActivityResultSender] rather than the activity
 * itself. The sender's constructor calls `registerForActivityResult`,
 * which the AndroidX lifecycle requires to happen *before* the activity
 * reaches STARTED — so each Activity is responsible for constructing
 * the sender as a field initialized in `onCreate`, and then passing it
 * through to MwaSigner. Constructing it lazily inside a click handler
 * crashes with "Lifecycle owners must register before they are started."
 */
class MwaSigner(
    blockchain: Blockchain = Solana.Devnet,
    identityName: String = "OverCall",
    identityUri: Uri = Uri.parse("https://overcall.app"),
    iconUri: Uri = Uri.parse("favicon.ico"),
    sessionTimeoutMs: Int = SESSION_TIMEOUT_MS,
) {
    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = identityUri,
            iconUri = iconUri,
            identityName = identityName,
        ),
        timeout = sessionTimeoutMs,
    ).also {
        it.blockchain = blockchain
    }

    /**
     * Forget any cached MWA auth token. Next [connect] will issue a
     * fresh authorize request (wallet will re-prompt the user). Used
     * by the home-screen "Disconnect Wallet" button and by the auth-
     * retry path in [signAndSend] when a wallet rejects a stale token.
     */
    fun disconnect() {
        adapter.authToken = null
    }

    /**
     * Pop the wallet picker, request authorization, return a [WalletInfo]
     * carrying the authorized pubkey + the wallet's `walletUriBase` (used
     * to recognize the Seed Vault wallet on Seeker). Returns null if the
     * user cancels, no wallet is installed, or authorization fails.
     */
    suspend fun connect(sender: ActivityResultSender): WalletInfo? {
        val result = adapter.transact(sender) { authResult ->
            val pk = authResult.accounts.firstOrNull()?.publicKey
            val uri = authResult.walletUriBase
            Pair(pk, uri)
        }
        return when (result) {
            is TransactionResult.Success -> {
                val (pkBytes, uri) = result.payload ?: return null
                if (pkBytes == null) return null
                WalletInfo(
                    pubkey = SolanaPublicKey(pkBytes),
                    walletUriBase = uri,
                    hardwareBacked = SeedVaultDetector.isLikelySeedVault(uri),
                )
            }
            is TransactionResult.NoWalletFound -> {
                Log.w(TAG, "connect: NoWalletFound — no MWA-capable wallet installed")
                null
            }
            is TransactionResult.Failure -> {
                Log.w(TAG, "connect: Failure — ${result.e.javaClass.simpleName}: ${result.e.message}", result.e)
                null
            }
        }
    }

    /**
     * Sign and submit pre-built transactions atomically. Returns the
     * signatures (one per input transaction) on success; null on user
     * cancel or wallet failure.
     */
    suspend fun signAndSend(
        sender: ActivityResultSender,
        transactions: List<ByteArray>,
    ): List<ByteArray>? {
        if (com.overcall.BuildConfig.DEBUG) {
            transactions.forEachIndexed { i, tx ->
                Log.d(TAG, "tx[$i] (${tx.size}B): ${tx.joinToString("") { "%02x".format(it) }}")
            }
        }
        val first = doSignAndSend(sender, transactions)
        // If the wallet rejected our cached auth token (user reinstalled the
        // wallet, wallet rotated keys, or in dev/emulator: `pm clear` between
        // sessions), drop the cached token and retry once. The retry forces
        // a fresh authorize prompt — the user re-confirms in the wallet UI,
        // we get a new token, and signAndSend completes.
        if (first is SendOutcome.AuthInvalid) {
            Log.w(TAG, "signAndSend: auth_token rejected — clearing cached token and retrying with fresh authorize")
            adapter.authToken = null
            val second = doSignAndSend(sender, transactions)
            return (second as? SendOutcome.Ok)?.signatures
        }
        return (first as? SendOutcome.Ok)?.signatures
    }

    private sealed interface SendOutcome {
        data class Ok(val signatures: List<ByteArray>) : SendOutcome
        object AuthInvalid : SendOutcome
        object Other : SendOutcome
    }

    private suspend fun doSignAndSend(
        sender: ActivityResultSender,
        transactions: List<ByteArray>,
    ): SendOutcome {
        val result = adapter.transact(sender) {
            signAndSendTransactions(transactions.toTypedArray()).signatures
        }
        return when (result) {
            is TransactionResult.Success -> {
                val payload = result.payload
                if (payload != null) SendOutcome.Ok(payload.toList()) else SendOutcome.Other
            }
            is TransactionResult.NoWalletFound -> {
                Log.w(TAG, "signAndSend: NoWalletFound — wallet vanished between connect and submit?")
                SendOutcome.Other
            }
            is TransactionResult.Failure -> {
                val isAuthInvalid = isAuthTokenInvalid(result.e)
                if (isAuthInvalid) {
                    Log.w(TAG, "signAndSend: Failure — wallet rejected auth_token (will retry once)", result.e)
                    SendOutcome.AuthInvalid
                } else {
                    Log.w(TAG, "signAndSend: Failure — ${result.e.javaClass.simpleName}: ${result.e.message}", result.e)
                    SendOutcome.Other
                }
            }
        }
    }

    // Walk the cause chain looking for the JSON-RPC remote error that
    // means "your auth_token is invalid" — the message is stable (server
    // emits it from MobileWalletAdapterServer / BaseScenario.doReauthorize)
    // but the exception type lives in another package, so match on text.
    private fun isAuthTokenInvalid(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val msg = cur.message ?: ""
            if (msg.contains("auth_token not valid")) return true
            cur = cur.cause
        }
        return false
    }

    companion object {
        private const val TAG = "OverCall/MwaSigner"

        // MWA's default per-session timeout is 90s. That's tight when
        // signAndSendTransactions also waits for the wallet to broadcast
        // to devnet/mainnet (slow RPC + emulator NAT can push the
        // round-trip past 90s). 5 min is generous in production (no real
        // user takes that long to tap Authorize) and gives emulator-based
        // UI automation enough headroom to capture screenshots between
        // taps without losing the session.
        private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000
    }
}

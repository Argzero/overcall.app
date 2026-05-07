package com.overcall.pay

import android.util.Log
import com.overcall.attestation.AttestationResult
import com.overcall.attestation.KeystoreAttestor
import com.overcall.config.OverCallConfig
import com.overcall.registry.PhoneRegistryProgram
import com.overcall.registry.RegistryClient
import com.overcall.registry.SolanaRpc
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction

/**
 * Orchestrator for the on-chain registration flow:
 *
 *   1. Generate a hardware-backed Android Keystore key attestation
 *      binding the wallet pubkey to the SIM-read E.164. The on-chain
 *      register instruction rejects any zero-hash so this step is
 *      non-optional.
 *   2. Fetch the live treasury (so a set_treasury rotation doesn't
 *      break us) and the latest blockhash.
 *   3. Build register_with_sol_fee with the attestation hash + kind.
 *   4. Compile to LegacyMessage, wrap in unsigned Transaction.
 *   5. Hand to MWA — wallet signs + submits.
 *   6. Return signature + the AttestationResult (caller may want to
 *      persist the cert chain locally for later off-chain verification).
 */
class RegisterFlow(
    private val rpc: SolanaRpc,
    private val mwa: MwaSigner,
    private val registry: RegistryClient = RegistryClient(rpc),
    private val attestor: KeystoreAttestor = KeystoreAttestor(),
) {
    sealed interface Result {
        data class Success(
            val signature: ByteArray,
            val phoneE164: String,
            val attestation: AttestationResult,
        ) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun registerWithSolFee(
        sender: ActivityResultSender,
        owner: SolanaPublicKey,
        phoneE164: String,
        acceptedMints: List<SolanaPublicKey> = OverCallConfig.DEFAULT_ACCEPTED,
        preferredReceive: SolanaPublicKey = OverCallConfig.DUSDC,
        flags: UInt = 0u,
    ): Result {
        Log.i(TAG, "registerWithSolFee: enter (phone=$phoneE164, owner=${owner.base58().take(8)}…)")
        val attestation = try {
            attestor.attest(phoneE164, owner)
        } catch (t: Throwable) {
            Log.e(TAG, "Keystore attestation failed", t)
            return Result.Failure(
                "Hardware attestation failed: ${t.message ?: t::class.simpleName}. " +
                    "This device may not have a hardware-backed Keystore.",
            )
        }
        Log.i(TAG, "registerWithSolFee: attestation ok (kind=${attestation.kind})")

        val treasury = try {
            registry.fetchConfig().treasury
        } catch (t: Throwable) {
            Log.e(TAG, "fetchConfig failed", t)
            return Result.Failure("RegistryConfig fetch failed: ${t.message ?: t::class.simpleName}")
        }
        Log.i(TAG, "registerWithSolFee: fetchConfig ok (treasury=${treasury.base58().take(8)}…)")
        val blockhashBase58 = try {
            rpc.getLatestBlockhash()
        } catch (t: Throwable) {
            Log.e(TAG, "getLatestBlockhash failed", t)
            return Result.Failure("RPC blockhash fetch failed: ${t.message ?: t::class.simpleName}")
        }
        Log.i(TAG, "registerWithSolFee: blockhash ok (${blockhashBase58.take(8)}…)")
        val recentBlockhash = SolanaPublicKey.from(blockhashBase58)

        val ix = PhoneRegistryProgram.registerWithSolFee(
            phoneE164 = phoneE164,
            owner = owner,
            treasury = treasury,
            attestationHash = attestation.hash,
            attestationKind = attestation.kind,
            acceptedMints = acceptedMints,
            preferredReceive = preferredReceive,
            flags = flags,
        )

        val message = MessageBuilder.buildLegacyMessage(
            feePayer = owner,
            instructions = listOf(ix),
            recentBlockhash = recentBlockhash,
        )
        val txBytes = Transaction(message).serialize()

        Log.i(TAG, "registerWithSolFee: about to call mwa.signAndSend (txBytes=${txBytes.size}B)")
        val sigs = mwa.signAndSend(sender, listOf(txBytes))
            ?: return Result.Failure("Wallet rejected or no wallet installed")
        Log.i(TAG, "registerWithSolFee: mwa.signAndSend returned ${sigs.size} sig(s)")
        val sig = sigs.firstOrNull()
            ?: return Result.Failure("Wallet returned no signature")

        return Result.Success(sig, phoneE164, attestation)
    }

    companion object {
        private const val TAG = "RegisterFlow"
    }
}

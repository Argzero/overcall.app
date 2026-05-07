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
        val attestation = try {
            attestor.attest(phoneE164, owner)
        } catch (t: Throwable) {
            Log.e(TAG, "Keystore attestation failed", t)
            return Result.Failure(
                "Hardware attestation failed: ${t.message ?: t::class.simpleName}. " +
                    "This device may not have a hardware-backed Keystore.",
            )
        }

        val treasury = try {
            registry.fetchConfig().treasury
        } catch (t: Throwable) {
            return Result.Failure("RegistryConfig fetch failed: ${t.message ?: t::class.simpleName}")
        }
        val blockhashBase58 = try {
            rpc.getLatestBlockhash()
        } catch (t: Throwable) {
            return Result.Failure("RPC blockhash fetch failed: ${t.message ?: t::class.simpleName}")
        }
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

        val sigs = mwa.signAndSend(sender, listOf(txBytes))
            ?: return Result.Failure("Wallet rejected or no wallet installed")
        val sig = sigs.firstOrNull()
            ?: return Result.Failure("Wallet returned no signature")

        return Result.Success(sig, phoneE164, attestation)
    }

    companion object {
        private const val TAG = "RegisterFlow"
    }
}

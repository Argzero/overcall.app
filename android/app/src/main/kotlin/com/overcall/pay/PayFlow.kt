package com.overcall.pay

import com.overcall.config.OverCallConfig
import com.overcall.registry.PhoneRecord
import com.overcall.registry.PhoneRegistryProgram
import com.overcall.registry.RegistryClient
import com.overcall.registry.SolanaRpc
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction

/**
 * Orchestrator for in-call SOL payments. Resolves the called E.164 to a
 * PhoneRecord, builds a pay_sol instruction (program enforces the additive
 * 0.01% fee skim), and signs+sends through MWA.
 */
class PayFlow(
    private val rpc: SolanaRpc,
    private val mwa: MwaSigner,
    private val registry: RegistryClient = RegistryClient(rpc),
) {
    sealed interface Result {
        data class Success(val signature: ByteArray) : Result
        data class NotRegistered(val phoneE164: String) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Look up the recipient by E.164 without sending. Returns null when no
     * PhoneRecord exists.
     */
    suspend fun resolve(phoneE164: String): PhoneRecord? = registry.lookupByPhone(phoneE164)

    suspend fun paySol(
        resultSender: ActivityResultSender,
        sender: SolanaPublicKey,
        phoneE164: String,
        amountLamports: Long,
    ): Result {
        val record = registry.lookupByPhone(phoneE164)
            ?: return Result.NotRegistered(phoneE164)

        // Fetch live treasury so set_treasury rotation doesn't break us.
        val treasury = try {
            registry.fetchConfig().treasury
        } catch (t: Throwable) {
            return Result.Failure("RegistryConfig fetch failed: ${t.message ?: t::class.simpleName}")
        }

        val blockhash = try {
            SolanaPublicKey.from(rpc.getLatestBlockhash())
        } catch (t: Throwable) {
            return Result.Failure("RPC blockhash fetch failed: ${t.message ?: t::class.simpleName}")
        }

        val ix = PhoneRegistryProgram.paySol(
            phoneE164 = phoneE164,
            sender = sender,
            recipient = record.owner,
            treasury = treasury,
            amountLamports = amountLamports,
        )
        val message = MessageBuilder.buildLegacyMessage(
            feePayer = sender,
            instructions = listOf(ix),
            recentBlockhash = blockhash,
        )
        val txBytes = Transaction(message).serialize()

        val sigs = mwa.signAndSend(resultSender, listOf(txBytes))
            ?: return Result.Failure("Wallet rejected or no wallet installed")
        val sig = sigs.firstOrNull()
            ?: return Result.Failure("Wallet returned no signature")
        return Result.Success(sig)
    }
}

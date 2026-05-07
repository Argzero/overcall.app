package com.overcall.pay

import com.overcall.registry.PhoneRegistryProgram
import com.overcall.registry.SolanaRpc
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Transaction

/**
 * Closes a PhoneRecord + ReverseIndex on-chain and refunds rent to the
 * owner. Registration fee already went to treasury and is not refunded.
 */
class RevokeFlow(
    private val rpc: SolanaRpc,
    private val mwa: MwaSigner,
) {
    sealed interface Result {
        data class Success(val signature: ByteArray) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun revoke(
        sender: ActivityResultSender,
        owner: SolanaPublicKey,
        phoneE164: String,
    ): Result {
        val blockhash = try {
            SolanaPublicKey.from(rpc.getLatestBlockhash())
        } catch (t: Throwable) {
            return Result.Failure("RPC blockhash fetch failed: ${t.message ?: t::class.simpleName}")
        }

        val ix = PhoneRegistryProgram.revoke(phoneE164, owner)
        val message = MessageBuilder.buildLegacyMessage(
            feePayer = owner,
            instructions = listOf(ix),
            recentBlockhash = blockhash,
        )
        val txBytes = Transaction(message).serialize()

        val sigs = mwa.signAndSend(sender, listOf(txBytes))
            ?: return Result.Failure("Wallet rejected or no wallet installed")
        val sig = sigs.firstOrNull()
            ?: return Result.Failure("Wallet returned no signature")
        return Result.Success(sig)
    }
}

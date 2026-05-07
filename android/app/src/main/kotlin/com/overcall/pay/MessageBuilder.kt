package com.overcall.pay

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Instruction
import com.solana.transaction.LegacyMessage
import com.solana.transaction.TransactionInstruction

/**
 * Compile a list of high-level [TransactionInstruction]s into a wire-format
 * [LegacyMessage] (unsigned). Solana's account ordering is:
 *
 *   1. writable signers (fee payer first)
 *   2. read-only signers
 *   3. writable non-signers
 *   4. read-only non-signers (program IDs included here)
 *
 * Each instruction's pubkeys are replaced with byte indexes into the unified
 * account list. Program IDs are added as read-only non-signers automatically.
 */
object MessageBuilder {

    fun buildLegacyMessage(
        feePayer: SolanaPublicKey,
        instructions: List<TransactionInstruction>,
        recentBlockhash: SolanaPublicKey,
    ): LegacyMessage {
        // Aggregate (isSigner, isWritable) per pubkey; OR the strictest flags.
        val flags = LinkedHashMap<SolanaPublicKey, Pair<Boolean, Boolean>>()

        fun touch(key: SolanaPublicKey, isSigner: Boolean, isWritable: Boolean) {
            val (s, w) = flags[key] ?: (false to false)
            flags[key] = (s || isSigner) to (w || isWritable)
        }

        // Fee payer always signer + writable.
        touch(feePayer, isSigner = true, isWritable = true)
        for (ix in instructions) {
            // Program IDs are read-only non-signers.
            touch(ix.programId, isSigner = false, isWritable = false)
            for (meta in ix.accounts) {
                touch(meta.publicKey, meta.isSigner, meta.isWritable)
            }
        }

        // Bucket and order. Fee payer must be first among writable signers.
        val writableSigners = mutableListOf<SolanaPublicKey>()
        val readOnlySigners = mutableListOf<SolanaPublicKey>()
        val writableNonSigners = mutableListOf<SolanaPublicKey>()
        val readOnlyNonSigners = mutableListOf<SolanaPublicKey>()

        for ((key, sw) in flags) {
            val (isSigner, isWritable) = sw
            when {
                isSigner && isWritable -> if (key == feePayer) writableSigners.add(0, key)
                                          else writableSigners.add(key)
                isSigner && !isWritable -> readOnlySigners.add(key)
                !isSigner && isWritable -> writableNonSigners.add(key)
                else                    -> readOnlyNonSigners.add(key)
            }
        }
        // Defensive: ensure fee payer is at index 0
        if (writableSigners.firstOrNull() != feePayer) {
            writableSigners.remove(feePayer)
            writableSigners.add(0, feePayer)
        }

        val accounts: List<SolanaPublicKey> =
            writableSigners + readOnlySigners + writableNonSigners + readOnlyNonSigners
        val indexOf: Map<SolanaPublicKey, Int> = accounts.withIndex().associate { (i, k) -> k to i }

        val compiled: List<Instruction> = instructions.map { ix ->
            Instruction(
                /* programIdIndex */ indexOf.getValue(ix.programId).toUByte(),
                /* accountIndices */ ix.accounts.map { indexOf.getValue(it.publicKey).toByte() }.toByteArray(),
                /* data           */ ix.data,
            )
        }

        return LegacyMessage(
            /* signatureCount      */ (writableSigners.size + readOnlySigners.size).toUByte(),
            /* readOnlyAccounts    */ readOnlySigners.size.toUByte(),
            /* readOnlyNonSigners  */ readOnlyNonSigners.size.toUByte(),
            /* accounts            */ accounts,
            /* blockhash           */ recentBlockhash,
            /* instructions        */ compiled,
        )
    }
}

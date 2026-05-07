package com.overcall.pay

import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBuilderTest {

    private fun pk(seed: Int): SolanaPublicKey =
        SolanaPublicKey(ByteArray(32) { (seed + it).toByte() })

    @Test
    fun fee_payer_first_program_id_last() {
        val feePayer = pk(1)
        val account = pk(2)
        val programId = pk(99)
        val ix = TransactionInstruction(
            programId,
            listOf(
                AccountMeta(feePayer, isSigner = true, isWritable = true),
                AccountMeta(account, isSigner = false, isWritable = true),
            ),
            byteArrayOf(0xAA.toByte()),
        )
        val msg = MessageBuilder.buildLegacyMessage(
            feePayer = feePayer,
            instructions = listOf(ix),
            recentBlockhash = pk(255),
        )

        assertEquals(feePayer.base58(), msg.accounts[0].base58()) // fee payer at index 0
        assertEquals(programId.base58(), msg.accounts.last().base58()) // program ID at end (RO non-signer)
        assertEquals(1.toUByte(), msg.signatureCount)
        assertEquals(0.toUByte(), msg.readOnlyAccounts)
        assertEquals(1.toUByte(), msg.readOnlyNonSigners) // just the program ID
        assertEquals(1, msg.instructions.size)
        // accountIndices reference accounts by index — verify
        val compiled = msg.instructions[0]
        assertEquals(feePayer.base58(), msg.accounts[compiled.accountIndices[0].toInt()].base58())
        assertEquals(account.base58(), msg.accounts[compiled.accountIndices[1].toInt()].base58())
        assertEquals(programId.base58(), msg.accounts[compiled.programIdIndex.toInt()].base58())
    }

    @Test
    fun strictest_flags_win_for_pubkey_referenced_in_multiple_ixes() {
        val feePayer = pk(1)
        val shared = pk(2)
        val pgmA = pk(98)
        val pgmB = pk(99)
        val ixReadonly = TransactionInstruction(
            pgmA,
            listOf(
                AccountMeta(feePayer, isSigner = true, isWritable = true),
                AccountMeta(shared, isSigner = false, isWritable = false),
            ),
            byteArrayOf(),
        )
        val ixWritable = TransactionInstruction(
            pgmB,
            listOf(
                AccountMeta(feePayer, isSigner = true, isWritable = true),
                AccountMeta(shared, isSigner = false, isWritable = true),
            ),
            byteArrayOf(),
        )
        val msg = MessageBuilder.buildLegacyMessage(
            feePayer = feePayer,
            instructions = listOf(ixReadonly, ixWritable),
            recentBlockhash = pk(255),
        )
        // shared should land in the writable-non-signers bucket (NOT readonly).
        // Layout: [writableSigners=1, readonlySigners=0, writableNonSigners=1, readonlyNonSigners=2]
        assertEquals(4, msg.accounts.size)
        assertEquals(feePayer.base58(), msg.accounts[0].base58())
        assertEquals(shared.base58(), msg.accounts[1].base58())
        // last 2 are program IDs
        val tail = listOf(msg.accounts[2].base58(), msg.accounts[3].base58()).toSet()
        assertEquals(setOf(pgmA.base58(), pgmB.base58()), tail)

        assertEquals(1.toUByte(), msg.signatureCount)
        assertEquals(2.toUByte(), msg.readOnlyNonSigners)
    }
}

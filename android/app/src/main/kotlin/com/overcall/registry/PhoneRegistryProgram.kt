package com.overcall.registry

import com.overcall.config.OverCallConfig
import com.solana.programs.SystemProgram
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.TransactionInstruction
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Instruction builders for the deployed phone_registry Anchor program.
 * Each builder mirrors the corresponding Rust handler's account ordering
 * and Borsh-encoded args.
 *
 * Anchor instruction wire format:
 *   [0..8)   sha256("global:<snake_case_method>")[0..8]
 *   [8..)    Borsh-encoded args, in declared order
 */
object PhoneRegistryProgram {
    private val PROGRAM_ID = OverCallConfig.PHONE_REGISTRY_PROGRAM

    private val DISC_REGISTER_WITH_SOL_FEE = anchorIxDiscriminator("register_with_sol_fee")
    private val DISC_PAY_SOL = anchorIxDiscriminator("pay_sol")
    private val DISC_REVOKE = anchorIxDiscriminator("revoke")

    /**
     * Build the `register_with_sol_fee` instruction for a phone owner. Caller is
     * responsible for fetching the latest blockhash and wrapping in a
     * Transaction.
     */
    suspend fun registerWithSolFee(
        phoneE164: String,
        owner: SolanaPublicKey,
        treasury: SolanaPublicKey,
        attestationHash: ByteArray,
        attestationKind: Byte,
        acceptedMints: List<SolanaPublicKey> = emptyList(),
        preferredReceive: SolanaPublicKey = SolanaPublicKey(ByteArray(32)),
        flags: UInt = 0u,
    ): TransactionInstruction {
        require(acceptedMints.size <= 8) { "max 8 accepted mints" }
        require(attestationHash.size == 32) { "attestation hash must be 32 bytes" }

        val phoneRecord = PhonePdas.phoneRecord(phoneE164)
        val reverseIndex = PhonePdas.reverseIndex(owner)
        val configPda = OverCallConfig.REGISTRY_CONFIG_PDA

        val data = ByteArrayOutputStream().apply {
            write(DISC_REGISTER_WITH_SOL_FEE)
            writeBorshString(phoneE164)
            writeBorshVecPubkey(acceptedMints)
            write(preferredReceive.bytes)
            writeU32LE(flags.toInt())
            // Fixed-size [u8; 32] — no length prefix
            write(attestationHash)
            write(byteArrayOf(attestationKind))
        }.toByteArray()

        return TransactionInstruction(
            PROGRAM_ID,
            listOf(
                AccountMeta(configPda, isSigner = false, isWritable = false),
                AccountMeta(phoneRecord, isSigner = false, isWritable = true),
                AccountMeta(reverseIndex, isSigner = false, isWritable = true),
                AccountMeta(treasury, isSigner = false, isWritable = true),
                AccountMeta(owner, isSigner = true, isWritable = true),
                AccountMeta(SystemProgram.PROGRAM_ID, isSigner = false, isWritable = false),
            ),
            data,
        )
    }

    /**
     * Build the `pay_sol` instruction. Atomically transfers `amount` from
     * sender → recipient and `amount * payment_fee_bps / 10_000` from
     * sender → treasury. Recipient receives the full nominal amount;
     * the fee is additive (sender debited amount + fee).
     */
    suspend fun paySol(
        phoneE164: String,
        sender: SolanaPublicKey,
        recipient: SolanaPublicKey,
        treasury: SolanaPublicKey,
        amountLamports: Long,
    ): TransactionInstruction {
        val phoneRecord = PhonePdas.phoneRecord(phoneE164)
        val configPda = OverCallConfig.REGISTRY_CONFIG_PDA

        val data = ByteArrayOutputStream().apply {
            write(DISC_PAY_SOL)
            writeBorshString(phoneE164)
            writeU64LE(amountLamports)
        }.toByteArray()

        return TransactionInstruction(
            PROGRAM_ID,
            listOf(
                AccountMeta(configPda, isSigner = false, isWritable = false),
                AccountMeta(phoneRecord, isSigner = false, isWritable = false),
                AccountMeta(recipient, isSigner = false, isWritable = true),
                AccountMeta(treasury, isSigner = false, isWritable = true),
                AccountMeta(sender, isSigner = true, isWritable = true),
                AccountMeta(SystemProgram.PROGRAM_ID, isSigner = false, isWritable = false),
            ),
            data,
        )
    }

    /**
     * Build the `revoke` instruction. Closes both PhoneRecord and
     * ReverseIndex PDAs, refunding rent to owner. Registration fee is
     * NOT refunded — it's already in treasury.
     */
    suspend fun revoke(
        phoneE164: String,
        owner: SolanaPublicKey,
    ): TransactionInstruction {
        val phoneRecord = PhonePdas.phoneRecord(phoneE164)
        val reverseIndex = PhonePdas.reverseIndex(owner)

        val data = ByteArrayOutputStream().apply {
            write(DISC_REVOKE)
            writeBorshString(phoneE164)
        }.toByteArray()

        return TransactionInstruction(
            PROGRAM_ID,
            listOf(
                AccountMeta(phoneRecord, isSigner = false, isWritable = true),
                AccountMeta(reverseIndex, isSigner = false, isWritable = true),
                AccountMeta(owner, isSigner = true, isWritable = true),
            ),
            data,
        )
    }

    /** sha256("global:<method>")[0..8] — Anchor's instruction discriminator. */
    fun anchorIxDiscriminator(method: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("global:$method".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 8)
    }

    private fun ByteArrayOutputStream.writeU32LE(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        write(buf.array())
    }

    private fun ByteArrayOutputStream.writeU64LE(value: Long) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
        write(buf.array())
    }

    private fun ByteArrayOutputStream.writeBorshString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeU32LE(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeBorshVecPubkey(items: List<SolanaPublicKey>) {
        writeU32LE(items.size)
        items.forEach { write(it.bytes) }
    }
}

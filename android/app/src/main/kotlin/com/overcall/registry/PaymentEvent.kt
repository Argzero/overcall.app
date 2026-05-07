package com.overcall.registry

import com.solana.publickey.SolanaPublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Mirrors the on-chain `#[event] PaymentEvent` emitted by pay_sol and
 * pay_spl_direct. Wire layout (after the 8-byte event discriminator):
 *
 *   sender         : 32  Pubkey
 *   recipient      : 32  Pubkey
 *   phone_record   : 32  Pubkey
 *   mint           : 32  Pubkey   (Pubkey::default() = native SOL)
 *   amount         :  8  u64 LE
 *   fee            :  8  u64 LE
 *   ts             :  8  i64 LE   (unix seconds)
 *
 * Total = 8 + 4*32 + 3*8 = 160 bytes.
 *
 * Anchor encodes events in `Program data: <base64>` log lines. To pick out
 * one of these, base64-decode the log text and check the first 8 bytes
 * against [DISCRIMINATOR].
 */
data class PaymentEvent(
    val sender: SolanaPublicKey,
    val recipient: SolanaPublicKey,
    val phoneRecord: SolanaPublicKey,
    val mint: SolanaPublicKey,
    val amount: Long,
    val fee: Long,
    val ts: Long,
) {
    companion object {
        const val SIZE = 160

        val DISCRIMINATOR: ByteArray = run {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest("event:PaymentEvent".toByteArray(Charsets.UTF_8))
                .copyOfRange(0, 8)
        }

        /**
         * Decode a Borsh-encoded PaymentEvent payload (the bytes after the
         * Anchor `Program data:` prefix has been base64-decoded).
         * Returns null if the bytes don't match this event's discriminator.
         */
        fun decode(data: ByteArray): PaymentEvent? {
            if (data.size < SIZE) return null
            if (!data.copyOfRange(0, 8).contentEquals(DISCRIMINATOR)) return null
            val buf = ByteBuffer.wrap(data, 8, SIZE - 8).order(ByteOrder.LITTLE_ENDIAN)
            val sender = readPubkey(buf)
            val recipient = readPubkey(buf)
            val phoneRecord = readPubkey(buf)
            val mint = readPubkey(buf)
            return PaymentEvent(
                sender = sender,
                recipient = recipient,
                phoneRecord = phoneRecord,
                mint = mint,
                amount = buf.long,
                fee = buf.long,
                ts = buf.long,
            )
        }

        private fun readPubkey(buf: ByteBuffer): SolanaPublicKey {
            val bytes = ByteArray(32)
            buf.get(bytes)
            return SolanaPublicKey(bytes)
        }
    }
}

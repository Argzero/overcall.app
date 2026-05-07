package com.overcall.registry

import com.solana.publickey.SolanaPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PaymentEventTest {

    private val sender    = SolanaPublicKey(ByteArray(32) { (it + 1).toByte() })
    private val recipient = SolanaPublicKey(ByteArray(32) { (it + 33).toByte() })
    private val phoneRec  = SolanaPublicKey(ByteArray(32) { (it + 65).toByte() })
    private val mint      = SolanaPublicKey(ByteArray(32) { (it + 97).toByte() })

    @Test
    fun discriminator_is_8_bytes_and_stable() {
        assertEquals(8, PaymentEvent.DISCRIMINATOR.size)
        // recompute and ensure determinism
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expected = md.digest("event:PaymentEvent".toByteArray()).copyOfRange(0, 8)
        org.junit.Assert.assertArrayEquals(expected, PaymentEvent.DISCRIMINATOR)
    }

    @Test
    fun decodes_round_trip_payload() {
        val data = build(
            sender, recipient, phoneRec, mint,
            amount = 1_500_000L,
            fee = 150L,
            ts = 1_715_000_000L,
        )
        val event = PaymentEvent.decode(data)!!
        assertEquals(sender.base58(),    event.sender.base58())
        assertEquals(recipient.base58(), event.recipient.base58())
        assertEquals(phoneRec.base58(),  event.phoneRecord.base58())
        assertEquals(mint.base58(),      event.mint.base58())
        assertEquals(1_500_000L,         event.amount)
        assertEquals(150L,               event.fee)
        assertEquals(1_715_000_000L,     event.ts)
    }

    @Test
    fun rejects_wrong_discriminator() {
        val data = ByteArray(PaymentEvent.SIZE).also { it[0] = 0xff.toByte() }
        assertNull(PaymentEvent.decode(data))
    }

    @Test
    fun rejects_short_payload() {
        assertNull(PaymentEvent.decode(ByteArray(64)))
    }

    private fun build(
        s: SolanaPublicKey, r: SolanaPublicKey, pr: SolanaPublicKey, m: SolanaPublicKey,
        amount: Long, fee: Long, ts: Long,
    ): ByteArray {
        val out = ByteArray(PaymentEvent.SIZE)
        PaymentEvent.DISCRIMINATOR.copyInto(out, 0)
        val buf = ByteBuffer.wrap(out, 8, PaymentEvent.SIZE - 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(s.bytes)
        buf.put(r.bytes)
        buf.put(pr.bytes)
        buf.put(m.bytes)
        buf.putLong(amount)
        buf.putLong(fee)
        buf.putLong(ts)
        return out
    }
}

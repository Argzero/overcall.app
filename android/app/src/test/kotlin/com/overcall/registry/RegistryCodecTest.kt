package com.overcall.registry

import com.solana.publickey.SolanaPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Round-trip tests against synthetic payloads we construct by hand to match the
 * on-chain Anchor account layout. If the program's state.rs ever changes
 * field order or sizes, these tests fail loudly.
 */
class RegistryCodecTest {

    private val somePubkey = SolanaPublicKey(ByteArray(32) { it.toByte() })
    private val anotherPubkey = SolanaPublicKey(ByteArray(32) { (it + 100).toByte() })

    @Test
    fun phone_record_decodes_round_trip_payload() {
        val phone = "+15551234567"
        val data = buildPhoneRecordPayload(
            bump = 254,
            phone = phone,
            owner = somePubkey,
            acceptedMints = listOf(anotherPubkey, somePubkey),
            preferredReceive = anotherPubkey,
            registeredAt = 1_716_000_000L,
            expiresAt = 0L,
            flags = 0u,
        )

        val record = PhoneRecord.decode(data)

        assertEquals(254.toUByte(), record.bump)
        assertEquals(phone, record.phoneE164)
        assertEquals(somePubkey.base58(), record.owner.base58())
        assertEquals(2, record.acceptedMints.size)
        assertEquals(anotherPubkey.base58(), record.acceptedMints[0].base58())
        assertEquals(somePubkey.base58(), record.acceptedMints[1].base58())
        assertEquals(anotherPubkey.base58(), record.preferredReceive.base58())
        assertEquals(1_716_000_000L, record.registeredAt)
        assertEquals(0L, record.expiresAt)
        assertEquals(0u, record.flags)
    }

    @Test(expected = IllegalArgumentException::class)
    fun phone_record_rejects_wrong_discriminator() {
        val data = ByteArray(PhoneRecord.SIZE).also { it[0] = 1 } // bad disc
        PhoneRecord.decode(data)
    }

    @Test
    fun reverse_index_decodes() {
        val data = ByteArray(ReverseIndex.SIZE).also { buf ->
            AnchorDiscriminator.REVERSE_INDEX.copyInto(buf, 0)
            buf[8] = 253.toByte() // bump
            somePubkey.bytes.copyInto(buf, 9)
            anotherPubkey.bytes.copyInto(buf, 9 + 32)
        }
        val ri = ReverseIndex.decode(data)
        assertEquals(253.toUByte(), ri.bump)
        assertEquals(somePubkey.base58(), ri.owner.base58())
        assertEquals(anotherPubkey.base58(), ri.phoneRecord.base58())
    }

    @Test
    fun pda_is_deterministic_for_same_phone() = runBlocking {
        val a = PhonePdas.phoneRecord("+15551234567")
        val b = PhonePdas.phoneRecord("+15551234567")
        assertEquals(a.base58(), b.base58())
        assertNotNull(a.base58())
    }

    @Test
    fun pda_differs_across_phones() = runBlocking {
        val a = PhonePdas.phoneRecord("+15551234567")
        val b = PhonePdas.phoneRecord("+15559999999")
        org.junit.Assert.assertNotEquals(a.base58(), b.base58())
    }

    private fun buildPhoneRecordPayload(
        bump: Int,
        phone: String,
        owner: SolanaPublicKey,
        acceptedMints: List<SolanaPublicKey>,
        preferredReceive: SolanaPublicKey,
        registeredAt: Long,
        expiresAt: Long,
        flags: UInt,
    ): ByteArray {
        val out = ByteArray(PhoneRecord.SIZE)
        AnchorDiscriminator.PHONE_RECORD.copyInto(out, 0)
        val buf = ByteBuffer.wrap(out, 8, PhoneRecord.SIZE - 8).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(bump.toByte())
        val phoneBytes = phone.toByteArray(Charsets.US_ASCII)
        require(phoneBytes.size <= 16)
        buf.put(phoneBytes)
        repeat(16 - phoneBytes.size) { buf.put(0) }
        buf.put(phoneBytes.size.toByte())

        buf.put(owner.bytes)

        for (mint in acceptedMints) buf.put(mint.bytes)
        repeat((8 - acceptedMints.size)) { buf.put(ByteArray(32)) }
        buf.put(acceptedMints.size.toByte())

        buf.put(preferredReceive.bytes)
        buf.putLong(registeredAt)
        buf.putLong(expiresAt)
        buf.putInt(flags.toInt())

        return out
    }
}

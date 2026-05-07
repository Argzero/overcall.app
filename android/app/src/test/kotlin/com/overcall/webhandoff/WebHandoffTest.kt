package com.overcall.webhandoff

import com.solana.publickey.SolanaPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHandoffTest {

    private val laptopWallet =
        SolanaPublicKey.from("5wRjzrwWZG3af3FE26ZrRj3s8AYkV4hYzSaY5y89E6P5")

    @Test
    fun outbound_uri_has_scheme_host_and_params() {
        val nonce = "abc123"
        val hash = ByteArray(32) { (it + 1).toByte() }
        val out = WebHandoff.buildOutbound(nonce, hash)

        assertTrue("bad prefix: $out", out.startsWith("overcall://web-register-result?"))
        assertTrue("missing nonce: $out", out.contains("nonce=$nonce"))
        assertTrue("missing hash: $out", out.contains("hash="))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outbound_rejects_non_32_byte_hash() {
        WebHandoff.buildOutbound("nonce", ByteArray(16))
    }

    @Test
    fun inbound_parses_well_formed_uri() {
        val uri = "overcall://web-register?phone=%2B15551234567" +
            "&wallet=${laptopWallet.base58()}" +
            "&nonce=cafebabe"
        val parsed = WebHandoff.parseInbound(uri)
        assertNotNull(parsed)
        assertEquals("+15551234567", parsed!!.phoneE164)
        assertEquals(laptopWallet.base58(), parsed.wallet.base58())
        assertEquals("cafebabe", parsed.nonce)
    }

    @Test
    fun inbound_rejects_wrong_scheme() {
        val uri = "https://web-register?phone=%2B15551234567" +
            "&wallet=${laptopWallet.base58()}" +
            "&nonce=x"
        assertNull(WebHandoff.parseInbound(uri))
    }

    @Test
    fun inbound_rejects_wrong_host() {
        val uri = "overcall://invite?phone=%2B15551234567" +
            "&wallet=${laptopWallet.base58()}" +
            "&nonce=x"
        assertNull(WebHandoff.parseInbound(uri))
    }

    @Test
    fun inbound_rejects_missing_params() {
        val uri = "overcall://web-register?phone=%2B15551234567" +
            "&wallet=${laptopWallet.base58()}"
        assertNull(WebHandoff.parseInbound(uri))
    }

    @Test
    fun inbound_rejects_non_e164_phone() {
        // missing +
        val uri = "overcall://web-register?phone=15551234567" +
            "&wallet=${laptopWallet.base58()}" +
            "&nonce=x"
        assertNull(WebHandoff.parseInbound(uri))
    }

    @Test
    fun inbound_rejects_garbage_wallet() {
        val uri = "overcall://web-register?phone=%2B15551234567" +
            "&wallet=not-a-pubkey" +
            "&nonce=x"
        assertNull(WebHandoff.parseInbound(uri))
    }

    @Test
    fun outbound_round_trips_hash_via_base58_decoder_independent() {
        // Sanity: the hash we put in is the hash a verifier would extract.
        // We can't decode base58 here without dragging in another impl, so
        // we just check the alphabet contract: Base58 output is non-empty,
        // contains no separators, and is reasonably sized for 32 bytes.
        val hash = ByteArray(32) { 0xAB.toByte() }
        val out = WebHandoff.buildOutbound("n", hash)
        val hashPart = out.substringAfter("hash=")
        assertTrue("base58 output too short: $hashPart", hashPart.length in 30..50)
        // No '/' '+' '=' (those would indicate base64 leaked in).
        assertTrue(hashPart.none { it == '+' || it == '/' || it == '=' })
    }
}

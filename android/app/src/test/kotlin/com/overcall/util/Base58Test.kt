package com.overcall.util

import org.junit.Assert.assertEquals
import org.junit.Test

class Base58Test {
    @Test
    fun encodes_known_solana_pubkey() {
        // The all-zeros 32-byte pubkey base58 encodes to a 32-char "11..."
        val zeros = ByteArray(32)
        assertEquals("11111111111111111111111111111111", Base58.encode(zeros))
    }

    @Test
    fun encodes_64_byte_signature_to_typical_length() {
        // Solana signatures are 64 random bytes — base58-encoded length is
        // typically 87-88 chars. Just verifying the encoder doesn't truncate
        // and produces something in that ballpark.
        val sig = ByteArray(64) { (it + 1).toByte() }
        val encoded = Base58.encode(sig)
        assert(encoded.length in 86..88) { "unexpected sig length ${encoded.length}: $encoded" }
    }

    @Test
    fun preserves_leading_zero_bytes_as_leading_ones() {
        val input = byteArrayOf(0, 0, 0, 1, 2, 3)
        val encoded = Base58.encode(input)
        // Three leading zero bytes -> three leading '1's, then the encoded
        // value of 0x010203.
        assert(encoded.startsWith("111")) { "expected 3 leading 1s, got: $encoded" }
    }

    @Test
    fun empty_input_returns_empty() {
        assertEquals("", Base58.encode(byteArrayOf()))
    }

    @Test
    fun encodes_known_test_vector() {
        // "Hello" => "9Ajdvzr"  (well-known Bitcoin test vector)
        assertEquals("9Ajdvzr", Base58.encode("Hello".toByteArray(Charsets.US_ASCII)))
    }
}

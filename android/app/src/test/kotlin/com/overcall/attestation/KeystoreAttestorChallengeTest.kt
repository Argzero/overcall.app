package com.overcall.attestation

import com.solana.publickey.SolanaPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The challenge derivation is pure — no Keystore required — so we can
 * unit-test it on the JVM. Verifies determinism, separation by phone,
 * separation by owner, and the 32-byte sha256 output shape.
 */
class KeystoreAttestorChallengeTest {

    private val ownerA = SolanaPublicKey(ByteArray(32) { (it + 1).toByte() })
    private val ownerB = SolanaPublicKey(ByteArray(32) { (it + 100).toByte() })

    @Test fun challenge_is_32_bytes() {
        val c = KeystoreAttestor.computeChallenge("+15551234567", ownerA)
        assertEquals(32, c.size)
    }

    @Test fun deterministic_for_same_inputs() {
        val c1 = KeystoreAttestor.computeChallenge("+15551234567", ownerA)
        val c2 = KeystoreAttestor.computeChallenge("+15551234567", ownerA)
        org.junit.Assert.assertArrayEquals(c1, c2)
    }

    @Test fun differs_when_phone_differs() {
        val c1 = KeystoreAttestor.computeChallenge("+15551234567", ownerA)
        val c2 = KeystoreAttestor.computeChallenge("+15559999999", ownerA)
        assertNotEquals(c1.toList(), c2.toList())
    }

    @Test fun differs_when_owner_differs() {
        val c1 = KeystoreAttestor.computeChallenge("+15551234567", ownerA)
        val c2 = KeystoreAttestor.computeChallenge("+15551234567", ownerB)
        assertNotEquals(c1.toList(), c2.toList())
    }

    @Test fun alias_is_owner_phone_specific() {
        val a1 = KeystoreAttestor.aliasFor("+15551234567", ownerA)
        val a2 = KeystoreAttestor.aliasFor("+15551234567", ownerA)
        val a3 = KeystoreAttestor.aliasFor("+15551234567", ownerB)
        val a4 = KeystoreAttestor.aliasFor("+15559999999", ownerA)
        assertEquals(a1, a2)
        assertNotEquals(a1, a3)
        assertNotEquals(a1, a4)
    }
}

package com.overcall.attestation

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.solana.publickey.SolanaPublicKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import javax.security.auth.x500.X500Principal

/**
 * Generates a hardware-backed Android Keystore key with a key-attestation
 * extension binding it to the (phone E.164, wallet pubkey) pair, then
 * returns the certificate chain hash for on-chain registration.
 *
 * On stock Android (S23, etc.) the attestation is rooted in Google's
 * Hardware Attestation Root (the leaf cert chains to it via vendor
 * intermediates). On Seeker the same Keystore APIs are available — the
 * attestation root is still Google's, since Seed Vault doesn't currently
 * expose its own attestation signer to third-party dApps. Future Task
 * (Seed-Vault-specific path) will swap kind=1 → kind=2 on Seeker.
 *
 * Hardware-backed availability: requires API 24+, but full TEE-backed
 * attestation is reliable on API 28+. Our minSdk = 31, so always
 * supported. Emulators produce software-attested chains — the leaf cert
 * still validates but the security level field reads `Software` instead
 * of `TrustedEnvironment`. Off-chain verifiers can choose to gate on
 * security level for higher-assurance contexts.
 */
class KeystoreAttestor {

    /**
     * Generate a fresh attestation for the given (phone, owner) pair.
     * Idempotent at the alias level — overwrites any existing key for
     * this owner/phone pair.
     */
    fun attest(phoneE164: String, owner: SolanaPublicKey): AttestationResult {
        val challenge = computeChallenge(phoneE164, owner)
        val alias = aliasFor(phoneE164, owner)

        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)

        val params = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=OverCall Attestation"))
            .setAttestationChallenge(challenge)
            .build()

        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        kpg.initialize(params)
        kpg.generateKeyPair()

        val chain: Array<Certificate> = ks.getCertificateChain(alias)
            ?: error("Keystore returned no certificate chain for alias $alias")
        require(chain.isNotEmpty()) { "empty cert chain — Keystore did not produce attestation" }

        // Concatenate DER encodings for a stable canonical blob. Order is
        // leaf → intermediates → root, which is the same order Keystore
        // returns and what verifiers expect.
        val chainBytes = chain.fold(ByteArray(0)) { acc, cert -> acc + cert.encoded }
        val hash = MessageDigest.getInstance("SHA-256").digest(chainBytes)

        return AttestationResult(
            hash = hash,
            kind = AttestationKind.ANDROID_KEYSTORE,
            chainBytes = chainBytes,
            challenge = challenge,
            alias = alias,
        )
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val DOMAIN_TAG = "overcall.attest"

        /**
         * Deterministic challenge bound to the (phone, owner) pair so an
         * attestation for one identity can't be replayed for another.
         * Off-chain verifiers compute the same value to confirm the leaf
         * cert's attestation challenge matches the registered pair.
         */
        fun computeChallenge(phoneE164: String, owner: SolanaPublicKey): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(DOMAIN_TAG.toByteArray(Charsets.US_ASCII))
            md.update(phoneE164.toByteArray(Charsets.US_ASCII))
            md.update(owner.bytes)
            return md.digest()
        }

        fun aliasFor(phoneE164: String, owner: SolanaPublicKey): String =
            "overcall.attestation.${owner.base58().take(16)}.$phoneE164"
    }
}

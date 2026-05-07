package com.overcall.attestation

/**
 * Result of an attestation operation.
 *
 *  - [hash] is the 32-byte sha256 of [chainBytes] — this is what lands
 *    on-chain in PhoneRecord.attestation_hash.
 *  - [kind] is one of [AttestationKind] mirroring the on-chain constants.
 *  - [chainBytes] is the canonical DER-concatenated certificate chain
 *    (Android Keystore: leaf + intermediates + Google root). Stays on the
 *    user's device until off-chain verification is requested.
 *  - [challenge] is the bytes embedded in the leaf cert's attestation
 *    extension — verifiers re-derive it from the registered (E.164,
 *    owner) and confirm the leaf cert's challenge matches.
 *  - [alias] is the Keystore alias for the generated key (kept so a
 *    Settings re-attest can clean up old keys).
 */
data class AttestationResult(
    val hash: ByteArray,
    val kind: Byte,
    val chainBytes: ByteArray,
    val challenge: ByteArray,
    val alias: String,
) {
    init {
        require(hash.size == 32) { "attestation hash must be 32 bytes, was ${hash.size}" }
    }
}

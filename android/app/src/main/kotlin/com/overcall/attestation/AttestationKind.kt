package com.overcall.attestation

/** Mirror of the on-chain `attestation_kind` constants. */
object AttestationKind {
    const val NONE: Byte = 0
    const val ANDROID_KEYSTORE: Byte = 1
    const val SEEKER_SEED_VAULT: Byte = 2
    const val WEB_DELEGATED: Byte = 3
}

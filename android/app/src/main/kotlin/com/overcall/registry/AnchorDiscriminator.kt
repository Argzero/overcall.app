package com.overcall.registry

import java.security.MessageDigest

/**
 * Anchor accounts are prefixed with 8 bytes derived from
 * `sha256("account:<StructName>")[0..8]`. We compute these once at class
 * init so a deserializer can verify the prefix before reading fields.
 */
object AnchorDiscriminator {
    fun compute(structName: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("account:$structName".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 8)
    }

    val PHONE_RECORD: ByteArray   = compute("PhoneRecord")
    val REVERSE_INDEX: ByteArray  = compute("ReverseIndex")
    val REGISTRY_CONFIG: ByteArray = compute("RegistryConfig")
}

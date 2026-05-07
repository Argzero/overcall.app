package com.overcall.registry

import com.overcall.registry.AnchorDiscriminator.PHONE_RECORD
import com.solana.publickey.SolanaPublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mirror of the on-chain `PhoneRecord` Anchor account.
 *
 * Two layouts exist on devnet:
 *
 *   • LEGACY (deployed program ≤ aa2f495, 367 bytes total):
 *
 *       discriminator      : 8
 *       bump               : 1
 *       phone_e164         : 16  (null-padded)
 *       phone_len          : 1
 *       owner              : 32
 *       accepted_mints     : 8 * 32 = 256
 *       accepted_count     : 1
 *       preferred_receive  : 32
 *       registered_at      : 8   (i64 LE)
 *       expires_at         : 8   (i64 LE)
 *       flags              : 4   (u32 LE)
 *
 *   • CURRENT (after the mandatory-attestation commit, 495 bytes total):
 *       …everything above plus
 *       attestation_hash   : 32
 *       attestation_kind   : 1
 *       attested_at        : 8   (i64 LE)
 *       reserved           : 87
 *
 * The decoder accepts either: it requires the legacy core (367 bytes) and,
 * if the buffer has the attestation tail, parses it; otherwise the
 * attestation fields decode to zeros / kind=NONE. This lets the Android
 * client read accounts written by either program version.
 */
data class PhoneRecord(
    val bump: UByte,
    val phoneE164: String,
    val owner: SolanaPublicKey,
    val acceptedMints: List<SolanaPublicKey>,
    val preferredReceive: SolanaPublicKey,
    val registeredAt: Long,
    val expiresAt: Long,
    val flags: UInt,
    val attestationHash: ByteArray,
    val attestationKind: UByte,
    val attestedAt: Long,
) {
    companion object {
        // Bytes required for the pre-attestation core. Equals the on-chain
        // size of records written by the original deployed program.
        const val LEGACY_SIZE = 367

        // Bytes for the post-attestation layout (current source).
        const val SIZE = 495

        fun decode(data: ByteArray): PhoneRecord {
            require(data.size >= LEGACY_SIZE) {
                "PhoneRecord payload too short: ${data.size} < $LEGACY_SIZE"
            }
            require(data.copyOfRange(0, 8).contentEquals(PHONE_RECORD)) {
                "Account discriminator does not match PhoneRecord"
            }
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.LITTLE_ENDIAN)

            val bump = buf.get().toUByte()
            val rawPhone = ByteArray(16).also { buf.get(it) }
            val phoneLen = buf.get().toInt() and 0xff
            require(phoneLen <= 16) { "phone_len out of range: $phoneLen" }
            val phone = String(rawPhone, 0, phoneLen, Charsets.US_ASCII)

            val owner = readPubkey(buf)

            val rawMints = ByteArray(8 * 32)
            buf.get(rawMints)
            val acceptedCount = buf.get().toInt() and 0xff
            require(acceptedCount <= 8) { "accepted_count out of range: $acceptedCount" }
            val acceptedMints = (0 until acceptedCount).map { i ->
                SolanaPublicKey(rawMints.copyOfRange(i * 32, (i + 1) * 32))
            }

            val preferredReceive = readPubkey(buf)
            val registeredAt = buf.long
            val expiresAt = buf.long
            val flags = buf.int.toUInt()

            // Tail (attestation_hash + kind + timestamp) is present only on
            // the post-attestation layout. Records written by the legacy
            // program get all-zeros for those fields, kind=NONE.
            val hasAttestation = data.size >= 8 + 1 + 16 + 1 + 32 + (8 * 32) + 1 +
                32 + 8 + 8 + 4 + 32 + 1 + 8
            val attestationHash: ByteArray
            val attestationKind: UByte
            val attestedAt: Long
            if (hasAttestation) {
                attestationHash = ByteArray(32).also { buf.get(it) }
                attestationKind = buf.get().toUByte()
                attestedAt = buf.long
            } else {
                attestationHash = ByteArray(32)
                attestationKind = 0u
                attestedAt = 0L
            }

            return PhoneRecord(
                bump = bump,
                phoneE164 = phone,
                owner = owner,
                acceptedMints = acceptedMints,
                preferredReceive = preferredReceive,
                registeredAt = registeredAt,
                expiresAt = expiresAt,
                flags = flags,
                attestationHash = attestationHash,
                attestationKind = attestationKind,
                attestedAt = attestedAt,
            )
        }

        private fun readPubkey(buf: ByteBuffer): SolanaPublicKey {
            val bytes = ByteArray(32)
            buf.get(bytes)
            return SolanaPublicKey(bytes)
        }
    }
}

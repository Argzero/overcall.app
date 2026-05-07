package com.overcall.registry

import com.overcall.registry.AnchorDiscriminator.REGISTRY_CONFIG
import com.solana.publickey.SolanaPublicKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mirror of the on-chain `RegistryConfig` Anchor account. Decoded so the
 * Android client can fetch the live treasury at flow start instead of
 * baking a stale pubkey into the binary.
 *
 * Wire layout:
 *   discriminator                : 8
 *   bump                         : 1
 *   admin                        : 32
 *   treasury                     : 32
 *   registration_fee_sol_lamports: 8
 *   registration_fee_spl_amount  : 8
 *   fee_spl_mint                 : 32
 *   payment_fee_bps              : 2  (u16 LE)
 *   registration_lifetime        : 8  (i64 LE)
 *   --------------------------------
 *   total                        : 131
 */
data class RegistryConfig(
    val bump: UByte,
    val admin: SolanaPublicKey,
    val treasury: SolanaPublicKey,
    val registrationFeeSolLamports: Long,
    val registrationFeeSplAmount: Long,
    val feeSplMint: SolanaPublicKey,
    val paymentFeeBps: UShort,
    val registrationLifetime: Long,
) {
    companion object {
        const val SIZE = 131

        fun decode(data: ByteArray): RegistryConfig {
            require(data.size >= SIZE) { "RegistryConfig payload too short: ${data.size} < $SIZE" }
            require(data.copyOfRange(0, 8).contentEquals(REGISTRY_CONFIG)) {
                "Account discriminator does not match RegistryConfig"
            }
            val buf = ByteBuffer.wrap(data, 8, data.size - 8).order(ByteOrder.LITTLE_ENDIAN)
            val bump = buf.get().toUByte()
            val admin = readPubkey(buf)
            val treasury = readPubkey(buf)
            val regFeeSol = buf.long
            val regFeeSpl = buf.long
            val feeSplMint = readPubkey(buf)
            val bps = buf.short.toUShort()
            val lifetime = buf.long
            return RegistryConfig(
                bump = bump,
                admin = admin,
                treasury = treasury,
                registrationFeeSolLamports = regFeeSol,
                registrationFeeSplAmount = regFeeSpl,
                feeSplMint = feeSplMint,
                paymentFeeBps = bps,
                registrationLifetime = lifetime,
            )
        }

        private fun readPubkey(buf: ByteBuffer): SolanaPublicKey {
            val bytes = ByteArray(32)
            buf.get(bytes)
            return SolanaPublicKey(bytes)
        }
    }
}

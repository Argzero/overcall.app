package com.overcall.registry

import com.overcall.registry.AnchorDiscriminator.REVERSE_INDEX
import com.solana.publickey.SolanaPublicKey

/**
 * On-chain `ReverseIndex` account: maps owner pubkey → PhoneRecord PDA.
 *
 *   discriminator : 8
 *   bump          : 1
 *   owner         : 32
 *   phone_record  : 32
 *  ----------------------------
 *   total         : 73
 */
data class ReverseIndex(
    val bump: UByte,
    val owner: SolanaPublicKey,
    val phoneRecord: SolanaPublicKey,
) {
    companion object {
        const val SIZE = 73

        fun decode(data: ByteArray): ReverseIndex {
            require(data.size >= SIZE) {
                "ReverseIndex payload too short: ${data.size} < $SIZE"
            }
            require(data.copyOfRange(0, 8).contentEquals(REVERSE_INDEX)) {
                "Account discriminator does not match ReverseIndex"
            }
            val bump = data[8].toUByte()
            val owner = SolanaPublicKey(data.copyOfRange(9, 9 + 32))
            val phoneRecord = SolanaPublicKey(data.copyOfRange(9 + 32, 9 + 64))
            return ReverseIndex(bump, owner, phoneRecord)
        }
    }
}

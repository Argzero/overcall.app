package com.overcall.registry

import com.overcall.config.OverCallConfig
import com.solana.publickey.SolanaPublicKey

/**
 * Forward (E.164 → record) and reverse (wallet → phone) lookups against the
 * on-chain phone_registry program. Each lookup is one RPC `getAccountInfo`.
 *
 * No caching here — the bubble's on-call resolver wraps this in a small LRU.
 */
class RegistryClient(private val rpc: SolanaRpc) {

    /**
     * Resolve an E.164 number to its on-chain PhoneRecord, or null if no
     * record has been registered for this number.
     */
    suspend fun lookupByPhone(e164: String): PhoneRecord? {
        val pda = PhonePdas.phoneRecord(e164)
        val data = rpc.getAccountData(pda) ?: return null
        return PhoneRecord.decode(data)
    }

    /**
     * Reverse: given a wallet pubkey, find its registered PhoneRecord (if any).
     * Returns null when the wallet has not registered a phone.
     */
    suspend fun lookupByOwner(owner: SolanaPublicKey): PhoneRecord? {
        val reversePda = PhonePdas.reverseIndex(owner)
        val reverseData = rpc.getAccountData(reversePda) ?: return null
        val reverse = ReverseIndex.decode(reverseData)
        val recordData = rpc.getAccountData(reverse.phoneRecord) ?: return null
        return PhoneRecord.decode(recordData)
    }

    /**
     * Fetch the live RegistryConfig (treasury, fees, etc.). Throws on RPC
     * failure or if the config PDA doesn't exist (which would mean the
     * deployment isn't initialized — caller should treat as unrecoverable).
     */
    suspend fun fetchConfig(): RegistryConfig {
        val pda = OverCallConfig.REGISTRY_CONFIG_PDA
        val data = rpc.getAccountData(pda)
            ?: error("RegistryConfig PDA not found at $pda — did init_config run?")
        return RegistryConfig.decode(data)
    }
}

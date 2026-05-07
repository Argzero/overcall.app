package com.overcall.registry

import com.overcall.config.OverCallConfig
import com.solana.publickey.ProgramDerivedAddress
import com.solana.publickey.SolanaPublicKey

/**
 * PDA helpers for the phone_registry program. Deterministic — these mirror the
 * seeds in `programs/phone_registry/src/instructions/`.
 *
 * web3-solana's `ProgramDerivedAddress.find` is `suspend` because PDA finding
 * may iterate up to 256 times computing SHA-256 — the lib offloads work to a
 * coroutine to keep the caller's thread responsive.
 */
object PhonePdas {
    private val PROGRAM_ID = OverCallConfig.PHONE_REGISTRY_PROGRAM
    private val PHONE = "phone".toByteArray(Charsets.US_ASCII)
    private val BY_OWNER = "by_owner".toByteArray(Charsets.US_ASCII)

    /** PDA at seeds=[b"phone", e164]. e164 is the raw E.164 string bytes. */
    suspend fun phoneRecord(e164: String): SolanaPublicKey {
        val seeds = listOf(PHONE, e164.toByteArray(Charsets.US_ASCII))
        val pda = ProgramDerivedAddress.find(seeds, PROGRAM_ID).getOrThrow()
        return SolanaPublicKey.from(pda.address)
    }

    /** PDA at seeds=[b"by_owner", owner_pubkey]. */
    suspend fun reverseIndex(owner: SolanaPublicKey): SolanaPublicKey {
        val seeds = listOf(BY_OWNER, owner.bytes)
        val pda = ProgramDerivedAddress.find(seeds, PROGRAM_ID).getOrThrow()
        return SolanaPublicKey.from(pda.address)
    }
}

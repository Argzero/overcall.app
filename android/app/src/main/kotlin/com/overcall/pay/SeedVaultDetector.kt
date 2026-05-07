package com.overcall.pay

import android.net.Uri

/**
 * Best-effort detection of whether a wallet authorized via MWA is the
 * Seeker's bundled Seed Vault wallet (hardware-backed signing) vs. a
 * conventional hot-key wallet (Backpack / Phantom / Solflare / fakewallet).
 *
 * The check is purely a heuristic on the wallet's `walletUriBase` reported
 * in MWA's AuthorizationResult. INTENTIONALLY conservative — false
 * negatives ("unknown") are fine, false positives ("Backpack reported as
 * hardware-backed") are not. The UI uses the result only to show a small
 * advisory badge, never to gate functionality. For a guaranteed hardware
 * path, see Task 27+ (direct Seed Vault SDK integration).
 *
 * Implemented to take the URI as a String so unit tests can run without
 * Robolectric. Use [isLikelySeedVault] from JVM tests; the [Uri] overload
 * is for production code paths that already hold a parsed Uri.
 */
object SeedVaultDetector {
    /** Hosts known to belong to Solana Mobile's Seed Vault wallet. */
    private val SEED_VAULT_HOSTS = setOf("solanamobile.com")

    /** Path/query fragments indicating Seed Vault. Matched case-insensitively. */
    private val SEED_VAULT_HINTS = listOf("seedvault", "seed-vault", "seed_vault")

    fun isLikelySeedVault(uri: Uri?): Boolean = isLikelySeedVault(uri?.toString())

    fun isLikelySeedVault(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        val parts = parseHostAndRest(uriString) ?: return false
        if (parts.host !in SEED_VAULT_HOSTS) return false
        val haystack = parts.rest.lowercase()
        return SEED_VAULT_HINTS.any { haystack.contains(it) }
    }

    private data class HostAndRest(val host: String, val rest: String)

    /** Tiny URI splitter so we don't need android.net.Uri in tests. */
    private fun parseHostAndRest(s: String): HostAndRest? {
        val schemeEnd = s.indexOf("://")
        val afterScheme = if (schemeEnd >= 0) s.substring(schemeEnd + 3) else s
        val firstSlash = afterScheme.indexOf('/')
        val authority = if (firstSlash >= 0) afterScheme.substring(0, firstSlash) else afterScheme
        val rest = if (firstSlash >= 0) afterScheme.substring(firstSlash) else ""
        val host = authority.substringAfter('@', authority).substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        return HostAndRest(host, rest)
    }
}

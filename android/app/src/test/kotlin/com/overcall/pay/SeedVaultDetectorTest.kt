package com.overcall.pay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedVaultDetectorTest {

    @Test fun null_uri_is_not_seed_vault() {
        assertFalse(SeedVaultDetector.isLikelySeedVault(null as String?))
    }

    @Test fun blank_uri_is_not_seed_vault() {
        assertFalse(SeedVaultDetector.isLikelySeedVault("   "))
    }

    @Test fun unknown_host_is_not_seed_vault() {
        assertFalse(SeedVaultDetector.isLikelySeedVault("https://backpack.app/wallet"))
    }

    @Test fun solana_mobile_root_without_seedvault_path_is_not_recognized() {
        // Conservative: solanamobile.com alone isn't enough; need the path hint.
        assertFalse(SeedVaultDetector.isLikelySeedVault("https://solanamobile.com/"))
    }

    @Test fun solana_mobile_with_seedvault_path_is_recognized() {
        assertTrue(SeedVaultDetector.isLikelySeedVault("https://solanamobile.com/seedvault"))
        assertTrue(SeedVaultDetector.isLikelySeedVault("https://solanamobile.com/wallet?app=seed-vault"))
        assertTrue(SeedVaultDetector.isLikelySeedVault("https://solanamobile.com/seed_vault/"))
    }

    @Test fun host_match_is_case_insensitive() {
        assertTrue(SeedVaultDetector.isLikelySeedVault("https://SolanaMobile.com/SeedVault"))
    }
}

package com.overcall.device

import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceProfileShapeTest {
    /**
     * JVM-side smoke test that confirms the data class shape compiles and all
     * fields are wired. Real instrumented tests against the actual emulator
     * device live in androidTest (added with the registry/MWA tasks).
     */
    @Test
    fun fields_are_present() {
        val profile = DeviceProfile(
            isSeeker = true,
            hasSeedVaultService = true,
            hasTelephony = true,
            canDrawOverlays = true,
        )
        assertNotNull(profile.isSeeker)
        assertNotNull(profile.hasSeedVaultService)
        assertNotNull(profile.hasTelephony)
        assertNotNull(profile.canDrawOverlays)
    }
}

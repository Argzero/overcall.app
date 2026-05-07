package com.overcall.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Runtime capability snapshot. Both flavors compile against the same code; this
 * tells the app what's actually available on the running device. Seeker flavor
 * additionally hard-gates installation via the seeker uses-feature in its
 * AndroidManifest, but DeviceProfile is the single source of truth at runtime.
 *
 * Permission booleans are included because the call-detection feature
 * requires three separate permissions that the user must grant before the
 * bubble can auto-attach to active calls. The home screen surfaces any
 * missing ones as a "Set up call detection" prompt.
 */
data class DeviceProfile(
    /** Package manager reports the Seeker hardware feature. */
    val isSeeker: Boolean,
    /** Seed Vault Service ContentProvider is reachable. */
    val hasSeedVaultService: Boolean,
    /** Device has telephony hardware (READ_PHONE_STATE works, SIM read is meaningful). */
    val hasTelephony: Boolean,
    /** SYSTEM_ALERT_WINDOW has been granted (overlay can draw). Re-check on resume. */
    val canDrawOverlays: Boolean,
    /** READ_PHONE_STATE granted — required for [com.overcall.call.CallWatcher] to subscribe. */
    val canReadPhoneState: Boolean,
    /** READ_CALL_LOG granted — required to capture the other party's number on OFFHOOK. */
    val canReadCallLog: Boolean,
) {
    /**
     * True when every permission needed for "bubble auto-pops on call" works.
     * Missing telephony hardware also disqualifies (no calls to detect).
     */
    val callDetectionReady: Boolean
        get() = hasTelephony && canDrawOverlays && canReadPhoneState && canReadCallLog

    companion object {
        const val SEEKER_FEATURE = "com.solanamobile.feature.seeker"
        const val SEED_VAULT_AUTHORITY = "com.solanamobile.seedvault"
        const val SEED_VAULT_INTENT = "com.solanamobile.seedvault.SeedVault"

        fun detect(ctx: Context): DeviceProfile {
            val pm = ctx.packageManager
            return DeviceProfile(
                isSeeker = pm.hasSystemFeature(SEEKER_FEATURE),
                hasSeedVaultService = seedVaultAvailable(ctx),
                hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
                canDrawOverlays = Settings.canDrawOverlays(ctx),
                canReadPhoneState = isGranted(ctx, Manifest.permission.READ_PHONE_STATE),
                canReadCallLog = isGranted(ctx, Manifest.permission.READ_CALL_LOG),
            )
        }

        private fun isGranted(ctx: Context, perm: String): Boolean =
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

        private fun seedVaultAvailable(ctx: Context): Boolean {
            // Probing without acquiring is enough to know whether SVS is installed.
            val client = ctx.contentResolver.acquireUnstableContentProviderClient(SEED_VAULT_AUTHORITY)
            return if (client != null) {
                client.close()
                true
            } else {
                ctx.packageManager.queryIntentServices(Intent(SEED_VAULT_INTENT), 0).isNotEmpty()
            }
        }
    }
}

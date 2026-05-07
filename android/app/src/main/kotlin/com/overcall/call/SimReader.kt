package com.overcall.call

import android.Manifest.permission.READ_PHONE_NUMBERS
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Reads the device's SIM-bound E.164 phone number via TelephonyManager.
 *
 * Returns null when:
 *  - READ_PHONE_NUMBERS permission isn't granted
 *  - There's no TelephonyManager (no telephony hardware — most emulators)
 *  - The carrier doesn't expose MSISDN (common — many MVNOs and eSIM-only
 *    devices return blank/null)
 *  - line1Number throws SecurityException
 *
 * The registration UI must always offer manual entry as a fallback.
 */
class SimReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED

    fun read(): String? {
        if (!hasPermission()) return null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val raw = try {
            @Suppress("MissingPermission")
            tm.line1Number
        } catch (_: SecurityException) {
            null
        }
        return raw?.takeIf { it.isNotBlank() }
    }

    /** ISO-3166 alpha-2 country code (e.g. "US") if the network reports one. */
    fun networkCountryIso(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        return tm.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
    }
}

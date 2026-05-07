package com.overcall.call

import android.content.Context
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/**
 * libphonenumber-android wrapper. Handles two operations:
 *   1. normalize(raw, defaultRegion) → strict E.164 string for protocol use
 *   2. formatForDisplay(e164) → locale-friendly INTERNATIONAL format
 *      (e.g. "+1 555-123-4567") for the bubble's recipient state
 */
class PhoneFormatter(context: Context) {
    private val util: PhoneNumberUtil = PhoneNumberUtil.createInstance(context)

    /**
     * Normalize a user-entered phone string to E.164. `defaultRegion` is the
     * ISO-3166 alpha-2 used when the input lacks a + country code prefix
     * (typically the SIM's network country). Returns null on parse failure.
     */
    fun normalize(input: String, defaultRegion: String? = null): String? {
        if (input.isBlank()) return null
        return try {
            val parsed = util.parse(input, defaultRegion ?: "US")
            if (!util.isValidNumber(parsed)) return null
            util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            null
        }
    }

    /**
     * Format an E.164 string for display (e.g. "+15551234567" → "+1 555-123-4567").
     * Falls back to the input on any parse failure.
     */
    fun formatForDisplay(e164: String): String = try {
        val parsed = util.parse(e164, null)
        util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
    } catch (_: NumberParseException) {
        e164
    }
}

package com.overcall.prefs

import android.content.Context

/**
 * Tiny SharedPreferences wrapper. We don't need DataStore — there's a
 * handful of boolean flags and the writes are infrequent.
 */
class AppPrefs(context: Context) {
    private val prefs = context
        .applicationContext
        .getSharedPreferences("overcall", Context.MODE_PRIVATE)

    /**
     * Set true after the Seeker first-launch register prompt has been
     * shown once (whether the user accepted or dismissed). Prevents
     * nagging on every cold start. Settings tab still has a manual
     * "Register from this SIM" button as the always-available retry.
     */
    var firstRegisterPromptShown: Boolean
        get() = prefs.getBoolean(KEY_FIRST_REGISTER_PROMPT, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_REGISTER_PROMPT, value).apply() }

    private companion object {
        const val KEY_FIRST_REGISTER_PROMPT = "first_register_prompt_shown"
    }
}

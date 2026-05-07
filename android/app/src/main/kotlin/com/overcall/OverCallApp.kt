package com.overcall

import android.app.Application
import com.overcall.call.CallWatcher

class OverCallApp : Application() {

    /** Public so MainActivity can re-attempt start() after a permission grant. */
    val callWatcher by lazy { CallWatcher(this) }

    override fun onCreate() {
        super.onCreate()
        // Register the telephony observer at process start. CallWatcher
        // no-ops if READ_PHONE_STATE hasn't been granted yet — MainActivity
        // calls start() again on every onResume, so a permission grant in
        // any other activity's launcher takes effect on next foreground
        // without requiring an app restart.
        callWatcher.start()
    }
}

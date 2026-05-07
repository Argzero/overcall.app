package com.overcall

import android.app.Application
import android.content.Intent
import android.util.Log
import com.overcall.call.CallWatcher
import com.overcall.overlay.OverlayController
import com.overcall.ui.PanelActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverCallApp : Application() {

    /** Public so MainActivity can re-attempt start() after a permission grant. */
    val callWatcher: CallWatcher by lazy { CallWatcher(this, overlay, _activeCallPhone) }

    /**
     * Latest E.164 of the in-progress call. `null` means no call is active
     * (idle, or call just ended). Updated by [CallWatcher] on OFFHOOK/IDLE.
     * Activities that depend on a live call context (notably [PanelActivity])
     * collect this and finish themselves when it goes null, so a "send to
     * +1234..." panel doesn't linger after hangup and let the user pay
     * post-hoc.
     */
    private val _activeCallPhone: MutableStateFlow<String?> = MutableStateFlow(null)
    val activeCallPhone: StateFlow<String?> = _activeCallPhone.asStateFlow()

    /**
     * App-scoped overlay singleton. Owned here so [CallWatcher] (telephony-
     * driven) and [com.overcall.service.OverCallForegroundService] (when it
     * does manage to start, e.g. from foreground UI) share one instance and
     * never end up showing duplicate bubble windows.
     */
    val overlay: OverlayController by lazy {
        OverlayController(
            context = this,
            onDismissed = { /* swipe-dismiss just hides until next call */ },
            onTap = { phone -> onBubbleTapped(phone) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Register the telephony observer at process start. CallWatcher
        // no-ops if READ_PHONE_STATE hasn't been granted yet — MainActivity
        // calls start() again on every onResume, so a permission grant in
        // any other activity's launcher takes effect on next foreground
        // without requiring an app restart.
        callWatcher.start()
    }

    /**
     * Bubble tap. Phone arg is the recipient set on the overlay (via
     * [OverlayController.setRecipient]) — either CallWatcher's OFFHOOK
     * capture or whatever the FGS/manual re-attach passed in. Falls back
     * to CallWatcher's tracked phone, then to MainActivity so a tap is
     * never silently ignored.
     */
    private fun onBubbleTapped(phone: String?) {
        val resolved = phone ?: callWatcher.currentPhone()
        try {
            val intent = if (resolved != null) {
                // In-call (or manual attach with a phone): open the
                // recipient-specific payment panel.
                PanelActivity.newIntent(this, resolved)
            } else {
                // No phone context (bubble re-attached manually outside
                // a call). Bring the user into the main app instead of
                // ignoring the tap.
                Log.i(TAG, "bubble tapped with no recipient — opening MainActivity")
                Intent(this, com.overcall.ui.MainActivity::class.java)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "bubble tap launch failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "OverCallApp"
    }
}

package com.overcall.call

import android.Manifest.permission.READ_PHONE_STATE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.overcall.overlay.OverlayController
import com.overcall.service.OverCallForegroundService
import java.util.concurrent.Executors

/**
 * Application-scoped observer of telephony state changes. When the device
 * enters an active call (CALL_STATE_OFFHOOK) it starts OverCallForegroundService
 * with the captured E.164 of the other party; when the call ends
 * (CALL_STATE_IDLE) it stops the service.
 *
 * Phone-number capture sources, in priority order:
 *   1. The CallStateListener's onCallStateChanged variant that includes a
 *      `phoneNumber` argument (API 31+, requires READ_CALL_LOG)
 *   2. Most-recent CallLog row at the moment of OFFHOOK
 *   3. Null (UI shows "unknown number" — bubble can still appear with
 *      manual-entry option)
 *
 * Outgoing-call capture via NEW_OUTGOING_CALL is not used here — that
 * broadcast was deprecated in Android 10 and requires being the default
 * dialer's redirection service to remain reliable. The CallLog fallback
 * covers the same ground at the cost of a ~100ms post-OFFHOOK delay.
 */
/**
 * Telephony observer + bubble auto-attacher.
 *
 * The overlay is owned by [com.overcall.OverCallApp] (app-scoped singleton)
 * and shared with [OverCallForegroundService]. We attach the overlay
 * directly from the OFFHOOK callback because Android 14+ blocks
 * `startForegroundService()` from background for FGS_TYPE_PHONE_CALL unless
 * the app is the default dialer or holds MANAGE_OWN_CALLS (a VoIP-app
 * permission, not appropriate for call observers like us). Attaching the
 * overlay window directly only requires SYSTEM_ALERT_WINDOW (the user
 * grants that explicitly in OverCall's home screen) and works regardless
 * of foreground/background state.
 *
 * The FGS is still started best-effort for [com.overcall.registry.WalletWatcher]
 * (the WS subscription that drives "received payment" bubble updates); a
 * failed FGS start no longer hides the bubble.
 */
class CallWatcher(
    private val context: Context,
    private val overlay: OverlayController,
    /**
     * Hot state of the active call's E.164. Pushed to `null` on IDLE,
     * pushed to the captured number on OFFHOOK. Activities that depend
     * on a live call context (notably PanelActivity) collect this and
     * close themselves when it goes null.
     */
    private val activeCallPhone: kotlinx.coroutines.flow.MutableStateFlow<String?>,
) {

    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var registered = false

    private var currentPhoneE164: String? = null

    /** Read by [com.overcall.OverCallApp.onBubbleTapped] to launch the panel. */
    fun currentPhone(): String? = currentPhoneE164

    @Suppress("MissingPermission")
    private val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> onOffhook()
                TelephonyManager.CALL_STATE_IDLE -> onIdle()
                TelephonyManager.CALL_STATE_RINGING -> Log.d(TAG, "ringing")
            }
        }
    }

    fun start() {
        if (registered) return
        if (ContextCompat.checkSelfPermission(context, READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted; CallWatcher idle")
            return
        }
        val tm = tm ?: return
        try {
            tm.registerTelephonyCallback(executor, callback)
            registered = true
            Log.i(TAG, "CallWatcher registered")
        } catch (e: SecurityException) {
            Log.w(TAG, "registerTelephonyCallback denied: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            tm?.unregisterTelephonyCallback(callback)
        } catch (_: Throwable) { /* ignore */ }
        registered = false
    }

    private fun onOffhook() {
        // Defensive: any throw on the telephony executor thread takes
        // OverCall down and silently kills the bubble auto-attach. Wrap
        // and log instead.
        val number = try {
            readMostRecentCallNumber()
        } catch (t: Throwable) {
            Log.w(TAG, "readMostRecentCallNumber crashed", t)
            null
        }
        Log.i(TAG, "OFFHOOK: number=${number ?: "<unknown>"}")
        currentPhoneE164 = number
        activeCallPhone.value = number

        // Attach the overlay directly. Independent of the FGS.
        try {
            overlay.setRecipient(number)
            val attached = overlay.attach()
            if (!attached) {
                Log.w(TAG, "overlay.attach() returned false — SYSTEM_ALERT_WINDOW likely missing")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "overlay attach crashed", t)
        }

        // Best-effort: also start the FGS so WalletWatcher can subscribe
        // for "received payment" events. On Android 14+ this throws
        // ForegroundServiceStartNotAllowedException from background;
        // catch and continue — the bubble is the user-visible feature
        // and is already attached.
        try {
            val intent = Intent(context, OverCallForegroundService::class.java).apply {
                action = OverCallForegroundService.ACTION_START
                putExtra(OverCallForegroundService.EXTRA_PHONE, number)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            Log.w(TAG, "FGS start blocked (bubble still attached): ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun onIdle() {
        Log.i(TAG, "IDLE: detaching bubble + stopping foreground service")
        currentPhoneE164 = null
        // Clear the overlay's cached recipient so a manual re-attach
        // (e.g. via the home-screen button) doesn't show the just-ended
        // call's phone number.
        try { overlay.setRecipient(null) } catch (_: Throwable) { /* ignore */ }
        try { overlay.detach() } catch (t: Throwable) {
            Log.w(TAG, "overlay.detach failed: ${t.message}")
        }
        // Signal call end to PanelActivity (it self-finishes when this
        // goes null so the user can't pay post-hoc to a hung-up caller).
        activeCallPhone.value = null
        try {
            context.stopService(Intent(context, OverCallForegroundService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "stopService failed: ${t.message}")
        }
    }


    /**
     * Best-effort read of the most recent CallLog entry. Returns null on
     * any failure: missing permission, vendor-specific provider quirks,
     * empty log, etc. Never throws — a CallLog read failure must NOT kill
     * the OFFHOOK handler (otherwise the foreground service never starts
     * and the bubble never attaches).
     *
     * Note on the LIMIT clause: stock Android historically accepted
     * "DATE DESC LIMIT 1" inline in sortOrder, but Samsung One UI
     * (and modern AOSP) rejects it as "Invalid token LIMIT" to block
     * SQL-injection-style suffix tricks. The Bundle query API
     * (ContentResolver.QUERY_ARG_LIMIT, API 26+) is the supported path.
     */
    private fun readMostRecentCallNumber(): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return null
        val cursor = try {
            val args = android.os.Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls.DATE} DESC")
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
            }
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                args,
                /* cancellationSignal */ null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "CallLog read failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } ?: return null
        return try {
            cursor.use {
                if (it.moveToFirst()) it.getString(0)?.takeIf { s -> s.isNotBlank() } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "CallLog cursor read failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "CallWatcher"
    }
}

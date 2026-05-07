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
class CallWatcher(private val context: Context) {

    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var registered = false

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
        val number = readMostRecentCallNumber()
        Log.i(TAG, "OFFHOOK: number=${number ?: "<unknown>"}")
        val intent = Intent(context, OverCallForegroundService::class.java).apply {
            action = OverCallForegroundService.ACTION_START
            putExtra(OverCallForegroundService.EXTRA_PHONE, number)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun onIdle() {
        Log.i(TAG, "IDLE: stopping foreground service")
        context.stopService(Intent(context, OverCallForegroundService::class.java))
    }

    /** Best-effort read of the most recent CallLog entry. Null if no permission. */
    private fun readMostRecentCallNumber(): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) return null
        val cursor = try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 1",
            )
        } catch (_: SecurityException) { null } ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf { s -> s.isNotBlank() } else null
        }
    }

    companion object {
        private const val TAG = "CallWatcher"
    }
}

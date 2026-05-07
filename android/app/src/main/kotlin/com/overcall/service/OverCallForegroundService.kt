package com.overcall.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.overcall.BuildConfig
import com.overcall.call.PhoneFormatter
import com.overcall.overlay.OverlayController
import com.overcall.pay.WalletHolder
import com.overcall.registry.RegistryClient
import com.overcall.registry.SolanaRpc
import com.overcall.registry.SolanaWebSocket
import com.overcall.registry.WalletWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Single foreground service for the in-call experience. Multi-typed
 * (phoneCall | specialUse) so we get the active-call exemption while the
 * call is live and the specialUse subtype covers the always-on overlay.
 *
 * Owns the OverlayController. Lifecycle:
 *   ACTION_START   — start FGS, attach bubble, show "On call" notification
 *   ACTION_REOPEN  — reattach bubble (after user swipe-dismissed it)
 *   ACTION_STOP    — detach bubble, stop FGS
 */
class OverCallForegroundService : Service() {

    // App-scoped overlay singleton — same instance CallWatcher uses, so we
    // never end up with duplicate bubble windows. Owned by OverCallApp.
    private val overlay: OverlayController
        get() = (applicationContext as com.overcall.OverCallApp).overlay

    private val rpc by lazy { SolanaRpc(BuildConfig.RPC_URL) }
    private val ws by lazy { SolanaWebSocket(BuildConfig.RPC_URL) }
    private val registry by lazy { RegistryClient(rpc) }
    private val phoneFormatter by lazy { PhoneFormatter(applicationContext) }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watcher: WalletWatcher? = null

    private var phoneE164: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Bubble is now attached by CallWatcher directly (Android
                // 14+ won't let us start an FGS from a background telephony
                // callback). This service still owns WalletWatcher (the WS
                // subscription that surfaces "received payment" updates on
                // the bubble) and the in-call notification. The overlay
                // attach below is a no-op when CallWatcher already attached;
                // we keep the call so manual re-attach (ACTION_REOPEN, e.g.
                // from the notification action) still works.
                phoneE164 = intent.getStringExtra(EXTRA_PHONE)
                if (!tryPromoteForeground()) return START_NOT_STICKY
                overlay.setRecipient(phoneE164)
                overlay.attach()
                startWalletWatcher()
            }
            ACTION_REOPEN -> {
                if (!tryPromoteForeground()) return START_NOT_STICKY
                overlay.setRecipient(phoneE164)
                overlay.attach()
            }
            ACTION_STOP -> {
                // Don't touch the overlay here — CallWatcher owns the
                // bubble lifecycle (attach on OFFHOOK, detach on IDLE),
                // and the FGS may stop while a call is still active
                // (e.g. when Android 14+ refuses our foreground promotion).
                stopWalletWatcher()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Same reasoning as ACTION_STOP: the bubble is owned by
        // CallWatcher (app-scoped), not by this service. FGS getting
        // torn down (lmkd, system memory pressure, foreground-promotion
        // refusal) must NOT yank the bubble out from under an active
        // call.
        stopWalletWatcher()
        scope.cancel()
    }

    private fun startWalletWatcher() {
        if (watcher != null) return
        val self = WalletHolder.pubkey() ?: run {
            Log.i(TAG, "no connected wallet — recipient watcher not started")
            return
        }
        val w = WalletWatcher(ws, registry, self)
        watcher = w
        w.start(scope)
        scope.launch {
            w.events.collect { event ->
                when (event) {
                    is WalletWatcher.Event.PaymentReceived -> onPaymentReceived(event)
                }
            }
        }
        Log.i(TAG, "WalletWatcher started for ${self.base58().take(8)}…")
    }

    private fun stopWalletWatcher() {
        watcher?.stop()
        watcher = null
    }

    private fun onPaymentReceived(event: WalletWatcher.Event.PaymentReceived) {
        val displayPhone = event.senderPhoneE164?.let { phoneFormatter.formatForDisplay(it) }
            ?: "<unknown>"
        val amountText = formatAmount(event.amount, event.mint)
        overlay.showReceived(displayPhone = displayPhone, amountText = amountText)
        Log.i(TAG, "Received $amountText from $displayPhone")
    }

    private fun formatAmount(amountBaseUnits: Long, mint: com.solana.publickey.SolanaPublicKey): String {
        // SOL = mint pubkey is the all-zeros sentinel emitted by pay_sol.
        val isSol = mint.bytes.all { it == 0.toByte() }
        // Devnet test mints we know: dSKR (6dec), dUSDC (6dec), dWSOL (9dec).
        val decimals = when {
            isSol -> 9
            mint.base58() == "ACR8rbY1XZdnpoxWekbMm3hw8SzANMYcrR31BPXcNH2d" -> 9 // dWSOL
            else -> 6 // dSKR / dUSDC default
        }
        val whole = amountBaseUnits.toDouble() / Math.pow(10.0, decimals.toDouble())
        val symbol = when {
            isSol -> "SOL"
            mint.base58() == "6K5waTfHBfww76rkr6zwGqk52EhCiWBpAwNoxt4GUzyU" -> "dSKR"
            mint.base58() == "2Mj7KWrVUYExnivQj1y7NtQA2FQWSvfrkYaAL83KGpeH" -> "dUSDC"
            mint.base58() == "ACR8rbY1XZdnpoxWekbMm3hw8SzANMYcrR31BPXcNH2d" -> "dWSOL"
            else -> mint.base58().take(4)
        }
        return "%.6f %s".format(whole, symbol)
    }

    private fun startAsForeground(showReopenAction: Boolean) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(showReopenAction), types)
    }

    /**
     * Promote to foreground, catching the Android 14+
     * `ForegroundServiceStartNotAllowedException` that fires when our
     * process is too "background" to host a phoneCall-type FGS. On
     * failure we self-stop instead of crashing the whole app — the
     * bubble is already attached by CallWatcher (overlay path), so the
     * user-visible feature still works; we just lose the WalletWatcher
     * WS subscription for this call.
     */
    private fun tryPromoteForeground(): Boolean = try {
        startAsForeground(showReopenAction = false)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "FGS promotion blocked (${t.javaClass.simpleName}: ${t.message}) — service self-stopping", t)
        try { stopSelf() } catch (_: Throwable) { /* ignore */ }
        false
    }

    private fun refreshNotification(showReopenAction: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(showReopenAction))
    }

    private fun buildNotification(showReopenAction: Boolean): Notification {
        val text = phoneE164?.let { "On call with $it" } ?: "On call"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("OverCall")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (showReopenAction) {
            val reopenIntent = Intent(this, OverCallForegroundService::class.java).apply {
                action = ACTION_REOPEN
            }
            val pi = PendingIntent.getService(
                this, 0, reopenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_view,
                "Reopen Pay Bubble",
                pi,
            )
        }
        return builder.build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "In-call overlay",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while OverCall is active during a phone call."
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "OverCallFGS"
        const val CHANNEL_ID = "overcall.in_call"
        const val NOTIF_ID = 7474
        const val ACTION_START = "com.overcall.service.action.START"
        const val ACTION_STOP = "com.overcall.service.action.STOP"
        const val ACTION_REOPEN = "com.overcall.service.action.REOPEN"
        const val EXTRA_PHONE = "phone_e164"
    }
}

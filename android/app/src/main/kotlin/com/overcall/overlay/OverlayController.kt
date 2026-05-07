package com.overcall.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Floating bubble overlay drawn at TYPE_APPLICATION_OVERLAY. Handles the
 * YouTube-PiP-style mechanics:
 *
 *  - drag freely while a finger is down
 *  - on release, fling to the nearest left/right screen edge
 *  - swipe straight down past a threshold to dismiss
 *  - tap (no-drag) reserved for future expand-to-panel (Task 19)
 *
 * Single instance per process — owned by OverCallForegroundService and
 * attached/detached based on call state. attach() is a no-op without
 * SYSTEM_ALERT_WINDOW permission; the service surfaces that as a
 * notification.
 */
class OverlayController(
    private val context: Context,
    private val onDismissed: () -> Unit = {},
    private val onTap: () -> Unit = {},
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var bubble: View? = null
    private var phoneE164: String? = null

    fun isAttached(): Boolean = bubble != null

    fun setRecipient(phoneE164: String?) {
        this.phoneE164 = phoneE164
        // If already attached, refresh the label
        bubble?.findViewById<TextView>(LABEL_ID)?.text = phoneE164 ?: ""
    }

    /**
     * Update the bubble to the recipient-variant payment-received card.
     * `displayPhone` is already locale-formatted via libphonenumber, e.g.
     * "+1 555-123-4567"; pass "<unknown>" or similar when the sender isn't
     * in the registry.
     */
    fun showReceived(displayPhone: String, amountText: String) {
        val label = bubble?.findViewById<TextView>(LABEL_ID) ?: return
        label.text = "Received $amountText from $displayPhone"
        // Switch the pill background to a green accent. The icon is left
        // alone so the bubble still reads as "in a call" at a glance.
        (label.background as? android.graphics.drawable.GradientDrawable)?.apply {
            setColor(0xCC15803D.toInt())
            setStroke(
                (context.resources.displayMetrics.density).toInt(),
                0xFF22C55E.toInt(),
            )
        }
    }

    fun attach(): Boolean {
        if (bubble != null) return true
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay not attached")
            return false
        }
        val view = createBubble()
        try {
            wm.addView(view, layoutParams())
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed: ${t.message}")
            return false
        }
        bubble = view
        return true
    }

    fun detach() {
        bubble?.let {
            try { wm.removeView(it) } catch (_: Throwable) { /* ignore */ }
        }
        bubble = null
    }

    private fun createBubble(): View {
        val density = context.resources.displayMetrics.density
        val sizePx = (BUBBLE_DP * density).toInt()
        val labelPaddingPx = (LABEL_PADDING_DP * density).toInt()

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val pill = TextView(context).apply {
            id = LABEL_ID
            text = phoneE164 ?: ""
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(labelPaddingPx, labelPaddingPx / 2, labelPaddingPx, labelPaddingPx / 2)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f * density
                setColor(0xCC000000.toInt())
                setStroke((1f * density).toInt(), 0xFF60A5FA.toInt())
            }
        }

        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.sym_call_outgoing)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1D4ED8.toInt())
            }
            val padPx = (12 * density).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }

        val iconLp = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        val pillLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = sizePx + (4 * density).toInt()
        }
        container.addView(icon, iconLp)
        container.addView(pill, pillLp)

        attachGestures(container)
        return container
    }

    private fun attachGestures(view: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val dismissThresholdPx =
            (DISMISS_DP * context.resources.displayMetrics.density).toInt()

        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            val lp = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!moved && abs(dx) < touchSlop && abs(dy) < touchSlop) {
                        return@setOnTouchListener true
                    }
                    moved = true
                    lp.x = (startX + dx.toInt()).coerceAtLeast(0)
                    lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                    try { wm.updateViewLayout(view, lp) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    when {
                        !moved -> onTap()
                        dy > dismissThresholdPx && abs(dy) > abs(dx) -> {
                            // straight-down swipe past threshold — dismiss
                            detach()
                            onDismissed()
                        }
                        else -> snapToEdge(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { moved = false; true }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        val centerX = lp.x + view.width / 2
        lp.x = if (centerX < metrics.widthPixels / 2) 0 else metrics.widthPixels - view.width
        try { wm.updateViewLayout(view, lp) } catch (_: Throwable) {}
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * context.resources.displayMetrics.density).toInt()
            y = (160 * context.resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        private const val TAG = "OverlayController"
        private const val BUBBLE_DP = 56
        private const val LABEL_PADDING_DP = 16
        private const val DISMISS_DP = 120
        private const val LABEL_ID = 0x7f001000
    }
}

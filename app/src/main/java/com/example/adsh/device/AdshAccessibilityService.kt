package com.example.adsh.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.example.adsh.MainActivity
import com.example.adsh.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Holds the live service instance so the loopback bridge can read and operate
 * the foreground window. Enabling it is always an explicit user action in the
 * system accessibility settings.
 *
 * It also owns the optional floating wake bubble. The bubble uses an
 * accessibility overlay window, so it needs no SYSTEM_ALERT_WINDOW grant;
 * a tap opens chat with dictation, a long press opens chat only.
 */
class AdshAccessibilityService : AccessibilityService() {
    companion object {
        private const val PREFS = "adsh_wake"
        private const val KEY_BUBBLE = "bubble_enabled"
        private const val KEY_HOME_WAKE = "home_wake_enabled"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val BUBBLE_DP = 56
        private const val LONG_PRESS_MS = 520L
        private const val HOME_WAKE_MS = 3_000L

        @Volatile private var active: AdshAccessibilityService? = null

        fun instance(): AdshAccessibilityService? = active

        fun isEnabled(context: Context): Boolean {
            if (active != null) return true
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isBubbleEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_BUBBLE, false)

        fun setBubbleEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_BUBBLE, enabled).apply()
            active?.applyBubblePreference()
        }

        fun isHomeWakeEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_HOME_WAKE, true)

        fun setHomeWakeEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_HOME_WAKE, enabled).apply()
        }

        /** Re-applies the stored preference; safe to call from the settings UI. */
        fun refreshBubble(context: Context) {
            active?.applyBubblePreference()
        }
    }

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var homeDownAt = 0L
    private var homeDown = false
    private var homeTriggered = false
    private val homeWakeRunnable = Runnable {
        if (homeDown && !homeTriggered && isHomeWakeEnabled(this)) {
            homeTriggered = true
            openChat(withVoice = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        applyBubblePreference()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Best-effort 3 s Home-key wake. Many Android builds deliver Home to the system before
     * accessibility key filtering, so the default-assistant role is the reliable trigger.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (!isHomeWakeEnabled(this)) return false
        if (event.action == KeyEvent.ACTION_UP) {
            homeDown = false
            homeDownAt = 0L
            mainHandler.removeCallbacks(homeWakeRunnable)
            val consumed = homeTriggered
            homeTriggered = false
            return consumed
        }
        if (homeTriggered) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                homeDown = true
                homeDownAt = SystemClock.uptimeMillis()
                homeTriggered = false
                mainHandler.removeCallbacks(homeWakeRunnable)
                mainHandler.postDelayed(homeWakeRunnable, HOME_WAKE_MS)
            } else if (SystemClock.uptimeMillis() - homeDownAt >= HOME_WAKE_MS) {
                homeTriggered = true
                openChat(withVoice = true)
            }
        }
        return false
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(homeWakeRunnable)
        removeBubble()
        if (active === this) active = null
        super.onDestroy()
    }

    private fun applyBubblePreference() {
        if (isBubbleEnabled(this)) showBubble() else removeBubble()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showBubble() {
        if (bubble != null) return
        val manager = windowManager ?: return
        val size = dp(BUBBLE_DP)
        val stored = prefs(this)
        val metrics = resources.displayMetrics
        val defaultX = metrics.widthPixels - dp(BUBBLE_DP + 12)
        val defaultY = (metrics.heightPixels * 0.62f).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = stored.getInt(KEY_X, defaultX)
            y = stored.getInt(KEY_Y, defaultY)
        }
        val view = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF21A73E8.toInt())
            }
            elevation = dp(6).toFloat()
            contentDescription = getString(R.string.wake_bubble_label)
            addView(
                ImageView(this@AdshAccessibilityService).apply {
                    setImageResource(R.drawable.ic_mic)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                    scaleType = ImageView.ScaleType.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }
        attachTouchHandler(view, params)
        val added = runCatching { manager.addView(view, params) }.isSuccess
        if (!added) return
        bubble = view
        bubbleParams = params
    }

    private fun attachTouchHandler(view: View, params: WindowManager.LayoutParams) {
        val slop = dp(6)
        var downAt = 0L
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) dragged = true
                    if (dragged) {
                        params.x = clampX(startX + dx)
                        params.y = clampY(startY + dy)
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        prefs(this).edit()
                            .putInt(KEY_X, params.x)
                            .putInt(KEY_Y, params.y)
                            .apply()
                    } else {
                        val longPress = SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS
                        openChat(withVoice = !longPress)
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun clampX(x: Int): Int {
        val metrics = resources.displayMetrics
        return max(0, min(x, metrics.widthPixels - dp(BUBBLE_DP)))
    }

    private fun clampY(y: Int): Int {
        val metrics = resources.displayMetrics
        return max(0, min(y, metrics.heightPixels - dp(BUBBLE_DP)))
    }

    private fun openChat(withVoice: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            putExtra(MainActivity.EXTRA_VOICE_START, withVoice)
        }
        runCatching { startActivity(intent) }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        bubbleParams = null
    }
}

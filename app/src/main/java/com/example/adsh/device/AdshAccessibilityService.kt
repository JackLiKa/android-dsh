package com.example.adsh.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.adsh.MainActivity
import com.example.adsh.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private var approvalView: View? = null
    private var approvalId: String? = null
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
        DeviceGovernor.pending.value?.let { showApproval(it) }
    }

    /**
     * Shows the approval card as an accessibility overlay. Unlike the in-app
     * dialog this keeps the target app in the foreground, so its keyboard,
     * scroll position and control coordinates stay valid while the user decides.
     */
    fun showApproval(approval: PendingApproval) {
        mainHandler.post {
            if (active !== this) return@post
            if (rootInActiveWindow?.packageName?.toString() == packageName) return@post
            val manager = windowManager ?: return@post
            removeApprovalView()
            val density = resources.displayMetrics.density
            fun px(value: Int) = (value * density).toInt()

            fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
                TextView(this).apply {
                    this.text = text
                    setTextColor(color)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                    if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
                }

            val remember = CheckBox(this).apply {
                text = "始终允许此应用中的这一操作"
                setTextColor(0xFF3A3A3C.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(16), px(20), px(12))
                background = GradientDrawable().apply {
                    cornerRadius = px(18).toFloat()
                    setColor(0xF7FFFFFF.toInt())
                }
                elevation = px(10).toFloat()
                addView(label("需要确认：${approval.title}", 17f, 0xFF1B1B1F.toInt(), bold = true))
                addView(
                    label(
                        approval.summary.replace('\n', ' ') + "\n60 秒内未处理将自动拒绝。",
                        13f,
                        0xFF4A4A4F.toInt(),
                    ).apply { setPadding(0, px(8), 0, 0) },
                )
                addView(remember.apply { setPadding(0, px(4), 0, 0) })
                addView(
                    LinearLayout(this@AdshAccessibilityService).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        addView(
                            Button(this@AdshAccessibilityService).apply {
                                text = "拒绝"
                                setOnClickListener {
                                    DeviceGovernor.resolve(approval.id, DeviceDecision.DENY, false)
                                }
                            },
                        )
                        addView(
                            Button(this@AdshAccessibilityService).apply {
                                text = "允许一次"
                                setOnClickListener {
                                    DeviceGovernor.resolve(
                                        approval.id,
                                        DeviceDecision.ALLOW,
                                        remember.isChecked,
                                    )
                                }
                            },
                        )
                    },
                )
            }
            val params = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
                val inset = if (statusBar > 0) {
                    resources.getDimensionPixelSize(statusBar)
                } else {
                    px(24)
                }
                y = inset + px(8)
            }
            if (runCatching { manager.addView(card, params) }.isSuccess) {
                approvalView = card
                approvalId = approval.id
            }
        }
    }

    fun dismissApproval(id: String? = null) {
        mainHandler.post {
            if (id != null && approvalId != null && id != approvalId) return@post
            removeApprovalView()
        }
    }

    private fun removeApprovalView() {
        approvalView?.let { runCatching { windowManager?.removeView(it) } }
        approvalView = null
        approvalId = null
    }

    /**
     * Captures the display for offline OCR. WeChat and Douyin publish an empty
     * accessibility tree, so a screenshot plus text coordinates is the only
     * way to locate visible controls in those apps.
     */
    fun captureScreen(timeoutMs: Long = 5_000): Bitmap {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            throw IllegalStateException("Android 11 以下不支持无障碍截屏")
        }
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var failure: String? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                failure = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截屏太频繁，请稍后再试"
                    ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "截屏显示无效"
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                        "无障碍截屏权限未生效，请重新开启无障碍服务"
                    else -> "截屏失败（错误码 $errorCode）"
                }
                latch.countDown()
            }
        })
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("截屏超时，请保持屏幕亮起后重试")
        }
        failure?.let { throw IllegalStateException(it) }
        return bitmap ?: throw IllegalStateException("截屏返回空图像")
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
        removeApprovalView()
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

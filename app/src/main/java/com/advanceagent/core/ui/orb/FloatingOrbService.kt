package com.advanceagent.core.ui.orb

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.advanceagent.core.R
import com.advanceagent.core.service.WakeWordForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingOrbService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var orbBgDrawable: GradientDrawable? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = (64 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC0A111A"))
                setStroke((2.5f * resources.displayMetrics.density).toInt(), Color.parseColor("#00E5FF"))
            }
            orbBgDrawable = bg
            background = bg
            elevation = 16f
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        container.addView(iconView, FrameLayout.LayoutParams(sizePx, sizePx))

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTimestamp = 0L

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTimestamp = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val pressDuration = System.currentTimeMillis() - downTimestamp
                    if (dx < 15 && dy < 15) {
                        if (pressDuration >= 550L) {
                            // Long press opens the main NAYRO application
                            val openAppIntent = Intent(this, com.example.MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            startActivity(openAppIntent)
                        } else {
                            // Single tap triggers or interrupts Gemini Live session
                            val triggerIntent = Intent(this, WakeWordForegroundService::class.java).apply {
                                action = WakeWordForegroundService.ACTION_MANUAL_TRIGGER
                            }
                            startService(triggerIntent)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = container
        windowManager?.addView(floatingView, params)

        stateObserverJob = serviceScope.launch {
            WakeWordForegroundService.agentOrbStateFlow.collectLatest { state ->
                updateOrbVisualState(state)
            }
        }
    }

    private fun updateOrbVisualState(state: String) {
        val strokePx = (2.5f * resources.displayMetrics.density).toInt()
        val strokeColor = when (state.uppercase()) {
            "LISTENING" -> Color.parseColor("#00E676") // Neon emerald green
            "THINKING", "EXECUTING", "CONNECTING" -> Color.parseColor("#FFB300") // Amber gold
            "SPEAKING" -> Color.parseColor("#7C4DFF") // Electric violet
            "ERROR" -> Color.parseColor("#FF5252") // Red alert
            "OFFLINE" -> Color.parseColor("#78909C") // Muted slate
            else -> Color.parseColor("#00E5FF") // Idle cyan
        }
        orbBgDrawable?.setStroke(strokePx, strokeColor)
        floatingView?.invalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stateObserverJob?.cancel()
        serviceScope.cancel()
        try {
            if (floatingView != null) {
                windowManager?.removeView(floatingView)
                floatingView = null
            }
        } catch (_: Exception) {}
    }
}

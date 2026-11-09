package com.example.core.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * System B — Android Floating Assistant Service for Babu.
 * Uses Android WindowManager with TYPE_APPLICATION_OVERLAY.
 * Implements draggable bubble, edge docking, panel expansion, and lifecycle coordination.
 */
class NayroFloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "nayro_floating_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.nayro.floating.START"
        const val ACTION_STOP = "com.example.nayro.floating.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun startService(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                return
            }
            val intent = Intent(context, NayroFloatingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, NayroFloatingService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: FloatingAssistantPreferences

    // Views
    private var bubbleRootView: FrameLayout? = null
    private var panelRootView: FrameLayout? = null

    // Layout Params
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var isPanelOpen = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = FloatingAssistantPreferences(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeOverlayViews()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            _isRunning.value = false
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            _isRunning.value = false
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        initBubbleView()
        _isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlayViews()
        _isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NAYRO Floating Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status notification while Babu floating assistant bubble is active."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NayroFloatingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Babu Floating Assistant Active")
            .setContentText("Draggable overlay bubble ready. Tap to open NAYRO.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close Overlay", stopPendingIntent)
            .build()
    }

    private fun initBubbleView() {
        if (bubbleRootView != null) return // Avoid duplicate window

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (savedX, savedY) = prefs.getPosition()

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        val dpSize = (56 * resources.displayMetrics.density).toInt()

        // Create Custom Babu Floating Bubble View Programmatically with Exact NAYRO Colors
        val bubble = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(dpSize, dpSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#153B35"), // Forest Green
                    Color.parseColor("#0E2925")  // Deep Green
                )
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpSize * 0.7f
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#D56A50")) // Coral ring
            }
        }

        val innerIcon = ImageView(this).apply {
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.parseColor("#F6F2E8")) // Warm Ivory
        }
        bubble.addView(innerIcon)

        // Touch listener for dragging and click detection
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDrag = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDrag = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDrag = true
                        }
                        bubbleParams.x = initialX + dx
                        bubbleParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(bubbleRootView, bubbleParams)
                        } catch (e: Exception) {
                            // View might be detached
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDrag) {
                            // Snap to nearest screen edge (left or right)
                            val screenWidth = resources.displayMetrics.widthPixels
                            val margin = (16 * resources.displayMetrics.density).toInt()
                            bubbleParams.x = if (bubbleParams.x + dpSize / 2 < screenWidth / 2) {
                                margin
                            } else {
                                screenWidth - dpSize - margin
                            }
                            try {
                                windowManager.updateViewLayout(bubbleRootView, bubbleParams)
                            } catch (e: Exception) {}
                            prefs.savePosition(bubbleParams.x, bubbleParams.y)
                        } else {
                            togglePanel()
                        }
                        return true
                    }
                }
                return false
            }
        })

        bubbleRootView = bubble
        try {
            windowManager.addView(bubbleRootView, bubbleParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun togglePanel() {
        if (isPanelOpen) {
            closePanel()
        } else {
            openPanel()
        }
    }

    private fun openPanel() {
        if (panelRootView != null) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        panelParams = WindowManager.LayoutParams(
            (320 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * resources.displayMetrics.density
                setColor(Color.parseColor("#182521")) // DarkSurface
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#153B35")) // Forest Green
            }
        }

        // Header
        val header = TextView(this).apply {
            text = "NAYRO • Babu Assistant"
            setTextColor(Color.parseColor("#F6F2E8"))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panel.addView(header)

        val subHeader = TextView(this).apply {
            text = "Private Personal Voice Agent (Ready)"
            setTextColor(Color.parseColor("#DCE7DE"))
            textSize = 13f
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        panel.addView(subHeader)

        // Text input field
        val input = EditText(this).apply {
            hint = "Ask Babu anything..."
            setHintTextColor(Color.parseColor("#6F7C76"))
            setTextColor(Color.parseColor("#F6F2E8"))
            textSize = 14f
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(Color.parseColor("#0E2925"))
            }
        }
        panel.addView(input)

        // Button row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val sendBtn = Button(this).apply {
            text = "Send"
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#153B35"))
            setOnClickListener {
                closePanel()
                val mainIntent = Intent(this@NayroFloatingService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(mainIntent)
            }
        }
        btnRow.addView(sendBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(this).apply {
            text = "Close"
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#D56A50"))
            setOnClickListener {
                closePanel()
            }
        }
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        panel.addView(btnRow)

        val root = FrameLayout(this).apply {
            addView(panel)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    closePanel()
                    true
                } else false
            }
        }

        panelRootView = root
        try {
            windowManager.addView(panelRootView, panelParams)
            isPanelOpen = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closePanel() {
        if (panelRootView != null) {
            try {
                windowManager.removeView(panelRootView)
            } catch (e: Exception) {}
            panelRootView = null
        }
        isPanelOpen = false
    }

    private fun removeOverlayViews() {
        closePanel()
        if (bubbleRootView != null) {
            try {
                windowManager.removeView(bubbleRootView)
            } catch (e: Exception) {}
            bubbleRootView = null
        }
    }
}

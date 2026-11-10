package com.example.core.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.service.ServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * System B — Android Floating Assistant Service for Babu.
 * Real WindowManager TYPE_APPLICATION_OVERLAY implementation.
 * Fixes:
 * 1. Floating assistant input submission to main conversation pipeline
 * 2. Proper window creation verification & lifecycle states
 * 3. Empty input rejection and state reporting
 */
class NayroFloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "nayro_floating_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.nayro.floating.START"
        const val ACTION_STOP = "com.example.nayro.floating.STOP"

        const val EXTRA_FLOATING_PROMPT = "com.example.nayro.extra.FLOATING_PROMPT"

        val status: StateFlow<ServiceStatus> = ServiceStateRepository.floatingServiceStatus

        fun startService(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                ServiceStateRepository.updateFloatingStatus(
                    ServiceLifecycleState.PERMISSION_DENIED,
                    "Overlay permission (SYSTEM_ALERT_WINDOW) not granted."
                )
                return
            }

            ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STARTING)
            val intent = Intent(context, NayroFloatingService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                ServiceStateRepository.updateFloatingStatus(
                    ServiceLifecycleState.FAILED,
                    "Failed to start floating service: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
        }

        fun stopService(context: Context) {
            ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPING)
            val intent = Intent(context, NayroFloatingService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPED)
            }
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
            ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPING)
            removeOverlayViews()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPED)
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            ServiceStateRepository.updateFloatingStatus(
                ServiceLifecycleState.PERMISSION_DENIED,
                "SYSTEM_ALERT_WINDOW revoked by user."
            )
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val windowAdded = initBubbleView()
            if (windowAdded) {
                ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.RUNNING)
            } else {
                ServiceStateRepository.updateFloatingStatus(
                    ServiceLifecycleState.FAILED,
                    "WindowManager failed to attach floating bubble view."
                )
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            ServiceStateRepository.updateFloatingStatus(
                ServiceLifecycleState.FAILED,
                "Floating window initialization error: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlayViews()
        ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPED)
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
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
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

    private fun initBubbleView(): Boolean {
        if (bubbleRootView != null) return true // Already created

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
        return try {
            windowManager.addView(bubbleRootView, bubbleParams)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
            text = "Voice & Text Assistant (Connected)"
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
            setBackgroundColor(Color.parseColor("#6F7C76")) // Start disabled color
            isEnabled = false
        }

        // Enable send only when input is non-empty
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                sendBtn.isEnabled = hasText
                sendBtn.setBackgroundColor(
                    if (hasText) Color.parseColor("#153B35") else Color.parseColor("#6F7C76")
                )
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val serviceScope = CoroutineScope(Dispatchers.Default)

        sendBtn.setOnClickListener {
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) {
                Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            closePanel()

            // Transmit user text directly to AgentRuntime and ConversationManager
            serviceScope.launch {
                try {
                    val app = com.example.NayroApplication.instance
                    app.repository.addConversationMessage(
                        com.example.data.local.entities.ConversationEntity(sender = "user", content = userText)
                    )
                    val responseText = app.agentRuntime.executeUserInstruction(userText)
                    app.repository.addConversationMessage(
                        com.example.data.local.entities.ConversationEntity(sender = "babu", content = responseText)
                    )
                    if (app.voiceManager.configuration.value.isTtsEnabled) {
                        app.voiceManager.speak(responseText)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Bring MainActivity to foreground to display the updated conversation without re-executing the prompt
            val mainIntent = Intent(this@NayroFloatingService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            try {
                startActivity(mainIntent)
            } catch (e: Exception) {}
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
            panelRootView = null
            isPanelOpen = false
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

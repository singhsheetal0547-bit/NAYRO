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
import android.graphics.Typeface
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
import com.example.NayroApplication
import com.example.R
import com.example.core.agent.AgentState
import com.example.core.agent.toUserFriendlyDescription
import com.example.core.agent.toUserFriendlyIntentLabel
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.service.ServiceStatus
import com.example.core.voice.VoiceState
import com.example.data.local.entities.ConversationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * System B — Andee-Style Floating Babu Agent Service.
 * Genuine WindowManager TYPE_APPLICATION_OVERLAY implementation connected directly
 * to the single unified AgentRuntime.
 * Supports:
 * - Draggable floating icon with automatic screen-edge snapping & position persistence
 * - Compact interactive overlay panel with live AgentState & Task progress synchronization
 * - In-overlay text input + Send execution without leaving the current app
 * - In-overlay Voice listen toggle connected to VoiceManager & AgentRuntime
 * - Inline Confirmation Approve / Reject controls for R2/R3 actions
 * - Quick Screen Read & Stop Task triggers, Minimize, Expand to Full App, and Hide Overlay
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
            FloatingAssistantPreferences(context).setFloatingEnabled(true)
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
            FloatingAssistantPreferences(context).setFloatingEnabled(false)
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectorJob: Job? = null

    // Views
    private var bubbleRootView: FrameLayout? = null
    private var panelRootView: FrameLayout? = null

    // Dynamic UI elements inside the open panel
    private var stateBadgeView: TextView? = null
    private var responsePreviewView: TextView? = null
    private var confirmationContainer: LinearLayout? = null
    private var confirmationTextView: TextView? = null
    private var voiceBtnView: Button? = null

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
            prefs.setFloatingEnabled(false)
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
                prefs.setFloatingEnabled(true)
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
        stateCollectorJob?.cancel()
        serviceScope.cancel()
        removeOverlayViews()
        ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubbleRootView != null && ::bubbleParams.isInitialized) {
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val margin = dp(12)
            val dpSize = dp(58)
            bubbleParams.x = if (bubbleParams.x + dpSize / 2 < screenWidth / 2) {
                margin
            } else {
                (screenWidth - dpSize - margin).coerceAtLeast(margin)
            }
            bubbleParams.y = bubbleParams.y.coerceIn(margin, (screenHeight - dpSize - margin).coerceAtLeast(margin))
            try {
                windowManager.updateViewLayout(bubbleRootView, bubbleParams)
                prefs.savePosition(bubbleParams.x, bubbleParams.y)
            } catch (_: Exception) {}
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NAYRO Floating Babu Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status notification while Babu floating assistant overlay is active."
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
            .setContentTitle("Babu Floating Agent Active")
            .setContentText("Tap floating bubble anywhere to talk or run commands.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide Bubble", stopPendingIntent)
            .build()
    }

    private fun initBubbleView(): Boolean {
        if (bubbleRootView != null) return true

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

        val dpSize = dp(58)

        val bubble = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(dpSize, dpSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#F1F5F9")
                )
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpSize * 0.75f
                setStroke(dp(3), Color.parseColor("#0F3D36"))
            }
            elevation = dp(8).toFloat()
        }

        val innerBadge = TextView(this).apply {
            text = "B"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F3D36"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        bubble.addView(innerIconOrBadge(innerBadge))

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
                        } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDrag) {
                            val screenWidth = resources.displayMetrics.widthPixels
                            val margin = dp(12)
                            bubbleParams.x = if (bubbleParams.x + dpSize / 2 < screenWidth / 2) {
                                margin
                            } else {
                                screenWidth - dpSize - margin
                            }
                            try {
                                windowManager.updateViewLayout(bubbleRootView, bubbleParams)
                            } catch (_: Exception) {}
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

    private fun innerIconOrBadge(badge: TextView): View {
        val container = FrameLayout(this)
        val innerCircle = FrameLayout(this).apply {
            val innerSize = dp(42)
            layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F3D36"))
            }
        }
        badge.setTextColor(Color.parseColor("#FFFFFF"))
        innerCircle.addView(badge)
        container.addView(innerCircle)
        return container
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
            dp(344),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dp(2), Color.parseColor("#CBD5E1"))
            }
            elevation = dp(12).toFloat()
        }

        // Top Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val header = TextView(this).apply {
            text = "Babu • Autonomous Agent"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        titleCol.addView(header)

        val stateBadge = TextView(this).apply {
            text = "State: Idle • Ready"
            setTextColor(Color.parseColor("#0F3D36"))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        }
        stateBadgeView = stateBadge
        titleCol.addView(stateBadge)
        headerRow.addView(titleCol)

        val expandBtn = Button(this).apply {
            text = "Open App"
            textSize = 11f
            setTextColor(Color.parseColor("#0F3D36"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            setOnClickListener {
                closePanel()
                val mainIntent = Intent(this@NayroFloatingService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                try {
                    startActivity(mainIntent)
                } catch (_: Exception) {}
            }
        }
        headerRow.addView(expandBtn)
        panel.addView(headerRow)

        // Response / Live Output Box
        val responseBox = TextView(this).apply {
            text = "Ask Babu to open apps, read the current screen, send messages, or run multi-step tasks."
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            val p = dp(10)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        responsePreviewView = responseBox
        panel.addView(responseBox)

        // Dynamic Confirmation Container (shown only when ConfirmationManager has a pending request)
        val confBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val p = dp(10)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FFF7ED"))
                setStroke(dp(1), Color.parseColor("#D56A50"))
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        val confLabel = TextView(this).apply {
            text = "Confirmation Required"
            setTextColor(Color.parseColor("#9A3412"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        confBox.addView(confLabel)

        val confRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val approveBtn = Button(this).apply {
            text = "Approve"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#15803D"))
            setOnClickListener {
                try {
                    val app = NayroApplication.instance
                    val pending = app.agentRuntime.confirmationManager.pendingConfirmation.value
                    if (pending != null) {
                        app.agentRuntime.confirmationManager.approve(pending.actionId, pending.parameters)
                    }
                } catch (_: Exception) {}
            }
        }
        val rejectBtn = Button(this).apply {
            text = "Reject"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B91C1C"))
            setOnClickListener {
                try {
                    val app = NayroApplication.instance
                    val pending = app.agentRuntime.confirmationManager.pendingConfirmation.value
                    if (pending != null) {
                        app.agentRuntime.confirmationManager.reject(pending.actionId)
                    }
                } catch (_: Exception) {}
            }
        }
        confRow.addView(approveBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        confRow.addView(rejectBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        confBox.addView(confRow)
        confirmationContainer = confBox
        confirmationTextView = confLabel
        panel.addView(confBox)

        // Quick Action Row inside Overlay
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        val readScreenBtn = Button(this).apply {
            text = "Read Screen"
            textSize = 11f
            setTextColor(Color.parseColor("#0F3D36"))
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setOnClickListener {
                dispatchOverlayCommand("Screen par kya likha hai?")
            }
        }
        val pauseResumeBtn = Button(this).apply {
            text = "Pause"
            textSize = 11f
            setTextColor(Color.parseColor("#B45309"))
            setBackgroundColor(Color.parseColor("#FEF3C7"))
            setOnClickListener {
                try {
                    val runtime = NayroApplication.instance.agentRuntime
                    if (runtime.isPaused()) {
                        runtime.resumeCurrentTask()
                        text = "Pause"
                        responsePreviewView?.text = "Resumed active task."
                    } else {
                        runtime.pauseCurrentTask()
                        text = "Resume"
                        responsePreviewView?.text = "Paused active task."
                    }
                } catch (_: Exception) {}
            }
        }
        val stopTaskBtn = Button(this).apply {
            text = "Stop Task"
            textSize = 11f
            setTextColor(Color.parseColor("#B91C1C"))
            setBackgroundColor(Color.parseColor("#FEF2F2"))
            setOnClickListener {
                try {
                    NayroApplication.instance.agentRuntime.cancelCurrentTask()
                    responsePreviewView?.text = "Current task cancelled."
                } catch (_: Exception) {}
            }
        }
        quickRow.addView(readScreenBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickRow.addView(pauseResumeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickRow.addView(stopTaskBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(quickRow)

        // Text Input Field
        val input = EditText(this).apply {
            hint = "Ask Babu anything (Hindi / English)..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 14f
            val p = dp(12)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#94A3B8"))
            }
        }
        panel.addView(input)

        // Primary Action Row: Send | Voice | Minimize | Hide
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val sendBtn = Button(this).apply {
            text = "Send"
            textSize = 12f
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#94A3B8"))
            isEnabled = false
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                sendBtn.isEnabled = hasText
                sendBtn.setBackgroundColor(
                    if (hasText) Color.parseColor("#0F3D36") else Color.parseColor("#94A3B8")
                )
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        sendBtn.setOnClickListener {
            val userText = input.text.toString().trim()
            if (userText.isEmpty()) {
                Toast.makeText(this, "Please enter a command", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            input.setText("")
            dispatchOverlayCommand(userText)
        }
        btnRow.addView(sendBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val voiceBtn = Button(this).apply {
            text = "Voice"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D56A50"))
            setOnClickListener {
                try {
                    val app = NayroApplication.instance
                    if (!app.permissionManager.isMicrophoneGranted()) {
                        responsePreviewView?.text = "Microphone permission needed. Opening NAYRO permission setup..."
                        val mainIntent = Intent(this@NayroFloatingService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(mainIntent)
                    } else if (app.voiceManager.voiceState.value == VoiceState.LISTENING) {
                        app.voiceManager.stopListening()
                    } else {
                        app.voiceManager.onSpeechRecognized = { recognized ->
                            dispatchOverlayCommand(recognized)
                        }
                        app.voiceManager.startListening()
                        responsePreviewView?.text = "Listening... speak your command to Babu."
                    }
                } catch (e: Exception) {
                    responsePreviewView?.text = "Voice error: ${e.localizedMessage ?: "unavailable"}"
                }
            }
        }
        voiceBtnView = voiceBtn
        btnRow.addView(voiceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(this).apply {
            text = "Minimize"
            textSize = 12f
            setTextColor(Color.parseColor("#0F172A"))
            setBackgroundColor(Color.parseColor("#E2E8F0"))
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
            startObservingAgentState()
        } catch (e: Exception) {
            e.printStackTrace()
            panelRootView = null
            isPanelOpen = false
        }
    }

    private fun dispatchOverlayCommand(userText: String) {
        val cleanText = userText.trim()
        if (cleanText.isEmpty()) return

        responsePreviewView?.text = "You: \"$cleanText\"\nBabu is working on it..."
        serviceScope.launch(Dispatchers.Default) {
            try {
                val app = NayroApplication.instance
                app.repository.addConversationMessage(
                    ConversationEntity(sender = "user", content = cleanText)
                )
                val responseText = app.agentRuntime.executeUserInstruction(cleanText)
                app.repository.addConversationMessage(
                    ConversationEntity(sender = "babu", content = responseText)
                )
                withContext(Dispatchers.Main) {
                    responsePreviewView?.text = responseText
                }
                if (app.voiceManager.configuration.value.isTtsEnabled) {
                    app.voiceManager.speak(responseText)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    responsePreviewView?.text = "Execution error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun startObservingAgentState() {
        stateCollectorJob?.cancel()
        stateCollectorJob = serviceScope.launch {
            try {
                val app = NayroApplication.instance
                launch {
                    app.agentRuntime.currentState.collect { state ->
                        val label = when (state) {
                            is AgentState.Idle -> "State: Idle • Ready"
                            is AgentState.StandbyListening -> "State: Wake Standby Active"
                            is AgentState.UserListening -> "State: Listening to Microphone..."
                            is AgentState.Transcribing -> "State: Transcribing Speech..."
                            is AgentState.Thinking -> "State: Understanding your request..."
                            is AgentState.PlanningTask -> "State: Planning (${state.task.resolvedIntent.toUserFriendlyIntentLabel()})"
                            is AgentState.ProposingAction -> "State: Proposing Action"
                            is AgentState.ExecutingTask -> "State: Step ${state.step}/${state.totalSteps} • ${state.currentAction.actionType.toUserFriendlyDescription(state.currentAction.target, state.currentAction.parameters)}"
                            is AgentState.ExecutingAction -> "State: ${state.toolName.toUserFriendlyIntentLabel()}..."
                            is AgentState.WaitingForConfirmation -> "State: Waiting for Confirmation"
                            is AgentState.WaitingForPermission -> "State: Waiting for Permission (${state.permission})"
                            is AgentState.Speaking -> "State: Speaking Response"
                            is AgentState.Completed -> "State: Completed"
                            is AgentState.Cancelled -> "State: Cancelled (${state.reason.take(30)})"
                            is AgentState.Error -> "State: Error (${state.message.take(40)})"
                        }
                        stateBadgeView?.text = label
                    }
                }
                launch {
                    app.agentRuntime.confirmationManager.pendingConfirmation.collect { pending ->
                        if (pending != null) {
                            confirmationContainer?.visibility = View.VISIBLE
                            confirmationTextView?.text = "${pending.title}: ${pending.description}"
                        } else {
                            confirmationContainer?.visibility = View.GONE
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun closePanel() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        stateBadgeView = null
        responsePreviewView = null
        confirmationContainer = null
        confirmationTextView = null
        voiceBtnView = null

        if (panelRootView != null) {
            try {
                windowManager.removeView(panelRootView)
            } catch (_: Exception) {}
            panelRootView = null
        }
        isPanelOpen = false
    }

    private fun removeOverlayViews() {
        closePanel()
        if (bubbleRootView != null) {
            try {
                windowManager.removeView(bubbleRootView)
            } catch (_: Exception) {}
            bubbleRootView = null
        }
    }
}

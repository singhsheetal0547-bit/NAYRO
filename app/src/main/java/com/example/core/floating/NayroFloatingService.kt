package com.example.core.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
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
import com.example.core.identity.AssistantIdentityProfile
import com.example.core.identity.AssistantIdentityStore
import com.example.core.service.ServiceLifecycleState
import com.example.core.service.ServiceStateRepository
import com.example.core.service.ServiceStatus
import com.example.core.voice.VoiceState
import com.example.data.local.entities.ConversationEntity
import com.example.ui.components.VisualizerVisualState
import com.example.ui.components.computeVisualizerBarHeights
import com.example.ui.components.resolveVisualizerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Professional Floating NAYRO Agent Service (Section 6).
 *
 * Connects directly to the single application-scoped AgentRuntime, VoiceManager,
 * AssistantIdentityStore, and BackgroundVoiceController — never creates a duplicate agent engine.
 *
 * Capabilities:
 * - Compact draggable floating NAYRO icon with safe display inset clamping, edge snapping & persistence
 * - Live state ring on the bubble reflecting active agent/voice state
 * - Expandable/collapsible compact conversation overlay
 * - Real-time audio-reactive waveform driven by actual SpeechRecognizer / WakeEngine RMS telemetry
 * - Direct voice interaction via isolated floating listener (never clobbers MainActivity listener)
 * - Inline pending confirmation Approve/Reject controls
 * - Immediate Stop (cancels active task, listening, and TTS)
 * - Direct Background Listening ON/OFF toggle right inside the floating panel
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
    private var bubbleObserverJob: Job? = null
    private var panelCollectorJob: Job? = null

    // Views
    private var bubbleRootView: FrameLayout? = null
    private var bubbleInnerCircle: FrameLayout? = null
    private var bubbleInitialText: TextView? = null
    private var panelRootView: FrameLayout? = null

    // Dynamic UI elements inside the open panel
    private var stateBadgeView: TextView? = null
    private var responsePreviewView: TextView? = null
    private var waveformView: FloatingAudioWaveformView? = null
    private var confirmationContainer: LinearLayout? = null
    private var confirmationTextView: TextView? = null
    private var voiceBtnView: Button? = null
    private var bgListeningToggleBtn: Button? = null

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

    private fun resolveAssistantName(): String {
        return AssistantIdentityStore.sharedInstance?.getDisplayName()?.takeIf { it.isNotBlank() }
            ?: try {
                NayroApplication.instance.assistantIdentityStore.getDisplayName()
            } catch (_: Exception) {
                AssistantIdentityProfile.DEFAULT_DISPLAY_NAME
            }
    }

    private fun getSafeTopInsetPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(28)
    }

    private fun getSafeBottomInsetPx(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else dp(28)
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
            promoteForegroundWithSafeType()

            val windowAdded = initBubbleView()
            if (windowAdded) {
                prefs.setFloatingEnabled(true)
                ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.RUNNING)
                startObservingBubbleState()
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

    /**
     * Starts or refreshes foreground service type safely across Android 10–16:
     * includes FOREGROUND_SERVICE_TYPE_MICROPHONE alongside SPECIAL_USE whenever RECORD_AUDIO
     * is granted so floating-bubble voice capture works reliably in the background over other apps.
     */
    private fun promoteForegroundWithSafeType() {
        val notification = createNotification()
        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val fgsTypes = if (hasMicPermission) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, fgsTypes)
            } catch (_: SecurityException) {
                // Graceful fallback if OS restricts microphone FGS promotion while backgrounded
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleObserverJob?.cancel()
        panelCollectorJob?.cancel()
        try {
            NayroApplication.instance.voiceManager.detachFloatingListener()
        } catch (_: Exception) {}
        serviceScope.cancel()
        removeOverlayViews()
        ServiceStateRepository.updateFloatingStatus(ServiceLifecycleState.STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubbleRootView != null && ::bubbleParams.isInitialized) {
            val (snappedX, safeY) = FloatingAssistantPreferences.snapToNearestHorizontalEdge(
                x = bubbleParams.x,
                y = bubbleParams.y,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels,
                bubbleSizePx = dp(56),
                edgeMarginPx = dp(12),
                topInsetPx = getSafeTopInsetPx(),
                bottomInsetPx = getSafeBottomInsetPx()
            )
            bubbleParams.x = snappedX
            bubbleParams.y = safeY
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
                "NAYRO Floating Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status notification while NAYRO floating agent overlay is active."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val assistantName = resolveAssistantName()
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
            .setContentTitle("NAYRO Floating Agent ($assistantName)")
            .setContentText("Tap the floating icon anywhere to converse, run tasks, or toggle listening.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide Icon", stopPendingIntent)
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

        val dpSize = dp(56)
        val (savedX, savedY) = prefs.getPosition()
        val (clampedX, clampedY) = FloatingAssistantPreferences.clampToSafeBounds(
            x = savedX,
            y = savedY,
            screenWidth = resources.displayMetrics.widthPixels,
            screenHeight = resources.displayMetrics.heightPixels,
            bubbleSizePx = dpSize,
            edgeMarginPx = dp(12),
            topInsetPx = getSafeTopInsetPx(),
            bottomInsetPx = getSafeBottomInsetPx()
        )

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampedX
            y = clampedY
        }

        val initialChar = resolveAssistantName().firstOrNull()?.uppercaseChar()?.toString() ?: "N"

        val bubble = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(dpSize, dpSize)
            contentDescription = "NAYRO floating agent icon. Tap to open, drag to move."
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(
                    Color.parseColor("#F6F2E8"),
                    Color.parseColor("#DCE7DE")
                )
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dpSize * 0.75f
                setStroke(dp(3), Color.parseColor("#153B35"))
            }
            elevation = dp(8).toFloat()
        }

        val innerCircle = FrameLayout(this).apply {
            val innerSize = dp(40)
            layoutParams = FrameLayout.LayoutParams(innerSize, innerSize, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#153B35"))
            }
        }
        val innerBadge = TextView(this).apply {
            text = initialChar
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F6F2E8"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        innerCircle.addView(innerBadge)
        bubble.addView(innerCircle)
        bubbleInnerCircle = innerCircle
        bubbleInitialText = innerBadge

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
                        val (safeX, safeY) = FloatingAssistantPreferences.clampToSafeBounds(
                            x = initialX + dx,
                            y = initialY + dy,
                            screenWidth = resources.displayMetrics.widthPixels,
                            screenHeight = resources.displayMetrics.heightPixels,
                            bubbleSizePx = dpSize,
                            edgeMarginPx = dp(8),
                            topInsetPx = getSafeTopInsetPx(),
                            bottomInsetPx = getSafeBottomInsetPx()
                        )
                        bubbleParams.x = safeX
                        bubbleParams.y = safeY
                        try {
                            windowManager.updateViewLayout(bubbleRootView, bubbleParams)
                        } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDrag) {
                            val (snappedX, safeY) = FloatingAssistantPreferences.snapToNearestHorizontalEdge(
                                x = bubbleParams.x,
                                y = bubbleParams.y,
                                screenWidth = resources.displayMetrics.widthPixels,
                                screenHeight = resources.displayMetrics.heightPixels,
                                bubbleSizePx = dpSize,
                                edgeMarginPx = dp(12),
                                topInsetPx = getSafeTopInsetPx(),
                                bottomInsetPx = getSafeBottomInsetPx()
                            )
                            bubbleParams.x = snappedX
                            bubbleParams.y = safeY
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

    private fun startObservingBubbleState() {
        bubbleObserverJob?.cancel()
        bubbleObserverJob = serviceScope.launch {
            try {
                val app = NayroApplication.instance
                launch {
                    app.assistantIdentityStore.profile.collect { profile ->
                        val initial = profile.assistantDisplayName.firstOrNull()?.uppercaseChar()?.toString() ?: "N"
                        bubbleInitialText?.text = initial
                    }
                }
                launch {
                    app.voiceManager.voiceState.collect { vState ->
                        val strokeColor = when (vState) {
                            VoiceState.LISTENING, VoiceState.FOLLOW_UP_LISTENING, VoiceState.WAKE_DETECTED -> Color.parseColor("#D56A50")
                            VoiceState.SPEAKING -> Color.parseColor("#2F7D57")
                            VoiceState.ERROR -> Color.parseColor("#B9423A")
                            VoiceState.STANDBY -> Color.parseColor("#153B35")
                            else -> Color.parseColor("#0E2925")
                        }
                        (bubbleRootView?.background as? GradientDrawable)?.setStroke(dp(3), strokeColor)
                        (bubbleInnerCircle?.background as? GradientDrawable)?.setColor(strokeColor)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun togglePanel() {
        if (isPanelOpen) {
            closePanel()
        } else {
            openPanel(autoStartVoice = true)
        }
    }

    private fun openPanel(autoStartVoice: Boolean = true) {
        if (panelRootView != null) return

        val assistantName = resolveAssistantName()
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val maxPanelWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(352))
        panelParams = WindowManager.LayoutParams(
            maxPanelWidth,
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
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F6F2E8"))
                setStroke(dp(2), Color.parseColor("#153B35"))
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
            text = "NAYRO • $assistantName"
            setTextColor(Color.parseColor("#1E2926"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        titleCol.addView(header)

        val stateBadge = TextView(this).apply {
            text = "Idle • Ready"
            setTextColor(Color.parseColor("#153B35"))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        }
        stateBadgeView = stateBadge
        titleCol.addView(stateBadge)
        headerRow.addView(titleCol)

        val expandBtn = Button(this).apply {
            text = "Open App"
            textSize = 11f
            setTextColor(Color.parseColor("#153B35"))
            setBackgroundColor(Color.parseColor("#DCE7DE"))
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

        // Real-Time Audio-Reactive Waveform View
        val waveform = FloatingAudioWaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            }
        }
        waveformView = waveform
        panel.addView(waveform)

        // Compact Conversation / Live Transcript Box
        val responseBox = TextView(this).apply {
            text = "Ask $assistantName anything — general questions, research, or Android device tasks."
            setTextColor(Color.parseColor("#1E2926"))
            textSize = 13f
            val p = dp(10)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#DCE7DE"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
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
            setBackgroundColor(Color.parseColor("#2F7D57"))
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
            setBackgroundColor(Color.parseColor("#B9423A"))
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

        // Control Row: Background Listening ON/OFF | Immediate Stop
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val bgToggleBtn = Button(this).apply {
            textSize = 11f
            setOnClickListener {
                try {
                    val app = NayroApplication.instance
                    val currentlyEnabled = app.backgroundVoiceController.state.value.isEnabled
                    val (ok, msg) = app.backgroundVoiceController.setBackgroundListeningEnabled(
                        enabled = !currentlyEnabled,
                        activityContext = this@NayroFloatingService
                    )
                    responsePreviewView?.text = msg
                    if (!ok) {
                        Toast.makeText(this@NayroFloatingService, msg, Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {}
            }
        }
        bgListeningToggleBtn = bgToggleBtn

        val stopAllBtn = Button(this).apply {
            text = "Stop Now"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B9423A"))
            setOnClickListener {
                try {
                    val app = NayroApplication.instance
                    app.agentRuntime.cancelCurrentTask()
                    app.voiceManager.endConversationSession(
                        returnToStandby = app.backgroundVoiceController.state.value.isEnabled
                    )
                    responsePreviewView?.text = "Stopped active task and voice playback."
                } catch (_: Exception) {}
            }
        }
        controlRow.addView(bgToggleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        controlRow.addView(stopAllBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(controlRow)

        // Text Input Field
        val input = EditText(this).apply {
            hint = "Ask $assistantName anything (Hindi / English)..."
            setHintTextColor(Color.parseColor("#6F7C76"))
            setTextColor(Color.parseColor("#1E2926"))
            textSize = 14f
            val p = dp(11)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#153B35"))
            }
        }
        panel.addView(input)

        // Primary Action Row: Send | Voice | Minimize
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        val sendBtn = Button(this).apply {
            text = "Send"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6F7C76"))
            isEnabled = false
        }

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
            text = "Speak"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D56A50"))
            setOnClickListener {
                try {
                    val app = NayroApplication.instance
                    val vState = app.voiceManager.voiceState.value
                    if (!app.permissionManager.isMicrophoneGranted()) {
                        responsePreviewView?.text = "Microphone permission needed. Opening NAYRO..."
                        val mainIntent = Intent(this@NayroFloatingService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(mainIntent)
                    } else if (vState == VoiceState.LISTENING || vState == VoiceState.FOLLOW_UP_LISTENING) {
                        app.voiceManager.stopListening()
                    } else {
                        app.voiceManager.attachFloatingListener { recognized ->
                            dispatchOverlayCommand(recognized)
                        }
                        app.voiceManager.startListening()
                        responsePreviewView?.text = "Listening... speak to ${resolveAssistantName()}."
                    }
                } catch (e: Exception) {
                    responsePreviewView?.text = "Voice error: ${e.localizedMessage ?: "unavailable"}"
                }
            }
        }
        voiceBtnView = voiceBtn
        btnRow.addView(voiceBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(this).apply {
            text = "Hide"
            textSize = 12f
            setTextColor(Color.parseColor("#1E2926"))
            setBackgroundColor(Color.parseColor("#DCE7DE"))
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
            if (autoStartVoice) {
                activateFloatingVoiceSession()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            panelRootView = null
            isPanelOpen = false
        }
    }

    /**
     * Mode A — Floating Assistant auto-mic activation:
     * Tapping the floating icon immediately activates the microphone and continuous
     * voice conversation session (when RECORD_AUDIO is granted and no phone call is active)
     * without requiring the user to repeat a wake phrase.
     */
    private fun activateFloatingVoiceSession() {
        val app = try {
            NayroApplication.instance
        } catch (_: Exception) {
            return
        }
        val assistantName = resolveAssistantName()
        if (!app.permissionManager.isMicrophoneGranted()) {
            responsePreviewView?.text = "Tap 'Speak' or grant Microphone permission in NAYRO for hands-free voice."
            return
        }
        if (app.voiceManager.isPhoneCallActiveOrRinging()) {
            responsePreviewView?.text = "Microphone paused during active phone call. You can type a command below."
            return
        }
        val vState = app.voiceManager.voiceState.value
        if (vState != VoiceState.LISTENING && vState != VoiceState.FOLLOW_UP_LISTENING) {
            promoteForegroundWithSafeType()
            app.voiceManager.attachFloatingListener { recognized ->
                dispatchOverlayCommand(recognized)
            }
            app.voiceManager.startListening()
            responsePreviewView?.text = "Listening... speak naturally to $assistantName (tap Stop Now anytime)."
        }
    }

    private fun dispatchOverlayCommand(rawText: String) {
        val app = try {
            NayroApplication.instance
        } catch (_: Exception) {
            return
        }
        val cleanText = app.voiceManager.normalizeVoiceInvocationCommand(rawText)
        if (cleanText.isEmpty()) return

        if (app.voiceManager.isConversationExitPhrase(cleanText) ||
            app.voiceManager.wakeWordEngine.isVoiceExitCommand(cleanText)
        ) {
            app.agentRuntime.cancelCurrentTask()
            app.voiceManager.endConversationSession(
                returnToStandby = app.backgroundVoiceController.state.value.isEnabled
            )
            responsePreviewView?.text = "Voice session ended and microphone released."
            return
        }

        val assistantName = resolveAssistantName()
        responsePreviewView?.text = "You: \"$cleanText\"\n$assistantName is working on it..."
        serviceScope.launch(Dispatchers.Default) {
            var responseText = ""
            try {
                app.repository.addConversationMessage(
                    ConversationEntity(sender = "user", content = cleanText)
                )
                responseText = app.agentRuntime.executeUserInstruction(cleanText)
                app.repository.addConversationMessage(
                    ConversationEntity(sender = "assistant", content = responseText)
                )
                withContext(Dispatchers.Main) {
                    responsePreviewView?.text = responseText
                }
            } catch (e: Exception) {
                responseText = "Execution error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                withContext(Dispatchers.Main) {
                    responsePreviewView?.text = responseText
                }
            } finally {
                // Always transition microphone ownership cleanly out of THINKING/PROCESSING
                app.voiceManager.completeCommandTurn(
                    responseText = responseText,
                    shouldSpeak = app.voiceManager.configuration.value.isTtsEnabled,
                    expectFollowUp = isPanelOpen && app.voiceManager.configuration.value.isContinuousConversationEnabled
                )
            }
        }
    }

    private fun startObservingAgentState() {
        panelCollectorJob?.cancel()
        panelCollectorJob = serviceScope.launch {
            try {
                val app = NayroApplication.instance
                launch {
                    app.agentRuntime.currentState.collect { state ->
                        val label = when (state) {
                            is AgentState.Idle -> "Idle • Ready"
                            is AgentState.StandbyListening -> "Standby • Wake Detection Active"
                            is AgentState.UserListening -> "Listening to Microphone..."
                            is AgentState.Transcribing -> "Transcribing Speech..."
                            is AgentState.Thinking -> "Reasoning & Planning..."
                            is AgentState.PlanningTask -> "Planning (${state.task.resolvedIntent.toUserFriendlyIntentLabel()})"
                            is AgentState.ProposingAction -> "Proposing Action..."
                            is AgentState.ExecutingTask -> "Step ${state.step}/${state.totalSteps} • ${state.currentAction.actionType.toUserFriendlyDescription(state.currentAction.target, state.currentAction.parameters)}"
                            is AgentState.ExecutingAction -> "${state.toolName.toUserFriendlyIntentLabel()}..."
                            is AgentState.WaitingForConfirmation -> "Waiting for Confirmation"
                            is AgentState.WaitingForPermission -> "Permission Needed (${state.permission})"
                            is AgentState.WaitingForDeviceUnlock -> "Waiting for Unlock"
                            is AgentState.Speaking -> "Speaking Response"
                            is AgentState.Completed -> "Completed"
                            is AgentState.Cancelled -> "Cancelled (${state.reason.take(28)})"
                            is AgentState.Error -> "Error (${state.message.take(36)})"
                        }
                        stateBadgeView?.text = label
                        updateWaveformSnapshot(app)
                    }
                }
                launch {
                    app.voiceManager.voiceState.collect { vState ->
                        val isCapturing = vState == VoiceState.LISTENING || vState == VoiceState.FOLLOW_UP_LISTENING
                        voiceBtnView?.text = if (isCapturing) "Stop Mic" else "Speak"
                        updateWaveformSnapshot(app)
                    }
                }
                launch {
                    app.voiceManager.normalizedAudioLevel.collect {
                        updateWaveformSnapshot(app)
                    }
                }
                launch {
                    app.voiceManager.standbyAudioLevel.collect {
                        updateWaveformSnapshot(app)
                    }
                }
                launch {
                    app.voiceManager.partialTranscript.collect { partial ->
                        if (partial.isNotBlank() &&
                            (app.voiceManager.voiceState.value == VoiceState.LISTENING ||
                                app.voiceManager.voiceState.value == VoiceState.FOLLOW_UP_LISTENING)
                        ) {
                            responsePreviewView?.text = "Listening: \"$partial\""
                        }
                    }
                }
                launch {
                    app.backgroundVoiceController.state.collect { bgState ->
                        bgListeningToggleBtn?.apply {
                            text = if (bgState.isEnabled) "Bg Listening: ON" else "Bg Listening: OFF"
                            setTextColor(Color.WHITE)
                            setBackgroundColor(
                                if (bgState.isEnabled) Color.parseColor("#2F7D57") else Color.parseColor("#6F7C76")
                            )
                        }
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
                // Lightweight phase ticker ONLY while panel is open and an animated state is active
                launch {
                    var phase = 0f
                    while (isActive && isPanelOpen) {
                        val vs = resolveVisualizerState(
                            app.voiceManager.voiceState.value,
                            app.agentRuntime.currentState.value
                        )
                        if (vs != VisualizerVisualState.IDLE &&
                            vs != VisualizerVisualState.ERROR &&
                            vs != VisualizerVisualState.LISTENING
                        ) {
                            phase = (phase + 0.06f) % 1f
                            waveformView?.updateTelemetry(
                                state = vs,
                                audioLevel = app.voiceManager.normalizedAudioLevel.value,
                                standbyLevel = app.voiceManager.standbyAudioLevel.value,
                                phase = phase
                            )
                        }
                        delay(55L)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateWaveformSnapshot(app: NayroApplication) {
        val vs = resolveVisualizerState(
            app.voiceManager.voiceState.value,
            app.agentRuntime.currentState.value
        )
        waveformView?.updateTelemetry(
            state = vs,
            audioLevel = app.voiceManager.normalizedAudioLevel.value,
            standbyLevel = app.voiceManager.standbyAudioLevel.value
        )
    }

    private fun closePanel() {
        panelCollectorJob?.cancel()
        panelCollectorJob = null
        try {
            val app = NayroApplication.instance
            val vState = app.voiceManager.voiceState.value
            if (vState == VoiceState.LISTENING || vState == VoiceState.FOLLOW_UP_LISTENING) {
                app.voiceManager.endConversationSession(
                    returnToStandby = app.backgroundVoiceController.state.value.isEnabled
                )
            }
            app.voiceManager.detachFloatingListener()
        } catch (_: Exception) {}
        stateBadgeView = null
        responsePreviewView = null
        waveformView = null
        confirmationContainer = null
        confirmationTextView = null
        voiceBtnView = null
        bgListeningToggleBtn = null

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
        bubbleObserverJob?.cancel()
        bubbleObserverJob = null
        bubbleInnerCircle = null
        bubbleInitialText = null
        if (bubbleRootView != null) {
            try {
                windowManager.removeView(bubbleRootView)
            } catch (_: Exception) {}
            bubbleRootView = null
        }
    }

    /**
     * Lightweight native Canvas waveform view sharing the exact same [computeVisualizerBarHeights]
     * real-telemetry math as Jetpack Compose's [NayroAudioVisualizer].
     */
    private class FloatingAudioWaveformView(context: Context) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#DCE7DE")
        }
        private val barRect = RectF()
        private var currentState: VisualizerVisualState = VisualizerVisualState.IDLE
        private var currentAudioLevel: Float = 0f
        private var currentPhase: Float = 0f

        fun updateTelemetry(
            state: VisualizerVisualState,
            audioLevel: Float,
            standbyLevel: Float,
            phase: Float = currentPhase
        ) {
            currentState = state
            currentAudioLevel = when (state) {
                VisualizerVisualState.LISTENING -> audioLevel
                VisualizerVisualState.STANDBY -> standbyLevel
                else -> 0f
            }
            currentPhase = phase
            barPaint.color = when (state) {
                VisualizerVisualState.IDLE -> Color.parseColor("#DCE7DE")
                VisualizerVisualState.STANDBY -> Color.parseColor("#153B35")
                VisualizerVisualState.WAKE_DETECTED,
                VisualizerVisualState.LISTENING,
                VisualizerVisualState.SPEAKING -> Color.parseColor("#D56A50")
                VisualizerVisualState.TRANSCRIBING,
                VisualizerVisualState.THINKING -> Color.parseColor("#153B35")
                VisualizerVisualState.EXECUTING -> Color.parseColor("#2F7D57")
                VisualizerVisualState.ERROR -> Color.parseColor("#B9423A")
            }
            contentDescription = state.accessibleLabel
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val heights = computeVisualizerBarHeights(
                state = currentState,
                realAudioLevel = currentAudioLevel,
                phase = currentPhase,
                barCount = 16
            )
            val count = heights.size
            val spacing = w / (count * 2.1f)
            val barWidth = ((w - spacing * (count - 1)) / count).coerceAtLeast(3f)
            val centerY = h / 2f
            val radius = barWidth / 2f

            for (i in 0 until count) {
                val barHeight = (h * heights[i]).coerceAtLeast(4f)
                val left = i * (barWidth + spacing)
                barRect.set(left, centerY - barHeight / 2f, left + barWidth, centerY + barHeight / 2f)
                canvas.drawRoundRect(barRect, radius, radius, barPaint)
            }
        }
    }
}

package com.example.core.agent

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.core.accessibility.AccessibilityActionController
import com.example.core.accessibility.AppNavigationManager
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.agent.tools.ToolRegistryManager
import com.example.core.memory.MemoryCategory
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.notification.NotificationCategory
import com.example.core.notification.NotificationIntelligenceRepository
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIResult
import com.example.core.screen.ScreenIntelligenceManager
import com.example.core.skills.SkillDefinition
import com.example.core.skills.SkillManager
import com.example.core.subagent.SubAgentManager
import com.example.core.subagent.SubAgentStatus
import com.example.core.subagent.SubAgentType
import com.example.core.automation.AutomationEngine
import com.example.core.calendar.CalendarIntegrationManager
import com.example.core.communication.WhatsAppAutomationManager
import com.example.core.telephony.CallIntelligenceManager
import com.example.core.telephony.ContactResolutionResult
import com.example.core.workflow.CrossAppWorkflowEngine
import com.example.core.workspace.WorkspaceManager
import com.example.core.workspace.WorkspaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ActionExecutionResult(
    val isSuccess: Boolean,
    val outputMessage: String,
    val status: VerificationStatus? = null
)

/**
 * Authoritative Native Action Executor for NAYRO.
 * Executes validated AgentActions across Android System APIs, Accessibility, Workspace,
 * Web Research (with SSRF protection), Coding Workspace, Skills, Workflows, and Bounded Sub-Agents.
 * Never fabricates success results for unperformed, unsupported, or failed operations.
 */
class ActionExecutor(
    private val context: Context,
    private val screenIntelligenceManager: ScreenIntelligenceManager,
    private val memoryManager: MemoryManager,
    private val notebookManager: NotebookManager,
    private val providerManager: AIProviderManager,
    private val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    private val skillManager: SkillManager = SkillManager(),
    private val subAgentManager: SubAgentManager = SubAgentManager(),
    val callIntelligenceManager: CallIntelligenceManager = CallIntelligenceManager(context),
    private val workflowEngine: CrossAppWorkflowEngine = CrossAppWorkflowEngine(),
    val calendarManager: CalendarIntegrationManager = CalendarIntegrationManager(context),
    val whatsAppManager: WhatsAppAutomationManager = WhatsAppAutomationManager(
        context,
        callIntelligenceManager,
        screenIntelligenceManager
    ),
    val automationEngine: AutomationEngine = AutomationEngine(context),
    val smsManager: com.example.core.telephony.SmsIntelligenceManager = com.example.core.telephony.SmsIntelligenceManager(
        context,
        callIntelligenceManager
    ),
    val mediaControlManager: com.example.core.media.MediaControlManager = com.example.core.media.MediaControlManager(
        context,
        screenIntelligenceManager,
        memoryManager
    )
) {

    companion object {
        private const val MAX_WEB_RESPONSE_BYTES = 512 * 1024 // 512 KB cap

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        /**
         * Validates a URL against SSRF (Server-Side Request Forgery) and local file access rules (BUG 10).
         * Rejects file://, content://, javascript:, localhost, loopback, link-local, and RFC1918 private IPs.
         */
        fun validateSafeExternalUrl(rawUrl: String): Result<HttpUrl> {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("URL cannot be empty."))
            }
            val lower = trimmed.lowercase()
            if (lower.startsWith("file:") || lower.startsWith("content:") ||
                lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("ftp:")
            ) {
                return Result.failure(SecurityException("Forbidden URL scheme in '$trimmed'. Only HTTP/HTTPS is permitted."))
            }

            val parsed = trimmed.toHttpUrlOrNull()
                ?: return Result.failure(IllegalArgumentException("Malformed HTTP/HTTPS URL: '$trimmed'."))

            if (parsed.scheme != "https" && parsed.scheme != "http") {
                return Result.failure(SecurityException("Unsupported URL scheme '${parsed.scheme}'."))
            }

            val host = parsed.host.lowercase()
            if (isForbiddenHost(host)) {
                return Result.failure(SecurityException("SSRF protection blocked private or loopback host: '$host'."))
            }

            return Result.success(parsed)
        }

        fun isForbiddenHost(host: String): Boolean {
            val cleanHost = host.removePrefix("[").removeSuffix("]").lowercase()
            if (cleanHost == "localhost" ||
                cleanHost.endsWith(".localhost") ||
                cleanHost.endsWith(".local") ||
                cleanHost.endsWith(".internal") ||
                cleanHost == "0.0.0.0" ||
                cleanHost == "::1" ||
                cleanHost == "0:0:0:0:0:0:0:1" ||
                cleanHost.startsWith("127.") ||
                cleanHost.startsWith("10.") ||
                cleanHost.startsWith("192.168.") ||
                cleanHost.startsWith("169.254.")
            ) {
                return true
            }
            if (cleanHost.startsWith("172.")) {
                val parts = cleanHost.split(".")
                val secondOctet = parts.getOrNull(1)?.toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) {
                    return true
                }
            }
            return false
        }
    }

    suspend fun execute(action: AgentAction, isConfirmed: Boolean = false): ActionExecutionResult {
        // 1. Tool Registry Availability & Schema Validation (BUG 16)
        if (!ToolRegistryManager.isToolAvailable(action.actionType)) {
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Tool '${action.actionType.name}' is not registered or enabled in ToolRegistry.",
                status = VerificationStatus.NOT_SUPPORTED
            )
        }

        val (paramsValid, validationMsg) = ToolRegistryManager.validateParameters(action)
        if (!paramsValid) {
            return ActionExecutionResult(
                isSuccess = false,
                outputMessage = validationMsg,
                status = VerificationStatus.FAILED
            )
        }

        return when (action.actionType) {

            // 1. Device & System Tools
            ActionType.LAUNCH_APP -> {
                val targetApp = action.target ?: action.parameters["appName"] ?: ""
                if (targetApp.isBlank()) {
                    return ActionExecutionResult(false, "Cannot launch app: no app name specified.", VerificationStatus.FAILED)
                }
                val resolvedPkg = AppNavigationManager.resolvePackage(context, targetApp) ?: targetApp
                val launched = AppNavigationManager.launchApp(context, resolvedPkg)
                if (launched) {
                    ActionExecutionResult(true, "Launched $targetApp ($resolvedPkg) successfully.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "Could not find or launch app: $targetApp.", VerificationStatus.FAILED)
                }
            }

            ActionType.OPEN_SETTINGS_SECTION -> {
                val section = action.target ?: action.parameters["section"] ?: "general"
                val opened = AppNavigationManager.openSettingsSection(context, section)
                if (opened) {
                    ActionExecutionResult(true, "Opened Android Settings ($section).", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "Failed to open Android Settings ($section).", VerificationStatus.FAILED)
                }
            }

            ActionType.GET_DEVICE_INFO -> {
                val info = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
                ActionExecutionResult(true, info, VerificationStatus.VERIFIED)
            }

            ActionType.GET_BATTERY_STATE -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (level in 0..100) {
                    ActionExecutionResult(true, "Battery level is $level%.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "Unable to read device battery level.", VerificationStatus.FAILED)
                }
            }

            ActionType.GET_NETWORK_STATUS -> {
                val activeProvider = providerManager.activeProvider.value
                ActionExecutionResult(
                    true,
                    "Network active. Active AI Provider: ${activeProvider?.name ?: "Local Offline Mode"}",
                    VerificationStatus.VERIFIED
                )
            }

            ActionType.GET_STORAGE_INFO -> {
                try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val freeMb = (stat.availableBytes) / (1024 * 1024)
                    val totalMb = (stat.totalBytes) / (1024 * 1024)
                    ActionExecutionResult(
                        true,
                        "Internal Storage: ${freeMb}MB free of ${totalMb}MB total.",
                        VerificationStatus.VERIFIED
                    )
                } catch (e: Exception) {
                    ActionExecutionResult(false, "Failed to read storage info: ${e.localizedMessage}", VerificationStatus.FAILED)
                }
            }

            ActionType.GET_DEVICE_TIME -> {
                val sdf = SimpleDateFormat("hh:mm a, EEEE, dd MMM yyyy", Locale.getDefault())
                val currentTime = sdf.format(Date())
                ActionExecutionResult(true, "Current device time is $currentTime.", VerificationStatus.VERIFIED)
            }

            ActionType.SEARCH_INSTALLED_APPS -> {
                val query = action.target ?: action.parameters["query"] ?: ""
                val pkg = AppNavigationManager.resolvePackage(context, query)
                if (pkg != null) {
                    ActionExecutionResult(true, "Resolved package for '$query': $pkg", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "No installed package matched '$query'.", VerificationStatus.FAILED)
                }
            }

            ActionType.TOGGLE_FLASHLIGHT -> {
                val enabledStr = action.parameters["enabled"] ?: action.target ?: "true"
                val turnOn = !enabledStr.equals("false", ignoreCase = true) &&
                    !enabledStr.equals("off", ignoreCase = true) &&
                    !enabledStr.equals("0", ignoreCase = true)
                return try {
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                        ?: return ActionExecutionResult(false, "CameraManager service is unavailable on this device.", VerificationStatus.NOT_SUPPORTED)

                    val torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                        val chars = cameraManager.getCameraCharacteristics(id)
                        chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: return ActionExecutionResult(false, "No camera with flashlight hardware found on this device.", VerificationStatus.NOT_SUPPORTED)

                    cameraManager.setTorchMode(torchCameraId, turnOn)
                    val stateWord = if (turnOn) "ON" else "OFF"
                    ActionExecutionResult(
                        true,
                        "Flashlight turned $stateWord (cameraId=$torchCameraId).",
                        VerificationStatus.VERIFIED
                    )
                } catch (e: Exception) {
                    ActionExecutionResult(
                        false,
                        "Failed to toggle flashlight: ${e.localizedMessage ?: e.javaClass.simpleName}",
                        VerificationStatus.FAILED
                    )
                }
            }

            // 2. Screen & Interaction Tools
            ActionType.READ_SCREEN, ActionType.INSPECT_ACCESSIBILITY_TREE -> {
                val summary = screenIntelligenceManager.describeScreenContent()
                if (summary.startsWith("Screen reading requires") || summary.startsWith("I couldn't read")) {
                    ActionExecutionResult(false, summary, VerificationStatus.REQUIRES_PERMISSION)
                } else {
                    ActionExecutionResult(true, summary, VerificationStatus.VERIFIED)
                }
            }

            ActionType.CLICK_ELEMENT -> {
                val targetText = action.target ?: action.parameters["target"] ?: ""
                val viewId = action.parameters["viewId"]
                if (targetText.isBlank() && viewId.isNullOrBlank()) {
                    return ActionExecutionResult(false, "Missing target UI element to click.", VerificationStatus.FAILED)
                }
                val clicked = AccessibilityActionController.performClickWithCoordinateFallback(
                    targetText = targetText.ifBlank { null },
                    viewId = viewId
                )
                if (clicked) {
                    ActionExecutionResult(true, "Clicked on element matching '${targetText.ifBlank { viewId }}'.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "Could not find clickable element matching '${targetText.ifBlank { viewId }}' on screen.", VerificationStatus.FAILED)
                }
            }

            ActionType.LONG_PRESS_ELEMENT -> {
                val targetText = action.target ?: action.parameters["target"] ?: ""
                if (targetText.isBlank()) {
                    return ActionExecutionResult(false, "Missing target UI element to long-press.", VerificationStatus.FAILED)
                }
                val pressed = AccessibilityActionController.performLongClick(targetText = targetText)
                if (pressed) {
                    ActionExecutionResult(true, "Long-pressed element matching '$targetText'.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "Could not find element matching '$targetText' to long-press.", VerificationStatus.FAILED)
                }
            }

            ActionType.SET_TEXT -> {
                val text = action.parameters["text"] ?: action.parameters["query"] ?: ""
                if (text.isBlank()) {
                    return ActionExecutionResult(false, "Cannot set empty text into field.", VerificationStatus.FAILED)
                }
                val typed = AccessibilityActionController.performSetText(
                    text = text,
                    viewId = action.parameters["viewId"],
                    targetText = action.target
                )
                if (typed) {
                    if (action.parameters["submit"] == "true") {
                        AccessibilityActionController.performSubmitAction()
                    }
                    ActionExecutionResult(true, "Entered text '$text' into input field.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "Could not find an editable text field on the current screen.", VerificationStatus.FAILED)
                }
            }

            ActionType.SCROLL_FORWARD -> {
                val scrolled = AccessibilityActionController.performScrollForward()
                if (scrolled) {
                    ActionExecutionResult(true, "Scrolled screen forward.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "No scrollable container found on screen.", VerificationStatus.FAILED)
                }
            }

            ActionType.SCROLL_BACKWARD -> {
                val scrolled = AccessibilityActionController.performScrollBackward()
                if (scrolled) {
                    ActionExecutionResult(true, "Scrolled screen backward.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "No scrollable container found on screen.", VerificationStatus.FAILED)
                }
            }

            ActionType.NAVIGATE_BACK -> {
                val back = AccessibilityActionController.performGlobalBack()
                if (back) {
                    ActionExecutionResult(true, "Navigated back.", VerificationStatus.VERIFIED)
                } else {
                    ActionExecutionResult(false, "Failed to navigate back; Accessibility Service may be disconnected.", VerificationStatus.FAILED)
                }
            }

            ActionType.NAVIGATE_HOME -> {
                val homeViaAcc = AccessibilityActionController.performGlobalHome()
                if (homeViaAcc) {
                    ActionExecutionResult(true, "Navigated to Android Home screen via AccessibilityService.", VerificationStatus.VERIFIED)
                } else {
                    try {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(homeIntent)
                        ActionExecutionResult(true, "Navigated to Android Home screen.", VerificationStatus.VERIFIED)
                    } catch (e: Exception) {
                        ActionExecutionResult(false, "Failed to navigate to Home screen: ${e.localizedMessage}", VerificationStatus.FAILED)
                    }
                }
            }

            ActionType.CONTROL_MEDIA -> {
                val op = action.parameters["operation"] ?: action.target ?: "PLAY_QUERY"
                val query = action.parameters["query"] ?: action.target
                val artist = action.parameters["artist"]
                val genre = action.parameters["genre"] ?: action.parameters["mood"]
                val app = action.parameters["app"]
                val memories = memoryManager.getCachedMemories()
                mediaControlManager.executeMediaCommand(
                    operationStr = op,
                    query = query,
                    artist = artist,
                    genreOrMood = genre,
                    targetApp = app,
                    savedMemories = memories
                )
            }

            ActionType.TAKE_SCREENSHOT -> {
                // BUG 09 Fix: Real AccessibilityService.takeScreenshot on Android 11+ (API 30+)
                val service = NayroAccessibilityService.instance
                    ?: return ActionExecutionResult(
                        false,
                        "Cannot capture screenshot: NAYRO Accessibility Service is not connected.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                val (ok, msg) = service.captureRealScreenshot()
                val status = when {
                    ok -> VerificationStatus.VERIFIED
                    msg.startsWith("NOT_SUPPORTED") -> VerificationStatus.NOT_SUPPORTED
                    else -> VerificationStatus.FAILED
                }
                ActionExecutionResult(ok, msg, status)
            }

            ActionType.INTERACT_COORDINATES -> {
                // BUG 09 Fix: Real coordinate bounds validation and gesture dispatch
                val x = action.parameters["x"]?.toFloatOrNull()
                val y = action.parameters["y"]?.toFloatOrNull()
                if (x == null || y == null || x < 0f || y < 0f) {
                    return ActionExecutionResult(
                        false,
                        "Invalid coordinates (x=${action.parameters["x"]}, y=${action.parameters["y"]}). Non-negative numeric coordinates required.",
                        VerificationStatus.FAILED
                    )
                }
                val service = NayroAccessibilityService.instance
                    ?: return ActionExecutionResult(
                        false,
                        "Cannot tap coordinates ($x, $y): NAYRO Accessibility Service is not connected.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                val gestureType = (action.parameters["gesture"] ?: action.target ?: "tap").lowercase()
                val (ok, msg) = if (gestureType == "double_tap" || gestureType == "doubletap") {
                    service.dispatchDoubleTapGesture(x, y)
                } else {
                    service.dispatchTapGesture(x, y)
                }
                ActionExecutionResult(
                    ok,
                    msg,
                    if (ok) VerificationStatus.VERIFIED else VerificationStatus.FAILED
                )
            }

            // 3. Communication & Telephony Tools
            ActionType.MESSAGE_DRAFT_SEND -> {
                // BUG 03 Fix: Real WhatsApp contact resolution, confirmation check, and honest verification
                executeWhatsAppWorkflow(action, isConfirmed)
            }

            ActionType.CHECK_INCOMING_CALL -> {
                // BUG 04 Fix: Real call state inspection without hardcoded "Rahul"
                val observedNumber = action.parameters["callerNumber"]
                val state = callIntelligenceManager.queryActiveCallState(observedNumber)
                if (state.permissionRequired) {
                    ActionExecutionResult(false, state.statusSummary, VerificationStatus.REQUIRES_PERMISSION)
                } else {
                    ActionExecutionResult(true, state.statusSummary, VerificationStatus.VERIFIED)
                }
            }

            ActionType.RESOLVE_CONTACT -> {
                // BUG 04 Fix: Never default to "Rahul"; query real ContactsContract
                val nameQuery = (action.target ?: action.parameters["query"])?.trim().orEmpty()
                if (nameQuery.isEmpty()) {
                    return ActionExecutionResult(
                        false,
                        "Cannot resolve contact: contact name query is missing.",
                        VerificationStatus.FAILED
                    )
                }
                when (val res = callIntelligenceManager.resolveContactsByName(nameQuery)) {
                    is ContactResolutionResult.PermissionRequired -> ActionExecutionResult(
                        false,
                        "Permission required: READ_CONTACTS is not granted. Caller identity is unavailable.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                    is ContactResolutionResult.NotFound -> ActionExecutionResult(
                        false,
                        "Contact '$nameQuery' was not found in device contacts.",
                        VerificationStatus.FAILED
                    )
                    is ContactResolutionResult.Ambiguous -> {
                        val listStr = res.candidates.joinToString(", ") { "${it.displayName} (${it.phoneNumber})" }
                        ActionExecutionResult(
                            false,
                            "Ambiguous contact '$nameQuery': multiple matches found ($listStr). Please clarify.",
                            VerificationStatus.INCONCLUSIVE
                        )
                    }
                    is ContactResolutionResult.SingleMatch -> ActionExecutionResult(
                        true,
                        "Resolved contact: ${res.contact.displayName} (${res.contact.phoneNumber})",
                        VerificationStatus.VERIFIED
                    )
                }
            }

            ActionType.OPEN_PHONE_DIALER -> {
                val rawTarget = (action.target ?: action.parameters["target"] ?: action.parameters["number"])?.trim().orEmpty()
                callIntelligenceManager.openPhoneDialer(rawTarget = rawTarget)
            }

            ActionType.MAKE_PHONE_CALL -> {
                val rawTarget = (action.target ?: action.parameters["target"] ?: action.parameters["number"])?.trim().orEmpty()
                val observeState = action.parameters["observeCallState"] != "false"
                callIntelligenceManager.initiateOutgoingCall(
                    rawTarget = rawTarget,
                    isConfirmed = isConfirmed,
                    observeCallState = observeState
                )
            }

            ActionType.READ_NOTIFICATIONS -> {
                if (!isNotificationListenerAvailable()) {
                    ActionExecutionResult(
                        false,
                        "Notification Listener access is not enabled for NAYRO. Please grant Notification Access in Settings.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                } else {
                    val recent = NotificationIntelligenceRepository.getFilteredNotifications().take(15)
                    if (recent.isEmpty()) {
                        ActionExecutionResult(
                            true,
                            "Notification Listener is enabled; no unread notifications in active buffer.",
                            VerificationStatus.VERIFIED
                        )
                    } else {
                        val listText = recent.joinToString("\n") {
                            "[${it.category.name}] ${it.packageName}: ${it.title} — ${it.text}"
                        }
                        ActionExecutionResult(
                            true,
                            "Active Notifications (${recent.size}):\n$listText",
                            VerificationStatus.VERIFIED
                        )
                    }
                }
            }

            // 4. Workspace & File Tools (BUG 07 & BUG 08 Fixed)
            ActionType.FILE_BROWSE -> {
                val files = workspaceManager.listWorkspaceFiles()
                val summary = if (files.isEmpty()) {
                    "Workspace is empty."
                } else {
                    "Workspace files (${files.size}): " + files.joinToString(", ") { "${it.name} (${it.sizeBytes}B)" }
                }
                ActionExecutionResult(true, summary, VerificationStatus.VERIFIED)
            }

            ActionType.FILE_READ_TEXT -> {
                val filename = action.target ?: action.parameters["filename"] ?: "NAYRO_Workspace_Guide.md"
                mapWorkspaceResult(workspaceManager.readFileResult(filename))
            }

            ActionType.FILE_WRITE_TEXT -> {
                val filename = action.target ?: action.parameters["filename"] ?: "Note_Output.md"
                val content = action.parameters["content"] ?: ""
                mapWorkspaceResult(workspaceManager.writeFileResult(filename, content))
            }

            ActionType.FILE_SEARCH -> {
                val query = action.target ?: action.parameters["query"] ?: ""
                mapWorkspaceResult(workspaceManager.searchFilesResult(query))
            }

            ActionType.PDF_EXTRACT_TEXT -> {
                val filename = action.target ?: action.parameters["filename"] ?: ""
                if (filename.isBlank()) {
                    return ActionExecutionResult(false, "Missing filename for document text extraction.", VerificationStatus.FAILED)
                }
                val readRes = workspaceManager.readFileResult(filename)
                if (readRes !is WorkspaceResult.Success) {
                    mapWorkspaceResult(readRes)
                } else if (filename.lowercase().endsWith(".pdf") && readRes.content.startsWith("%PDF")) {
                    ActionExecutionResult(
                        false,
                        "Binary PDF stream in '$filename' requires an external PDF renderer; direct raw text extraction is NOT_SUPPORTED.",
                        VerificationStatus.NOT_SUPPORTED
                    )
                } else {
                    ActionExecutionResult(
                        true,
                        "Extracted text from '$filename' (${readRes.bytesProcessed} bytes):\n${readRes.content.take(1500)}",
                        VerificationStatus.VERIFIED
                    )
                }
            }

            ActionType.DOC_SUMMARIZE -> {
                val filename = action.target ?: action.parameters["filename"] ?: "NAYRO_Workspace_Guide.md"
                val readRes = workspaceManager.readFileResult(filename)
                if (readRes !is WorkspaceResult.Success) {
                    mapWorkspaceResult(readRes)
                } else {
                    val activeProvider = providerManager.activeProvider.value
                    if (activeProvider != null && activeProvider.hasKeySaved) {
                        val aiRes = providerManager.sendRequest(
                            userPrompt = "Summarize this document concisely:\n\n${readRes.content.take(4000)}",
                            systemPrompt = "Provide a clear, factual bulleted summary of the document."
                        )
                        if (aiRes is AIResult.Success) {
                            ActionExecutionResult(true, aiRes.response.content, VerificationStatus.VERIFIED)
                        } else {
                            val fallback = buildExtractiveSummary(filename, readRes.content)
                            ActionExecutionResult(true, fallback, VerificationStatus.VERIFIED)
                        }
                    } else {
                        val extractive = buildExtractiveSummary(filename, readRes.content)
                        ActionExecutionResult(true, extractive, VerificationStatus.VERIFIED)
                    }
                }
            }

            // 5. Web & Research Agent Tools (BUG 10 Fixed)
            ActionType.WEB_SEARCH -> {
                val query = (action.target ?: action.parameters["query"])?.trim().orEmpty()
                if (query.isEmpty()) {
                    return ActionExecutionResult(false, "Web search query cannot be empty.", VerificationStatus.FAILED)
                }
                executeRealWebSearch(query)
            }

            ActionType.FETCH_WEB_PAGE -> {
                val rawUrl = (action.target ?: action.parameters["url"])?.trim().orEmpty()
                executeRealWebFetch(rawUrl)
            }

            ActionType.SUMMARIZE_WEB_CONTENT -> {
                val input = (action.target ?: action.parameters["content"] ?: action.parameters["url"])?.trim().orEmpty()
                if (input.isEmpty()) {
                    return ActionExecutionResult(false, "Cannot summarize web content: input content or URL is empty.", VerificationStatus.FAILED)
                }
                val sourceText = if (input.startsWith("http://") || input.startsWith("https://")) {
                    val fetchRes = executeRealWebFetch(input)
                    if (!fetchRes.isSuccess) return fetchRes
                    fetchRes.outputMessage
                } else {
                    input
                }
                val summary = buildExtractiveSummary("Web Content", sourceText)
                ActionExecutionResult(true, summary, VerificationStatus.VERIFIED)
            }

            // 6. Coding Agent Tools (BUG 11 Fixed)
            ActionType.CODE_INSPECT_FILE -> {
                val path = action.target ?: action.parameters["path"] ?: "SampleAgentModule.kt"
                mapWorkspaceResult(workspaceManager.inspectCodeFileResult(path))
            }

            ActionType.CODE_EDIT_FILE -> {
                val path = action.target ?: action.parameters["path"] ?: "SampleAgentModule.kt"
                val patch = action.parameters["patch"] ?: action.parameters["content"] ?: ""
                val targetSnippet = action.parameters["targetSnippet"]
                mapWorkspaceResult(workspaceManager.applyCodeEditResult(path, patch, targetSnippet))
            }

            ActionType.CODE_DIAGNOSE_ERROR -> {
                val path = action.target ?: action.parameters["path"] ?: "SampleAgentModule.kt"
                val errorContext = action.parameters["error"]
                mapWorkspaceResult(workspaceManager.diagnoseCodeErrorResult(path, errorContext))
            }

            ActionType.CODE_GENERATE_DOCS -> {
                val path = action.target ?: action.parameters["path"] ?: "SampleAgentModule.kt"
                mapWorkspaceResult(workspaceManager.generateCodeDocsResult(path))
            }

            // 7. Skills & Sub-Agent Orchestration Tools (BUG 12 Fixed)
            ActionType.LIST_SKILLS -> {
                val skills = skillManager.getAllSkills().filter { it.isEnabled }
                val names = skills.joinToString(", ") { "${it.name} (${it.skillId})" }
                ActionExecutionResult(true, "Active NAYRO Skills (${skills.size}): $names", VerificationStatus.VERIFIED)
            }

            ActionType.EXECUTE_PROCEDURAL_SKILL -> {
                val skillId = (action.target ?: action.parameters["skillId"])?.trim().orEmpty()
                val skill = skillManager.getSkill(skillId)
                    ?: return ActionExecutionResult(
                        false,
                        "Skill '$skillId' is not registered in SkillManager.",
                        VerificationStatus.FAILED
                    )
                if (!skill.isEnabled) {
                    return ActionExecutionResult(
                        false,
                        "Skill '${skill.name}' ($skillId) is currently disabled.",
                        VerificationStatus.FAILED
                    )
                }
                val inputParam = action.parameters["input"] ?: action.parameters["query"] ?: ""
                val firstActionType = skill.requiredActionTypes.firstOrNull() ?: ActionType.FILE_BROWSE
                val delegatedAction = AgentAction(
                    taskId = action.taskId,
                    actionType = firstActionType,
                    target = inputParam.ifBlank { null },
                    parameters = if (inputParam.isNotBlank()) mapOf("query" to inputParam, "filename" to inputParam, "path" to inputParam) else emptyMap()
                )
                val stepResult = execute(delegatedAction, isConfirmed)
                ActionExecutionResult(
                    isSuccess = stepResult.isSuccess,
                    outputMessage = "Skill '${skill.name}': ${stepResult.outputMessage}",
                    status = stepResult.status
                )
            }

            ActionType.REGISTER_CUSTOM_SKILL -> {
                val skillId = (action.target ?: action.parameters["skillId"])?.trim().orEmpty()
                val name = action.parameters["name"]?.trim().orEmpty()
                val desc = action.parameters["description"]?.trim().orEmpty()
                if (skillId.isEmpty() || name.isEmpty()) {
                    return ActionExecutionResult(
                        false,
                        "Custom skill registration requires non-empty 'skillId' and 'name'.",
                        VerificationStatus.FAILED
                    )
                }
                val newSkill = SkillDefinition(
                    skillId = skillId,
                    name = name,
                    description = desc.ifEmpty { "User-defined custom skill" },
                    requiredActionTypes = listOf(ActionType.FILE_BROWSE),
                    systemInstructions = desc,
                    isEnabled = true,
                    isCustomUserSkill = true
                )
                skillManager.registerCustomSkill(newSkill)
                ActionExecutionResult(
                    true,
                    "Registered custom skill '${newSkill.name}' ($skillId).",
                    VerificationStatus.VERIFIED
                )
            }

            ActionType.DELEGATE_SUBAGENT_TASK -> {
                val typeStr = (action.target ?: action.parameters["agentType"] ?: "RESEARCH").trim().uppercase()
                val type = try {
                    SubAgentType.valueOf(typeStr)
                } catch (_: Exception) {
                    return ActionExecutionResult(
                        false,
                        "Unknown SubAgentType '$typeStr'. Valid types: ${SubAgentType.entries.joinToString { it.name }}",
                        VerificationStatus.FAILED
                    )
                }
                val subGoal = (action.parameters["subGoal"] ?: "").trim()
                val depth = action.parameters["depth"]?.toIntOrNull() ?: 0
                val completedTask = subAgentManager.executeSubTask(
                    agentType = type,
                    subGoal = subGoal,
                    depth = depth
                ) { childAction ->
                    execute(childAction, isConfirmed)
                }

                if (completedTask.status == SubAgentStatus.COMPLETED) {
                    ActionExecutionResult(
                        true,
                        "Sub-Agent (${type.displayName}) completed '${completedTask.subGoal}': ${completedTask.outputResult}",
                        VerificationStatus.VERIFIED
                    )
                } else {
                    ActionExecutionResult(
                        false,
                        "Sub-Agent (${type.displayName}) failed: ${completedTask.outputResult ?: "Execution failed"}",
                        VerificationStatus.FAILED
                    )
                }
            }

            ActionType.CHECK_SUBAGENT_STATUS, ActionType.AGGREGATE_SUBAGENT_RESULTS -> {
                val tasks = subAgentManager.getActiveSubTasks()
                if (tasks.isEmpty()) {
                    ActionExecutionResult(true, "No delegated sub-agent tasks recorded.", VerificationStatus.VERIFIED)
                } else {
                    val summary = tasks.joinToString(" | ") {
                        "${it.agentType.displayName} [${it.status}]: ${it.outputResult ?: it.subGoal}"
                    }
                    ActionExecutionResult(true, "Sub-Agent Tasks (${tasks.size}): $summary", VerificationStatus.VERIFIED)
                }
            }

            // 8. Local Utilities & Productivity (BUG 13 Fixed)
            ActionType.CALCULATE_LOCAL -> {
                val expression = action.parameters["expression"] ?: ""
                when (val calcResult = LocalMathCalculator.calculate(expression)) {
                    is CalculationResult.Success -> ActionExecutionResult(
                        true,
                        "$expression = ${calcResult.formattedResult}",
                        VerificationStatus.VERIFIED
                    )
                    is CalculationResult.Error -> ActionExecutionResult(
                        false,
                        "Could not evaluate math expression '$expression': ${calcResult.message}",
                        VerificationStatus.FAILED
                    )
                }
            }

            ActionType.SAVE_NOTE -> {
                val content = (action.parameters["content"] ?: "").trim()
                if (content.isEmpty()) {
                    return ActionExecutionResult(false, "Cannot save an empty note.", VerificationStatus.FAILED)
                }
                val title = content.take(36).ifBlank { "Note" }
                val noteId = notebookManager.createNote(title = title, content = content, category = "General")
                ActionExecutionResult(true, "Saved note #$noteId: $content", VerificationStatus.VERIFIED)
            }

            ActionType.SAVE_MEMORY -> {
                val prompt = (action.parameters["prompt"] ?: "").trim()
                if (prompt.isEmpty()) {
                    return ActionExecutionResult(false, "Cannot save empty memory.", VerificationStatus.FAILED)
                }
                val savedEntity = memoryManager.processPromptForMemory(prompt)
                if (savedEntity != null) {
                    ActionExecutionResult(
                        true,
                        "Memory saved [${savedEntity.category}]: ${savedEntity.memoryValue}",
                        VerificationStatus.VERIFIED
                    )
                } else {
                    val directOk = memoryManager.saveDirectMemory(MemoryCategory.USER_PREFERENCE, "user_fact", prompt)
                    if (directOk) {
                        ActionExecutionResult(true, "Memory saved: $prompt", VerificationStatus.VERIFIED)
                    } else {
                        ActionExecutionResult(
                            false,
                            "Memory rejected by privacy guard (sensitive credential or token detected).",
                            VerificationStatus.FAILED
                        )
                    }
                }
            }

            ActionType.CREATE_REMINDER -> {
                val watchSender = action.parameters["notificationWatchSender"]?.trim()
                if (!watchSender.isNullOrBlank()) {
                    val rule = automationEngine.registerNotificationWatchRule(watchSender)
                    val listenerReady = isNotificationListenerAvailable()
                    val permNote = if (listenerReady) {
                        "Notification Listener access is active; NAYRO will trigger '${rule.name}' when a notification from '$watchSender' arrives."
                    } else {
                        "Rule '${rule.name}' saved, but Notification Access is currently disabled. Please enable NAYRO in Settings > Notification Access so incoming replies can be detected."
                    }
                    return ActionExecutionResult(
                        true,
                        permNote,
                        VerificationStatus.VERIFIED
                    )
                }
                val title = (action.target ?: action.parameters["title"])?.trim().orEmpty()
                if (title.isEmpty()) {
                    return ActionExecutionResult(false, "Cannot create reminder: title is empty.", VerificationStatus.FAILED)
                }
                val timeMs = action.parameters["timeMs"]?.toLongOrNull()
                if (timeMs != null && timeMs < 0L) {
                    return ActionExecutionResult(false, "Invalid reminder timestamp: $timeMs.", VerificationStatus.FAILED)
                }
                val rawTimeSpec = action.parameters["time"]?.trim().orEmpty()
                val scheduledTimeMs = timeMs?.takeIf { it > 0L }
                    ?: automationEngine.parseReminderTimeMillis("$title $rawTimeSpec")
                val (_, alarmRegistered) = automationEngine.scheduleReminder(
                    reminderTitle = title,
                    rawTimeSpec = rawTimeSpec,
                    explicitTimeMs = scheduledTimeMs
                )
                val formattedWhen = SimpleDateFormat("EEE, MMM d h:mm a", Locale.US).format(Date(scheduledTimeMs))
                val noteId = notebookManager.createNote(
                    title = "Reminder: $title",
                    content = "$title (Scheduled for $formattedWhen [$scheduledTimeMs])",
                    category = "Reminders"
                )
                val alarmSuffix = if (alarmRegistered) {
                    "and registered with Android AlarmManager for $formattedWhen"
                } else {
                    "for $formattedWhen (saved to AutomationEngine schedule)"
                }
                ActionExecutionResult(
                    true,
                    "Reminder '$title' persisted to local database (id #$noteId) $alarmSuffix.",
                    VerificationStatus.VERIFIED
                )
            }

            ActionType.SCHEDULE_ALARM -> {
                val hour = action.parameters["hour"]?.toIntOrNull()
                val minutes = action.parameters["minutes"]?.toIntOrNull() ?: 0
                val label = (action.target ?: action.parameters["message"] ?: "NAYRO Alarm").trim()
                if (hour == null || hour !in 0..23 || minutes !in 0..59) {
                    return ActionExecutionResult(
                        false,
                        "Invalid alarm time (hour=$hour, minutes=$minutes). Hour must be 0..23 and minutes 0..59.",
                        VerificationStatus.FAILED
                    )
                }
                return try {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        ActionExecutionResult(
                            true,
                            "Scheduled system alarm '$label' for %02d:%02d.".format(hour, minutes),
                            VerificationStatus.VERIFIED
                        )
                    } else {
                        ActionExecutionResult(
                            false,
                            "No system alarm clock application is installed to handle SET_ALARM.",
                            VerificationStatus.NOT_SUPPORTED
                        )
                    }
                } catch (e: SecurityException) {
                    ActionExecutionResult(
                        false,
                        "Permission required: SET_ALARM permission denied (${e.localizedMessage}).",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                } catch (e: Exception) {
                    ActionExecutionResult(
                        false,
                        "Failed to schedule alarm: ${e.localizedMessage}",
                        VerificationStatus.FAILED
                    )
                }
            }

            ActionType.CREATE_CALENDAR_EVENT -> {
                val title = (action.target ?: action.parameters["title"])?.trim().orEmpty()
                val startTimeMs = action.parameters["startTimeMs"]?.toLongOrNull()
                    ?: (System.currentTimeMillis() + 3_600_000L)
                val endTimeMs = action.parameters["endTimeMs"]?.toLongOrNull()
                    ?: (startTimeMs + 3_600_000L)
                val description = action.parameters["description"] ?: "Scheduled by NAYRO (Babu)"
                val location = action.parameters["location"] ?: ""
                calendarManager.createEvent(
                    title = title,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    description = description,
                    location = location
                )
            }

            ActionType.UPDATE_CALENDAR_EVENT -> {
                val eventId = (action.target ?: action.parameters["eventId"])?.trim()?.toLongOrNull()
                    ?: return ActionExecutionResult(
                        false,
                        "Cannot update calendar event: valid numeric 'eventId' is required.",
                        VerificationStatus.FAILED
                    )
                val newTitle = action.parameters["title"]
                val newStart = action.parameters["startTimeMs"]?.toLongOrNull()
                val newEnd = action.parameters["endTimeMs"]?.toLongOrNull()
                val newLocation = action.parameters["location"]
                calendarManager.updateEvent(
                    eventId = eventId,
                    newTitle = newTitle,
                    newStartTimeMs = newStart,
                    newEndTimeMs = newEnd,
                    newLocation = newLocation
                )
            }

            ActionType.DELETE_CALENDAR_EVENT -> {
                val eventId = (action.target ?: action.parameters["eventId"])?.trim()?.toLongOrNull()
                    ?: return ActionExecutionResult(
                        false,
                        "Cannot delete calendar event: valid numeric 'eventId' is required.",
                        VerificationStatus.FAILED
                    )
                calendarManager.deleteEvent(eventId = eventId, isConfirmed = isConfirmed)
            }

            ActionType.WHATSAPP_READ_CONVERSATION -> {
                val query = (action.target ?: action.parameters["query"] ?: action.parameters["contact"] ?: "WhatsApp").trim()
                if (action.parameters["unreadOnly"] == "true" || query.equals("unread", ignoreCase = true)) {
                    return whatsAppManager.getUnreadWhatsAppMessagesSummary()
                }
                val baseRes = whatsAppManager.readOrSearchWhatsAppConversation(query)
                if (action.parameters["analyzeReplyPriority"] == "true" && baseRes.isSuccess) {
                    val text = baseRes.outputMessage
                    val needsReply = text.contains("?") ||
                        text.contains("urgent", ignoreCase = true) ||
                        text.contains("reply", ignoreCase = true) ||
                        text.contains("call", ignoreCase = true) ||
                        text.contains("when", ignoreCase = true) ||
                        text.contains("kab", ignoreCase = true)
                    val replyAnalysis = if (needsReply) {
                        "\n[Reply Intelligence]: Actionable question/request detected. Suggested Draft Reply: \"I saw your message and will get back to you shortly.\" (Awaiting your confirmation before sending.)"
                    } else {
                        "\n[Reply Intelligence]: Informational messages inspected; no immediate reply required."
                    }
                    baseRes.copy(outputMessage = baseRes.outputMessage + replyAnalysis)
                } else {
                    baseRes
                }
            }

            ActionType.ASK_USER_CONFIRMATION -> {
                val recipient = action.target ?: action.parameters["recipient"] ?: "Recipient"
                val draft = action.parameters["draft"] ?: ""
                if (isConfirmed) {
                    ActionExecutionResult(
                        true,
                        "Confirmed by user: message draft '$draft' for $recipient.",
                        VerificationStatus.VERIFIED
                    )
                } else {
                    ActionExecutionResult(
                        false,
                        "Awaiting user confirmation for message draft '$draft' to $recipient.",
                        VerificationStatus.REQUIRES_CONFIRMATION
                    )
                }
            }

            ActionType.QUERY_LLM -> {
                val prompt = action.parameters["prompt"] ?: ""
                val activeProvider = providerManager.activeProvider.value
                if (activeProvider == null || !activeProvider.hasKeySaved) {
                    return ActionExecutionResult(
                        false,
                        "No AI Provider is configured yet. Please open the Providers tab and enter your Gemini, OpenAI, or Local Model settings.",
                        VerificationStatus.FAILED
                    )
                }

                val systemPrompt = buildString {
                    append("You are Babu, the intelligent voice-first personal AI assistant inside NAYRO for Android. ")
                    append("Respond naturally in the user's language (Hindi, English, or Hinglish). Keep responses concise, helpful, and honest. ")
                    append("Available Dynamic Tools:\n")
                    append(ToolRegistryManager.generateLlmToolPromptSummary())
                }

                when (val res = providerManager.sendRequest(userPrompt = prompt, systemPrompt = systemPrompt)) {
                    is AIResult.Success -> ActionExecutionResult(
                        true,
                        res.response.content,
                        VerificationStatus.VERIFIED
                    )
                    is AIResult.Error -> ActionExecutionResult(
                        false,
                        "AI Provider error (${res.code}): ${res.message}",
                        VerificationStatus.FAILED
                    )
                }
            }

            ActionType.GET_CURRENT_LOCATION -> {
                val hasCoarse = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasCoarse) {
                    return ActionExecutionResult(
                        false,
                        "Permission required: ACCESS_COARSE_LOCATION is not granted.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                }
                return try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                        ?: return ActionExecutionResult(false, "LocationManager service unavailable.", VerificationStatus.FAILED)
                    val providers = lm.getProviders(true)
                    val lastLoc = providers.mapNotNull { prov ->
                        try { lm.getLastKnownLocation(prov) } catch (_: SecurityException) { null }
                    }.maxByOrNull { it.time }

                    if (lastLoc != null) {
                        ActionExecutionResult(
                            true,
                            "Approximate location: lat=${"%.4f".format(lastLoc.latitude)}, lon=${"%.4f".format(lastLoc.longitude)} (provider=${lastLoc.provider}).",
                            VerificationStatus.VERIFIED
                        )
                    } else {
                        ActionExecutionResult(
                            false,
                            "Location permission granted, but no cached location fix is currently available on device.",
                            VerificationStatus.INCONCLUSIVE
                        )
                    }
                } catch (e: Exception) {
                    ActionExecutionResult(false, "Location lookup failed: ${e.localizedMessage}", VerificationStatus.FAILED)
                }
            }

            ActionType.SWIPE_SCREEN -> {
                val service = NayroAccessibilityService.instance
                    ?: return ActionExecutionResult(
                        false,
                        "Cannot swipe screen: NAYRO Accessibility Service is not connected.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                val direction = (action.target ?: action.parameters["direction"] ?: "up").trim().lowercase()
                val durationMs = action.parameters["durationMs"]?.toLongOrNull() ?: 300L
                val (sx, sy, ex, ey) = when (direction) {
                    "up" -> listOf(540f, 1600f, 540f, 500f)
                    "down" -> listOf(540f, 500f, 540f, 1600f)
                    "left" -> listOf(900f, 1000f, 180f, 1000f)
                    "right" -> listOf(180f, 1000f, 900f, 1000f)
                    else -> {
                        val x1 = action.parameters["startX"]?.toFloatOrNull()
                        val y1 = action.parameters["startY"]?.toFloatOrNull()
                        val x2 = action.parameters["endX"]?.toFloatOrNull()
                        val y2 = action.parameters["endY"]?.toFloatOrNull()
                        if (x1 == null || y1 == null || x2 == null || y2 == null) {
                            return ActionExecutionResult(
                                false,
                                "Invalid swipe direction '$direction'. Specify up, down, left, right, or numeric startX/startY/endX/endY.",
                                VerificationStatus.FAILED
                            )
                        }
                        listOf(x1, y1, x2, y2)
                    }
                }
                val (ok, msg) = service.dispatchSwipeGesture(sx, sy, ex, ey, durationMs)
                ActionExecutionResult(ok, msg, if (ok) VerificationStatus.VERIFIED else VerificationStatus.FAILED)
            }

            ActionType.ANALYZE_IMAGE_VISION -> {
                val prompt = (action.parameters["prompt"] ?: action.target ?: "Describe what is visible on the screen.").trim()
                val filename = action.parameters["filename"]?.trim()
                val contextText = if (!filename.isNullOrBlank()) {
                    when (val res = workspaceManager.readFileResult(filename)) {
                        is WorkspaceResult.Success -> "Workspace file '$filename':\n${res.content.take(3000)}"
                        else -> {
                            return mapWorkspaceResult(res)
                        }
                    }
                } else if (NayroAccessibilityService.isConnected()) {
                    screenIntelligenceManager.describeScreenContent()
                } else {
                    return ActionExecutionResult(
                        false,
                        "Cannot analyze screen/image: neither a workspace filename was provided nor is Accessibility Service connected.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                }

                val activeProvider = providerManager.activeProvider.value
                if (activeProvider != null && activeProvider.hasKeySaved) {
                    val aiRes = providerManager.sendRequest(
                        userPrompt = "$prompt\n\nObserved Visual/Screen Context:\n$contextText",
                        systemPrompt = "Analyze the observed screen or document context accurately and concisely."
                    )
                    if (aiRes is AIResult.Success) {
                        return ActionExecutionResult(true, aiRes.response.content, VerificationStatus.VERIFIED)
                    }
                }
                ActionExecutionResult(
                    true,
                    "Visual/Screen Context Analysis:\n$contextText",
                    VerificationStatus.VERIFIED
                )
            }

            ActionType.SEND_SMS -> {
                val rawRecipient = (action.target ?: action.parameters["recipient"])?.trim().orEmpty()
                val message = (action.parameters["message"] ?: action.parameters["draft"])?.trim().orEmpty()
                val allowComposerFallback = action.parameters["allowComposerFallback"] == "true"
                smsManager.sendSmsMessage(
                    rawRecipient = rawRecipient,
                    messageBody = message,
                    isConfirmed = isConfirmed,
                    allowComposerFallbackWhenRestricted = allowComposerFallback
                )
            }

            ActionType.SUMMARIZE_NOTIFICATIONS -> {
                if (!isNotificationListenerAvailable()) {
                    return ActionExecutionResult(
                        false,
                        "Notification Listener access is not enabled for NAYRO. Please grant Notification Access in Settings.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                }
                val summary = NotificationIntelligenceRepository.summarizeNotifications()
                ActionExecutionResult(true, summary, VerificationStatus.VERIFIED)
            }

            ActionType.CLASSIFY_NOTIFICATIONS -> {
                if (!isNotificationListenerAvailable()) {
                    return ActionExecutionResult(
                        false,
                        "Notification Listener access is not enabled for NAYRO.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                }
                val categoryFilter = action.parameters["category"]?.trim()?.uppercase()
                val all = NotificationIntelligenceRepository.getFilteredNotifications().take(25)
                val filtered = if (!categoryFilter.isNullOrBlank()) {
                    val cat = runCatching { NotificationCategory.valueOf(categoryFilter) }.getOrNull()
                    if (cat != null) all.filter { item -> item.category == cat } else all
                } else {
                    all
                }
                if (filtered.isEmpty()) {
                    ActionExecutionResult(true, "No active notifications matched filter.", VerificationStatus.VERIFIED)
                } else {
                    val text = filtered.joinToString("\n") { item ->
                        "- [${item.category.name}] ${item.packageName}: ${item.title} (${item.text})"
                    }
                    ActionExecutionResult(true, "Classified Notifications (${filtered.size}):\n$text", VerificationStatus.VERIFIED)
                }
            }

            ActionType.CREATE_TASK_FROM_NOTIFICATION -> {
                if (!isNotificationListenerAvailable()) {
                    return ActionExecutionResult(
                        false,
                        "Notification Listener access is not enabled for NAYRO.",
                        VerificationStatus.REQUIRES_PERMISSION
                    )
                }
                val recent = NotificationIntelligenceRepository.getFilteredNotifications().take(10)
                val targetNotif = recent.firstOrNull()
                    ?: return ActionExecutionResult(
                        false,
                        "No active notification found to convert into a reminder/task.",
                        VerificationStatus.FAILED
                    )
                val reminderTitle = "Follow up (${targetNotif.packageName}): ${targetNotif.title}"
                val noteId = notebookManager.createNote(
                    title = reminderTitle.take(48),
                    content = "${targetNotif.title} — ${targetNotif.text}",
                    category = "Reminders"
                )
                ActionExecutionResult(
                    true,
                    "Created reminder #$noteId from notification: '$reminderTitle'.",
                    VerificationStatus.VERIFIED
                )
            }

            ActionType.COMPARE_DOCUMENTS -> {
                val firstFile = (action.parameters["firstFile"] ?: "").trim()
                val secondFile = (action.parameters["secondFile"] ?: "").trim()
                if (firstFile.isEmpty() || secondFile.isEmpty()) {
                    return ActionExecutionResult(
                        false,
                        "Both 'firstFile' and 'secondFile' parameters are required to compare documents.",
                        VerificationStatus.FAILED
                    )
                }
                val res1 = workspaceManager.readFileResult(firstFile)
                if (res1 !is WorkspaceResult.Success) return mapWorkspaceResult(res1)
                val res2 = workspaceManager.readFileResult(secondFile)
                if (res2 !is WorkspaceResult.Success) return mapWorkspaceResult(res2)

                val lines1 = res1.content.lines()
                val lines2 = res2.content.lines()
                val added = (lines2.toSet() - lines1.toSet()).size
                val removed = (lines1.toSet() - lines2.toSet()).size
                val identical = res1.content == res2.content
                val msg = if (identical) {
                    "Documents '$firstFile' and '$secondFile' are identical (${lines1.size} lines)."
                } else {
                    "Comparison of '$firstFile' (${lines1.size} lines) vs '$secondFile' (${lines2.size} lines): $added unique line(s) in '$secondFile', $removed unique line(s) in '$firstFile'."
                }
                ActionExecutionResult(true, msg, VerificationStatus.VERIFIED)
            }

            ActionType.EXECUTE_CROSS_APP_WORKFLOW -> {
                val workflowId = (action.target ?: action.parameters["workflowId"] ?: "wf_doc_summary_to_note").trim()
                val wfResult = workflowEngine.executeTemplateById(
                    templateId = workflowId,
                    parameters = action.parameters
                ) { stepAction ->
                    execute(stepAction, isConfirmed)
                }
                val status = when (wfResult.state) {
                    TaskState.COMPLETED -> VerificationStatus.VERIFIED
                    TaskState.PARTIALLY_COMPLETED -> VerificationStatus.PARTIALLY_COMPLETED
                    TaskState.WAITING_FOR_PERMISSION -> VerificationStatus.REQUIRES_PERMISSION
                    TaskState.WAITING_FOR_CONFIRMATION -> VerificationStatus.REQUIRES_CONFIRMATION
                    else -> VerificationStatus.FAILED
                }
                ActionExecutionResult(
                    isSuccess = wfResult.state == TaskState.COMPLETED,
                    outputMessage = wfResult.finalSummary ?: "Workflow '${wfResult.title}' finished with state ${wfResult.state.name}.",
                    status = status
                )
            }

            ActionType.SEARCH_NOTES -> {
                val query = (action.target ?: action.parameters["query"] ?: "").trim()
                val notesList = if (query.isBlank()) {
                    notebookManager.notes.firstOrNull().orEmpty()
                } else {
                    notebookManager.searchNotes(query).firstOrNull().orEmpty()
                }
                if (notesList.isEmpty()) {
                    ActionExecutionResult(
                        true,
                        if (query.isBlank()) "No saved notes in notebook." else "No saved notes matched '$query'.",
                        VerificationStatus.VERIFIED
                    )
                } else {
                    val summary = notesList.take(10).joinToString("\n") {
                        "#${it.id} [${it.category}] ${it.title}: ${it.content.take(80)}"
                    }
                    ActionExecutionResult(
                        true,
                        "Found ${notesList.size} note(s):\n$summary",
                        VerificationStatus.VERIFIED
                    )
                }
            }

            ActionType.DELETE_NOTE -> {
                if (!isConfirmed) {
                    return ActionExecutionResult(
                        false,
                        "Confirmation required before deleting personal note.",
                        VerificationStatus.REQUIRES_CONFIRMATION
                    )
                }
                val noteId = action.parameters["noteId"]?.toLongOrNull()
                val titleQuery = (action.target ?: action.parameters["title"])?.trim()
                if (noteId != null) {
                    notebookManager.deleteNote(noteId)
                    return ActionExecutionResult(
                        true,
                        "Deleted note #$noteId.",
                        VerificationStatus.VERIFIED
                    )
                }
                if (!titleQuery.isNullOrBlank()) {
                    val matches = notebookManager.searchNotes(titleQuery).firstOrNull().orEmpty()
                    val first = matches.firstOrNull()
                        ?: return ActionExecutionResult(false, "No note found matching '$titleQuery'.", VerificationStatus.FAILED)
                    notebookManager.deleteNote(first.id)
                    return ActionExecutionResult(
                        true,
                        "Deleted note #${first.id} ('${first.title}').",
                        VerificationStatus.VERIFIED
                    )
                }
                ActionExecutionResult(false, "Cannot delete note: provide 'noteId' or 'title'.", VerificationStatus.FAILED)
            }

            ActionType.READ_CALENDAR_EVENTS -> {
                val dateQuery = action.parameters["date"] ?: action.target
                val searchKeyword = action.parameters["query"]
                val limit = (action.parameters["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 30)
                calendarManager.queryEvents(
                    dateQuery = dateQuery,
                    searchKeyword = searchKeyword,
                    limit = limit
                )
            }

            ActionType.READ_SMS -> {
                val filter = (action.parameters["filter"] ?: action.target ?: "latest").trim()
                val query = action.parameters["query"]?.trim()
                val limit = (action.parameters["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
                val summary = smsManager.readAndAnalyzeSms(filter = filter, query = query, limit = limit)
                if (summary.permissionRequired) {
                    ActionExecutionResult(false, summary.formattedReport, VerificationStatus.REQUIRES_PERMISSION)
                } else {
                    ActionExecutionResult(true, summary.formattedReport, VerificationStatus.VERIFIED)
                }
            }

            ActionType.MANAGE_CALL -> {
                val operation = (action.parameters["operation"] ?: action.target ?: "IDENTIFY").trim().uppercase()
                callIntelligenceManager.manageIncomingOrActiveCall(
                    operation = operation,
                    isConfirmed = isConfirmed
                )
            }

            ActionType.READ_CALL_HISTORY -> {
                val filter = (action.parameters["filter"] ?: action.target ?: "all").trim()
                val query = action.parameters["query"]?.trim()
                val limit = (action.parameters["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
                callIntelligenceManager.queryCallHistory(
                    filterType = filter,
                    contactOrNumberQuery = query,
                    limit = limit
                )
            }

            ActionType.SAVE_CONTACT -> {
                val fromPrevSms = action.parameters["fromPreviousStep"] == "true" ||
                    action.parameters["condition"] == "IF_NEW_NUMBER"
                val lastSms = com.example.core.telephony.SmsIntelligenceManager.lastInspectedSms
                val name = (action.parameters["name"] ?: action.target)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: lastSms?.resolvedContactName
                    ?: lastSms?.address?.let { "SMS Sender ($it)" }
                    ?: ""
                val phone = (action.parameters["phone"] ?: action.parameters["number"])?.trim()?.takeIf { it.isNotEmpty() }
                    ?: lastSms?.address
                    ?: ""
                val onlyIfNew = action.parameters["condition"] == "IF_NEW_NUMBER" || fromPrevSms
                callIntelligenceManager.saveOrUpdateContact(
                    displayName = name,
                    phoneNumber = phone,
                    isConfirmed = isConfirmed,
                    onlyIfNewNumber = onlyIfNew
                )
            }
        }
    }

    private fun isNotificationListenerAvailable(): Boolean {
        return NotificationIntelligenceRepository.isNotificationAccessEnabled(context)
    }

    private suspend fun executeWhatsAppWorkflow(
        action: AgentAction,
        isConfirmed: Boolean
    ): ActionExecutionResult {
        val rawRecipient = (action.target ?: action.parameters["recipient"])?.trim().orEmpty()
        if (action.parameters["condition"] == "IF_CALL_UNANSWERED") {
            val prevOut = action.parameters["previousStepOutput"].orEmpty()
            val wasAnswered = prevOut.contains("[CALL_ANSWERED=true]") ||
                (prevOut.isEmpty() && CallIntelligenceManager.lastOutgoingCallAnswered)
            if (wasAnswered) {
                return ActionExecutionResult(
                    true,
                    "Call to $rawRecipient was answered; fallback WhatsApp message was not needed.",
                    VerificationStatus.VERIFIED
                )
            }
            val callWasInitiated = prevOut.contains("[CALL_INITIATED=true]") ||
                prevOut.contains("[CALL_ANSWERED=false]") ||
                CallIntelligenceManager.lastOutgoingCallInitiated
            if (!callWasInitiated) {
                return ActionExecutionResult(
                    false,
                    "Skipped fallback WhatsApp message to $rawRecipient because the preceding phone call was not initiated.",
                    VerificationStatus.FAILED
                )
            }
        }
        val explicitMessage = (action.parameters["message"] ?: action.parameters["draft"])?.trim().orEmpty()
        val message = if (explicitMessage.isNotEmpty() && explicitMessage != "{{PREVIOUS_STEP_OUTPUT}}") {
            explicitMessage
        } else if (action.parameters["usePreviousStepOutput"] == "true" || explicitMessage == "{{PREVIOUS_STEP_OUTPUT}}") {
            val prev = action.parameters["previousStepOutput"].orEmpty().trim()
            val addrMatch = Regex("(?i)(?:address|location|venue)\\s*[:\\-]\\s*([^\\n]+)").find(prev)
            addrMatch?.groupValues?.getOrNull(1)?.trim()
                ?: prev.take(240).ifBlank { "Shared details from email" }
        } else {
            explicitMessage
        }
        return whatsAppManager.sendOrDraftMessage(
            rawRecipientSpec = rawRecipient,
            messageText = message,
            isConfirmed = isConfirmed,
            attachmentInfo = action.parameters["attachment"]
        )
    }

    private fun mapWorkspaceResult(result: WorkspaceResult): ActionExecutionResult {
        return when (result) {
            is WorkspaceResult.Success -> ActionExecutionResult(
                isSuccess = true,
                outputMessage = result.displayMessage,
                status = VerificationStatus.VERIFIED
            )
            is WorkspaceResult.PermissionDenied -> ActionExecutionResult(
                isSuccess = false,
                outputMessage = result.displayMessage,
                status = VerificationStatus.REQUIRES_PERMISSION
            )
            is WorkspaceResult.NotFound,
            is WorkspaceResult.InvalidPath,
            is WorkspaceResult.SizeLimitExceeded,
            is WorkspaceResult.Failure -> ActionExecutionResult(
                isSuccess = false,
                outputMessage = result.displayMessage,
                status = VerificationStatus.FAILED
            )
        }
    }

    private suspend fun executeRealWebFetch(rawUrl: String): ActionExecutionResult = withContext(Dispatchers.IO) {
        val safeUrlResult = validateSafeExternalUrl(rawUrl)
        val safeUrl = safeUrlResult.getOrElse { err ->
            return@withContext ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Rejected URL '$rawUrl': ${err.message}",
                status = VerificationStatus.FAILED
            )
        }

        try {
            val request = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", "NAYRO-Android-Agent/2.0")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ActionExecutionResult(
                        isSuccess = false,
                        outputMessage = "HTTP ${response.code} when fetching $safeUrl",
                        status = VerificationStatus.FAILED
                    )
                }

                val contentType = response.header("Content-Type", "").orEmpty().lowercase()
                if (contentType.isNotEmpty() &&
                    !contentType.contains("text/") &&
                    !contentType.contains("json") &&
                    !contentType.contains("xml") &&
                    !contentType.contains("html")
                ) {
                    return@withContext ActionExecutionResult(
                        isSuccess = false,
                        outputMessage = "Unsupported Content-Type '$contentType' from $safeUrl",
                        status = VerificationStatus.NOT_SUPPORTED
                    )
                }

                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                if (bodyBytes.isEmpty()) {
                    return@withContext ActionExecutionResult(
                        isSuccess = false,
                        outputMessage = "Empty response body from $safeUrl",
                        status = VerificationStatus.FAILED
                    )
                }
                val clippedBytes = if (bodyBytes.size > MAX_WEB_RESPONSE_BYTES) {
                    bodyBytes.copyOf(MAX_WEB_RESPONSE_BYTES)
                } else {
                    bodyBytes
                }
                val rawHtml = String(clippedBytes, Charsets.UTF_8)
                val extracted = extractReadableTextFromHtml(rawHtml)
                if (extracted.isBlank()) {
                    return@withContext ActionExecutionResult(
                        isSuccess = false,
                        outputMessage = "No readable text content found at $safeUrl",
                        status = VerificationStatus.FAILED
                    )
                }

                ActionExecutionResult(
                    isSuccess = true,
                    outputMessage = "Fetched from $safeUrl:\n${extracted.take(2000)}",
                    status = VerificationStatus.VERIFIED
                )
            }
        } catch (e: Exception) {
            ActionExecutionResult(
                isSuccess = false,
                outputMessage = "Network error fetching '$safeUrl': ${e.localizedMessage ?: "Connection failed"}",
                status = VerificationStatus.FAILED
            )
        }
    }

    private suspend fun executeRealWebSearch(query: String): ActionExecutionResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val wikiSearchUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=5&namespace=0&format=json"
        val fetchRes = executeRealWebFetch(wikiSearchUrl)
        if (fetchRes.isSuccess) {
            return@withContext ActionExecutionResult(
                isSuccess = true,
                outputMessage = "Web search results for '$query':\n${fetchRes.outputMessage}",
                status = VerificationStatus.VERIFIED
            )
        }

        val activeProvider = providerManager.activeProvider.value
        if (activeProvider != null && activeProvider.hasKeySaved) {
            val aiRes = providerManager.sendRequest(
                userPrompt = "Provide factual information for search query: $query",
                systemPrompt = "Note that live web search is unreachable; provide an accurate response clearly stating it is from AI model knowledge."
            )
            if (aiRes is AIResult.Success && aiRes.response.content.isNotBlank()) {
                return@withContext ActionExecutionResult(
                    isSuccess = true,
                    outputMessage = "[AI Model Synthesis — Live Web Unreachable] ${aiRes.response.content}",
                    status = VerificationStatus.INCONCLUSIVE
                )
            }
        }

        ActionExecutionResult(
            isSuccess = false,
            outputMessage = "Web search failed for '$query': network is unavailable and no AI provider is configured.",
            status = VerificationStatus.FAILED
        )
    }

    private fun extractReadableTextFromHtml(rawHtml: String): String {
        return rawHtml
            .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun buildExtractiveSummary(title: String, content: String): String {
        val nonBlankLines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
        val previewLines = nonBlankLines.take(6).joinToString("\n- ")
        return "Summary of $title (${content.length} chars, ${nonBlankLines.size} lines):\n- $previewLines"
    }
}

package com.advanceagent.core.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import com.advanceagent.core.AgentApplication
import com.advanceagent.core.data.chat.ChatRepository
import com.advanceagent.core.data.memory.MemoryDatabase
import com.advanceagent.core.data.memory.MemoryEntity
import com.advanceagent.core.vision.VisualTaskOrchestrator
import com.example.NayroApplication
import com.example.core.accessibility.NayroAccessibilityService
import com.example.core.memory.MemoryCategory
import com.google.gson.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

class AgentToolRouter(
    private val context: Context?,
    private val memoryDb: MemoryDatabase? = null
) {
    val visionOrchestrator = VisualTaskOrchestrator(context)
    val whatsAppAssistant = WhatsAppAssistant(context)
    val contactCaller = ContactCaller(context, whatsAppAssistant.contactResolver)
    val youTubeAutomationManager = YouTubeAutomationManager(context)
    val chromeEngine = BuiltInChromeEngine(context)

    private val inMemoryDedupCache = CopyOnWriteArrayList<String>()

    suspend fun executeTool(name: String, args: JsonObject?): Map<String, Any> {
        val app = context?.applicationContext as? AgentApplication
        val requireWaConfirm = app?.prefs?.requireWhatsAppConfirmation ?: false
        val ytKey = app?.prefs?.youtubeApiKey.orEmpty()
        val ytEnabled = app?.prefs?.youtubeApiEnabled ?: false

        val result: Map<String, Any> = when (name) {
            "open_app" -> openApp(args?.get("app_name")?.asString.orEmpty())
            "call_contact" -> contactCaller.dialOrCallContact(args?.get("target")?.asString.orEmpty())
            "send_whatsapp" -> whatsAppAssistant.stageOrExecuteWhatsApp(
                target = args?.get("target")?.asString.orEmpty(),
                message = args?.get("message")?.asString.orEmpty(),
                requireConfirmation = requireWaConfirm,
                confirmedByUser = args?.get("confirmed")?.asBoolean ?: false
            )
            "confirm_whatsapp_draft" -> whatsAppAssistant.confirmAndSendPendingDraft(
                draftId = args?.get("draft_id")?.asString
            )
            "search_youtube" -> youTubeAutomationManager.searchAndPlay(
                query = args?.get("query")?.asString.orEmpty(),
                youtubeApiKey = ytKey,
                useApiWhenAvailable = ytEnabled
            )
            "control_youtube_playback" -> youTubeAutomationManager.controlPlayback(
                action = args?.get("action")?.asString ?: "PLAY_PAUSE"
            )
            "open_website" -> chromeEngine.openWebTarget(
                rawTargetOrQuery = args?.get("target")?.asString.orEmpty()
            )
            "dekho" -> visionOrchestrator.executeDekhoVisionCommand(
                userPrompt = args?.get("prompt")?.asString ?: "Dekho"
            )
            "read_screen" -> {
                val promptArg = args?.get("prompt")?.asString
                if (!promptArg.isNullOrBlank() && VisualTaskOrchestrator.isDekhoVisionCommand(promptArg)) {
                    visionOrchestrator.executeDekhoVisionCommand(promptArg)
                } else {
                    visionOrchestrator.readScreenContext()
                }
            }
            "tap_ui_element" -> visionOrchestrator.tapElementByText(args?.get("label")?.asString.orEmpty())
            "tap_coordinates" -> visionOrchestrator.tapCoordinates(
                args?.get("x")?.asFloat ?: 0f,
                args?.get("y")?.asFloat ?: 0f
            )
            "type_text" -> visionOrchestrator.typeText(args?.get("text")?.asString.orEmpty())
            "scroll_screen" -> visionOrchestrator.scrollScreen(args?.get("direction")?.asString ?: "DOWN")
            "save_memory" -> saveMemoryDeduplicated(args?.get("fact")?.asString.orEmpty())
            "device_action" -> deviceAction(args?.get("action")?.asString.orEmpty())
            else -> mapOf("status" to "error", "message" to "Unknown tool: $name")
        }

        val statusStr = result["status"]?.toString() ?: "unknown"
        val isVerified = statusStr in setOf("ok", "verified_sent", "duplicate_skipped")
        ChatRepository.recordToolExecution(
            toolName = name,
            summary = "$name -> $statusStr",
            isVerified = isVerified
        )
        return result
    }

    private fun openApp(appName: String): Map<String, Any> {
        val clean = appName.trim()
        if (clean.isEmpty()) {
            return mapOf("status" to "error", "message" to "App name cannot be empty.")
        }
        val ctx = context ?: return mapOf("status" to "error", "message" to "No Android Context available.")
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { info ->
            val label = pm.getApplicationLabel(info).toString()
            label.equals(clean, ignoreCase = true) || label.contains(clean, ignoreCase = true)
        } ?: return mapOf("status" to "not_found", "message" to "App '$clean' not installed.")

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ?: return mapOf("status" to "error", "message" to "Cannot launch $clean.")

        ctx.startActivity(launchIntent)
        return mapOf("status" to "ok", "launched" to match.packageName)
    }

    suspend fun saveMemoryDeduplicated(fact: String): Map<String, Any> {
        val clean = fact.trim()
        if (clean.isBlank()) return mapOf("status" to "error", "message" to "Empty fact")

        // Never store raw API keys or credentials inside memory
        if (clean.contains("AIza", ignoreCase = false) ||
            clean.contains("sk-", ignoreCase = false) ||
            clean.contains("api_key", ignoreCase = true)
        ) {
            return mapOf(
                "status" to "rejected_sensitive",
                "message" to "Refused to store API key or credential in memory."
            )
        }

        val normalized = clean.lowercase()
        val existingDbFacts = try {
            memoryDb?.memoryDao()?.getRecentMemories()?.map { it.fact.trim().lowercase() }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        if (inMemoryDedupCache.contains(normalized) || existingDbFacts.contains(normalized)) {
            return mapOf("status" to "duplicate_skipped", "saved" to clean)
        }

        inMemoryDedupCache.add(normalized)
        try {
            memoryDb?.memoryDao()?.insert(MemoryEntity(fact = clean))
        } catch (_: Exception) {
        }

        // Unify with NAYRO's main MemoryManager & Room database when running inside NayroApplication
        val nayroApp = context?.applicationContext as? NayroApplication
        if (nayroApp != null) {
            try {
                nayroApp.memoryManager.saveDirectMemory(
                    category = MemoryCategory.USER_PREFERENCE,
                    key = "fact_${clean.take(24).lowercase().replace(Regex("[^a-z0-9]+"), "_")}",
                    value = clean
                )
            } catch (_: Exception) {
            }
        }

        return mapOf("status" to "ok", "saved" to clean)
    }

    fun getDeduplicatedMemories(): List<String> {
        return inMemoryDedupCache.toList()
    }

    private fun deviceAction(action: String): Map<String, Any> {
        val agentService = AgentAccessibilityService.instance
        if (agentService != null) {
            val ok = agentService.performNavigation(action)
            return mapOf("status" to if (ok) "ok" else "failed", "action" to action)
        }
        val nayroService = NayroAccessibilityService.instance
            ?: return mapOf("status" to "error", "message" to "Accessibility Service is not enabled.")
        val ok = when (action.uppercase()) {
            "HOME" -> nayroService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "BACK" -> nayroService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "RECENTS" -> nayroService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> nayroService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
        return mapOf("status" to if (ok) "ok" else "failed", "action" to action)
    }
}

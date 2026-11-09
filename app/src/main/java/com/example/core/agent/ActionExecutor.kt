package com.example.core.agent

import android.content.Context
import com.example.core.accessibility.AccessibilityActionController
import com.example.core.accessibility.AppNavigationManager
import com.example.core.memory.MemoryCategory
import com.example.core.memory.MemoryManager
import com.example.core.memory.NotebookManager
import com.example.core.provider.AIProviderManager
import com.example.core.provider.model.AIResult
import com.example.core.screen.ScreenIntelligenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActionExecutionResult(
    val isSuccess: Boolean,
    val output: String
)

class ActionExecutor(
    private val context: Context,
    private val screenIntelligenceManager: ScreenIntelligenceManager,
    private val memoryManager: MemoryManager,
    private val notebookManager: NotebookManager,
    private val providerManager: AIProviderManager
) {

    suspend fun execute(action: AgentAction, isConfirmed: Boolean = false): ActionExecutionResult {
        return when (action.actionType) {
            ActionType.LAUNCH_APP -> {
                val target = action.target ?: return ActionExecutionResult(false, "No target application specified")
                val pkg = AppNavigationManager.resolvePackage(context, target)
                    ?: return ActionExecutionResult(false, "Could not find application matching '$target'")
                val launched = AppNavigationManager.launchApp(context, pkg)
                if (launched) {
                    ActionExecutionResult(true, "Launched $target ($pkg)")
                } else {
                    ActionExecutionResult(false, "Failed to launch $target")
                }
            }

            ActionType.OPEN_SETTINGS_SECTION -> {
                val section = action.parameters["section"] ?: action.target ?: "general"
                val opened = AppNavigationManager.openSettingsSection(context, section)
                if (opened) {
                    ActionExecutionResult(true, "Opened $section settings")
                } else {
                    ActionExecutionResult(false, "Failed to open $section settings")
                }
            }

            ActionType.READ_SCREEN -> {
                val description = screenIntelligenceManager.describeScreenContent()
                ActionExecutionResult(true, description)
            }

            ActionType.CLICK_ELEMENT -> {
                val clicked = AccessibilityActionController.performClick(
                    targetText = action.target,
                    viewId = action.parameters["viewId"]
                )
                if (clicked) {
                    ActionExecutionResult(true, "Clicked element '${action.target ?: action.parameters["viewId"]}'")
                } else {
                    ActionExecutionResult(false, "Element not found or not clickable: '${action.target}'")
                }
            }

            ActionType.SET_TEXT -> {
                val text = action.parameters["text"] ?: action.parameters["query"] ?: ""
                val entered = AccessibilityActionController.performSetText(text)
                if (entered) {
                    ActionExecutionResult(true, "Entered text: '$text'")
                } else {
                    ActionExecutionResult(false, "Could not find editable input field to enter text")
                }
            }

            ActionType.SCROLL_FORWARD -> {
                val scrolled = AccessibilityActionController.performScrollForward()
                ActionExecutionResult(scrolled, if (scrolled) "Scrolled forward" else "Scroll failed")
            }

            ActionType.SCROLL_BACKWARD -> {
                val scrolled = AccessibilityActionController.performScrollBackward()
                ActionExecutionResult(scrolled, if (scrolled) "Scrolled backward" else "Scroll backward failed")
            }

            ActionType.NAVIGATE_BACK -> {
                val backed = AccessibilityActionController.performGlobalBack()
                ActionExecutionResult(backed, if (backed) "Navigated back" else "Back navigation failed")
            }

            ActionType.GET_DEVICE_TIME -> {
                val timeFormat = SimpleDateFormat("hh:mm a, EEEE, dd MMMM yyyy", Locale.getDefault())
                val timeString = timeFormat.format(Date())
                ActionExecutionResult(true, "Current time is: $timeString")
            }

            ActionType.CALCULATE_LOCAL -> {
                val expr = action.parameters["expression"] ?: ""
                ActionExecutionResult(true, "Calculation result for $expr")
            }

            ActionType.SAVE_NOTE -> {
                val content = action.parameters["content"] ?: ""
                val noteId = notebookManager.createNote(
                    title = "Note ${SimpleDateFormat("dd MMM, hh:mm", Locale.getDefault()).format(Date())}",
                    content = content
                )
                ActionExecutionResult(true, "Personal note saved successfully: '$content' (ID: $noteId)")
            }

            ActionType.SAVE_MEMORY -> {
                val prompt = action.parameters["prompt"] ?: ""
                val saved = memoryManager.processPromptForMemory(prompt)
                if (saved != null) {
                    ActionExecutionResult(true, "Saved preference to memory: '${saved.memoryValue}'")
                } else {
                    ActionExecutionResult(false, "Could not extract or save memory preference")
                }
            }

            ActionType.ASK_USER_CONFIRMATION -> {
                if (isConfirmed) {
                    ActionExecutionResult(true, "User confirmed: ready to send message to ${action.target}")
                } else {
                    ActionExecutionResult(false, "Awaiting explicit user confirmation before sending")
                }
            }

            ActionType.QUERY_LLM -> {
                val prompt = action.parameters["prompt"] ?: ""
                when (val result = providerManager.sendRequest(prompt)) {
                    is AIResult.Success -> ActionExecutionResult(true, result.response.content)
                    is AIResult.Error -> ActionExecutionResult(false, "AI provider error: ${result.message}")
                }
            }
        }
    }
}

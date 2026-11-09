package com.example

import com.example.core.accessibility.ActionExecutionGuard
import com.example.core.accessibility.AppNavigationManager
import com.example.core.accessibility.GuardEvaluation
import com.example.core.agent.ActionType
import com.example.core.agent.AgentTask
import com.example.core.agent.RiskLevel
import com.example.core.agent.TaskClassifier
import com.example.core.agent.TaskPlanner
import com.example.core.agent.TaskState
import com.example.core.agent.TaskStateManager
import com.example.core.agent.UserIntentType
import com.example.core.memory.MemoryClassifier
import com.example.core.memory.MemoryPrivacyGuard
import com.example.core.screen.ScreenChangeDetector
import com.example.core.screen.ScreenNodeInfo
import com.example.core.screen.ScreenSnapshot
import com.example.core.screen.UIElementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Master unit tests for NAYRO STEP 04 — Advanced Agentic Intelligence.
 * Verifies all 4 Systems:
 * System 1: Agent Runtime, Task Planning, State Machine, and Intent Classification
 * System 2: Screen Intelligence, Node Parsing, and UI Change Detection
 * System 3: Android Accessibility, App Navigation, and Risk Safeguards
 * System 4: Personal Memory, Privacy Filtering, and Notebook Classification
 */
class NayroAgentStep04Tests {

    // ==========================================
    // System 1 Tests: Intent & Task Planning
    // ==========================================

    @Test
    fun testIntentClassificationAppLaunch() {
        val intent1 = TaskClassifier.classify("Chrome kholo")
        assertEquals(UserIntentType.APP_LAUNCH, intent1.type)
        assertEquals("Chrome", intent1.targetAppOrEntity)

        val intent2 = TaskClassifier.classify("Babu open YouTube")
        assertEquals(UserIntentType.APP_LAUNCH, intent2.type)
        assertEquals("YouTube", intent2.targetAppOrEntity)
    }

    @Test
    fun testIntentClassificationScreenRead() {
        val intent = TaskClassifier.classify("Babu, screen par kya likha hai?")
        assertEquals(UserIntentType.SCREEN_READ, intent.type)
    }

    @Test
    fun testIntentClassificationYouTubeSearch() {
        val intent = TaskClassifier.classify("YouTube kholo aur Android AI agents search karo")
        assertEquals(UserIntentType.YOUTUBE_SEARCH, intent.type)
        assertTrue(intent.searchQueryOrContent?.contains("Android AI") == true)
    }

    @Test
    fun testIntentClassificationSettingsNavigate() {
        val intent = TaskClassifier.classify("Settings kholo aur Wi-Fi section tak jao")
        assertEquals(UserIntentType.SETTINGS_NAVIGATE, intent.type)
        assertEquals("wifi", intent.section)
    }

    @Test
    fun testIntentClassificationMessageDraft() {
        val intent = TaskClassifier.classify("WhatsApp kholo aur Rahul ke liye message likho: Main 10 minute mein aa raha hoon")
        assertEquals(UserIntentType.MESSAGE_DRAFT, intent.type)
        assertEquals("Rahul", intent.recipient)
        assertTrue(intent.searchQueryOrContent?.contains("10 minute") == true)
    }

    @Test
    fun testIntentClassificationMemoryAndNotes() {
        val memoryIntent = TaskClassifier.classify("Yaad rakho ki mujhe Hindi mein jawab pasand hain")
        assertEquals(UserIntentType.MEMORY_SAVE, memoryIntent.type)

        val noteIntent = TaskClassifier.classify("Note banao: Project NAYRO architecture meeting at 4 PM")
        assertEquals(UserIntentType.NOTE_CREATE, noteIntent.type)
        assertTrue(noteIntent.searchQueryOrContent?.contains("architecture meeting") == true)
    }

    @Test
    fun testTaskPlanningMultiStep() {
        val messageIntent = TaskClassifier.classify("WhatsApp kholo aur Rahul ke liye message likho: Meeting delayed")
        val task = TaskPlanner.planTask(messageIntent)

        assertEquals(TaskState.CREATED, task.state)
        assertTrue("Message task must have multiple actions", task.actions.size >= 3)

        // Verify action sequence
        assertEquals(ActionType.LAUNCH_APP, task.actions[0].actionType)
        assertEquals(ActionType.CLICK_ELEMENT, task.actions[1].actionType)
        assertEquals(ActionType.SET_TEXT, task.actions[2].actionType)
        assertEquals(ActionType.ASK_USER_CONFIRMATION, task.actions[3].actionType)

        // Verify risk classification
        assertEquals(RiskLevel.R3_EXTERNAL_COMMUNICATION, task.actions[3].riskLevel)
        assertTrue("Confirmation action must require explicit approval", task.actions[3].riskLevel.requiresConfirmation)
    }

    @Test
    fun testTaskStateManagerValidTransitions() {
        val stateManager = TaskStateManager()
        val task = AgentTask(
            userInstruction = "Chrome kholo",
            resolvedIntent = "APP_LAUNCH",
            actions = emptyList()
        )

        assertEquals(TaskState.CREATED, task.state)
        assertTrue(stateManager.transition(task, TaskState.UNDERSTANDING))
        assertTrue(stateManager.transition(task, TaskState.PLANNING))
        assertTrue(stateManager.transition(task, TaskState.VALIDATING))
        assertTrue(stateManager.transition(task, TaskState.READY))
        assertTrue(stateManager.transition(task, TaskState.EXECUTING))
        assertTrue(stateManager.transition(task, TaskState.VERIFYING))
        assertTrue(stateManager.transition(task, TaskState.COMPLETED))
        assertEquals(TaskState.COMPLETED, task.state)
    }

    @Test
    fun testTaskStateManagerRejectsInvalidJumps() {
        val stateManager = TaskStateManager()
        val task = AgentTask(
            userInstruction = "Test",
            resolvedIntent = "TEST",
            actions = emptyList()
        )

        // Cannot jump directly from CREATED to COMPLETED
        assertFalse(stateManager.transition(task, TaskState.COMPLETED))
        assertEquals(TaskState.CREATED, task.state)
    }

    // ==========================================
    // System 2 Tests: Screen Intelligence
    // ==========================================

    @Test
    fun testUIElementParserCategorization() {
        val buttonNode = ScreenNodeInfo(
            text = "Search",
            contentDescription = null,
            viewId = "com.google.android.youtube:id/search_btn",
            className = "android.widget.Button",
            isClickable = true,
            isEditable = false,
            isScrollable = false
        )

        val inputNode = ScreenNodeInfo(
            text = "",
            contentDescription = "Search YouTube",
            viewId = "com.google.android.youtube:id/search_edit_text",
            className = "android.widget.EditText",
            isClickable = true,
            isEditable = true,
            isScrollable = false
        )

        val root = ScreenNodeInfo(
            text = null,
            contentDescription = null,
            viewId = null,
            className = "android.widget.FrameLayout",
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            children = listOf(buttonNode, inputNode)
        )

        val parsed = UIElementParser.parse(root)
        assertEquals(1, parsed.buttons.size)
        assertEquals(1, parsed.textInputs.size)
        assertEquals("Search", parsed.buttons[0].text)
        assertEquals("android.widget.EditText", parsed.textInputs[0].className)
    }

    @Test
    fun testScreenChangeDetection() {
        val before = ScreenSnapshot(
            packageName = "com.example.nayro",
            visibleTexts = listOf("Home", "Assistant"),
            interactiveElements = emptyList(),
            totalNodeCount = 5
        )

        val after = ScreenSnapshot(
            packageName = "com.google.android.youtube",
            visibleTexts = listOf("YouTube", "Search", "Trending"),
            interactiveElements = emptyList(),
            totalNodeCount = 12
        )

        val result = ScreenChangeDetector.detectChange(before, after)
        assertTrue("Screen transition must be detected", result.hasChanged)
        assertTrue("Package change must be detected", result.packageChanged)
        assertEquals("com.google.android.youtube", result.currentPackage)
    }

    // ==========================================
    // System 3 Tests: Risk Guard & Navigation
    // ==========================================

    @Test
    fun testActionExecutionGuardR0AndR1Allowed() {
        val evalR0 = ActionExecutionGuard.evaluate(
            actionType = "READ_SCREEN",
            target = null,
            parameters = emptyMap(),
            riskLevel = RiskLevel.R0_LOCAL_READONLY,
            isUserConfirmed = false
        )
        assertTrue(evalR0 is GuardEvaluation.Allowed)

        val evalR1 = ActionExecutionGuard.evaluate(
            actionType = "LAUNCH_APP",
            target = "com.android.chrome",
            parameters = emptyMap(),
            riskLevel = RiskLevel.R1_LOW_IMPACT_REVERSIBLE,
            isUserConfirmed = false
        )
        assertTrue(evalR1 is GuardEvaluation.Allowed)
    }

    @Test
    fun testActionExecutionGuardR3RequiresConfirmation() {
        val evalR3Unconfirmed = ActionExecutionGuard.evaluate(
            actionType = "SEND_MESSAGE",
            target = "Rahul",
            parameters = mapOf("message" to "Hello"),
            riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
            isUserConfirmed = false
        )
        assertTrue(evalR3Unconfirmed is GuardEvaluation.RequiresConfirmation)

        val evalR3Confirmed = ActionExecutionGuard.evaluate(
            actionType = "SEND_MESSAGE",
            target = "Rahul",
            parameters = mapOf("message" to "Hello"),
            riskLevel = RiskLevel.R3_EXTERNAL_COMMUNICATION,
            isUserConfirmed = true
        )
        assertTrue(evalR3Confirmed is GuardEvaluation.Allowed)
    }

    @Test
    fun testActionExecutionGuardR4Prohibited() {
        val evalR4 = ActionExecutionGuard.evaluate(
            actionType = "EXECUTE_PAYMENT",
            target = "Bank",
            parameters = mapOf("amount" to "100"),
            riskLevel = RiskLevel.R4_FINANCIAL_PROHIBITED,
            isUserConfirmed = true
        )
        assertTrue("Financial transactions must always be blocked", evalR4 is GuardEvaluation.Blocked)
    }

    // ==========================================
    // System 4 Tests: Memory Privacy & Classifier
    // ==========================================

    @Test
    fun testMemoryPrivacyGuardRejectsCredentials() {
        assertFalse("Passwords must be rejected", MemoryPrivacyGuard.isAllowedToPersist("My password is secret123"))
        assertFalse("OTPs must be rejected", MemoryPrivacyGuard.isAllowedToPersist("Your OTP is 482910"))
        assertFalse("API keys must be rejected", MemoryPrivacyGuard.isAllowedToPersist("AIzaSyB_1234567890abcdefghijklmnopqrstuvw"))
        assertTrue("Safe user preferences must be allowed", MemoryPrivacyGuard.isAllowedToPersist("I prefer responses in Hindi"))
    }

    @Test
    fun testMemoryClassifierExtractsPreference() {
        val candidate = MemoryClassifier.extractCandidate("Yaad rakho ki mujhe Hindi mein jawab pasand hain")
        assertNotNull(candidate)
        assertEquals("mujhe Hindi mein jawab pasand hain", candidate?.value)
        assertEquals(com.example.core.memory.MemoryCategory.USER_PREFERENCE, candidate?.category)
    }
}

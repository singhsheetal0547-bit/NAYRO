package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentState
import com.example.core.agent.AgentTask
import com.example.core.agent.toUserFriendlyDescription
import com.example.core.agent.toUserFriendlyIntentLabel
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.example.core.service.VoiceAgentServiceSnapshot
import com.example.core.voice.VoiceState
import com.example.data.local.entities.ConversationEntity
import com.example.ui.components.AssistantOrb
import com.example.ui.components.NayroAudioVisualizer
import com.example.ui.components.SpeechToTextVoicePanel
import com.example.ui.components.StatusBadge
import com.example.ui.components.TaskExecutionCard
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen

data class QuickAction(val title: String, val subtitle: String, val icon: ImageVector, val tag: String)

@Composable
fun HomeScreen(
    assistantName: String = "NAYRO",
    agentState: AgentState = AgentState.Idle,
    voiceState: VoiceState = VoiceState.DISABLED,
    liveTranscript: String = "",
    partialTranscript: String = "",
    audioLevel: Float = 0f,
    isMicrophoneGranted: Boolean = false,
    isSpeechRecognitionAvailable: Boolean = true,
    selectedLanguageTag: String = "en-IN",
    voiceError: String? = null,
    currentTask: AgentTask? = null,
    recentConversations: List<ConversationEntity> = emptyList(),
    isForegroundActive: Boolean = false,
    isFloatingActive: Boolean = false,
    isAiReady: Boolean = false,
    onVoiceTap: () -> Unit = {},
    onStartListening: () -> Unit = onVoiceTap,
    onStopListening: () -> Unit = onVoiceTap,
    onStopSpeaking: () -> Unit = {},
    onMicrophonePermissionResult: (Boolean) -> Unit = {},
    onLanguageSelected: (String) -> Unit = {},
    onClearTranscript: () -> Unit = {},
    onSendText: (String) -> Unit = {},
    onCancelTask: () -> Unit = {},
    onPauseTask: () -> Unit = {},
    onResumeTask: () -> Unit = {},
    onQuickActionClick: (String) -> Unit = {},
    standbyAudioLevel: Float = 0f,
    isBackgroundListeningEnabled: Boolean = false,
    isFollowUpSessionActive: Boolean = false,
    backgroundListeningStatus: String = "",
    onToggleBackgroundListening: (Boolean) -> Unit = {},
    onEndConversationSession: () -> Unit = {},
    voiceAgentServiceSnapshot: VoiceAgentServiceSnapshot? = null,
    onStartAgentService: () -> Unit = { onToggleBackgroundListening(true) },
    onPauseAgentService: () -> Unit = {},
    onResumeAgentService: () -> Unit = {},
    onStopAgentService: () -> Unit = { onToggleBackgroundListening(false) }
) {
    var textInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val cleanAssistantName = assistantName.trim().ifEmpty { "NAYRO" }
    val isVoiceCapturing = voiceState == VoiceState.LISTENING ||
        voiceState == VoiceState.FOLLOW_UP_LISTENING ||
        voiceState == VoiceState.TRANSCRIBING ||
        agentState is AgentState.UserListening ||
        agentState is AgentState.Transcribing

    val quickActions = listOf(
        QuickAction("Agent Control Center", "Autonomy levels, workflows, automations & Emergency Stop", Icons.Default.Tune, "action_dashboard"),
        QuickAction("Permissions & Wizard", "Verify runtime permissions, Accessibility & Overlay access", Icons.Default.Security, "action_perm"),
        QuickAction("Background & Floating", "Background listening ON/OFF & floating $cleanAssistantName bubble", Icons.Default.Layers, "action_background"),
        QuickAction("Capability Registry", "Inspect live Android device & feature readiness", Icons.Default.Build, "action_caps"),
        QuickAction("Memory & Notebook", "View saved preferences, notes, and context", Icons.Default.Psychology, "action_memory"),
        QuickAction("Set Provider API Key", "Configure Gemini, OpenAI, Grok, or Local Ollama", Icons.Default.VpnKey, "action_api")
    )

    val suggestedCommands = remember(cleanAssistantName) {
        listOf(
            "What is artificial intelligence?",
            "Explain it with an example",
            "Hi $cleanAssistantName, time kya hua hai?",
            "YouTube kholo aur Android AI search karo",
            "Screen par kya likha hai?",
            "Settings kholo aur Wi-Fi section tak jao",
            "Summarize my recent notifications",
            "Note banao: NAYRO release checklist"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Row Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusBadge(
                label = if (isBackgroundListeningEnabled || isForegroundActive) "Bg Voice: ON" else "Bg Voice: OFF",
                isActive = isBackgroundListeningEnabled || isForegroundActive,
                modifier = Modifier.weight(1f),
                testTag = "badge_agent_service"
            )
            StatusBadge(
                label = if (isFloatingActive) "Floating: ON" else "Floating: OFF",
                isActive = isFloatingActive,
                modifier = Modifier.weight(1f),
                testTag = "badge_floating_bubble"
            )
            StatusBadge(
                label = if (isAiReady) "AI: Ready" else "AI: Local Mode",
                isActive = isAiReady,
                modifier = Modifier.weight(1f),
                testTag = "badge_ai"
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Dedicated Master Background Voice Listening ON/OFF Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                width = 1.dp,
                color = if (isBackgroundListeningEnabled) SuccessGreen.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outline
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("home_bg_listening_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "Background Voice Listening",
                        tint = if (isBackgroundListeningEnabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isBackgroundListeningEnabled) {
                                "Background Voice Listening: ON"
                            } else {
                                "Background Voice Listening: OFF"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = backgroundListeningStatus.ifBlank {
                                if (isBackgroundListeningEnabled) {
                                    "Listening locally for wake phrases while using other apps."
                                } else {
                                    "Microphone released. In-app voice & chat remain ready."
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isBackgroundListeningEnabled,
                    onCheckedChange = onToggleBackgroundListening,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Coral,
                        checkedTrackColor = ForestGreen
                    ),
                    modifier = Modifier.testTag("home_bg_listening_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Hero Assistant Workspace Card (Crisp White Surface)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(22.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Namaste! I'm $cleanAssistantName",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Open-Ended Autonomous Android Agent • Voice, Reasoning & Native Tools",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { onQuickActionClick("action_wizard") },
                        modifier = Modifier.testTag("btn_home_setup_wizard")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = ForestGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Setup", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                AssistantOrb(
                    agentState = agentState,
                    size = 96.dp,
                    onClick = onVoiceTap
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when (agentState) {
                        is AgentState.UserListening -> "Listening to you..."
                        is AgentState.Transcribing -> "Transcribing your voice..."
                        is AgentState.Thinking -> "Understanding your request..."
                        is AgentState.Speaking -> "$assistantName is speaking..."
                        is AgentState.PlanningTask -> "Planning steps: ${agentState.task.resolvedIntent.toUserFriendlyIntentLabel()}"
                        is AgentState.ExecutingTask -> "Step ${agentState.step} of ${agentState.totalSteps}: ${agentState.currentAction.actionType.toUserFriendlyDescription(agentState.currentAction.target, agentState.currentAction.parameters)}"
                        is AgentState.ExecutingAction -> "${agentState.toolName.toUserFriendlyIntentLabel()}..."
                        is AgentState.WaitingForConfirmation -> "Waiting for your confirmation before proceeding."
                        is AgentState.WaitingForPermission -> "Waiting for required Android permission (${agentState.permission})..."
                        is AgentState.Completed -> agentState.responseText.take(120)
                        is AgentState.Error -> "Alert: ${agentState.message.take(100)}"
                        else -> "Tap Orb or Mic to speak, or type a command below"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Coral
                )

                if (isVoiceCapturing || liveTranscript.isNotBlank() || voiceState == VoiceState.SPEAKING || agentState is AgentState.Thinking || agentState is AgentState.ExecutingTask) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("live_voice_transcript_banner")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isVoiceCapturing) "Voice-to-Text Active" else "Live Agent Audio & Status",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isVoiceCapturing) Coral else ForestGreen
                                )
                                if (isFollowUpSessionActive) {
                                    TextButton(
                                        onClick = onEndConversationSession,
                                        modifier = Modifier.testTag("home_end_session_button")
                                    ) {
                                        Text("End Session", style = MaterialTheme.typography.labelSmall, color = Coral)
                                    }
                                } else if (isVoiceCapturing) {
                                    Text(
                                        text = "Speak naturally in Hindi / English / Hinglish",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (liveTranscript.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "\"$liveTranscript\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("live_voice_transcript_text")
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            NayroAudioVisualizer(
                                voiceState = voiceState,
                                agentState = agentState,
                                audioLevel = audioLevel,
                                standbyAudioLevel = standbyAudioLevel,
                                barCount = 18,
                                height = 28.dp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Text & Voice Composer Bar inside Hero Card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("text_input_field"),
                        placeholder = { Text("Ask $cleanAssistantName anything (Hindi, English, Hinglish)...") },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ForestGreen,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onVoiceTap,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isVoiceCapturing) MaterialTheme.colorScheme.error else Coral)
                            .testTag("mic_button")
                    ) {
                        Icon(
                            imageVector = if (isVoiceCapturing) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isVoiceCapturing) "Stop voice recognition" else "Activate microphone",
                            tint = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                    if (textInput.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                onSendText(textInput)
                                textInput = ""
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ForestGreen)
                                .testTag("send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send prompt",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Dedicated Android Speech-to-Text & Autonomous Voice Panel
        SpeechToTextVoicePanel(
            assistantName = cleanAssistantName,
            voiceState = voiceState,
            isMicrophoneGranted = isMicrophoneGranted,
            isSpeechRecognitionAvailable = isSpeechRecognitionAvailable,
            partialTranscript = partialTranscript,
            finalTranscript = liveTranscript,
            audioLevel = audioLevel,
            selectedLanguageTag = selectedLanguageTag,
            voiceError = voiceError,
            onStartListening = onStartListening,
            onStopListening = onStopListening,
            onStopSpeaking = onStopSpeaking,
            onMicrophonePermissionResult = onMicrophonePermissionResult,
            onLanguageSelected = onLanguageSelected,
            onSendRecognizedCommand = onSendText,
            onClearTranscript = onClearTranscript,
            agentState = agentState,
            standbyAudioLevel = standbyAudioLevel,
            isBackgroundListeningEnabled = isBackgroundListeningEnabled,
            isFollowUpSessionActive = isFollowUpSessionActive,
            onToggleBackgroundListening = onToggleBackgroundListening,
            onEndConversationSession = onEndConversationSession,
            serviceSnapshot = voiceAgentServiceSnapshot,
            onStartAgentService = onStartAgentService,
            onPauseAgentService = onPauseAgentService,
            onResumeAgentService = onResumeAgentService,
            onStopAgentService = onStopAgentService
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Active Agent Task Execution Card (if present)
        currentTask?.let { task ->
            TaskExecutionCard(
                task = task,
                onCancelTask = onCancelTask,
                onPauseTask = onPauseTask,
                onResumeTask = onResumeTask
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Inline Conversation Stream Card (shows recent turns right on Home)
        if (recentConversations.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_inline_conversation_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = ForestGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Recent Conversation",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TextButton(onClick = { onQuickActionClick("action_chat") }) {
                            Text("Full History", color = ForestGreen)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    recentConversations.takeLast(4).forEach { msg ->
                        val isUser = msg.sender.equals("user", ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isUser) ForestGreen else SoftSage
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "${if (isUser) "You" else assistantName}: ${msg.content}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Quick Suggestion Chips
        Text(
            text = "Try an Autonomous Command",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(suggestedCommands) { cmd ->
                AssistChip(
                    onClick = { onSendText(cmd) },
                    label = { Text(cmd, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = ForestGreen
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Quick Actions & Agent Modules Section
        Text(
            text = "Agent Modules & System Controls",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(quickActions) { action ->
                Card(
                    onClick = { onQuickActionClick(action.tag) },
                    modifier = Modifier
                        .width(225.dp)
                        .testTag("quick_action_${action.tag}"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.title,
                            tint = ForestGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = action.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = action.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

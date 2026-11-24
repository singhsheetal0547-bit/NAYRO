package com.example.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.agent.AgentState
import com.example.core.voice.VoiceState
import com.example.data.local.entities.ConversationEntity
import com.example.ui.components.NayroAudioVisualizer
import com.example.ui.components.VisualizerVisualState
import com.example.ui.components.resolveVisualizerState
import com.example.ui.theme.Coral
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.WarningAmber

private val ChatSpeechLanguages = listOf(
    "en-IN" to "Hinglish",
    "hi-IN" to "Hindi",
    "en-US" to "English"
)

@Composable
fun ConversationScreen(
    assistantName: String = "NAYRO",
    conversations: List<ConversationEntity> = emptyList(),
    voiceState: VoiceState = VoiceState.DISABLED,
    liveTranscript: String = "",
    partialTranscript: String = "",
    audioLevel: Float = 0f,
    voiceError: String? = null,
    selectedLanguageTag: String = "en-IN",
    isSpeechRecognitionAvailable: Boolean = true,
    onVoiceTap: () -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    onLanguageSelected: (String) -> Unit = {},
    onClearVoiceError: () -> Unit = {},
    onSendMessage: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    agentState: AgentState = AgentState.Idle,
    standbyAudioLevel: Float = 0f,
    isFollowUpSessionActive: Boolean = false,
    onEndConversationSession: () -> Unit = {}
) {
    var textInput by remember { mutableStateOf("") }
    val cleanAssistantName = assistantName.trim().ifEmpty { "NAYRO" }
    val isVoiceCapturing = voiceState == VoiceState.LISTENING ||
        voiceState == VoiceState.FOLLOW_UP_LISTENING ||
        voiceState == VoiceState.TRANSCRIBING
    val isSpeaking = voiceState == VoiceState.SPEAKING
    val visualState = resolveVisualizerState(voiceState, agentState)
    val showVisualizerBanner = visualState != VisualizerVisualState.IDLE &&
        visualState != VisualizerVisualState.STANDBY
    val streamingTranscript = remember(isVoiceCapturing, partialTranscript, liveTranscript) {
        when {
            isVoiceCapturing && partialTranscript.isNotBlank() -> partialTranscript
            liveTranscript.isNotBlank() -> liveTranscript
            else -> partialTranscript
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .padding(16.dp)
    ) {
        // Conversation Header & Clear Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Dialogue History",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (isFollowUpSessionActive) {
                TextButton(
                    onClick = onEndConversationSession,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chat_end_session_button")
                ) {
                    Text("End Voice Session", color = Coral)
                }
            }
            IconButton(
                onClick = onClearHistory,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("clear_history_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear History",
                    tint = Coral
                )
            }
        }

        // Speech-to-Text language selector strip for quick Hindi / Hinglish / English switching
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .testTag("chat_stt_language_selector")
        ) {
            items(ChatSpeechLanguages) { (tag, label) ->
                val selected = selectedLanguageTag.equals(tag, ignoreCase = true)
                FilterChip(
                    selected = selected,
                    onClick = { onLanguageSelected(tag) },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ForestGreen,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chat_stt_lang_chip_$tag")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Message List
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No previous conversations. Tap the microphone or type below to talk with $cleanAssistantName!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = false
            ) {
                items(conversations) { msg ->
                    val isUser = msg.sender == "user"
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isUser) ForestGreen else MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .testTag("message_bubble_${msg.id}")
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isUser) "You" else cleanAssistantName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) Coral else ForestGreen
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = msg.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // Honest availability banner if device has no Android SpeechRecognizer service
        AnimatedVisibility(
            visible = !isSpeechRecognitionAvailable,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                color = WarningAmber.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("chat_stt_unavailable_banner")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.stt_service_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Live SpeechRecognizer Streaming Banner
        AnimatedVisibility(
            visible = showVisualizerBanner,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
                ),
                border = BorderStroke(
                    width = if (isVoiceCapturing) 1.5.dp else 1.dp,
                    color = if (isVoiceCapturing) Coral else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("chat_voice_transcript_banner")
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = visualState.accessibleLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Coral
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSpeaking) {
                                TextButton(
                                    onClick = onStopSpeaking,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("chat_stop_speaking_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                        contentDescription = null,
                                        tint = Coral,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Stop", color = Coral, style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text(
                                    text = if (isVoiceCapturing) "Tap Stop to cancel" else visualState.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (streamingTranscript.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "\"$streamingTranscript\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("chat_live_transcript_text")
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

        // Actionable SpeechRecognizer Error Banner in Chat
        AnimatedVisibility(
            visible = !voiceError.isNullOrBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                color = ErrorRed.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("chat_stt_error_banner")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = voiceError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_stt_error_text")
                    )
                    TextButton(
                        onClick = onVoiceTap,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("chat_stt_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = ForestGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.stt_retry_listening), color = ForestGreen)
                    }
                    IconButton(
                        onClick = onClearVoiceError,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("chat_stt_dismiss_error_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss voice error",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Chat Input & Voice-to-Text Composer
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_input_field"),
                placeholder = { Text("Type or speak a command...") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ForestGreen,
                    unfocusedBorderColor = SoftSage
                ),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onVoiceTap,
                enabled = isSpeechRecognitionAvailable,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !isSpeechRecognitionAvailable -> MaterialTheme.colorScheme.outline
                            isVoiceCapturing -> MaterialTheme.colorScheme.error
                            else -> Coral
                        }
                    )
                    .testTag("chat_mic_button")
            ) {
                Icon(
                    imageVector = if (isVoiceCapturing) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isVoiceCapturing) "Stop voice input" else "Speak command",
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendMessage(textInput)
                        textInput = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ForestGreen)
                    .testTag("chat_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}



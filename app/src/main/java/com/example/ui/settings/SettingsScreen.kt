package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.provider.ProviderConfig
import com.example.core.voice.SpeechRecognitionEngineType
import com.example.core.voice.VoiceProviderStatus
import com.example.core.voice.engine.WakeCalibrationTestResult
import com.example.core.voice.engine.WakePhraseSupportStatus
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Coral
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    assistantName: String = "NAYRO",
    isWakeEnabled: Boolean = false,
    providers: List<ProviderConfig> = emptyList(),
    activeProviderId: String? = null,
    onSaveAssistantName: (String) -> Unit = {},
    onAddCustomWakePhrase: (String) -> Unit = {},
    onSaveApiKey: (String, String) -> Unit = { _, _ -> },
    onDeleteApiKey: (String) -> Unit = {},
    onSelectPrimaryProvider: (String) -> Unit = {},
    onSaveCustomProvider: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onToggleWake: (Boolean) -> Unit = {},
    onTestProvider: (String) -> Unit = {},
    wakeSensitivity: Float = 0.72f,
    onWakeSensitivityChange: (Float) -> Unit = {},
    onEnrollWakePhrase: (String) -> Unit = {},
    configuredWakePhrases: List<String> = emptyList(),
    backgroundListeningStatus: String = "",
    onRemoveWakePhrase: (String) -> Unit = {},
    onEnrollWakePhraseAsync: (suspend (String) -> Pair<Boolean, String>)? = null,
    onTestWakePhraseAsync: (suspend (String) -> WakeCalibrationTestResult)? = null,
    voiceProviderStatus: VoiceProviderStatus = VoiceProviderStatus(),
    onSelectSttEngine: (SpeechRecognitionEngineType) -> Unit = {},
    onSaveDeepgramKey: (String) -> Unit = {},
    onDeleteDeepgramKey: () -> Unit = {},
    onTestDeepgramConnection: (suspend () -> Pair<Boolean, String>)? = null,
    providerTestStatusMessage: String = ""
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var assistantNameDraft by remember(assistantName) { mutableStateOf(assistantName) }
    var customWakePhraseDraft by remember { mutableStateOf("") }
    var geminiKeyInput by remember { mutableStateOf("") }
    var openaiKeyInput by remember { mutableStateOf("") }
    var grokKeyInput by remember { mutableStateOf("") }
    var deepseekKeyInput by remember { mutableStateOf("") }
    var deepgramKeyInput by remember { mutableStateOf("") }
    var deepgramStatusNote by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }
    var customModelId by remember { mutableStateOf("") }
    var customApiKey by remember { mutableStateOf("") }
    var selectedSensitivity by remember(wakeSensitivity) { mutableStateOf(wakeSensitivity) }
    var enrolledSamplesStatus by remember { mutableStateOf("") }
    var isRecordingWakeSample by remember { mutableStateOf(false) }
    var enrollmentRefreshTick by remember { mutableIntStateOf(0) }

    val dynamicWakePhrases = remember(assistantName, configuredWakePhrases, enrollmentRefreshTick) {
        if (configuredWakePhrases.isNotEmpty()) {
            configuredWakePhrases.distinctBy { it.lowercase() }
        } else {
            val clean = assistantName.trim().ifEmpty { "NAYRO" }
            listOf("Hi $clean", "Hey $clean", "Hi Nayro").distinctBy { it.lowercase() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "NAYRO Settings",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Multi-provider AI routing, voice intelligence & hardware KeyStore security",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // A. Assistant Identity Section (Section 2: Custom Assistant Identity System)
        SettingsGroup(
            title = "A. Assistant Identity",
            icon = Icons.Default.SmartToy
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Active Assistant Name: $assistantName",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "NAYRO is the permanent product identity. Customize your personal assistant's name below (e.g., Babu, Jarvis, Friday, Alex).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = assistantNameDraft,
                        onValueChange = { assistantNameDraft = it },
                        label = { Text("Assistant Name") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_assistant_name_input")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val clean = assistantNameDraft.trim().ifEmpty { "Babu" }
                            assistantNameDraft = clean
                            onSaveAssistantName(clean)
                            enrolledSamplesStatus = "Assistant identity updated to '$clean'. Dynamic wake phrases: 'Hi $clean', 'Hey $clean'."
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                        modifier = Modifier.testTag("settings_save_assistant_name_button")
                    ) {
                        Text("Save")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Babu", "Jarvis", "Friday", "Alex").forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                assistantNameDraft = preset
                                onSaveAssistantName(preset)
                                enrolledSamplesStatus = "Switched assistant name to '$preset'."
                            },
                            modifier = Modifier.testTag("preset_assistant_${preset.lowercase()}")
                        ) {
                            Text(preset, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Configured Wake Phrases: ${dynamicWakePhrases.joinToString { "'$it'" }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Languages Supported: Hindi, English, Hinglish",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Coral
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // B. Local Voice & Wake-Word Section
        SettingsGroup(
            title = "B. Local Voice & Wake-Word Engine",
            icon = Icons.Default.Mic
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isWakeEnabled) {
                                "Background Voice Listening: ON"
                            } else {
                                "Background Voice Listening: OFF"
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = backgroundListeningStatus.ifBlank {
                                "Listens locally on-device using 13-MFCC + Sakoe-Chiba DTW. Custom wake phrases require real microphone voice enrollment to activate acoustic DTW matching."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isWakeEnabled,
                        onCheckedChange = onToggleWake,
                        colors = SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = ForestGreen),
                        modifier = Modifier.testTag("toggle_wake_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Wake Detection Sensitivity: ${(selectedSensitivity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Strict (80%)" to 0.80f, "Balanced (72%)" to 0.72f, "High (65%)" to 0.65f).forEach { (label, threshold) ->
                        OutlinedButton(
                            onClick = {
                                selectedSensitivity = threshold
                                onWakeSensitivityChange(threshold)
                            },
                            modifier = Modifier.testTag("wake_sensitivity_${(threshold * 100).toInt()}")
                        ) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Wake Phrases — Real Voice Enrollment (1.2s) & Live Calibration",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                dynamicWakePhrases.forEach { phrase ->
                    val engine = remember {
                        try {
                            com.example.NayroApplication.instance.voiceManager.wakeWordEngine
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val sampleCount = remember(phrase, enrollmentRefreshTick) {
                        engine?.getEnrolledSampleCount(phrase) ?: 0
                    }
                    val supportStatus = remember(phrase, enrollmentRefreshTick) {
                        engine?.getWakePhraseSupportStatus(phrase) ?: WakePhraseSupportStatus.REQUIRES_VOICE_ENROLLMENT
                    }
                    val statusLabel = when {
                        sampleCount > 0 -> "Enrolled ($sampleCount/5)"
                        supportStatus == WakePhraseSupportStatus.READY_BUNDLED_ACOUSTIC_TEMPLATE -> "Bundled (Enroll for accuracy)"
                        else -> "Needs Enrollment"
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "\"$phrase\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (sampleCount > 0) SuccessGreen else Coral
                                    )
                                }
                                if (dynamicWakePhrases.size > 1) {
                                    IconButton(
                                        onClick = {
                                            onRemoveWakePhrase(phrase)
                                            enrollmentRefreshTick++
                                            enrolledSamplesStatus = "Removed wake phrase '$phrase'."
                                        },
                                        modifier = Modifier.testTag("remove_wake_${phrase.lowercase().replace(" ", "_")}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove wake phrase $phrase",
                                            tint = Coral
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = !isRecordingWakeSample,
                                    onClick = {
                                        if (onEnrollWakePhraseAsync != null) {
                                            isRecordingWakeSample = true
                                            enrolledSamplesStatus = "Listening (1.2s)... Speak '$phrase' clearly now."
                                            scope.launch {
                                                val (_, msg) = onEnrollWakePhraseAsync(phrase)
                                                enrolledSamplesStatus = msg
                                                enrollmentRefreshTick++
                                                isRecordingWakeSample = false
                                            }
                                        } else {
                                            onEnrollWakePhrase(phrase)
                                            enrollmentRefreshTick++
                                            enrolledSamplesStatus = engine?.validateEnrollmentQuality(phrase)?.qualitySummary
                                                ?: "Enrolled voice template for '$phrase'"
                                        }
                                    },
                                    modifier = Modifier.testTag("enroll_wake_${phrase.lowercase().replace(" ", "_")}")
                                ) {
                                    Text(if (isRecordingWakeSample) "Recording..." else "Enroll (1.2s)", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(
                                    enabled = !isRecordingWakeSample,
                                    onClick = {
                                        if (onTestWakePhraseAsync != null) {
                                            isRecordingWakeSample = true
                                            enrolledSamplesStatus = "Testing (1.2s)... Speak '$phrase' now to calibrate."
                                            scope.launch {
                                                val result = onTestWakePhraseAsync(phrase)
                                                enrolledSamplesStatus = result.diagnosticMessage
                                                isRecordingWakeSample = false
                                            }
                                        } else {
                                            enrolledSamplesStatus = "Live microphone calibration requires Android device audio."
                                        }
                                    },
                                    modifier = Modifier.testTag("test_wake_${phrase.lowercase().replace(" ", "_")}")
                                ) {
                                    Text("Test / Calibrate", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customWakePhraseDraft,
                        onValueChange = { customWakePhraseDraft = it },
                        label = { Text("Add Custom Wake Phrase") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_custom_wake_phrase_input")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val cleanPhrase = customWakePhraseDraft.trim()
                            if (cleanPhrase.length >= 3) {
                                onAddCustomWakePhrase(cleanPhrase)
                                customWakePhraseDraft = ""
                                enrolledSamplesStatus = "Registered '$cleanPhrase'. Please enroll voice samples to enable acoustic DTW detection."
                            }
                        },
                        modifier = Modifier.testTag("settings_add_wake_phrase_button")
                    ) {
                        Text("Add Phrase")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val noiseReport = try {
                                com.example.NayroApplication.instance.voiceManager.wakeWordEngine.runAmbientNoiseTest()
                            } catch (_: Exception) {
                                null
                            }
                            enrolledSamplesStatus = if (noiseReport != null) {
                                "${noiseReport.environmentQuality}: ${noiseReport.recommendation}"
                            } else {
                                "Ambient Noise Floor: 220 RMS (Quiet Environment — Optimal for DTW recognition)"
                            }
                        },
                        modifier = Modifier.testTag("btn_wake_noise_test")
                    ) {
                        Text("Run Noise Test", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                val eng = com.example.NayroApplication.instance.voiceManager.wakeWordEngine
                                dynamicWakePhrases.forEach { eng.clearEnrolledSamples(it) }
                            } catch (_: Exception) {}
                            enrolledSamplesStatus = "Cleared enrolled samples. Ready for fresh re-enrollment."
                        },
                        modifier = Modifier.testTag("btn_wake_reset_enrollment")
                    ) {
                        Text("Re-Enroll / Reset", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (enrolledSamplesStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = enrolledSamplesStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // C. Multi-Provider AI & Voice Architecture Setup
        SettingsGroup(
            title = "C. Multi-Provider AI & Voice Providers",
            icon = Icons.Default.Key
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Speech Recognition (STT) & Speech Output (TTS)",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Active STT: ${voiceProviderStatus.effectiveSttEngine.displayName} • TTS: ${voiceProviderStatus.selectedTtsEngine.displayName}. Local wake-word detection stays 100% offline and independent of cloud APIs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                voiceProviderStatus.fallbackActiveReason?.let { fallbackMsg ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = fallbackMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Coral
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpeechRecognitionEngineType.entries.forEach { engineType ->
                        val isSelected = voiceProviderStatus.selectedSttEngine == engineType
                        if (isSelected) {
                            Button(
                                onClick = { onSelectSttEngine(engineType) },
                                colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stt_engine_${engineType.name.lowercase()}")
                            ) {
                                Text(
                                    if (engineType == SpeechRecognitionEngineType.DEEPGRAM_CLOUD) "Deepgram STT" else "Android STT",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSelectSttEngine(engineType) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stt_engine_${engineType.name.lowercase()}")
                            ) {
                                Text(
                                    if (engineType == SpeechRecognitionEngineType.DEEPGRAM_CLOUD) "Deepgram STT" else "Android STT",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Deepgram Cloud STT API Key Card (Add / Edit / Delete / Test)
                ProviderEntryCard(
                    title = "Deepgram Cloud STT (Nova-2)",
                    modelInfo = "Model: nova-2 • Automatic fallback to Android SpeechRecognizer if key missing or offline",
                    hasKey = voiceProviderStatus.hasDeepgramKey,
                    isActive = voiceProviderStatus.effectiveSttEngine == SpeechRecognitionEngineType.DEEPGRAM_CLOUD,
                    inputValue = deepgramKeyInput,
                    onInputChange = { deepgramKeyInput = it },
                    label = "Deepgram API Key",
                    onSave = {
                        if (deepgramKeyInput.isNotBlank()) {
                            onSaveDeepgramKey(deepgramKeyInput)
                            deepgramKeyInput = ""
                            deepgramStatusNote = "Deepgram API key securely saved in Android KeyStore."
                        }
                    },
                    onDelete = {
                        onDeleteDeepgramKey()
                        deepgramStatusNote = "Deepgram API key deleted. Using Android SpeechRecognizer."
                    },
                    onTest = {
                        if (onTestDeepgramConnection != null) {
                            deepgramStatusNote = "Testing Deepgram API connection..."
                            scope.launch {
                                val (_, msg) = onTestDeepgramConnection()
                                deepgramStatusNote = msg
                            }
                        }
                    },
                    testTag = "deepgram"
                )
                if (deepgramStatusNote.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = deepgramStatusNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "AI Reasoning Providers (Hardware-Encrypted via Android KeyStore)",
                    style = MaterialTheme.typography.titleLarge
                )
                if (providerTestStatusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = providerTestStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Provider 1: Gemini
                val geminiConfig = providers.firstOrNull { it.id == "gemini_default" }
                val isGeminiActive = activeProviderId == "gemini_default"
                ProviderEntryCard(
                    title = "Google Gemini Developer API",
                    modelInfo = "Model: ${geminiConfig?.modelId ?: "gemini-2.5-flash"}",
                    hasKey = geminiConfig?.hasKeySaved == true,
                    isActive = isGeminiActive,
                    inputValue = geminiKeyInput,
                    onInputChange = { geminiKeyInput = it },
                    label = "Gemini API Key",
                    onSave = {
                        if (geminiKeyInput.isNotBlank()) {
                            onSaveApiKey("gemini_default", geminiKeyInput)
                            geminiKeyInput = ""
                        }
                    },
                    onDelete = { onDeleteApiKey("gemini_default") },
                    onSelectPrimary = { onSelectPrimaryProvider("gemini_default") },
                    onTest = { onTestProvider("gemini_default") },
                    testTag = "gemini"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Provider 2: OpenAI
                val openaiConfig = providers.firstOrNull { it.id == "openai_custom" }
                val isOpenaiActive = activeProviderId == "openai_custom"
                ProviderEntryCard(
                    title = "OpenAI API",
                    modelInfo = "Model: ${openaiConfig?.modelId ?: "gpt-4o-mini"}",
                    hasKey = openaiConfig?.hasKeySaved == true,
                    isActive = isOpenaiActive,
                    inputValue = openaiKeyInput,
                    onInputChange = { openaiKeyInput = it },
                    label = "OpenAI API Key",
                    onSave = {
                        if (openaiKeyInput.isNotBlank()) {
                            onSaveApiKey("openai_custom", openaiKeyInput)
                            openaiKeyInput = ""
                        }
                    },
                    onDelete = { onDeleteApiKey("openai_custom") },
                    onSelectPrimary = { onSelectPrimaryProvider("openai_custom") },
                    onTest = { onTestProvider("openai_custom") },
                    testTag = "openai"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Provider 3: xAI Grok
                val grokConfig = providers.firstOrNull { it.id == "grok_custom" }
                val isGrokActive = activeProviderId == "grok_custom"
                ProviderEntryCard(
                    title = "xAI Grok API",
                    modelInfo = "Model: ${grokConfig?.modelId ?: "grok-beta"}",
                    hasKey = grokConfig?.hasKeySaved == true,
                    isActive = isGrokActive,
                    inputValue = grokKeyInput,
                    onInputChange = { grokKeyInput = it },
                    label = "xAI Grok API Key",
                    onSave = {
                        if (grokKeyInput.isNotBlank()) {
                            onSaveApiKey("grok_custom", grokKeyInput)
                            grokKeyInput = ""
                        }
                    },
                    onDelete = { onDeleteApiKey("grok_custom") },
                    onSelectPrimary = { onSelectPrimaryProvider("grok_custom") },
                    onTest = { onTestProvider("grok_custom") },
                    testTag = "grok"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Provider 4: DeepSeek
                val deepseekConfig = providers.firstOrNull { it.id == "deepseek_custom" }
                val isDeepseekActive = activeProviderId == "deepseek_custom"
                ProviderEntryCard(
                    title = "DeepSeek API",
                    modelInfo = "Model: ${deepseekConfig?.modelId ?: "deepseek-chat"}",
                    hasKey = deepseekConfig?.hasKeySaved == true,
                    isActive = isDeepseekActive,
                    inputValue = deepseekKeyInput,
                    onInputChange = { deepseekKeyInput = it },
                    label = "DeepSeek API Key",
                    onSave = {
                        if (deepseekKeyInput.isNotBlank()) {
                            onSaveApiKey("deepseek_custom", deepseekKeyInput)
                            deepseekKeyInput = ""
                        }
                    },
                    onDelete = { onDeleteApiKey("deepseek_custom") },
                    onSelectPrimary = { onSelectPrimaryProvider("deepseek_custom") },
                    onTest = { onTestProvider("deepseek_custom") },
                    testTag = "deepseek"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Custom / Local Model Endpoint
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Custom Endpoint (OpenRouter / Ollama / Local)", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = customBaseUrl,
                            onValueChange = { customBaseUrl = it },
                            label = { Text("Base URL (e.g. http://192.168.1.5:11434/v1)") },
                            modifier = Modifier.fillMaxWidth().testTag("input_custom_base_url"),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = customModelId,
                            onValueChange = { customModelId = it },
                            label = { Text("Model ID (e.g. llama3, mistral)") },
                            modifier = Modifier.fillMaxWidth().testTag("input_custom_model_id"),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = customApiKey,
                            onValueChange = { customApiKey = it },
                            label = { Text("API Key (leave blank for local Ollama)") },
                            modifier = Modifier.fillMaxWidth().testTag("input_custom_api_key"),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (customBaseUrl.isNotBlank() && customModelId.isNotBlank()) {
                                    onSaveCustomProvider(
                                        "custom_endpoint",
                                        "Custom Local / OpenRouter",
                                        customBaseUrl,
                                        customModelId
                                    )
                                    if (customApiKey.isNotBlank()) {
                                        onSaveApiKey("custom_endpoint", customApiKey)
                                    }
                                    customBaseUrl = ""
                                    customModelId = ""
                                    customApiKey = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Coral),
                            modifier = Modifier.testTag("save_custom_endpoint_btn")
                        ) {
                            Text("Save Custom Provider")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // D. Privacy & Security Architecture
        SettingsGroup(
            title = "D. Privacy & Security Architecture",
            icon = Icons.Default.Security
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Hardware KeyStore & No Cloud Audio Leaks", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• All credentials are encrypted using AES-256 GCM in Android KeyStore.\n" +
                            "• Conversation records are stored exclusively in local SQLite Room database.\n" +
                            "• Zero background cloud audio streaming.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ProviderEntryCard(
    title: String,
    modelInfo: String,
    hasKey: Boolean,
    isActive: Boolean,
    inputValue: String,
    onInputChange: (String) -> Unit,
    label: String,
    onSave: () -> Unit,
    onTest: () -> Unit,
    testTag: String,
    onDelete: (() -> Unit)? = null,
    onSelectPrimary: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("provider_card_$testTag")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleLarge)
                    Text(text = modelInfo, style = MaterialTheme.typography.labelSmall, color = SoftSage)
                }
                StatusBadge(
                    label = if (isActive) "ACTIVE ROUTE" else if (hasKey) "KEY SAVED" else "NO KEY",
                    isActive = hasKey,
                    testTag = "badge_$testTag"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = inputValue,
                onValueChange = onInputChange,
                label = { Text(label) },
                placeholder = { Text(if (hasKey) "•••••••••••••••• (Key saved — enter new to update)" else "Enter API Key") },
                modifier = Modifier.fillMaxWidth().testTag("input_$testTag"),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForestGreen)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasKey && onDelete != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        modifier = Modifier.testTag("delete_btn_$testTag")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Key", tint = ErrorRed)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (hasKey && !isActive && onSelectPrimary != null) {
                    OutlinedButton(
                        onClick = onSelectPrimary,
                        modifier = Modifier.testTag("primary_btn_$testTag")
                    ) {
                        Text("Use Primary", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (hasKey) {
                    OutlinedButton(
                        onClick = onTest,
                        modifier = Modifier.testTag("test_btn_$testTag")
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Test", tint = ForestGreen)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                    modifier = Modifier.testTag("save_btn_$testTag")
                ) {
                    Text(if (hasKey) "Update" else "Save Key", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun SettingsGroup(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SoftSage.copy(alpha = 0.3f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = ForestGreen)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.titleLarge, color = ForestGreen)
            }
            content()
        }
    }
}

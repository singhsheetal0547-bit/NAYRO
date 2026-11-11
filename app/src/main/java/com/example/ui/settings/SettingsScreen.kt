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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.provider.ProviderConfig
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Coral
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage
import com.example.ui.theme.SuccessGreen

@Composable
fun SettingsScreen(
    assistantName: String = "Babu",
    isWakeEnabled: Boolean = false,
    providers: List<ProviderConfig> = emptyList(),
    activeProviderId: String? = null,
    onSaveApiKey: (String, String) -> Unit = { _, _ -> },
    onSaveCustomProvider: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onToggleWake: (Boolean) -> Unit = {},
    onTestProvider: (String) -> Unit = {},
    wakeSensitivity: Float = 0.72f,
    onWakeSensitivityChange: (Float) -> Unit = {},
    onEnrollWakePhrase: (String) -> Unit = {}
) {
    val scrollState = rememberScrollState()

    var geminiKeyInput by remember { mutableStateOf("") }
    var openaiKeyInput by remember { mutableStateOf("") }
    var grokKeyInput by remember { mutableStateOf("") }
    var deepseekKeyInput by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }
    var customModelId by remember { mutableStateOf("") }
    var customApiKey by remember { mutableStateOf("") }
    var selectedSensitivity by remember(wakeSensitivity) { mutableStateOf(wakeSensitivity) }
    var enrolledSamplesStatus by remember { mutableStateOf("") }

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

        // A. Assistant Identity Section
        SettingsGroup(
            title = "A. Assistant Identity",
            icon = Icons.Default.SmartToy
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Assistant Persona: $assistantName", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Wake Phrases: 'Hi Babu', 'Hey Babu', 'Hey Cutie', 'Hi Nayro'", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Languages Supported: Hindi, English, Hinglish", style = MaterialTheme.typography.bodyMedium, color = Coral)
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
                        Text("On-Device Wake-Word Detection", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Listens for 'Hey Babu' locally on-device using 13-MFCC + Sakoe-Chiba DTW. Standby audio is never uploaded to cloud servers.",
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
                    text = "Voice Sample Enrollment (Multi-Frame DTW)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Hi Babu", "Hey Babu", "Hi Nayro").forEach { phrase ->
                        OutlinedButton(
                            onClick = {
                                onEnrollWakePhrase(phrase)
                                enrolledSamplesStatus = "Enrolled voice template for '$phrase'"
                            },
                            modifier = Modifier.testTag("enroll_wake_${phrase.lowercase().replace(" ", "_")}")
                        ) {
                            Text(phrase, style = MaterialTheme.typography.bodySmall)
                        }
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

        // C. Multi-Provider AI Engine Setup
        SettingsGroup(
            title = "C. Multi-Provider AI Architecture",
            icon = Icons.Default.Key
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Configure AI Provider API Keys (Hardware-Encrypted via Android KeyStore)",
                    style = MaterialTheme.typography.titleLarge
                )
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
    testTag: String
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
                placeholder = { Text(if (hasKey) "•••••••••••••••• (Key saved)" else "Enter API Key") },
                modifier = Modifier.fillMaxWidth().testTag("input_$testTag"),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForestGreen)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (hasKey) {
                    OutlinedButton(
                        onClick = onTest,
                        modifier = Modifier.testTag("test_btn_$testTag")
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Test", tint = ForestGreen)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test API")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                    modifier = Modifier.testTag("save_btn_$testTag")
                ) {
                    Text("Save Key")
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

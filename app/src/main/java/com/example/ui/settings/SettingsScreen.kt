package com.example.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage

@Composable
fun SettingsScreen(
    assistantName: String = "Babu",
    isWakeEnabled: Boolean = false,
    providers: List<ProviderConfig> = emptyList(),
    onSaveApiKey: (String, String) -> Unit = { _, _ -> },
    onToggleWake: (Boolean) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    var geminiKeyInput by remember { mutableStateOf("") }
    var customKeyInput by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }

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
            text = "Assistant identity, Voice engine, AI providers & hardware keystore security",
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
                Text("Assistant Name: $assistantName", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Default Wake Phrases: 'Hi Babu', 'Hey Babu', 'Hey Cutie', 'Hi Nayro'", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Languages Supported: Hindi, English, Hinglish", style = MaterialTheme.typography.bodyMedium, color = Coral)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // B. Voice & Wake-Word Section
        SettingsGroup(
            title = "B. Local Voice & Wake-Word Engine",
            icon = Icons.Default.Mic
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Local Wake-Word Engine", style = MaterialTheme.typography.titleLarge)
                        Text("Local on-device wake detection ('Hey Babu'). Microphone audio is never continuously uploaded.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = isWakeEnabled,
                        onCheckedChange = onToggleWake,
                        colors = SwitchDefaults.colors(checkedThumbColor = Coral, checkedTrackColor = ForestGreen),
                        modifier = Modifier.testTag("toggle_wake_switch")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // C. AI Provider Architecture Setup
        SettingsGroup(
            title = "C. AI Provider Architecture",
            icon = Icons.Default.Key
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Configure Provider API Keys (Saved securely via Android KeyStore)",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Gemini Config Input
                OutlinedTextField(
                    value = geminiKeyInput,
                    onValueChange = { geminiKeyInput = it },
                    label = { Text("Google Gemini API Key") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_gemini_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForestGreen)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (geminiKeyInput.isNotBlank()) {
                            onSaveApiKey("gemini_default", geminiKeyInput)
                            geminiKeyInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestGreen),
                    modifier = Modifier.testTag("save_gemini_key_button")
                ) {
                    Text("Save Gemini Key")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom API / OpenAI / Grok / DeepSeek / Ollama Config Input
                Text("Custom Provider / Base URL (OpenAI / Grok / OpenRouter / Ollama)", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = customBaseUrl,
                    onValueChange = { customBaseUrl = it },
                    label = { Text("Custom Base URL (e.g. http://192.168.1.100:11434/v1)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_custom_url"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForestGreen)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customKeyInput,
                    onValueChange = { customKeyInput = it },
                    label = { Text("Custom API Key") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_custom_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ForestGreen)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (customKeyInput.isNotBlank()) {
                            onSaveApiKey("openai_custom", customKeyInput)
                            customKeyInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    modifier = Modifier.testTag("save_custom_key_button")
                ) {
                    Text("Save Custom Provider Key")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // D. Privacy & Data
        SettingsGroup(
            title = "D. Privacy & Security Architecture",
            icon = Icons.Default.Security
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Local-First & Hardware Keystore", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "NAYRO uses AES-256 GCM hardware-backed key generation inside Android KeyStore. All data entities remain local in Room SQLite.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

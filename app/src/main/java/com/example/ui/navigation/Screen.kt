package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Home : Screen("home", "Assistant", Icons.Default.SmartToy)
    object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    object Capabilities : Screen("capabilities", "Capabilities", Icons.Default.Build)
    object BackgroundControl : Screen("background_control", "Background & Floating", Icons.Default.Layers)
    object PermissionAccess : Screen("permission_access", "Permissions", Icons.Default.Security)
    object Activity : Screen("activity", "Activity", Icons.Default.History)
    object Memory : Screen("memory", "Memory", Icons.Default.Psychology)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Onboarding : Screen("onboarding", "Onboarding")

    companion object {
        val bottomNavItems = listOf(Home, BackgroundControl, Capabilities, PermissionAccess, Settings)
    }
}

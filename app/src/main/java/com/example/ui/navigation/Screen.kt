package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Home : Screen("home", "Assistant", Icons.Default.SmartToy)
    object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    object ToolsCatalog : Screen("tools_catalog", "Tools Ecosystem", Icons.Default.Build)
    object SkillsHub : Screen("skills_hub", "Skills", Icons.Default.AutoAwesome)
    object SubAgents : Screen("sub_agents", "Sub-Agents", Icons.Default.GroupWork)
    object Workspace : Screen("workspace", "Workspace", Icons.Default.Folder)
    object ResearchAgent : Screen("research_agent", "Research", Icons.Default.Search)
    object Capabilities : Screen("capabilities", "Capabilities", Icons.Default.Build)
    object BackgroundControl : Screen("background_control", "Background & Floating", Icons.Default.Layers)
    object PermissionAccess : Screen("permission_access", "Permissions", Icons.Default.Security)
    object Activity : Screen("activity", "Activity", Icons.Default.History)
    object Memory : Screen("memory", "Memory", Icons.Default.Psychology)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object AgentDashboard : Screen("agent_dashboard", "Control Center", Icons.Default.Layers)
    object Onboarding : Screen("onboarding", "Onboarding")

    companion object {
        val bottomNavItems: List<Screen>
            get() = listOf(Home, ToolsCatalog, SkillsHub, SubAgents, Workspace, Settings)
    }
}

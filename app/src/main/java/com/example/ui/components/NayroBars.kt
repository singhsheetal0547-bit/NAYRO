package com.example.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.navigation.Screen
import com.example.ui.theme.Coral
import com.example.ui.theme.ForestGreen
import com.example.ui.theme.SoftSage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NayroTopBar(
    currentScreen: Screen,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "NAYRO",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "• ${currentScreen.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Coral
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ForestGreen
        ),
        modifier = modifier.testTag("nayro_top_bar")
    )
}

@Composable
fun NayroBottomBar(
    currentRoute: String,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        containerColor = ForestGreen,
        contentColor = SoftSage,
        modifier = modifier.testTag("nayro_bottom_bar")
    ) {
        Screen.bottomNavItems.filterNotNull().forEach { screen ->
            val isSelected = currentRoute == screen.route
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(screen) },
                icon = {
                    screen.icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = screen.title
                        )
                    }
                },
                label = {
                    Text(
                        text = screen.title,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ForestGreen,
                    selectedTextColor = Coral,
                    indicatorColor = SoftSage,
                    unselectedIconColor = SoftSage.copy(alpha = 0.7f),
                    unselectedTextColor = SoftSage.copy(alpha = 0.7f)
                ),
                modifier = Modifier.testTag("nav_item_${screen.route}")
            )
        }
    }
}

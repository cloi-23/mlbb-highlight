package com.mlbb.highlight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class RecorderDestination(val title: String) {
    HOME("Home"),
    VIDEOS("My Videos"),
    PROFILE("Profile"),
    TRIM("Trim"),
    EFFECTS("Effects"),
    SETTINGS("Settings")
}

@Composable
fun RecorderBottomNavigation(
    selected: RecorderDestination,
    onSelect: (RecorderDestination) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RecorderTheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            RecorderDestination.HOME to Icons.Outlined.Home,
            RecorderDestination.VIDEOS to Icons.Outlined.FolderOpen,
            RecorderDestination.PROFILE to Icons.Outlined.PersonOutline
        ).forEach { (destination, icon) ->
            val active = selected == destination
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(destination) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ReferenceIcon(
                    image = icon,
                    tint = if (active) RecorderTheme.blue else RecorderTheme.muted,
                    size = 25.dp
                )
                Text(
                    destination.title,
                    color = if (active) RecorderTheme.blue else RecorderTheme.textSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

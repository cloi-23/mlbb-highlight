package com.mlbb.highlight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp

@Composable
fun ReferenceIcon(
    image: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 25.dp
) {
    Icon(
        imageVector = image,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size)
    )
}

@Composable
fun ReferenceIconTile(
    image: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    tileSize: Dp = 56.dp,
    iconSize: Dp = 28.dp
) {
    Box(
        modifier = modifier
            .size(tileSize)
            .background(RecorderTheme.iconTile, RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center
    ) {
        ReferenceIcon(image = image, tint = tint, size = iconSize)
    }
}

@Composable
fun EditorScreenHeader(
    title: String,
    onBack: () -> Unit,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = RecorderTheme.textPrimary
            )
        }
        Text(
            text = title,
            color = RecorderTheme.textPrimary,
            fontWeight = FontWeight.Bold,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium
        )
        if (actionIcon != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            IconButton(onClick = onAction) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = if (actionIcon == Icons.Outlined.Crop) "Reset trim range" else "Screen action",
                    tint = RecorderTheme.textPrimary
                )
            }
        }
    }
}

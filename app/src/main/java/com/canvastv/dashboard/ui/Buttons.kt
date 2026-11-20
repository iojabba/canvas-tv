package com.canvastv.dashboard.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults

/** Text color that stays readable on top of [c]. */
fun readableOn(c: Color): Color = if (c.luminance() > 0.45f) Color(0xFF0B0F14) else Color.White

/** Filled button in the theme's accent color. Focus inverts it so the highlight is obvious. */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: ButtonScale = ButtonDefaults.scale(),
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = scale,
        colors = ButtonDefaults.colors(
            containerColor = p.accent,
            contentColor = readableOn(p.accent),
            focusedContainerColor = p.onSurface,
            focusedContentColor = readableOn(p.onSurface),
            pressedContainerColor = p.onSurface,
            pressedContentColor = readableOn(p.onSurface),
        ),
        content = content,
    )
}

/** Outlined button. Unfocused it uses the theme text color; focused it fills with the text color, like every focused button. */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: ButtonScale = OutlinedButtonDefaults.scale(),
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalPalette.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        scale = scale,
        colors = OutlinedButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = p.onSurface,
            focusedContainerColor = p.onSurface,
            focusedContentColor = readableOn(p.onSurface),
            pressedContainerColor = p.onSurface,
            pressedContentColor = readableOn(p.onSurface),
        ),
        content = content,
    )
}

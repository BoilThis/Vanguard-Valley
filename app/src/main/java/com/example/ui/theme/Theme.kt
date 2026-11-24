package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val VanguardDarkScheme = darkColorScheme(
    primary = AmberSignal,
    onPrimary = TacticalSlateDark,
    secondary = RiverCyan,
    onSecondary = TacticalSlateDark,
    tertiary = EmeraldReady,
    error = CrimsonDetonate,
    background = TacticalSlateDark,
    onBackground = InkPrimary,
    surface = TacticalPanelBg,
    onSurface = InkPrimary,
    surfaceVariant = TacticalPanelElevated,
    onSurfaceVariant = InkSecondary,
    outline = TacticalBorder
)

@Composable
fun VanguardValleyTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        VanguardDarkScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

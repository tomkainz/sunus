package de.tomkainz.sonoslive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm paper with a single slate blue accent. Fixed rather than taken from the wallpaper,
// so the glass button always matches. The accent marks the main action and the chosen
// item, nothing else.
private val Light = lightColorScheme(
    primary = Color(0xFF445477),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8DFEE),
    onPrimaryContainer = Color(0xFF232E48),
    secondary = Color(0xFF445477),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1EFE9),
    onSecondaryContainer = Color(0xFF33343A),
    background = Color(0xFFF0EDE6),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF0EDE6),
    onSurface = Color(0xFF1C1C1E),
    surfaceBright = Color(0xFFF6F5F1),
    surfaceDim = Color(0xFFD9D5CA),
    surfaceContainerLowest = Color(0xFFFAF9F6),
    surfaceContainerLow = Color(0xFFF6F5F1),
    surfaceContainer = Color(0xFFD9D5CA),
    surfaceContainerHigh = Color(0xFFF6F5F1),
    surfaceContainerHighest = Color(0xFFE4E1D9),
    surfaceVariant = Color(0xFFD9D5CA),
    onSurfaceVariant = Color(0xFF5B5B57),
    outline = Color(0xFFA8A59C),
    outlineVariant = Color(0xFFD2CEC3),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9B9DE),
    onPrimary = Color(0xFF1E2A45),
    primaryContainer = Color(0xFF34415F),
    onPrimaryContainer = Color(0xFFD8E0F3),
    secondary = Color(0xFFA9B9DE),
    onSecondary = Color(0xFF1E2A45),
    secondaryContainer = Color(0xFF3A3C43),
    onSecondaryContainer = Color(0xFFDCDDE2),
    background = Color(0xFF15161A),
    onBackground = Color(0xFFEDEEF0),
    surface = Color(0xFF15161A),
    onSurface = Color(0xFFEDEEF0),
    surfaceBright = Color(0xFF34363C),
    surfaceDim = Color(0xFF15161A),
    surfaceContainerLowest = Color(0xFF101115),
    surfaceContainerLow = Color(0xFF1F2126),
    surfaceContainer = Color(0xFF292B31),
    surfaceContainerHigh = Color(0xFF24262B),
    surfaceContainerHighest = Color(0xFF34363C),
    surfaceVariant = Color(0xFF292B31),
    onSurfaceVariant = Color(0xFFA8AAB0),
    outline = Color(0xFF55575D),
    outlineVariant = Color(0xFF34363C),
    error = Color(0xFFE5796C),
    onError = Color(0xFF3B0906)
)

/** The round button: a coloured rim around a lighter disc. */
internal class GlassColors(
    val rimTop: Color, val rimBottom: Color, val discTop: Color, val discBottom: Color, val icon: Color
)

@Composable
internal fun glassColors(active: Boolean): GlassColors = when {
    isSystemInDarkTheme() && active -> GlassColors(
        Color(0xFF8FA6D4), Color(0xFF2C3A5E), Color(0xFF3E4864), Color(0xFF232A3E), Color(0xFFD8E0F3)
    )
    isSystemInDarkTheme() -> GlassColors(
        Color(0xFF4A4C52), Color(0xFF2E3035), Color(0xFF33353B), Color(0xFF26282D), Color(0xFF75777D)
    )
    active -> GlassColors(
        Color(0xFFAFC4E8), Color(0xFF46598A), Color(0xFFEEF1FA), Color(0xFFC3CCE0), Color(0xFF3D4C70)
    )
    else -> GlassColors(
        Color(0xFFD6D3CB), Color(0xFFB3B0A8), Color(0xFFF1EFEA), Color(0xFFDEDBD3), Color(0xFFA5A29A)
    )
}

// Four sizes, two weights. Otherwise hierarchy comes from size and opacity.
private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
)

@Composable
fun SonosLiveTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

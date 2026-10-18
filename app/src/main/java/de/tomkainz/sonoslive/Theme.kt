package de.tomkainz.sonoslive

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fallback colors when the device does not support dynamic colors (before Android 12).
// Teal green like Wavelet's default accent.
private val Accent = Color(0xFF26C6A6)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFF5BE6C6),
    secondary = Accent,
    background = Color(0xFF0D0E0E),
    onBackground = Color(0xFFE8E9E8),
    surface = Color(0xFF0D0E0E),
    onSurface = Color(0xFFE8E9E8),
    surfaceContainer = Color(0xFF1B1D1D),
    surfaceContainerHigh = Color(0xFF212424),
    surfaceVariant = Color(0xFF2A2D2D),
    onSurfaceVariant = Color(0xFF9BA0A0),
    outline = Color(0xFF3A3E3E),
    outlineVariant = Color(0xFF26292A),
    error = Color(0xFFE5796C),
    onError = Color(0xFF3B0906)
)

private val Light = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F2E0),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF00897B),
    background = Color(0xFFFBFCFB),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFBFCFB),
    onSurface = Color(0xFF191C1C),
    surfaceContainer = Color(0xFFEEF1F0),
    surfaceContainerHigh = Color(0xFFE8ECEB),
    surfaceVariant = Color(0xFFDCE4E2),
    onSurfaceVariant = Color(0xFF5A6360),
    outline = Color(0xFFABB3B1),
    outlineVariant = Color(0xFFCBD3D1),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

// Four sizes, two weights. Otherwise hierarchy comes from size and opacity.
private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
)

@Composable
fun SonosLiveTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Material You: take colors from the wallpaper, like Wavelet.
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

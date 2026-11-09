package com.example.adsh.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class AccentColor(
    val id: String,
    val lightPrimary: Long,
    val lightOnPrimary: Long,
    val lightContainer: Long,
    val lightOnContainer: Long,
    val darkPrimary: Long,
    val darkOnPrimary: Long,
    val darkContainer: Long,
    val darkOnContainer: Long,
) {
    BLUE(
        "blue", 0xFF2E5CE6, 0xFFFFFFFF, 0xFFDDE3FF, 0xFF0A1B4D,
        0xFFB5C4FF, 0xFF002780, 0xFF123FAE, 0xFFDDE3FF,
    ),
    TEAL(
        "teal", 0xFF00695C, 0xFFFFFFFF, 0xFFB2DFDB, 0xFF00251F,
        0xFF4DB6AC, 0xFF00382F, 0xFF005045, 0xFFB2DFDB,
    ),
    PURPLE(
        "purple", 0xFF6A3DE8, 0xFFFFFFFF, 0xFFE8DDFF, 0xFF1F0060,
        0xFFCBBEFF, 0xFF2D0096, 0xFF4527A0, 0xFFE8DDFF,
    ),
    ORANGE(
        "orange", 0xFFB45309, 0xFFFFFFFF, 0xFFFFDCC2, 0xFF2E1500,
        0xFFFFB77C, 0xFF4A2800, 0xFF7A3E00, 0xFFFFDCC2,
    ),
    ROSE(
        "rose", 0xFFB3165B, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3E0021,
        0xFFFFB1C8, 0xFF5E1134, 0xFF8E1249, 0xFFFFD9E2,
    ),
    GREEN(
        "green", 0xFF2E7D32, 0xFFFFFFFF, 0xFFC8E6C9, 0xFF0B200C,
        0xFF81C784, 0xFF00390A, 0xFF1B5E20, 0xFFC8E6C9,
    );

    companion object {
        fun fromId(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: BLUE
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5CE6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0A1B4D),
    secondary = Color(0xFF00696E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB2ECEE),
    onSecondaryContainer = Color(0xFF002021),
    tertiary = Color(0xFF735C00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE16B),
    onTertiaryContainer = Color(0xFF221B00),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE7E8F0),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC6C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFB5C4FF),
    surfaceTint = Color(0xFF2E5CE6),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    onPrimary = Color(0xFF002780),
    primaryContainer = Color(0xFF123FAE),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFF82D3D6),
    onSecondary = Color(0xFF003739),
    secondaryContainer = Color(0xFF004F52),
    onSecondaryContainer = Color(0xFFB2ECEE),
    tertiary = Color(0xFFE6C447),
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFF574500),
    onTertiaryContainer = Color(0xFFFFE16B),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE3E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF2E5CE6),
    surfaceTint = Color(0xFFB5C4FF),
    scrim = Color(0xFF000000),
)

// Chinese text needs a little more leading than the Material defaults.
private val AdshTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

private val AdshShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AdshTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.BLUE,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = (if (dark) DarkColors else LightColors).copy(
        primary = Color(if (dark) accent.darkPrimary else accent.lightPrimary),
        onPrimary = Color(if (dark) accent.darkOnPrimary else accent.lightOnPrimary),
        primaryContainer = Color(if (dark) accent.darkContainer else accent.lightContainer),
        onPrimaryContainer = Color(if (dark) accent.darkOnContainer else accent.lightOnContainer),
        inversePrimary = Color(if (dark) accent.lightPrimary else accent.darkPrimary),
        surfaceTint = Color(if (dark) accent.darkPrimary else accent.lightPrimary),
        // Chip/navigation selected states are built from the secondary family, so keep them on
        // the chosen accent instead of the fixed teal base palette.
        secondary = Color(if (dark) accent.darkPrimary else accent.lightPrimary),
        onSecondary = Color(if (dark) accent.darkOnPrimary else accent.lightOnPrimary),
        secondaryContainer = Color(if (dark) accent.darkContainer else accent.lightContainer),
        onSecondaryContainer = Color(if (dark) accent.darkOnContainer else accent.lightOnContainer),
    )
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AdshTypography,
        shapes = AdshShapes,
        content = content,
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

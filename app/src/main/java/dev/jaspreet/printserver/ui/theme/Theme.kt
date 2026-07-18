package dev.jaspreet.printserver.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

// Color Palette from Design Inspiration
val DarkNavy = Color(0xFF23314E)
val SlateBlue = Color(0xFF536284)
val LightSlate = Color(0xFFD2D8E3)
val OffWhite = Color(0xFFF2F4F8)
val PureWhite = Color(0xFFFFFFFF)
val Charcoal = Color(0xFF1E1E1E)
val MediumGray = Color(0xFF63666A)
val AndroidGreen = Color(0xFF3DDC84)

private val LightColorScheme = lightColorScheme(
    primary = SlateBlue,
    onPrimary = PureWhite,
    secondary = LightSlate,
    onSecondary = Charcoal,
    background = OffWhite,
    onBackground = Charcoal,
    surface = PureWhite,
    onSurface = Charcoal,
    primaryContainer = DarkNavy,
    onPrimaryContainer = PureWhite,
    surfaceVariant = OffWhite,
    onSurfaceVariant = MediumGray
)

private val DarkColorScheme = darkColorScheme(
    primary = LightSlate,
    onPrimary = DarkNavy,
    secondary = SlateBlue,
    onSecondary = PureWhite,
    background = Color(0xFF0F172A), // Tailwind Slate 900
    onBackground = OffWhite,
    surface = Color(0xFF1E293B), // Tailwind Slate 800
    onSurface = PureWhite,
    primaryContainer = Color(0xFF0F172A),
    onPrimaryContainer = PureWhite,
    surfaceVariant = Color(0xFF334155), // Tailwind Slate 700
    onSurfaceVariant = LightSlate
)

val PrintServerTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

@Suppress("DEPRECATION")
@Composable
fun PrintServerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+ (SDK 31+)
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            window?.let {
                it.statusBarColor = Color.Transparent.toArgb()
                it.navigationBarColor = Color.Transparent.toArgb()
                
                val insetsController = WindowCompat.getInsetsController(it, view)
                // The top bar now matches the main background, so we adapt status bar icons dynamically
                insetsController.isAppearanceLightStatusBars = !darkTheme
                // The navigation bar area is also the background color, so we match the system theme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PrintServerTypography,
        content = content
    )
}


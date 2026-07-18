package dev.jaspreet.printserver.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    onPrimaryContainer = PureWhite
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
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, // Sourced from Roboto system defaults
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

@Composable
fun PrintServerTheme(
    content: @Composable () -> Unit
) {
    // Keep it light-themed to strictly match the requested corporate trust palette
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = PrintServerTypography,
        content = content
    )
}

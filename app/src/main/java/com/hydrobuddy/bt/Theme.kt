// App-wide colors, Quicksand typography, and safe-area screen padding.

package com.hydrobuddy.bt

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val ScreenHorizontalPadding = 20.dp
val ScreenTopPadding = 20.dp
val HeaderIconSize = 34.dp

val AppBackground = Color.White

object HydroBuddyColors {
    val gradientStart = Color(0xFF90CDF3)
    val gradientMiddle = Color(0xFF71AED5)
    val gradientEnd = Color(0xFF338AB9)
    val mainText = Color(0xFF1B4F72)
    val accentBlue = Color(0xFF338AB8)
    val gainGreen = Color(0xFF2E7D32)
}

/** Padding that respects notch, status bar, and navigation bar (edge-to-edge). */
fun Modifier.hydroBuddyScreenPadding(): Modifier = this
    .windowInsetsPadding(WindowInsets.safeDrawing)
    .padding(horizontal = ScreenHorizontalPadding, vertical = ScreenTopPadding)

private val QuicksandFamily = FontFamily(
    Font(R.font.quicksand_regular, FontWeight.Normal),
    Font(R.font.quicksand_semibold, FontWeight.SemiBold),
    Font(R.font.quicksand_bold, FontWeight.Bold)
)

private val base = Typography()

private val HydroBuddyTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = QuicksandFamily),
    displayMedium = base.displayMedium.copy(fontFamily = QuicksandFamily),
    displaySmall = base.displaySmall.copy(fontFamily = QuicksandFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = QuicksandFamily, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = QuicksandFamily, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(
        fontFamily = QuicksandFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp * 0.55f
    ),
    titleLarge = TextStyle(
        fontFamily = QuicksandFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp * 0.55f
    ),
    titleMedium = TextStyle(
        fontFamily = QuicksandFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp * 0.55f
    ),
    titleSmall = base.titleSmall.copy(fontFamily = QuicksandFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(
        fontFamily = QuicksandFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp * 0.55f
    ),
    bodyMedium = TextStyle(
        fontFamily = QuicksandFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp * 0.55f
    ),
    bodySmall = TextStyle(
        fontFamily = QuicksandFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp * 0.55f
    ),
    labelLarge = base.labelLarge.copy(fontFamily = QuicksandFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = QuicksandFamily, fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontFamily = QuicksandFamily, fontWeight = FontWeight.SemiBold)
)

@Composable
fun HydroBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = HydroBuddyTypography,
        content = content
    )
}

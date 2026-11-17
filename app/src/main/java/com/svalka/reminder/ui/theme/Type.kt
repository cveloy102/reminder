package com.svalka.reminder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.svalka.reminder.R

/** Inter for text; weight 900 is Inter Display, used for the big headings. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
    Font(R.font.inter_display_black, FontWeight.Black)
)

/** JetBrains Mono for the small terminal-style captions. */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_extrabold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_extrabold, FontWeight.ExtraBold),
    Font(R.font.jetbrains_mono_extrabold, FontWeight.Black)
)

private val base = Typography()

val SvalkaTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Inter),
    displayMedium = base.displayMedium.copy(fontFamily = Inter),
    displaySmall = base.displaySmall.copy(fontFamily = Inter),
    headlineLarge = base.headlineLarge.copy(fontFamily = Inter),
    headlineMedium = base.headlineMedium.copy(fontFamily = Inter),
    headlineSmall = base.headlineSmall.copy(fontFamily = Inter),
    titleLarge = base.titleLarge.copy(fontFamily = Inter),
    titleMedium = base.titleMedium.copy(fontFamily = Inter),
    titleSmall = base.titleSmall.copy(fontFamily = Inter),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp,
        lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = base.bodySmall.copy(fontFamily = Inter),
    labelLarge = base.labelLarge.copy(fontFamily = Inter),
    labelMedium = base.labelMedium.copy(fontFamily = Inter),
    labelSmall = base.labelSmall.copy(fontFamily = Inter)
)

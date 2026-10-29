package com.aura.music.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aura.music.R

/** Editorial serif used sparingly for display moments (titles of albums, now playing). */
val InstrumentSerif = FontFamily(Font(R.font.instrument_serif_regular, FontWeight.Normal))

private fun manrope(weight: FontWeight) = Font(
    R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Geometric sans for all functional text. */
val Manrope = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

private val trimmed = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

@Immutable
data class AuraTypography(
    val displayLarge: TextStyle,
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val overline: TextStyle,
    val numeric: TextStyle,
)

val AuraType = AuraTypography(
    displayLarge = TextStyle(fontFamily = InstrumentSerif, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.01).em),
    display = TextStyle(fontFamily = InstrumentSerif, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.005).em),
    headline = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, lineHeightStyle = trimmed),
    overline = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.14.em),
    numeric = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
)

internal val LocalAuraTypography = staticCompositionLocalOf { AuraType }

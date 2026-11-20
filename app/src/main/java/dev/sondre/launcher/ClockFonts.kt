package dev.sondre.launcher

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** A font for the clock's time. See FONTS.md for the bundled font and its license. */
data class ClockFont(val family: FontFamily, val weight: FontWeight)

@OptIn(ExperimentalTextApi::class) // Font(…, variationSettings) for the variable font
object ClockFonts {
    /** JetBrains Mono (variable font) at any weight from 100 (thin) to 800 (ExtraBold). */
    fun jetBrainsMono(weight: Int) = ClockFont(
        FontFamily(
            Font(
                R.font.jetbrains_mono,
                FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
            )
        ),
        FontWeight(weight),
    )
}

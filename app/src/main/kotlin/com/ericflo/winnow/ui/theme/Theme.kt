package com.ericflo.winnow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ericflo.winnow.classifier.message.Category

/** Material You colors from the wallpaper, like the system Messages app. */
@Composable
fun WinnowTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        typography = Typography(),
        content = content,
    )
}

/** Stable avatar color for an address, from a fixed set of soft hues. */
@Composable
fun avatarColors(seed: String): Pair<Color, Color> {
    val hues = floatArrayOf(4f, 28f, 48f, 96f, 150f, 188f, 214f, 262f, 292f, 330f)
    val hue = hues[Math.floorMod(seed.hashCode(), hues.size)]
    return if (isSystemInDarkTheme()) {
        Color.hsl(hue, 0.35f, 0.30f) to Color.hsl(hue, 0.70f, 0.88f)
    } else {
        Color.hsl(hue, 0.60f, 0.86f) to Color.hsl(hue, 0.55f, 0.25f)
    }
}

/** Container and content colors for a category badge. */
@Composable
fun categoryColors(category: Category?): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (category) {
        Category.SCAM, Category.PHISHING -> c.errorContainer to c.onErrorContainer
        Category.SPAM, Category.POLITICAL -> c.tertiaryContainer to c.onTertiaryContainer
        Category.MARKETING -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

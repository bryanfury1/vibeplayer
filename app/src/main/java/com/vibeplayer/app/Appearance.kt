package com.vibeplayer.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** The color themes you can pick when wallpaper colors are off. */
val colorThemeNames = listOf("Purple", "Blue", "Teal", "Green", "Orange", "Rose")

// Each theme is built around one "hue" (a position on the color wheel, 0 to 360).
private val colorThemeHues = listOf(270f, 215f, 175f, 130f, 28f, 340f)

/** A small sample color for a theme, shown next to its name in Settings. */
fun themePreviewColor(index: Int): Color =
    Color.hsl(colorThemeHues[index.coerceIn(0, colorThemeHues.lastIndex)], 0.65f, 0.60f)

/** Builds a full set of app colors from one hue. */
private fun paletteScheme(index: Int, dark: Boolean): ColorScheme =
    schemeForHue(colorThemeHues[index.coerceIn(0, colorThemeHues.lastIndex)], dark)

/** Builds a full set of app colors from one hue (a position on the color wheel, 0 to 360). */
fun schemeForHue(h: Float, dark: Boolean): ColorScheme {
    val t = (h + 60f) % 360f
    return if (dark) {
        darkColorScheme(
            primary = Color.hsl(h, 0.80f, 0.78f),
            onPrimary = Color.hsl(h, 0.70f, 0.14f),
            primaryContainer = Color.hsl(h, 0.55f, 0.28f),
            onPrimaryContainer = Color.hsl(h, 0.90f, 0.90f),
            secondary = Color.hsl(h, 0.30f, 0.72f),
            onSecondary = Color.hsl(h, 0.30f, 0.14f),
            secondaryContainer = Color.hsl(h, 0.22f, 0.28f),
            onSecondaryContainer = Color.hsl(h, 0.40f, 0.88f),
            tertiary = Color.hsl(t, 0.60f, 0.76f),
            onTertiary = Color.hsl(t, 0.60f, 0.14f),
            tertiaryContainer = Color.hsl(t, 0.40f, 0.28f),
            onTertiaryContainer = Color.hsl(t, 0.60f, 0.90f),
            background = Color.hsl(h, 0.12f, 0.08f),
            onBackground = Color.hsl(h, 0.10f, 0.90f),
            surface = Color.hsl(h, 0.12f, 0.08f),
            onSurface = Color.hsl(h, 0.10f, 0.90f),
            surfaceVariant = Color.hsl(h, 0.14f, 0.22f),
            onSurfaceVariant = Color.hsl(h, 0.12f, 0.76f),
            outline = Color.hsl(h, 0.08f, 0.56f),
            outlineVariant = Color.hsl(h, 0.12f, 0.28f),
            surfaceContainerLowest = Color.hsl(h, 0.12f, 0.05f),
            surfaceContainerLow = Color.hsl(h, 0.12f, 0.10f),
            surfaceContainer = Color.hsl(h, 0.12f, 0.12f),
            surfaceContainerHigh = Color.hsl(h, 0.12f, 0.16f),
            surfaceContainerHighest = Color.hsl(h, 0.12f, 0.20f),
        )
    } else {
        lightColorScheme(
            primary = Color.hsl(h, 0.60f, 0.40f),
            onPrimary = Color.White,
            primaryContainer = Color.hsl(h, 0.80f, 0.90f),
            onPrimaryContainer = Color.hsl(h, 0.80f, 0.12f),
            secondary = Color.hsl(h, 0.25f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = Color.hsl(h, 0.30f, 0.89f),
            onSecondaryContainer = Color.hsl(h, 0.40f, 0.12f),
            tertiary = Color.hsl(t, 0.45f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = Color.hsl(t, 0.60f, 0.90f),
            onTertiaryContainer = Color.hsl(t, 0.60f, 0.12f),
            background = Color.hsl(h, 0.25f, 0.98f),
            onBackground = Color.hsl(h, 0.10f, 0.10f),
            surface = Color.hsl(h, 0.25f, 0.98f),
            onSurface = Color.hsl(h, 0.10f, 0.10f),
            surfaceVariant = Color.hsl(h, 0.18f, 0.90f),
            onSurfaceVariant = Color.hsl(h, 0.12f, 0.30f),
            outline = Color.hsl(h, 0.08f, 0.46f),
            outlineVariant = Color.hsl(h, 0.14f, 0.80f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color.hsl(h, 0.25f, 0.96f),
            surfaceContainer = Color.hsl(h, 0.22f, 0.94f),
            surfaceContainerHigh = Color.hsl(h, 0.20f, 0.92f),
            surfaceContainerHighest = Color.hsl(h, 0.18f, 0.90f),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The app's look, following the Appearance settings. VibeTheme (in Ui.kt) calls this. */
@Composable
fun VibeThemeImpl(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (ExtraSettings.themeMode) {
        1 -> true
        2 -> false
        else -> systemDark
    }
    val dynamic = ExtraSettings.useDynamicColor && Build.VERSION.SDK_INT >= 31
    val base = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        else -> paletteScheme(ExtraSettings.colorTheme, dark)
    }
    val colors = if (dark && ExtraSettings.amoledBlack) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
        )
    } else {
        base
    }

    // Makes the clock and battery icons at the top readable on light or dark backgrounds.
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}

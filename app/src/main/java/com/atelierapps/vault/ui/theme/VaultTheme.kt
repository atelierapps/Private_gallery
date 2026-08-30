package com.atelierapps.vault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The vault's single source of visual truth.
 *
 * Every screen used to declare its own `Ink` / `Brass` / `Muted` privately, which
 * had already drifted (the viewer's muted grey was a different hex from every
 * other screen's). One palette, one type scale, one set of shapes — so the app
 * reads as one designed thing rather than a dozen screens that merely agree.
 */

// ---- surfaces: a deliberate ladder, dark but never flat black ----
val Bg = Color(0xFF0B0D0F)          // app background
val Surface = Color(0xFF14181B)     // raised: bars, sheets
val SurfaceHigh = Color(0xFF1C2125) // cards, tiles, dialogs
val Hairline = Color(0xFF262C31)    // 1px separators, borders
val Scrim = Color(0xCC06080A)       // over-media overlays
val ScrimSoft = Color(0x9906080A)   // lighter wash: badges sitting on a thumbnail

// ---- content ----
val Ink = Color(0xFFF2F5F7)         // primary text
val Muted = Color(0xFF8D979E)       // secondary text
val Faint = Color(0xFF5C666D)       // tertiary / disabled

// ---- accent: used sparingly, so it still means something ----
val Brass = Color(0xFFD8B36B)
val BrassInk = Color(0xFF17130A)    // content sitting on brass
val Danger = Color(0xFFE38073)

/** Spacing scale. Layouts pick from these rather than inventing numbers. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

private val VaultShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Type scale. Tight, few steps, and weighted rather than sized for emphasis —
 * a big range of arbitrary sizes is what makes an interface look assembled.
 */
private val VaultTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    // Section headers: small, semibold, tracked out.
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    ),
)

private val VaultColorScheme = darkColorScheme(
    primary = Brass,
    onPrimary = BrassInk,
    primaryContainer = Brass,
    onPrimaryContainer = BrassInk,
    secondary = Brass,
    onSecondary = BrassInk,
    background = Bg,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Muted,
    surfaceContainer = SurfaceHigh,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    error = Danger,
    onError = BrassInk,
    outline = Hairline,
    outlineVariant = Hairline,
    scrim = Scrim,
)

/**
 * Wraps every screen. Mapping the scheme matters as much as the palette: without
 * it, Material's stock components (switches, chips, dialog buttons, sliders) draw
 * in default M3 purple, which is the single loudest tell that a UI was assembled
 * from defaults rather than designed.
 */
@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VaultColorScheme,
        typography = VaultTypography,
        shapes = VaultShapes,
        content = content,
    )
}

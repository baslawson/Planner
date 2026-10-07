package com.example.itinerary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.example.itinerary.data.AppTheme
import com.example.itinerary.ui.LocalHeadingColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.itinerary.R
import com.example.itinerary.data.AppFont
import com.example.itinerary.data.TextSize

// Matrix green actions: vivid on dark surfaces, contrast-safe green on light surfaces.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B1B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EFDF),
    onPrimaryContainer = Color(0xFF006B1B),
    secondary = Color(0xFF006B1B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0EFDF),
    onSecondaryContainer = Color(0xFF006B1B),
    tertiary = Color(0xFF006B1B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0EFDF),
    onTertiaryContainer = Color(0xFF006B1B),
    background = Color(0xFFF4F6F5),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4F6F5),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDCE5E3),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBFC9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3F2),
    surfaceContainer = Color(0xFFEAEEED),
    surfaceContainerHigh = Color(0xFFE4E9E7),
    surfaceContainerHighest = Color(0xFFDEE3E1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF00FF41),
    onPrimary = Color(0xFF00390C),
    primaryContainer = Color(0xFF112819),
    onPrimaryContainer = Color(0xFF00FF41),
    secondary = Color(0xFF00FF41),
    onSecondary = Color(0xFF00390C),
    secondaryContainer = Color(0xFF112819),
    onSecondaryContainer = Color(0xFF00FF41),
    tertiary = Color(0xFF00FF41),
    onTertiary = Color(0xFF00390C),
    tertiaryContainer = Color(0xFF112819),
    onTertiaryContainer = Color(0xFF00FF41),
    background = Color(0xFF101514),
    onBackground = Color(0xFFE0E4E2),
    surface = Color(0xFF101514),
    onSurface = Color(0xFFE0E4E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBFC9C6),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF181D1C),
    surfaceContainer = Color(0xFF1C2120),
    surfaceContainerHigh = Color(0xFF262B2A),
    surfaceContainerHighest = Color(0xFF313635),
    // A neon red beside the neon green, not Material's pastel pink: Delete buttons and error text.
    error = Color(0xFFFF4B4B),
    onError = Color.Black,
)

// Neutral surfaces and strong accents keep text and control boundaries distinct.
internal fun plannerColorScheme(theme: AppTheme, dark: Boolean): androidx.compose.material3.ColorScheme {
    val base = if (dark) DarkColors else LightColors
    if (theme == AppTheme.MATRIX) return base
    if (theme == AppTheme.SYNTHWAVE) return synthwave(base, dark)
    if (theme == AppTheme.COLOUR_BLIND) {
        val background = if (dark) Color(0xFF101418) else Color(0xFFFAFAFA)
        val foreground = if (dark) Color(0xFFF3F5F7) else Color(0xFF182028)
        val blue = if (dark) Color(0xFF79C8FF) else Color(0xFF005A9C)
        val onBlue = if (dark) Color(0xFF061521) else Color.White
        val blueContainer = if (dark) Color(0xFF163247) else Color(0xFFE3F1FC)
        val amber = if (dark) Color(0xFFFFD166) else Color(0xFF805200)
        val amberContainer = if (dark) Color(0xFF382B0D) else Color(0xFFFFEBC2)
        val surface = if (dark) Color(0xFF20262C) else Color(0xFFEFF2F5)
        return base.copy(
            primary = blue, onPrimary = onBlue, primaryContainer = blueContainer, onPrimaryContainer = blue,
            secondary = blue, onSecondary = onBlue, secondaryContainer = blueContainer, onSecondaryContainer = blue,
            tertiary = amber, onTertiary = background, tertiaryContainer = amberContainer, onTertiaryContainer = amber,
            error = amber, onError = background, errorContainer = amberContainer, onErrorContainer = foreground,
            background = background, onBackground = foreground, surface = background, onSurface = foreground,
            surfaceVariant = surface, onSurfaceVariant = if (dark) Color(0xFFC5CDD5) else Color(0xFF424C56),
            outline = if (dark) Color(0xFFA4AFBA) else Color(0xFF586572),
            outlineVariant = if (dark) Color(0xFF8995A1) else Color(0xFF687582),
            surfaceContainerLowest = background, surfaceContainerLow = surface,
            surfaceContainer = surface, surfaceContainerHigh = surface, surfaceContainerHighest = surface,
            inverseSurface = foreground, inverseOnSurface = background,
            inversePrimary = if (dark) Color(0xFF005A9C) else Color(0xFF79C8FF), surfaceTint = Color.Transparent,
        )
    }
    val background = if (dark) Color.Black else Color.White
    val foreground = if (dark) Color.White else Color.Black
    val accent = if (dark) Color(0xFFFFE600) else Color(0xFF003399)
    val container = if (dark) Color(0xFF242100) else Color(0xFFE5ECFF)
    val surface = if (dark) Color(0xFF111111) else Color(0xFFF5F5F5)
    return base.copy(
        primary = accent, onPrimary = background,
        primaryContainer = container, onPrimaryContainer = accent,
        secondary = accent, onSecondary = background,
        secondaryContainer = container, onSecondaryContainer = accent,
        tertiary = accent, onTertiary = background,
        tertiaryContainer = container, onTertiaryContainer = accent,
        background = background, onBackground = foreground,
        surface = background, onSurface = foreground,
        surfaceVariant = surface, onSurfaceVariant = foreground,
        outline = foreground, outlineVariant = if (dark) Color(0xFFBDBDBD) else Color(0xFF444444),
        surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = surface, surfaceContainerHighest = surface,
        inverseSurface = foreground, inverseOnSurface = background, inversePrimary = if (dark) Color(0xFF003399) else Color(0xFFFFE600),
        surfaceTint = Color.Transparent,
        // Material's own error colours, as before the Matrix dark theme got its neon red.
        error = if (dark) Color(0xFFFFB4AB) else base.error, onError = if (dark) Color(0xFF690005) else base.onError,
    )
}

// Synthwave: neon pink controls and cyan headings on deep purple; in light mode magenta and teal on pale pink. Every text
// and control colour keeps at least 4.5:1 against the surfaces it sits on (ThemeContrastTest).
private fun synthwave(base: androidx.compose.material3.ColorScheme, dark: Boolean): androidx.compose.material3.ColorScheme = if (dark) base.copy(
    primary = Color(0xFFFF5CD6), onPrimary = Color(0xFF2B0021), primaryContainer = Color(0xFF3D1450), onPrimaryContainer = Color(0xFFFF8DE3),
    secondary = Color(0xFFFF5CD6), onSecondary = Color(0xFF2B0021), secondaryContainer = Color(0xFF3D1450), onSecondaryContainer = Color(0xFFFF8DE3),
    tertiary = Color(0xFF4DEBFF), onTertiary = Color(0xFF00262C), tertiaryContainer = Color(0xFF0E3440), onTertiaryContainer = Color(0xFF4DEBFF),
    background = Color(0xFF160B2E), onBackground = Color(0xFFF5ECFF), surface = Color(0xFF160B2E), onSurface = Color(0xFFF5ECFF),
    surfaceVariant = Color(0xFF2A1B4A), onSurfaceVariant = Color(0xFFCDBDEB), outline = Color(0xFFA893C9), outlineVariant = Color(0xFF5A4780),
    surfaceContainerLowest = Color(0xFF100821), surfaceContainerLow = Color(0xFF1D1238), surfaceContainer = Color(0xFF221640),
    surfaceContainerHigh = Color(0xFF2A1B4A), surfaceContainerHighest = Color(0xFF33235A),
    inverseSurface = Color(0xFFF5ECFF), inverseOnSurface = Color(0xFF160B2E), inversePrimary = Color(0xFFA6007C), surfaceTint = Color.Transparent,
    error = Color(0xFFFF6B8A), onError = Color(0xFF2B0010),
) else base.copy(
    primary = Color(0xFFA6007C), onPrimary = Color.White, primaryContainer = Color(0xFFFFE0F4), onPrimaryContainer = Color(0xFF8A0067),
    secondary = Color(0xFFA6007C), onSecondary = Color.White, secondaryContainer = Color(0xFFFFE0F4), onSecondaryContainer = Color(0xFF8A0067),
    tertiary = Color(0xFF00687A), onTertiary = Color.White, tertiaryContainer = Color(0xFFD4F6FC), onTertiaryContainer = Color(0xFF00586A),
    background = Color(0xFFFFF6FC), onBackground = Color(0xFF2A1240), surface = Color(0xFFFFF6FC), onSurface = Color(0xFF2A1240),
    surfaceVariant = Color(0xFFF3E3F5), onSurfaceVariant = Color(0xFF5B4470), outline = Color(0xFF7D6A8E), outlineVariant = Color(0xFFCDBBD6),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCEEF8), surfaceContainer = Color(0xFFF8E8F5),
    surfaceContainerHigh = Color(0xFFF3E1F0), surfaceContainerHighest = Color(0xFFEEDAEB),
    inverseSurface = Color(0xFF2A1240), inverseOnSurface = Color(0xFFFFF6FC), inversePrimary = Color(0xFFFF5CD6), surfaceTint = Color.Transparent,
)

internal fun plannerHeadingColor(theme: AppTheme, colors: androidx.compose.material3.ColorScheme, custom: Color): Color = when (theme) {
    AppTheme.MATRIX -> custom
    AppTheme.HIGH_CONTRAST -> colors.onBackground
    AppTheme.COLOUR_BLIND, AppTheme.SYNTHWAVE -> colors.tertiary
}

val LocalColourBlindFriendly = compositionLocalOf { false }
val LocalHighContrast = compositionLocalOf { false }
// The app's own text size (Settings, 80–125 %), on top of the system font size; see LimitTextScale.
val LocalTextSizePercent = compositionLocalOf { TextSize.DEFAULT_PERCENT }
@Composable
fun controlBorderWidth() = if (LocalHighContrast.current) 2.dp else 1.dp

// The typeface family for each choice in Settings. The files are in res/font (open-licence fonts; their licence texts
// are in assets/font-licenses). A font with only one file is drawn with a synthesised bold where bold is asked for.
fun fontFamilyFor(font: AppFont): FontFamily = when (font) {
    AppFont.SPACE_MONO -> FontFamily(
        Font(R.font.space_mono_regular, FontWeight.Normal),
        Font(R.font.space_mono_bold, FontWeight.Bold),
    )
    // A variable font, used at its normal weight; bold is synthesised.
    AppFont.TERMINAL -> FontFamily(Font(R.font.jetbrains_mono_regular))
    AppFont.SYSTEM_MONO -> FontFamily.Monospace
    AppFont.SYSTEM_SANS -> FontFamily.SansSerif
    AppFont.ATKINSON -> FontFamily(
        Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
        Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
    )
    // A variable font, used at its normal weight; bold is synthesised.
    AppFont.LEXEND -> FontFamily(Font(R.font.lexend_regular))
    AppFont.OPEN_DYSLEXIC -> FontFamily(
        Font(R.font.opendyslexic_regular, FontWeight.Normal),
        Font(R.font.opendyslexic_bold, FontWeight.Bold),
    )
}

// Fonts draw at different sizes for the same point size (OpenDyslexic is tall and wide), so each is scaled to sit about
// as big as the system monospace did and the screens keep fitting. Tuned by eye on the emulator.
fun fontSizeScaleFor(font: AppFont): Float = when (font) {
    AppFont.SPACE_MONO -> 0.95f
    AppFont.TERMINAL -> 0.95f
    AppFont.SYSTEM_MONO -> 1.0f
    AppFont.SYSTEM_SANS -> 1.0f
    AppFont.ATKINSON -> 1.0f
    AppFont.LEXEND -> 0.95f
    AppFont.OPEN_DYSLEXIC -> 0.85f
}

// Every text style in the app comes from here, so one choice of font and size changes all the text. [textSizePercent]
// is the user's text size setting (100 = normal).
private fun typographyFor(font: AppFont, textSizePercent: Int): Typography {
    val base = Typography()
    val family = fontFamilyFor(font)
    val scale = fontSizeScaleFor(font) * textSizePercent / 100f
    fun TextStyle.styled(weight: FontWeight? = null) = copy(
        fontFamily = family,
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
        fontWeight = weight ?: fontWeight,
    )
    return Typography(
        displayLarge = base.displayLarge.styled(),
        displayMedium = base.displayMedium.styled(),
        displaySmall = base.displaySmall.styled(),
        headlineLarge = base.headlineLarge.styled(),
        headlineMedium = base.headlineMedium.styled(),
        headlineSmall = base.headlineSmall.styled(),
        titleLarge = base.titleLarge.styled(FontWeight.SemiBold),
        titleMedium = base.titleMedium.styled(),
        titleSmall = base.titleSmall.styled(),
        bodyLarge = base.bodyLarge.styled(),
        bodyMedium = base.bodyMedium.styled(),
        bodySmall = base.bodySmall.styled(),
        labelLarge = base.labelLarge.styled(),
        labelMedium = base.labelMedium.styled(),
        labelSmall = base.labelSmall.styled(),
    )
}

@Composable
fun ItineraryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appTheme: AppTheme = AppTheme.MATRIX,
    font: AppFont = AppFont.DEFAULT,
    textSizePercent: Int = TextSize.DEFAULT_PERCENT,
    content: @Composable () -> Unit,
) {
    val typography = remember(font, textSizePercent) { typographyFor(font, textSizePercent) }
    val colors = remember(appTheme, darkTheme) { plannerColorScheme(appTheme, darkTheme) }
    val highContrast = appTheme == AppTheme.HIGH_CONTRAST
    val heading = plannerHeadingColor(appTheme, colors, LocalHeadingColor.current)
    CompositionLocalProvider(LocalHighContrast provides highContrast,
        LocalColourBlindFriendly provides (appTheme == AppTheme.COLOUR_BLIND),
        LocalHeadingColor provides heading, LocalTextSizePercent provides textSizePercent) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}

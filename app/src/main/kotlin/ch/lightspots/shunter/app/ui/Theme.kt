package ch.lightspots.shunter.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** A background and text color that belong together, for badges, chips and notes. */
@Immutable
data class Tone(val bg: Color, val fg: Color)

/** The colors of the design (Shunter Desktop canvas), light and dark. */
@Immutable
data class ShunterColors(
    val isDark: Boolean,
    val page: Color,
    val surface: Color,
    val border: Color,
    val divider: Color,
    val inputBorder: Color,
    val text: Color,
    val heading: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val placeholder: Color,
    val primary: Color,
    val onPrimary: Color,
    val link: Color,
    val selectedRow: Color,
    val track: Color,
    val toggleOn: Color,
    val onToggleOn: Color,
    val nav: Color,
    val navText: Color,
    val navMuted: Color,
    val navActive: Color,
    val navCard: Color,
    val navCardText: Color,
    val navBorder: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val dangerBorder: Color,
    val warnBorder: Color,
    val neutral: Tone,
    val info: Tone,
    val warn: Tone,
    val error: Tone,
    val local: Tone,
    val staging: Tone,
    val modIo: Tone,
    val tfnet: Tone,
    val modwerkstatt: Tone,
)

val LightColors = ShunterColors(
    isDark = false,
    page = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFDFE3E8),
    divider = Color(0xFFEEF0F3),
    inputBorder = Color(0xFFC9CFD8),
    text = Color(0xFF15181D),
    heading = Color(0xFF15181D),
    textSecondary = Color(0xFF3B414B),
    textMuted = Color(0xFF59606C),
    placeholder = Color(0xFF6B717C),
    primary = Color(0xFF0B6B66),
    onPrimary = Color(0xFFFFFFFF),
    link = Color(0xFF0B6B66),
    selectedRow = Color(0xFFEAF4F3),
    track = Color(0xFFE3E6EB),
    toggleOn = Color(0xFF15181D),
    onToggleOn = Color(0xFFFFFFFF),
    nav = Color(0xFF1B1F24),
    navText = Color(0xFFC9CED6),
    navMuted = Color(0xFFA3AAB5),
    navActive = Color(0xFF2C333C),
    navCard = Color(0xFF23282F),
    navCardText = Color(0xFFE4E7EC),
    navBorder = Color(0xFF1B1F24),
    accent = Color(0xFFF2A93B),
    onAccent = Color(0xFF1B1F24),
    danger = Color(0xFF8A1F1F),
    dangerBorder = Color(0xFFE8B4B4),
    warnBorder = Color(0xFFF1D49A),
    neutral = Tone(Color(0xFFEEF0F3), Color(0xFF3B414B)),
    info = Tone(Color(0xFFE5EEFB), Color(0xFF1F4E8C)),
    warn = Tone(Color(0xFFFFF5E1), Color(0xFF6E4500)),
    error = Tone(Color(0xFFFDECEC), Color(0xFF8A1F1F)),
    local = Tone(Color(0xFFE3F2F0), Color(0xFF0B5550)),
    staging = Tone(Color(0xFFEEE9FB), Color(0xFF4B3A8C)),
    modIo = Tone(Color(0xFFE5EEFB), Color(0xFF1F4E8C)),
    tfnet = Tone(Color(0xFFFBEFE3), Color(0xFF7A3E0C)),
    modwerkstatt = Tone(Color(0xFFE8F0E6), Color(0xFF2F5A26)),
)

val DarkColors = ShunterColors(
    isDark = true,
    page = Color(0xFF121417),
    surface = Color(0xFF1B1E23),
    border = Color(0xFF2E333B),
    divider = Color(0xFF252930),
    inputBorder = Color(0xFF3A4049),
    text = Color(0xFFE6E8EC),
    heading = Color(0xFFF2F4F7),
    textSecondary = Color(0xFFC2C7CF),
    textMuted = Color(0xFF959CA8),
    placeholder = Color(0xFF7E8592),
    primary = Color(0xFF4FC1B7),
    onPrimary = Color(0xFF08201E),
    link = Color(0xFF6FD0C7),
    selectedRow = Color(0xFF1E3332),
    track = Color(0xFF2E333B),
    toggleOn = Color(0xFFE6E8EC),
    onToggleOn = Color(0xFF121417),
    nav = Color(0xFF0C0E11),
    navText = Color(0xFFB5BBC4),
    navMuted = Color(0xFF959CA8),
    navActive = Color(0xFF22272E),
    navCard = Color(0xFF181B20),
    navCardText = Color(0xFFE4E7EC),
    navBorder = Color(0xFF22262C),
    accent = Color(0xFFF2A93B),
    onAccent = Color(0xFF1B1F24),
    danger = Color(0xFFF4A9A9),
    dangerBorder = Color(0xFF6B2E2E),
    warnBorder = Color(0xFF5A4520),
    neutral = Tone(Color(0xFF262A31), Color(0xFFC2C7CF)),
    info = Tone(Color(0xFF1C2F4D), Color(0xFFA9C8F5)),
    warn = Tone(Color(0xFF3A2C12), Color(0xFFF2C879)),
    error = Tone(Color(0xFF3D1B1B), Color(0xFFF4A9A9)),
    local = Tone(Color(0xFF173A37), Color(0xFF8FE0D7)),
    staging = Tone(Color(0xFF2E2650), Color(0xFFC9BCFF)),
    modIo = Tone(Color(0xFF1C2F4D), Color(0xFFA9C8F5)),
    tfnet = Tone(Color(0xFF3D2A17), Color(0xFFF5C79E)),
    modwerkstatt = Tone(Color(0xFF22351E), Color(0xFFB4DBA9)),
)

val LocalShunterColors = staticCompositionLocalOf { LightColors }

/** Shortcut for the current colors. */
val colors: ShunterColors
    @Composable get() = LocalShunterColors.current

/** Text sizes of the design; the font is the system's sans serif (Inter in the design). */
object Type {
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val title = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em)
    val section = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val item = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    val detail = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val columnHeader = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em)
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun ShunterTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            background = c.page,
            surface = c.surface,
            onSurface = c.text,
            onBackground = c.text,
            outline = c.inputBorder,
            surfaceContainer = c.surface,
        )
    } else {
        lightColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            background = c.page,
            surface = c.surface,
            onSurface = c.text,
            onBackground = c.text,
            outline = c.inputBorder,
            surfaceContainer = c.surface,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(bodyMedium = Type.body, bodyLarge = Type.body)) {
        CompositionLocalProvider(LocalShunterColors provides c, content = content)
    }
}

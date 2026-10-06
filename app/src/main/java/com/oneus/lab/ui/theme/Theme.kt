package com.oneus.lab.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.oneus.lab.sim.GnssSystem

// ══════════════════════════════════════════
// 语义色:只用于数据可视化,不参与 M3 角色
// ══════════════════════════════════════════

/** "量"层 —— 卫星,蓝 */
val Accent = Color(0xFF8EC9FF)
val AccentDim = Color(0xFF3A6B8F)

/** "猜"层 —— 融合,琥珀 */
val AccentWarm = Color(0xFFFFC46B)
val AccentWarmDim = Color(0xFF8A6A2E)

/** "算"层 —— 解算,绿 */
val AccentGreen = Color(0xFF7FD8A8)

val Danger = Color(0xFFFFB4AB)
val Warn = Color(0xFFFFC46B)
val Good = Color(0xFF7FD8A8)

/** 四个星座的固定配色,全 App 统一 */
val systemColor: (GnssSystem) -> Color = { s ->
    when (s) {
        GnssSystem.GPS -> Color(0xFF7FD8A8)
        GnssSystem.BDS -> Color(0xFFFFB4AB)
        GnssSystem.GAL -> Color(0xFF8EC9FF)
        GnssSystem.GLO -> Color(0xFFFFC46B)
    }
}

/** 滤波曲线配色 */
val filterColors = listOf(
    Color(0xFF8EC9FF),
    Color(0xFF7FD8A8),
    Color(0xFFFFC46B),
    Color(0xFFFFB4AB)
)

// ══════════════════════════════════════════
// Material 3 深色配色
// ══════════════════════════════════════════

private val OneUsDarkScheme = darkColorScheme(
    primary = Color(0xFF8EC9FF),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004B6E),
    onPrimaryContainer = Color(0xFFC8E6FF),
    inversePrimary = Color(0xFF00658F),

    secondary = Color(0xFFB5C9DC),
    onSecondary = Color(0xFF20323F),
    secondaryContainer = Color(0xFF374956),
    onSecondaryContainer = Color(0xFFD1E5F7),

    tertiary = Color(0xFF7FD8A8),
    onTertiary = Color(0xFF00391D),
    tertiaryContainer = Color(0xFF00522E),
    onTertiaryContainer = Color(0xFF9BF5C4),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0A0D13),
    onBackground = Color(0xFFE3EAF3),
    surface = Color(0xFF0A0D13),
    onSurface = Color(0xFFE3EAF3),
    surfaceVariant = Color(0xFF1E2734),
    onSurfaceVariant = Color(0xFFB3C2D0),
    surfaceTint = Color(0xFF8EC9FF),
    inverseSurface = Color(0xFFE3EAF3),
    inverseOnSurface = Color(0xFF161C24),

    outline = Color(0xFF6E7F92),
    outlineVariant = Color(0xFF2A3646),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF2A3441),
    surfaceDim = Color(0xFF0A0D13),
    surfaceContainerLowest = Color(0xFF05070A),
    surfaceContainerLow = Color(0xFF0E1319),
    surfaceContainer = Color(0xFF131A22),
    surfaceContainerHigh = Color(0xFF1D242E),
    surfaceContainerHighest = Color(0xFF283039)
)

/**
 * 排版。
 *
 * 中文正文行高要比默认再松一点 —— 汉字没有升降部,
 * 默认 1.3 倍行高在手机屏上会显得挤。
 */
private val OneUsTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

/** 等宽排版:专给数字与代号用,让数据对齐 */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 20.sp
)
val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 17.sp
)
val MonoBig = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 28.sp,
    lineHeight = 36.sp,
    fontWeight = FontWeight.Bold
)

@Composable
fun OneUsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else OneUsDarkScheme
        }
        else -> OneUsDarkScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = OneUsTypography,
        content = content
    )
}

package com.cscec.sitestamp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 现场强光下使用的深色配色：
 * 取景底色纯黑能压低屏幕反光，主色取中国建筑蓝，警示用琥珀色。
 */
object BrandColors {
    val Primary = Color(0xFF2F7BF6)
    val PrimaryDeep = Color(0xFF1B5BD0)
    val Accent = Color(0xFFFFC53D)
    val Surface = Color(0xFF101317)
    val SurfaceElevated = Color(0xFF1B2027)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFFA8B0BA)
    val Success = Color(0xFF34C759)
    val Danger = Color(0xFFFF453A)

    /** 与 WatermarkRenderer 保持同一品牌色。 */
    const val BRAND_ARGB: Int = 0xFF2F7BF6.toInt()
}

private val ColorScheme = darkColorScheme(
    primary = BrandColors.Primary,
    onPrimary = Color.White,
    primaryContainer = BrandColors.PrimaryDeep,
    onPrimaryContainer = Color.White,
    secondary = BrandColors.Accent,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = BrandColors.TextPrimary,
    surface = BrandColors.Surface,
    onSurface = BrandColors.TextPrimary,
    surfaceVariant = BrandColors.SurfaceElevated,
    onSurfaceVariant = BrandColors.TextSecondary,
    error = BrandColors.Danger,
    onError = Color.White
)

@Composable
fun SiteStampTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, content = content)
}

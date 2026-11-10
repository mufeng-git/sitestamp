package com.cscec.sitestamp.data

import android.graphics.Color
import org.json.JSONObject

/**
 * 水印样式配置（随模板保存在 templates.styleConfig 里，不需要数据库迁移）。
 *
 * 三个可调维度对应现场反馈：字段自定义(在模板字段表里)、背景色 + 透明度、logo 自定义。
 * 颜色一律存不含 alpha 的 RGB，透明度由 [bgAlpha] 单独控制，界面上才好做滑条。
 */
data class WatermarkStyle(
    val styleId: Int = STYLE_BLOCK,
    val bgColor: Int = DEFAULT_BG,
    val bgAlpha: Int = DEFAULT_ALPHA,
    val primaryColor: Int = DEFAULT_PRIMARY,
    val textColor: Int = DEFAULT_TEXT,
    val logoPath: String = "",
    val showTime: Boolean = true,
    val showWeather: Boolean = true,
    val showAddress: Boolean = true,
    val showCoords: Boolean = false
) {

    /** 卡片底色（含透明度），渲染端直接 Color.argb 用。 */
    fun bgArgb(): Int = Color.argb(bgAlpha.coerceIn(0, 255), red(bgColor), green(bgColor), blue(bgColor))

    fun toJson(): String = JSONObject().apply {
        put("styleId", styleId)
        put("bgColor", bgColor)
        put("bgAlpha", bgAlpha)
        put("primaryColor", primaryColor)
        put("textColor", textColor)
        put("logoPath", logoPath)
        put("showTime", showTime)
        put("showWeather", showWeather)
        put("showAddress", showAddress)
        put("showCoords", showCoords)
    }.toString()

    companion object {
        const val STYLE_BLOCK = 1    // 三段式（蓝头/底色/蓝落款 + logo 竖栏）
        const val STYLE_CARD = 2     // 深色圆角卡片
        const val STYLE_WHITE = 3    // 白卡极简
        const val STYLE_TIMEBAR = 4  // 通栏大时间条
        const val STYLE_COMPACT = 5  // 紧凑角标

        /** 三段式默认浅灰底（与现场通用工程水印一致，文字自动转黑）。 */
        private const val DEFAULT_BG = 0xC0C0C0

        /** 三段式与白卡是"实心工程水印"风格，默认不透明；其余样式默认半透明。 */
        private const val DEFAULT_ALPHA = 255

        /** 各样式的默认底色：切样式时底色跟着换，避免"浅色字样式配黑底"的错搭。 */
        fun defaultBgFor(styleId: Int): Int = when (styleId) {
            STYLE_BLOCK -> 0xC0C0C0
            STYLE_WHITE -> 0xFFFFFF
            STYLE_CARD -> 0x080B0F
            STYLE_TIMEBAR -> 0x0A0E14
            STYLE_COMPACT -> 0x080B0F
            else -> 0xC0C0C0
        }

        /** 切换样式时用它决定默认透明度，用户手动调过就不再覆盖。 */
        fun defaultAlphaFor(styleId: Int): Int = when (styleId) {
            STYLE_BLOCK -> 255
            STYLE_WHITE -> 250
            STYLE_CARD -> 186
            STYLE_TIMEBAR -> 205
            STYLE_COMPACT -> 195
            else -> 255
        }
        private const val DEFAULT_PRIMARY = 0x2E6DB5
        /** 0 = 跟随底色自动取黑白。 */
        private const val DEFAULT_TEXT = 0

        fun red(color: Int): Int = (color shr 16) and 0xFF
        fun green(color: Int): Int = (color shr 8) and 0xFF
        fun blue(color: Int): Int = color and 0xFF

        fun fromJson(raw: String?): WatermarkStyle {
            if (raw.isNullOrBlank() || raw == "{}") {
                // 首装/老库（styleConfig 为空）一律落到三段式实心样板
                return WatermarkStyle(
                    bgColor = defaultBgFor(STYLE_BLOCK),
                    bgAlpha = defaultAlphaFor(STYLE_BLOCK)
                )
            }
            return try {
                val o = JSONObject(raw)
                val sid = o.optInt("styleId", STYLE_BLOCK).coerceIn(1, 5)
                val bg = o.optInt("bgColor", defaultBgFor(sid))
                val alpha = o.optInt("bgAlpha", defaultAlphaFor(sid)).coerceIn(0, 255)
                // 老版本存的默认组合（三段式 + 近黑底 + 73% 透明）视为"没自定义过"，迁到新默认
                if (sid == STYLE_BLOCK && bg == 0x080B0F && alpha == 186) {
                    return WatermarkStyle(bgColor = defaultBgFor(STYLE_BLOCK), bgAlpha = defaultAlphaFor(STYLE_BLOCK))
                }
                WatermarkStyle(
                    styleId = sid,
                    bgColor = bg,
                    bgAlpha = alpha,
                    primaryColor = o.optInt("primaryColor", DEFAULT_PRIMARY),
                    textColor = o.optInt("textColor", DEFAULT_TEXT),
                    logoPath = o.optString("logoPath", ""),
                    showTime = o.optBoolean("showTime", true),
                    showWeather = o.optBoolean("showWeather", true),
                    showAddress = o.optBoolean("showAddress", true),
                    showCoords = o.optBoolean("showCoords", false)
                )
            } catch (_: Exception) {
                WatermarkStyle()
            }
        }

        /** 设置面板里的预设底色。 */
        val BG_PRESETS = listOf(
            0x080B0F to "深黑",
            0x1B2027 to "炭灰",
            0x2E6DB5 to "中建蓝",
            0x0F2A4A to "藏蓝",
            0xFFFFFF to "纯白",
            0xC0C0C0 to "浅灰"
        )

        val PRIMARY_PRESETS = listOf(
            0x2E6DB5 to "中建蓝",
            0x007DB4 to "青蓝",
            0x1F7A4D to "安全绿",
            0xB3352E to "警示红",
            0x333333 to "墨黑"
        )
    }
}

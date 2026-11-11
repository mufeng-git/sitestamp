package com.cscec.sitestamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.cscec.sitestamp.core.WatermarkRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * 在 JVM 上真实渲染 5 款水印样张（Robolectric native graphics + 系统中文），
 * 输出到 `app/build/reports/watermark/`，用于改样式时"先看图再交付"，不必等装机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN-w1080dp-h1440dp-xxhdpi")
class WatermarkRenderTest {

    private fun loadFont(): Typeface? {
        val candidates = listOf(
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simhei.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists()) return Typeface.createFromFile(f)
        }
        return null
    }

    /** 造一张像现场混凝土的底图：底色渐变 + 竖向水痕 + 噪点。 */
    private fun samplePhoto(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(196, 194, 190))

        val shade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(Color.rgb(214, 212, 208), Color.rgb(178, 176, 172)),
                null, Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)

        val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, 96, 98, 102)
            strokeWidth = 3f
        }
        var x = 0f
        while (x < w) {
            val len = (h * (0.35f + (x % 7) / 14f))
            c.drawLine(x, 0f, x, len, streak)
            x += 23f
        }
        val pit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 70, 72, 76) }
        var y = 40f
        var i = 0
        while (y < h) {
            c.drawCircle((i * 137 % w).toFloat(), y, 3.5f, pit)
            y += 57f
            i++
        }
        return bmp
    }

    private fun data(style: Int) = WatermarkRenderer.WatermarkData(
        projectName = "乌兹别克斯坦国家博物馆项目",
        company = "中建国际建设有限公司",
        fields = listOf("施工区域" to "4A", "检查事项" to "清水混凝土"),
        captureTime = "2026.11.11 10:21",
        gpsText = "GPS：41.305769, 69.242995 (±4m)",
        addressText = "地址：乌兹别克斯坦, Tashkent, Bobur ko'chasi 174",
        latitude = 41.305769,
        longitude = 69.242995,
        weatherText = "阴 5℃",
        style = style,
        logo = null,
        bgArgb = Color.argb(255, 244, 245, 247),
        primaryColor = Color.rgb(97, 144, 219),
        textColor = null,
        showCoords = false,
        showGps = false,
        showTime = true,
        showAddress = true
    )

    @Test
    fun renderAllStyles() {
        WatermarkRenderer.customTypeface = loadFont()
        val w = 1200
        val h = 1600
        val outDir = File("build/reports/watermark").apply { mkdirs() }
        val base = samplePhoto(w, h)

        val names = mapOf(
            1 to "block_三段式",
            2 to "card_深色卡",
            3 to "white_白卡",
            4 to "timebar_大时间条",
            5 to "compact_角标"
        )
        var produced = 0
        for (style in 1..5) {
            val overlay = WatermarkRenderer.renderOverlay(w, h, data(style))
            val composed = base.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(composed).drawBitmap(overlay, 0f, 0f, null)
            val file = File(outDir, "style_${style}_${names[style]}.png")
            FileOutputStream(file).use { composed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            overlay.recycle()
            composed.recycle()
            produced++
            println("rendered -> ${file.absolutePath} (${file.length()} bytes)")
        }
        base.recycle()
        assertTrue("应渲染出 5 张样张，实际 $produced", produced == 5)
    }
}

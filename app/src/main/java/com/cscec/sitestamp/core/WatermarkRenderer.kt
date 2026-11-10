package com.cscec.sitestamp.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 水印渲染核心。
 *
 * 样式为现场内业通用的"卡片式"信息块：左下角圆角卡片 + 左侧品牌色竖条，
 * 分三组（项目 / 要素 / 时空）用细线分隔，字段两栏对齐，标签全中文。
 * 尺寸全部按照片高度归一化，保证 1200 万到 4800 万像素输出比例一致。
 *
 * 铁律：必须在原始高分辨率图上绘制，严禁先缩到预览尺寸再放大。
 */
object WatermarkRenderer {

    data class WatermarkData(
        val projectName: String,
        val company: String,
        val fields: List<Pair<String, String>>, // 中文标签 -> 值
        val captureTime: String,
        val gpsText: String,
        val addressText: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val showGps: Boolean = true,
        val showAddress: Boolean = true,
        val showTime: Boolean = true,
        val brandColor: Int = DEFAULT_BRAND,
        val weatherText: String = "",
        /** 1 三段式 2 深色卡片 3 白卡极简 4 大时间条 5 紧凑角标 */
        val style: Int = STYLE_BLOCK,
        val logo: Bitmap? = null,
        /** 字段区底色（已含用户设定的透明度）。 */
        val bgArgb: Int = Color.argb(186, 8, 11, 15),
        /** 标题条 / 落款 / 强调块颜色。 */
        val primaryColor: Int = Color.rgb(46, 109, 181),
        /** 正文颜色；null = 按底色亮度自动取黑或白。 */
        val textColor: Int? = null,
        /** 是否在水印里显示经纬度。 */
        val showCoords: Boolean = false
    )

    private const val DEFAULT_BRAND = 0xFF2F7BF6.toInt()

    /** 三段式（蓝标题条 / 字段区 / 蓝落款 + logo 竖栏），默认样式。 */
        const val STYLE_BLOCK = 1

        /** 深色圆角卡片（半透明，遮挡最小）。 */
        const val STYLE_CARD = 2

        /** 白卡极简（浅底深字，适合汇报材料）。 */
        const val STYLE_WHITE = 3

        /** 通栏大时间条（远看醒目）。 */
        const val STYLE_TIMEBAR = 4

        /** 紧凑角标（信息最少）。 */
        const val STYLE_COMPACT = 5

    private const val KIND_TITLE = 0
    private const val KIND_SUB = 1
    private const val KIND_DIVIDER = 2
    private const val KIND_FIELD = 3
    private const val KIND_TIME = 4
    private const val KIND_META = 5

    private val SECONDARY = Color.argb(255, 196, 204, 214)

    private data class Row(
        val kind: Int,
        val label: String = "",
        val text: String = "",
        val size: Float = 0f,
        val color: Int = Color.WHITE
    )

    /**
     * 合成水印照片。
     * @param originalFile 原图文件
     * @param outputFile 输出文件（水印图）
     */
    fun render(originalFile: File, outputFile: File, data: WatermarkData) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(originalFile.path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 4096)
        }
        val decoded = BitmapFactory.decodeFile(originalFile.path, opts)
            ?: throw IllegalStateException("无法解码原图：${originalFile.path}")
        val bitmap = rotateByExif(decoded, originalFile)
        if (bitmap !== decoded) decoded.recycle()

        drawByStyle(Canvas(bitmap), bitmap.width, bitmap.height, data)

        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, fos)
        }
        bitmap.recycle()

        // 写入拍摄时间与 GPS（EXIF 度分秒有理数格式）
        writeExif(outputFile, data)
    }

    /**
     * 透明底水印层：拍摄界面实时叠加用。
     *
     * 与成品走同一个 [drawByStyle]，只要预览区与照片同比例（4:3 的 EXACT_FIT），
     * 位置、字号、行距、配色就完全一致——这是"界面所见 = 拍出来"的唯一可靠做法。
     */
    fun renderOverlay(width: Int, height: Int, data: WatermarkData): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawByStyle(Canvas(bmp), w, h, data)
        return bmp
    }

    private fun drawByStyle(canvas: Canvas, w: Int, h: Int, data: WatermarkData) {
        when (data.style) {
            STYLE_CARD -> drawCard(canvas, w, h, data)
            STYLE_WHITE -> drawWhite(canvas, w, h, data)
            STYLE_TIMEBAR -> drawTimeBar(canvas, w, h, data)
            STYLE_COMPACT -> drawCompact(canvas, w, h, data)
            else -> drawBlock(canvas, w, h, data)
        }
    }

    // ------------------------------------------------------------------ 三段式（默认样式）

    private val BLOCK_BLUE = Color.rgb(46, 109, 181)
    private val BLOCK_GRAY = Color.rgb(192, 192, 192)
    private val BLOCK_INK = Color.rgb(32, 32, 32)
    private val BLOCK_LOGO_BLUE = Color.rgb(0, 125, 180)
    private val BLOCK_LOGO_BG = Color.rgb(250, 250, 250)

    /** 底亮用深字、底暗用白字，避免用户把底色调成浅色后字看不见。 */
    private fun inkOn(bgArgb: Int, override: Int?): Int {
        if (override != null) return override
        val r = (bgArgb shr 16) and 0xFF
        val g = (bgArgb shr 8) and 0xFF
        val b = bgArgb and 0xFF
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (lum > 0.62) Color.rgb(24, 26, 30) else Color.WHITE
    }

    private fun secondaryOn(text: Int): Int = Color.argb(
        170,
        (text shr 16) and 0xFF,
        (text shr 8) and 0xFF,
        text and 0xFF
    )

    /** 两字标签用全角空格撑成四字宽，冒号位置才对得齐。 */
    private fun spreadLabel(label: String): String {
        val t = label.trim().trimEnd('：', ':')
        return if (t.length == 2) t[0] + "\u3000" + t[1] else t
    }

    /**
     * 中建三段式水印：白底 logo 竖栏 + 蓝标题条 + 灰字段区 + 蓝落款条。
     * 结构照现场通用的工程水印模板，直角、不透明，缩略图里也一眼能认出。
     */
    private fun drawBlock(canvas: Canvas, w: Int, h: Int, data: WatermarkData) {
        val bodyS = h * 0.0185f
        val titleS = h * 0.0235f
        val footS = h * 0.0195f

        val cardW = w * 0.70f
        val marginX = w * 0.030f
        val marginBottom = h * 0.032f
        val logoW = cardW * 0.20f
        val headerH = h * 0.050f
        val footH = h * 0.044f
        val rowH = h * 0.040f
        val padIn = h * 0.011f
        val contentX = marginX + logoW + padIn
        val gapLabel = h * 0.012f

        val boldFace = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val normalFace = Typeface.create("sans-serif", Typeface.NORMAL)
        val measure = Paint(Paint.ANTI_ALIAS_FLAG)

        // ---- 组装字段行：模板字段 -> 拍摄时间 -> 天气 -> 地点
        val rows = ArrayList<Pair<String, String>>()
        data.fields.filter { it.second.isNotBlank() }
            .forEach { rows.add(spreadLabel(it.first) to it.second.trim()) }
        if (data.showTime) {
            val t = cleanTime(data.captureTime)
            if (t.isNotEmpty()) rows.add("拍摄时间" to t)
        }
        if (data.weatherText.isNotBlank()) rows.add("天\u3000气" to data.weatherText.trim())
        if (data.showCoords) {
            val coords = cleanGps(data.gpsText)
            if (coords.isNotEmpty()) rows.add("坐\u3000标" to coords)
        }
        val addr = if (data.showAddress) cleanAddress(data.addressText) else ""
        if (addr.isNotEmpty()) rows.add("地\u3000点" to addr)
        if (rows.isEmpty()) return

        // 标签列宽（绘制用粗体，测量也必须用粗体）
        var labelW = 0f
        measure.textSize = bodyS
        measure.typeface = boldFace
        for ((label, _) in rows) labelW = max(labelW, measure.measureText(label))
        val valueMaxW = max(cardW - logoW - padIn * 2f - labelW - gapLabel, cardW * 0.25f)

        measure.typeface = normalFace
        val laid = ArrayList<Pair<String, Pair<String, List<String>>>>()
        var bodyContentH = 0f
        for ((label, value) in rows) {
            measure.textSize = bodyS
            val lines = wrap(value, valueMaxW, measure)
            laid.add(label to (value to lines))
            bodyContentH += rowH * lines.size
        }

        val bodyH = padIn * 2f + bodyContentH
        val cardH = headerH + bodyH + footH
        val cardRight = marginX + cardW
        val cardBottom = h - marginBottom
        val cardTop = cardBottom - cardH
        val bodyTop = cardTop + headerH
        val footTop = bodyTop + bodyH

        // ---- 四个色块（底色/主色都来自用户的样式配置）
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = data.primaryColor }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = data.bgArgb }
        canvas.drawRect(
            RectF(marginX, cardTop, marginX + logoW, cardTop + headerH),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLOCK_LOGO_BG }
        )
        canvas.drawRect(RectF(marginX + logoW, cardTop, cardRight, bodyTop), headPaint)
        canvas.drawRect(RectF(marginX, bodyTop, cardRight, footTop), bodyPaint)
        canvas.drawRect(RectF(marginX, footTop, cardRight, cardBottom), headPaint)

        val ink = inkOn(data.bgArgb, data.textColor)
        val headInk = inkOn(data.primaryColor, null)

        drawLogo(canvas, marginX, cardTop, logoW, headerH, data.logo)

        // ---- 标题
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.textSize = titleS
        text.typeface = boldFace
        text.color = headInk
        canvas.drawText(data.projectName.trim(), contentX, cardTop + headerH / 2f + titleS * 0.36f, text)

        // ---- 字段区
        var y = bodyTop + padIn
        for ((label, pair) in laid) {
            val lines = pair.second
            text.textSize = bodyS
            text.typeface = boldFace
            text.color = ink
            canvas.drawText(label, contentX, y + rowH * 0.52f + bodyS * 0.36f, text)

            text.typeface = normalFace
            var vy = y + rowH * 0.52f + bodyS * 0.36f
            for (line in lines) {
                canvas.drawText(line, contentX + labelW + gapLabel, vy, text)
                vy += rowH
            }
            y += rowH * lines.size
        }

        // ---- 落款
        val builder = data.company.trim()
        val footText = if (builder.isEmpty()) "施工单位：中建国际建设有限公司" else "施工单位：$builder"
        text.textSize = footS
        text.typeface = normalFace
        text.color = headInk
        canvas.drawText(footText, contentX, footTop + footH / 2f + footS * 0.36f, text)
    }

    /** 通用 logo 徽标：等比绘制；没有素材时画一个蓝块占位，保证版式不塌。 */
    private fun drawLogoBadge(canvas: Canvas, left: Float, top: Float, size: Float, logo: Bitmap?) {
        if (logo != null) {
            canvas.drawBitmap(
                logo, null,
                RectF(left, top, left + size, top + size),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        } else {
            canvas.drawRect(
                RectF(left, top, left + size, top + size),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLOCK_LOGO_BLUE }
            )
        }
    }

    /** logo 素材缺失时画占位（蓝块 + 建筑抽象线 + 中国建筑四字），不让竖栏空着。 */
    private fun drawLogo(canvas: Canvas, x: Float, y: Float, lw: Float, lh: Float, logo: Bitmap?) {
        val box = lw * 0.52f
        val bx = x + (lw - box) / 2f
        val by = y + lh * 0.10f
        if (logo != null) {
            val dst = RectF(bx, by, bx + box, by + box * 0.92f)
            canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            return
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLOCK_LOGO_BLUE }
        canvas.drawRect(RectF(bx, by, bx + box, by + box * 0.92f), fill)

        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val barW = box * 0.14f
        val fracs = floatArrayOf(0.22f, 0.44f, 0.66f)
        for ((i, frac) in fracs.withIndex()) {
            val hh = box * (0.30f + 0.10f * i)
            canvas.drawRect(
                RectF(
                    bx + box * frac, by + box * 0.72f - hh,
                    bx + box * frac + barW, by + box * 0.72f
                ),
                white
            )
        }
        canvas.drawRect(
            RectF(bx + box * 0.18f, by + box * 0.16f, bx + box * 0.82f, by + box * 0.24f),
            white
        )

        val fs = box * 0.34f
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLOCK_LOGO_BLUE
            textSize = fs
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val cx = x + lw / 2f
        canvas.drawText("中国", cx, by + box * 0.92f + fs * 1.35f, namePaint)
        canvas.drawText("建筑", cx, by + box * 0.92f + fs * 2.45f, namePaint)
    }

    // ------------------------------------------------------------------ 白卡 / 时间条 / 角标

    /** 天气 + 坐标 + 地址合成一行次要信息。 */
    private fun buildMeta(data: WatermarkData): String {
        val parts = ArrayList<String>()
        if (data.weatherText.isNotBlank()) parts.add(data.weatherText.trim())
        if (data.showCoords) {
            val c = cleanGps(data.gpsText)
            if (c.isNotEmpty()) parts.add(c)
        }
        if (data.showAddress) {
            val a = cleanAddress(data.addressText)
            if (a.isNotEmpty()) parts.add(a)
        }
        return parts.joinToString("   ")
    }

    /** C 白卡极简：浅底圆角卡 + 顶部主色细条 + 主色大字时间。 */
    private fun drawWhite(canvas: Canvas, w: Int, h: Int, data: WatermarkData) {
        val unit = h * 0.0195f
        val pad = h * 0.026f
        val cardW = w * 0.62f
        val mx = w * 0.030f
        val mb = h * 0.032f
        val radius = w * 0.016f
        val titleS = unit * 1.30f
        val labelS = unit * 0.80f
        val valueS = unit * 0.94f
        val timeS = unit * 1.20f
        val accentH = unit * 0.55f

        val ink = inkOn(data.bgArgb, data.textColor)
        val sub = secondaryOn(ink)
        val boldFace = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val normalFace = Typeface.create("sans-serif", Typeface.NORMAL)
        val measure = Paint(Paint.ANTI_ALIAS_FLAG)

        val rows = ArrayList<Pair<String, String>>()
        data.fields.filter { it.second.isNotBlank() }
            .forEach { rows.add(spreadLabel(it.first) to it.second.trim()) }

        var labelW = 0f
        measure.textSize = labelS
        measure.typeface = normalFace
        for ((label, _) in rows) labelW = max(labelW, measure.measureText(label))
        if (labelW > 0f) labelW += unit * 1.0f
        val valueMaxW = max(cardW - pad * 2f - labelW, cardW * 0.30f)

        val laid = ArrayList<Pair<String, List<String>>>()
        var contentH = 0f
        for ((label, value) in rows) {
            measure.textSize = valueS
            val lines = wrap(value, valueMaxW, measure)
            laid.add(label to lines)
            contentH += valueS * 1.55f * lines.size
        }

        val meta = buildMeta(data)
        var cardH = accentH + pad * 2f + titleS * 1.5f + timeS * 1.6f + contentH
        if (meta.isNotEmpty()) cardH += labelS * 1.6f

        val top = h - mb - cardH
        val right = mx + cardW
        canvas.save()
        val clip = Path().apply {
            addRoundRect(RectF(mx, top, right, top + cardH), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        canvas.drawRect(RectF(mx, top, right, top + cardH), Paint().apply { color = data.bgArgb })
        canvas.drawRect(RectF(mx, top, right, top + accentH), Paint().apply { color = data.primaryColor })
        canvas.restore()

        val badge = unit * 1.9f
        drawLogoBadge(canvas, right - pad - badge, top + accentH + pad * 0.5f, badge, data.logo)

        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = top + accentH + pad
        text.textSize = titleS
        text.typeface = boldFace
        text.color = ink
        canvas.drawText(data.projectName.trim(), mx + pad, y + titleS, text)
        y += titleS * 1.5f

        text.textSize = timeS
        text.color = data.primaryColor
        canvas.drawText(cleanTime(data.captureTime), mx + pad, y + timeS, text)
        y += timeS * 1.6f

        for ((label, lines) in laid) {
            text.textSize = labelS
            text.typeface = normalFace
            text.color = sub
            canvas.drawText(label, mx + pad, y + valueS * 0.78f, text)
            text.textSize = valueS
            text.typeface = boldFace
            text.color = ink
            var vy = y + valueS * 0.78f
            for (line in lines) {
                canvas.drawText(line, mx + pad + labelW, vy, text)
                vy += valueS * 1.55f
            }
            y += valueS * 1.55f * lines.size
        }
        if (meta.isNotEmpty()) {
            text.textSize = labelS
            text.typeface = normalFace
            text.color = sub
            canvas.drawText(meta, mx + pad, y + labelS, text)
        }
    }

    /** D 通栏大时间条：整条通栏，时间超大，右侧显示首个字段值（区域号）。 */
    private fun drawTimeBar(canvas: Canvas, w: Int, h: Int, data: WatermarkData) {
        val barH = h * 0.150f
        val top = h - h * 0.032f - barH
        canvas.drawRect(
            RectF(0f, top, w.toFloat(), top + barH),
            Paint().apply { color = data.bgArgb }
        )
        canvas.drawRect(
            RectF(0f, top, w * 0.012f, top + barH),
            Paint().apply { color = data.primaryColor }
        )

        val ink = inkOn(data.bgArgb, data.textColor)
        val sub = secondaryOn(ink)
        val timeS = h * 0.043f
        val subS = h * 0.017f
        var padX = w * 0.045f
        val boldFace = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val normalFace = Typeface.create("sans-serif", Typeface.NORMAL)

        val barBadge = barH * 0.40f
        if (data.logo != null) {
            drawLogoBadge(canvas, padX, top + (barH - barBadge) / 2f, barBadge, data.logo)
            padX += barBadge + w * 0.014f
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.textSize = timeS
        text.typeface = boldFace
        text.color = ink
        canvas.drawText(
            cleanTime(data.captureTime), padX,
            top + barH * 0.42f + timeS * 0.36f, text
        )

        val parts = ArrayList<String>()
        if (data.projectName.isNotBlank()) parts.add(data.projectName.trim())
        if (data.weatherText.isNotBlank()) parts.add(data.weatherText.trim())
        if (data.showAddress) {
            val a = cleanAddress(data.addressText)
            if (a.isNotEmpty()) parts.add(a)
        }
        text.textSize = subS
        text.typeface = normalFace
        text.color = sub
        canvas.drawText(
            parts.joinToString(" · "), padX,
            top + barH * 0.80f + subS * 0.36f, text
        )

        val badge = data.fields.firstOrNull { it.second.isNotBlank() }?.second?.trim()
        if (!badge.isNullOrEmpty()) {
            text.textAlign = Paint.Align.RIGHT
            text.textSize = h * 0.030f
            text.typeface = boldFace
            text.color = ink
            canvas.drawText(
                badge, w - w * 0.03f,
                top + barH * 0.42f + h * 0.030f * 0.36f, text
            )
            text.textAlign = Paint.Align.LEFT
        }
    }

    /** E 紧凑角标：三行小卡，几乎不挡画面。 */
    private fun drawCompact(canvas: Canvas, w: Int, h: Int, data: WatermarkData) {
        val unit = h * 0.0195f
        val pad = h * 0.020f
        val s1 = unit * 1.05f
        val s2 = unit * 0.82f
        val ink = inkOn(data.bgArgb, data.textColor)
        val sub = secondaryOn(ink)
        val boldFace = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val normalFace = Typeface.create("sans-serif", Typeface.NORMAL)
        val measure = Paint(Paint.ANTI_ALIAS_FLAG)

        val line1 = buildString {
            append(cleanTime(data.captureTime))
            if (data.weatherText.isNotBlank()) append("   ").append(data.weatherText.trim())
        }
        val fieldPart = data.fields.filter { it.second.isNotBlank() }
            .joinToString(" · ") { it.second.trim() }
        val line2 = listOf(data.projectName.trim(), fieldPart)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val line3 = data.company.trim()

        measure.textSize = s1
        val w1 = measure.measureText(line1)
        measure.textSize = s2
        val w2 = max(measure.measureText(line2), measure.measureText(line3))
        val logoB = s1 * 1.35f
        val logoGap = if (data.logo != null) logoB + pad * 0.8f else 0f
        val boxW = minOf(max(w1, w2) + pad * 2f + logoGap, w - w * 0.056f)
        val boxH = pad * 2f + s1 * 1.6f + s2 * 1.5f * 2f
        val mx = w * 0.028f
        val mb = h * 0.030f
        val top = h - mb - boxH
        val radius = w * 0.012f

        canvas.save()
        val clip = Path().apply {
            addRoundRect(RectF(mx, top, mx + boxW, top + boxH), radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)
        canvas.drawRect(RectF(mx, top, mx + boxW, top + boxH), Paint().apply { color = data.bgArgb })
        canvas.drawRect(
            RectF(mx, top + boxH * 0.12f, mx + w * 0.006f, top + boxH * 0.88f),
            Paint().apply { color = data.primaryColor }
        )
        canvas.restore()

        if (data.logo != null) {
            drawLogoBadge(
                canvas,
                mx + boxW - pad - logoB,
                top + (boxH - logoB) / 2f,
                logoB,
                data.logo
            )
        }

        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = top + pad
        text.textSize = s1
        text.typeface = boldFace
        text.color = ink
        canvas.drawText(line1, mx + pad, y + s1, text)
        y += s1 * 1.6f
        text.textSize = s2
        text.typeface = normalFace
        text.color = sub
        if (line2.isNotEmpty()) canvas.drawText(line2, mx + pad, y + s2, text)
        y += s2 * 1.5f
        if (line3.isNotEmpty()) canvas.drawText(line3, mx + pad, y + s2, text)
    }

    // ------------------------------------------------------------------ 卡片绘制

    private fun drawCard(canvas: Canvas, w: Int, h: Int, data: WatermarkData) {
        val unit = h * 0.0195f
        val titleSize = unit * 1.50f
        val subSize = unit * 0.82f
        val labelSize = unit * 0.84f
        val valueSize = unit * 0.96f
        val timeSize = unit * 1.04f
        val metaSize = unit * 0.84f

        val padH = w * 0.030f
        val padV = h * 0.0190f
        val barW = w * 0.0072f
        val radius = w * 0.018f
        val marginX = w * 0.026f
        val marginBottom = h * 0.028f
        val maxCardW = w * 0.90f
        val contentX = marginX + padH + barW
        val textMaxW = maxCardW - (contentX - marginX) - padH

        val boldFace = Typeface.create("sans-serif-medium", Typeface.BOLD)
        val mediumFace = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val normalFace = Typeface.create("sans-serif", Typeface.NORMAL)

        val measure = Paint(Paint.ANTI_ALIAS_FLAG)

        // ---- 组装内容行
        val rows = ArrayList<Row>()
        val project = data.projectName.trim()
        if (project.isNotEmpty()) rows.add(Row(KIND_TITLE, text = project, size = titleSize))
        val company = data.company.trim()
        if (company.isNotEmpty()) rows.add(Row(KIND_SUB, text = company, size = subSize, color = SECONDARY))

        val filledFields = data.fields.filter { it.second.isNotBlank() }
        if (filledFields.isNotEmpty()) {
            if (rows.isNotEmpty()) rows.add(Row(KIND_DIVIDER))
            filledFields.forEach { (label, value) ->
                rows.add(Row(KIND_FIELD, label = label.trim(), text = value.trim(), size = valueSize))
            }
        }

        val timeLine = if (data.showTime) cleanTime(data.captureTime) else ""
        val gpsLine = if (data.showGps) cleanGps(data.gpsText) else ""
        val addressLine = if (data.showAddress) cleanAddress(data.addressText) else ""
        if (timeLine.isNotEmpty() || gpsLine.isNotEmpty() || addressLine.isNotEmpty()) {
            if (rows.isNotEmpty()) rows.add(Row(KIND_DIVIDER))
            if (timeLine.isNotEmpty()) rows.add(Row(KIND_TIME, text = timeLine, size = timeSize))
            if (gpsLine.isNotEmpty()) rows.add(Row(KIND_META, text = gpsLine, size = metaSize, color = SECONDARY))
            if (addressLine.isNotEmpty()) rows.add(Row(KIND_META, text = addressLine, size = metaSize, color = SECONDARY))
        }
        if (rows.isEmpty()) return

        // ---- 字段标签列宽（两栏对齐的关键）
        var labelColW = 0f
        measure.textSize = labelSize
        measure.typeface = mediumFace
        for (row in rows) {
            if (row.kind == KIND_FIELD && row.label.isNotEmpty()) {
                labelColW = max(labelColW, measure.measureText(row.label))
            }
        }
        if (labelColW > 0f) labelColW += unit * 1.25f
        val valueMaxW = max(textMaxW - labelColW, textMaxW * 0.45f)

        // ---- 逐行换行并累计内容高度
        val drawn = ArrayList<Pair<Row, List<String>>>()
        var contentH = 0f
        for (row in rows) {
            when (row.kind) {
                KIND_DIVIDER -> {
                    drawn.add(row to emptyList())
                    contentH += unit * 1.10f
                }
                KIND_FIELD -> {
                    measure.textSize = row.size
                    measure.typeface = mediumFace
                    val lines = wrap(row.text, valueMaxW, measure)
                    drawn.add(row to lines)
                    contentH += row.size * 1.46f + (lines.size - 1) * row.size * 1.52f
                }
                else -> {
                    measure.textSize = row.size
                    measure.typeface =
                        if (row.kind == KIND_TITLE || row.kind == KIND_TIME) boldFace else normalFace
                    val lines = wrap(row.text, textMaxW, measure)
                    drawn.add(row to lines)
                    contentH += row.size * 1.52f + (lines.size - 1) * row.size * 1.52f
                }
            }
        }

        val cardH = contentH + padV * 2f
        val cardBottom = h - marginBottom
        val cardTop = cardBottom - cardH

        // ---- 卡片宽度取最长一行的实际宽度（不铺满全屏）
        var widest = 0f
        for ((row, lines) in drawn) {
            when (row.kind) {
                KIND_DIVIDER -> Unit
                KIND_FIELD -> {
                    measure.textSize = row.size
                    measure.typeface = mediumFace
                    val vw = lines.maxOfOrNull { measure.measureText(it) } ?: 0f
                    val lw = if (row.label.isEmpty()) 0f else labelColW
                    widest = max(widest, lw + vw)
                }
                else -> {
                    measure.textSize = row.size
                    measure.typeface =
                        if (row.kind == KIND_TITLE || row.kind == KIND_TIME) boldFace else normalFace
                    val lw = lines.maxOfOrNull { measure.measureText(it) } ?: 0f
                    widest = max(widest, lw)
                }
            }
        }
        val cardRight = minOf(
            marginX + widest + padH * 2f + barW,
            marginX + maxCardW
        ).coerceAtLeast(marginX + w * 0.34f)

        // ---- 圆角底板 + 品牌色竖条
        val cardRect = RectF(marginX, cardTop, cardRight, cardBottom)
        canvas.save()
        val clip = Path().apply {
            addRoundRect(cardRect, radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)

        val ink = inkOn(data.bgArgb, data.textColor)
        val faint = secondaryOn(ink)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = data.bgArgb
            style = Paint.Style.FILL
        }
        canvas.drawRect(cardRect, bgPaint)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = data.primaryColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(RectF(marginX, cardTop, marginX + barW, cardBottom), barPaint)
        canvas.restore()

        // ---- 文字
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 255, 255, 255)
            strokeWidth = max(1.5f, h * 0.0008f)
        }
        val cardBadge = unit * 1.9f
        drawLogoBadge(canvas, cardRight - padH - cardBadge, cardTop + padV, cardBadge, data.logo)

        var y = cardTop + padV

        for ((row, lines) in drawn) {
            when (row.kind) {
                KIND_DIVIDER -> {
                    val dy = y + unit * 0.55f
                    canvas.drawLine(contentX, dy, cardRight - padH, dy, divPaint)
                    y += unit * 1.10f
                }
                KIND_FIELD -> {
                    if (row.label.isNotEmpty()) {
                        textPaint.textSize = labelSize
                        textPaint.typeface = mediumFace
                        textPaint.color = faint
                        canvas.drawText(row.label, contentX, y + row.size, textPaint)
                    }
                    textPaint.textSize = row.size
                    textPaint.typeface = mediumFace
                    textPaint.color = ink
                    var vy = y + row.size
                    for (line in lines) {
                        canvas.drawText(line, contentX + labelColW, vy, textPaint)
                        vy += row.size * 1.52f
                    }
                    y += row.size * 1.46f + (lines.size - 1) * row.size * 1.52f
                }
                else -> {
                    val isBold = row.kind == KIND_TITLE || row.kind == KIND_TIME
                    textPaint.textSize = row.size
                    textPaint.typeface = if (isBold) boldFace else normalFace
                    textPaint.color = if (row.color == Color.WHITE) ink else faint
                    var ty = y + row.size
                    for (line in lines) {
                        canvas.drawText(line, contentX, ty, textPaint)
                        ty += row.size * 1.52f
                    }
                    y += row.size * 1.52f + (lines.size - 1) * row.size * 1.52f
                }
            }
        }
    }

    // ------------------------------------------------------------------ 文本工具

    /** 中文没有词边界，按字符贪心断行；英文与标点处优先断开。 */
    private fun wrap(text: String, maxWidth: Float, paint: Paint): List<String> {
        if (text.isEmpty()) return listOf("")
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            var last = start + 1
            while (end <= text.length && paint.measureText(text.substring(start, end)) <= maxWidth) {
                val ch = text[end - 1]
                if (ch == ' ' || ch == '，' || ch == ',' || ch == '·' || ch == '/') last = end
                end++
            }
            if (end > text.length && paint.measureText(text.substring(start)) <= maxWidth) {
                out.add(text.substring(start))
                break
            }
            if (last <= start) last = max(start + 1, end - 1)
            out.add(text.substring(start, last))
            start = last
            if (out.size > 12) break
        }
        if (out.isEmpty()) out.add(text)
        return out
    }

    internal fun cleanTime(raw: String): String =
        raw.replace("拍摄时间：", "").replace("拍摄时间:", "").trim()

    internal fun cleanGps(raw: String): String {
        val text = raw.replace("GPS：", "").replace("GPS:", "").trim()
        return if (text.isEmpty() || text == "定位未获取") "" else "坐标 $text"
    }

    internal fun cleanAddress(raw: String): String {
        val text = raw
            .replace("地址：", "")
            .replace("地址:", "")
            .replace("乌兹别克斯坦塔什干", "乌兹别克斯坦 塔什干 ")
            .trim()
        return if (text.isEmpty() || text == "不可用") "" else text
    }

    // ------------------------------------------------------------------ EXIF

    private fun writeExif(file: File, data: WatermarkData) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val dateTime = toExifDateTime(data.captureTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime)
            val lat = data.latitude
            val lng = data.longitude
            if (lat != null && lng != null) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDmsRational(lat))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDmsRational(lng))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lng >= 0) "E" else "W")
            }
            exif.setAttribute(ExifInterface.TAG_MAKE, "SiteStamp")
            exif.saveAttributes()
        } catch (_: Exception) {
            // EXIF 写入失败不影响照片本身已成功保存
        }
    }

    internal fun toExifDateTime(captureTime: String): String {
        val outFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val patterns = listOf("yyyy.MM.dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
        for (p in patterns) {
            try {
                val parsed = SimpleDateFormat(p, Locale.getDefault()).parse(captureTime)
                if (parsed != null) return outFormat.format(parsed)
            } catch (_: ParseException) {
                // 当前 pattern 不匹配，跳过继续尝试下一个
            }
        }
        return outFormat.format(Date())
    }

    /** 十进制度转 EXIF 度/分/秒，形如 "31/1,14/1,5000/1000"。 */
    internal fun toDmsRational(value: Double): String {
        val totalMillis = (abs(value) * 3_600_000).roundToLong()
        val degrees = totalMillis / 3_600_000
        val minutes = (totalMillis % 3_600_000) / 60_000
        val secondsMillis = totalMillis % 60_000
        return "$degrees/1,$minutes/1,$secondsMillis/1000"
    }

    internal fun calculateInSampleSize(w: Int, h: Int, max: Int): Int {
        var sample = 1
        while (w / sample > max || h / sample > max) sample *= 2
        return sample
    }

    private fun rotateByExif(bitmap: Bitmap, file: File): Bitmap {
        file.inputStream().use { input ->
            val exif = ExifInterface(input)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            }
            if (matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    /** 现场口径：2026.11.10 07:34（点分、到分钟，与工程水印习惯一致）。 */
    fun nowTime(): String =
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date())
}

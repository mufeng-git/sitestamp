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
        val brandColor: Int = DEFAULT_BRAND
    )

    private const val DEFAULT_BRAND = 0xFF2F7BF6.toInt()

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

        val canvas = Canvas(bitmap)
        drawCard(canvas, bitmap.width, bitmap.height, data)

        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, fos)
        }
        bitmap.recycle()

        // 写入拍摄时间与 GPS（EXIF 度分秒有理数格式）
        writeExif(outputFile, data)
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
        val padV = h * 0.0132f
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
        if (labelColW > 0f) labelColW += unit * 0.85f
        val valueMaxW = max(textMaxW - labelColW, textMaxW * 0.45f)

        // ---- 逐行换行并累计内容高度
        val drawn = ArrayList<Pair<Row, List<String>>>()
        var contentH = 0f
        for (row in rows) {
            when (row.kind) {
                KIND_DIVIDER -> {
                    drawn.add(row to emptyList())
                    contentH += unit * 0.95f
                }
                KIND_FIELD -> {
                    measure.textSize = row.size
                    measure.typeface = mediumFace
                    val lines = wrap(row.text, valueMaxW, measure)
                    drawn.add(row to lines)
                    contentH += row.size * 1.30f + (lines.size - 1) * row.size * 1.42f
                }
                else -> {
                    measure.textSize = row.size
                    measure.typeface =
                        if (row.kind == KIND_TITLE || row.kind == KIND_TIME) boldFace else normalFace
                    val lines = wrap(row.text, textMaxW, measure)
                    drawn.add(row to lines)
                    contentH += row.size * 1.34f + (lines.size - 1) * row.size * 1.42f
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

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(186, 8, 11, 15) // 73% 深色：现场强光下白字依旧可读
            style = Paint.Style.FILL
        }
        canvas.drawRect(cardRect, bgPaint)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = data.brandColor
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
        var y = cardTop + padV

        for ((row, lines) in drawn) {
            when (row.kind) {
                KIND_DIVIDER -> {
                    val dy = y + unit * 0.475f
                    canvas.drawLine(contentX, dy, cardRight - padH, dy, divPaint)
                    y += unit * 0.95f
                }
                KIND_FIELD -> {
                    if (row.label.isNotEmpty()) {
                        textPaint.textSize = labelSize
                        textPaint.typeface = mediumFace
                        textPaint.color = SECONDARY
                        canvas.drawText(row.label, contentX, y + labelSize, textPaint)
                    }
                    textPaint.textSize = row.size
                    textPaint.typeface = mediumFace
                    textPaint.color = Color.WHITE
                    var vy = y + row.size
                    for (line in lines) {
                        canvas.drawText(line, contentX + labelColW, vy, textPaint)
                        vy += row.size * 1.42f
                    }
                    y += row.size * 1.30f + (lines.size - 1) * row.size * 1.42f
                }
                else -> {
                    val isBold = row.kind == KIND_TITLE || row.kind == KIND_TIME
                    textPaint.textSize = row.size
                    textPaint.typeface = if (isBold) boldFace else normalFace
                    textPaint.color = row.color
                    var ty = y + row.size
                    for (line in lines) {
                        canvas.drawText(line, contentX, ty, textPaint)
                        ty += row.size * 1.42f
                    }
                    y += row.size * 1.34f + (lines.size - 1) * row.size * 1.42f
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

    private fun cleanTime(raw: String): String =
        raw.replace("拍摄时间：", "").replace("拍摄时间:", "").trim()

    private fun cleanGps(raw: String): String {
        val text = raw.replace("GPS：", "").replace("GPS:", "").trim()
        return if (text.isEmpty() || text == "定位未获取") "" else "坐标 $text"
    }

    private fun cleanAddress(raw: String): String {
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

    private fun toExifDateTime(captureTime: String): String {
        val outFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(captureTime)
            outFormat.format(parsed ?: Date())
        } catch (_: Exception) {
            outFormat.format(Date())
        }
    }

    /** 十进制度转 EXIF 度/分/秒，形如 "31/1,14/1,5000/1000"。 */
    private fun toDmsRational(value: Double): String {
        val totalMillis = (abs(value) * 3_600_000).roundToLong()
        val degrees = totalMillis / 3_600_000
        val minutes = (totalMillis % 3_600_000) / 60_000
        val secondsMillis = totalMillis % 60_000
        return "$degrees/1,$minutes/1,$secondsMillis/1000"
    }

    private fun calculateInSampleSize(w: Int, h: Int, max: Int): Int {
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

    fun nowTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}

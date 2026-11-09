package com.cscec.sitestamp.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 水印渲染核心：在原始高分辨率照片上按比例绘制底部半透明信息块。
 * 严禁先缩小到预览尺寸再绘制，保证最终照片文字清晰。
 */
object WatermarkRenderer {

    data class WatermarkData(
        val projectName: String,
        val company: String,
        val fields: List<Pair<String, String>>, // label -> value
        val captureTime: String,
        val gpsText: String,
        val addressText: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val showGps: Boolean = true,
        val showAddress: Boolean = true,
        val showTime: Boolean = true
    )

    /**
     * 合成水印照片。
     * @param originalFile 原图文件
     * @param outputFile 输出文件（水印图）
     */
    fun render(originalFile: File, outputFile: File, data: WatermarkData) {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(originalFile.path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 4096)
        }
        var bitmap = BitmapFactory.decodeFile(originalFile.path, opts)
            ?: throw IllegalStateException("无法解码原图：${originalFile.path}")
        bitmap = rotateByExif(bitmap, originalFile)

        val width = bitmap.width
        val height = bitmap.height
        val canvas = Canvas(bitmap)

        // 以照片高度为基准动态计算字号，保证不同分辨率比例一致
        val baseSize = height * 0.022f // 约 2.2% 照片高度
        val titleSize = baseSize * 1.35f
        val labelSize = baseSize * 0.95f
        val valueSize = baseSize * 0.95f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        // 计算内容行
        val lines = mutableListOf<Pair<Float, String>>() // size, text
        lines.add(titleSize to data.projectName)
        lines.add(titleSize to data.company)
        lines.add(labelSize to "")
        for ((label, value) in data.fields) {
            val line = if (value.isEmpty()) label else "$label：$value"
            if (line.isNotEmpty()) lines.add(valueSize to line)
        }
        if (data.showTime) lines.add(valueSize to "拍摄时间：${data.captureTime}")
        if (data.showGps) lines.add(valueSize to data.gpsText)
        if (data.showAddress) lines.add(valueSize to data.addressText)

        // 计算块高度与内边距
        val padX = width * 0.035f
        val padY = height * 0.012f
        var blockHeight = 0f
        for ((size, text) in lines) {
            paint.textSize = size
            blockHeight += size * 1.5f
        }
        blockHeight += padY * 2f

        val blockTop = height - blockHeight
        val blockRect = RectF(0f, blockTop, width.toFloat(), height.toFloat())

        // 绘制半透明背景
        val bgPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0) // 68% 黑
            style = Paint.Style.FILL
        }
        canvas.drawRect(blockRect, bgPaint)

        // 绘制文字
        var y = blockTop + padY + baseSize
        for ((size, text) in lines) {
            paint.textSize = size
            paint.color = if (size >= titleSize) Color.WHITE else Color.LTGRAY
            canvas.drawText(text, padX, y, paint)
            y += size * 1.5f
        }

        // 输出
        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
        }
        bitmap.recycle()

        // 写入拍摄时间与 GPS（EXIF 度分秒有理数格式）
        writeExif(outputFile, data)
    }

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
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
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

    fun nowTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }
}

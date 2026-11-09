package com.cscec.sitestamp.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
        val accuracy: Float? = null,
        val showGps: Boolean = true,
        val showAddress: Boolean = true,
        val showTime: Boolean = true
    )

    /**
     * 合成水印照片。
     * @return true 表示水印图已成功写出
     */
    fun render(originalFile: File, outputFile: File, data: WatermarkData): Boolean {
        if (!originalFile.exists() || originalFile.length() <= 0L) {
            Log.e(TAG, "render: original file missing or empty")
            return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(originalFile.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.e(TAG, "render: cannot read image bounds")
            return false
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = BitmapFactory.decodeFile(originalFile.path, opts)
        if (decoded == null) {
            Log.e(TAG, "render: decode returned null")
            return false
        }
        val bitmap = rotateByExif(decoded, originalFile)
        if (bitmap !== decoded) decoded.recycle()

        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return false
            val canvas = Canvas(bitmap)

            // 以照片高度为基准动态计算字号，保证不同分辨率比例一致
            val baseSize = height * 0.022f
            val titleSize = baseSize * 1.35f
            val labelSize = baseSize * 0.95f
            val valueSize = baseSize * 0.95f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }

            val lines = mutableListOf<Pair<Float, String>>()
            if (data.projectName.isNotBlank()) lines.add(titleSize to data.projectName)
            if (data.company.isNotBlank()) lines.add(titleSize to data.company)
            if (data.fields.any { it.second.isNotBlank() }) lines.add(labelSize to "")
            for ((label, value) in data.fields) {
                if (value.isBlank()) continue
                lines.add(valueSize to label + "：" + value)
            }
            if (data.showTime && data.captureTime.isNotBlank()) {
                lines.add(valueSize to "拍摄时间：" + data.captureTime)
            }
            if (data.showGps && data.gpsText.isNotBlank()) lines.add(valueSize to data.gpsText)
            if (data.showAddress && data.addressText.isNotBlank()) lines.add(valueSize to data.addressText)

            if (lines.isEmpty()) {
                Log.w(TAG, "render: nothing to draw")
                return false
            }

            val padX = width * 0.035f
            val padY = height * 0.012f
            var blockHeight = 0f
            for ((size, _) in lines) blockHeight += size * 1.5f
            blockHeight += padY * 2f

            val blockTop = height - blockHeight
            val blockRect = RectF(0f, blockTop, width.toFloat(), height.toFloat())

            val bgPaint = Paint().apply {
                color = Color.argb(180, 0, 0, 0)
                style = Paint.Style.FILL
            }
            canvas.drawRect(blockRect, bgPaint)

            var y = blockTop + padY + baseSize
            for ((size, text) in lines) {
                if (text.isNotEmpty()) {
                    paint.textSize = size
                    paint.color = if (size >= titleSize) Color.WHITE else Color.LTGRAY
                    canvas.drawText(text, padX, y, paint)
                }
                y += size * 1.5f
            }

            FileOutputStream(outputFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                fos.flush()
            }
        } finally {
            bitmap.recycle()
        }

        writeExif(outputFile, data)
        return outputFile.exists() && outputFile.length() > 0L
    }

    /** README 承诺写入 EXIF 时间与 GPS；旧实现完全没有调用，这里补齐。 */
    private fun writeExif(file: File, data: WatermarkData) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
            exif.setAttribute(ExifInterface.TAG_DATETIME, now)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "SiteStamp")
            val lat = data.latitude
            val lng = data.longitude
            if (lat != null && lng != null) {
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(lat))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(lng))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lng >= 0) "E" else "W")
            }
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "writeExif failed", e)
        }
    }

    /** 十进制度转 EXIF 要求的度/分/秒有理数字符串。 */
    private fun toDms(value: Double): String {
        val absValue = abs(value)
        val deg = absValue.toInt()
        val minFull = (absValue - deg) * 60
        val min = minFull.toInt()
        val sec = (minFull - min) * 60
        return String.format(Locale.US, "%d/1,%d/1,%d/1000", deg, min, (sec * 1000).toInt())
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
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
            }
            if (matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    fun nowTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private const val TAG = "SiteStamp"
    private const val MAX_DIMENSION = 4096
}

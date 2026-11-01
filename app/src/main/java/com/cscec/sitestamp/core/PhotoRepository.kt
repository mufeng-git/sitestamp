package com.cscec.sitestamp.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoRepository(private val context: Context) {

    data class SaveResult(
        val originalUri: String,
        val watermarkedUri: String,
        val originalFile: File,
        val watermarkedFile: File
    )

    fun createOriginalFile(): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "SiteStamp_original"
        ).apply { if (!exists()) mkdirs() }
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return File(dir, name)
    }

    fun saveWatermarked(originalFile: File, data: WatermarkRenderer.WatermarkData): SaveResult {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "SiteStamp_watermarked"
        ).apply { if (!exists()) mkdirs() }
        val watermarkedFile = File(dir, originalFile.name)

        // 水印合成失败时返回原图，不丢照片
        val success = try {
            WatermarkRenderer.render(originalFile, watermarkedFile, data)
            true
        } catch (_: Exception) {
            false
        }

        val outputFile = if (success) watermarkedFile else originalFile

        // 保存到系统相册
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, originalFile.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SiteStamp")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { resolver.openOutputStream(it)?.use { fos -> watermarkedFile.inputStream().copyTo(fos) } }

        return SaveResult(
            originalUri = originalFile.absolutePath,
            watermarkedUri = uri?.toString() ?: outputFile.absolutePath,
            originalFile = originalFile,
            watermarkedFile = outputFile
        )
    }
}

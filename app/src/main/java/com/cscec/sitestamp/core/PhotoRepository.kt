package com.cscec.sitestamp.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
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

        // 原图与水印图双存进系统相册；水印失败时只保留原图，避免重复
        val originalUri = saveToGallery(originalFile, originalFile.name)
        val watermarkedUri = if (success) {
            saveToGallery(watermarkedFile, watermarkDisplayName(originalFile.name))
        } else {
            originalUri
        }

        return SaveResult(
            originalUri = originalUri ?: originalFile.absolutePath,
            watermarkedUri = watermarkedUri ?: outputFile.absolutePath,
            originalFile = originalFile,
            watermarkedFile = outputFile
        )
    }

    private fun watermarkDisplayName(originalName: String): String {
        val dot = originalName.lastIndexOf('.')
        return if (dot > 0) {
            originalName.substring(0, dot) + "_wm" + originalName.substring(dot)
        } else {
            originalName + "_wm"
        }
    }

    /**
     * 写入系统相册：API 29+ 走 MediaStore，API 28- 写公共 Pictures/SiteStamp
     * 并通过 MediaScanner 通知图库。失败返回 null，不影响其余流程。
     */
    private fun saveToGallery(file: File, displayName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(file, displayName)
            } else {
                saveToPublicPictures(file, displayName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToMediaStore(file: File, displayName: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SiteStamp")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.delete(uri, null, null)
                return null
            }
            stream.use { fos -> file.inputStream().use { it.copyTo(fos) } }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri.toString()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicPictures(file: File, displayName: String): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "SiteStamp"
        ).apply { if (!exists()) mkdirs() }
        val dest = File(dir, displayName)
        file.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
        return dest.absolutePath
    }
}

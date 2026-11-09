package com.cscec.sitestamp.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoRepository(private val context: Context) {

    data class SaveResult(
        val originalUri: String,
        val watermarkedUri: String,
        val originalFile: File,
        val watermarkedFile: File,
        val watermarkRendered: Boolean
    )

    fun createOriginalFile(): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "SiteStamp_original"
        ).apply { if (!exists()) mkdirs() }
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        return File(dir, name)
    }

    fun saveWatermarked(originalFile: File, data: WatermarkRenderer.WatermarkData): SaveResult {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "SiteStamp_watermarked"
        ).apply { if (!exists()) mkdirs() }
        val watermarkedFile = File(dir, originalFile.name)

        // 水印合成失败时回退原图：绝不能因为渲染异常把照片丢了。
        // 旧实现在失败分支仍然去读 watermarkedFile，文件不存在直接抛异常，拍照必崩。
        val rendered = try {
            WatermarkRenderer.render(originalFile, watermarkedFile, data)
        } catch (t: Throwable) {
            Log.e(TAG, "watermark render threw", t)
            false
        }
        val outputFile = if (rendered && watermarkedFile.exists()) watermarkedFile else originalFile
        if (!rendered) Log.w(TAG, "watermark failed, saving original only")

        // README 承诺「原图 + 水印图双存」，两版都进相册
        val originalUri = publishToGallery(originalFile, "O_" + originalFile.name)
        val watermarkedUri = publishToGallery(outputFile, outputFile.name)

        return SaveResult(
            originalUri = originalUri ?: originalFile.absolutePath,
            watermarkedUri = watermarkedUri ?: outputFile.absolutePath,
            originalFile = originalFile,
            watermarkedFile = outputFile,
            watermarkRendered = rendered
        )
    }

    private fun publishToGallery(file: File, displayName: String): String? {
        if (!file.exists() || file.length() <= 0L) {
            Log.w(TAG, "publishToGallery: skip empty file " + file.absolutePath)
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertViaMediaStore(file, displayName)
        } else {
            copyToPublicPictures(file, displayName)
        }
    }

    private fun insertViaMediaStore(file: File, displayName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SiteStamp")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.e(TAG, "MediaStore insert returned null")
            return null
        }
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore write failed", e)
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            return null
        }
    }

    /** API 26-28 没有 RELATIVE_PATH，直接写入公共 Pictures 目录再让媒体库扫描。 */
    private fun copyToPublicPictures(file: File, displayName: String): String? {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "SiteStamp"
            )
            if (!dir.exists() && !dir.mkdirs()) return null
            val target = File(dir, displayName)
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            MediaScannerConnection.scanFile(
                context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null
            )
            target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "copyToPublicPictures failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "SiteStamp"
    }
}

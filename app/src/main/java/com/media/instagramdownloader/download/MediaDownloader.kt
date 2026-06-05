package com.media.instagramdownloader.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.media.instagramdownloader.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MediaDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val FOLDER = "InstaDownloader"
    }

    data class Result(val success: Int, val failed: Int)

    /** Downloads each item into the device gallery. Returns counts. */
    suspend fun downloadAll(
        items: List<MediaItem>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        var ok = 0
        var fail = 0
        items.forEachIndexed { index, item ->
            val done = runCatching { downloadOne(item) }.getOrDefault(false)
            if (done) ok++ else fail++
            onProgress(index + 1, items.size)
        }
        Result(ok, fail)
    }

    private fun downloadOne(item: MediaItem): Boolean {
        val url = item.downloadUrl
        if (url.isBlank()) return false
        val isVideo = item.isVideoDownload
        val ext = if (isVideo) "mp4" else "jpg"
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val name = "ig_${item.id}_${System.currentTimeMillis()}.$ext"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) Mobile")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val bytes = resp.body?.bytes() ?: return false
            return saveToGallery(name, mime, isVideo, bytes)
        }
    }

    private fun saveToGallery(
        name: String,
        mime: String,
        isVideo: Boolean,
        bytes: ByteArray
    ): Boolean {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val sub = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$sub/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri).use { os ->
            if (os == null) return false
            os.write(bytes)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return true
    }
}

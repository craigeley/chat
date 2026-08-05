package com.craigeley.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.craigeley.chat.api.BlueBubblesApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Inline-image loader for message attachments — deliberately dependency-free (no
 * Coil/Glide), matching the rest of the app: [BlueBubblesApi.downloadAttachment]
 * streams the bytes over the same single Tailscale socket, we cache the raw file
 * under [Context.getCacheDir] so reopening a thread doesn't refetch, and decode a
 * downsampled [android.graphics.Bitmap] so a full-res photo can't OOM the phone.
 * A small in-memory [LruCache] keyed by attachment guid keeps scrolling smooth.
 */
object Attachments {
    /** Cap the decoded bitmap's longest edge — the Light Phone's screen is small,
     *  and downsampling at decode time is what keeps memory in check. */
    private const val MAX_DIM = 1080

    /** Decoded images held in memory; bitmaps are already downsampled, so a modest
     *  count keeps the working set tiny while smoothing re-scroll. */
    private val memory = object : LruCache<String, ImageBitmap>(16) {}

    /**
     * The decoded image for [attachment], or null if it isn't an image, the
     * download fails, or the bytes don't decode. Runs entirely off the main thread.
     */
    suspend fun image(context: Context, api: BlueBubblesApi, attachment: Attachment): ImageBitmap? {
        if (!attachment.isImage) return null
        memory.get(attachment.guid)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "att_" + safeName(attachment.guid))
            if (!file.exists() || file.length() == 0L) {
                // maxDim asks the server to downscale before sending — we'd decode
                // down to MAX_DIM anyway, so the extra bytes were pure waste (LP3-19).
                runCatching { api.downloadAttachment(attachment.guid, file, maxDim = MAX_DIM) }.getOrElse {
                    file.delete() // don't leave a truncated file to be trusted next time
                    return@withContext null
                }
            }
            val image = decode(file)?.asImageBitmap() ?: return@withContext null
            memory.put(attachment.guid, image)
            image
        }
    }

    /**
     * Seeds the cache with a locally-picked image's bytes under [guid], so an
     * optimistic outgoing message can render it through the normal [image] path
     * (which then finds the file and skips the download). Keyed by the send's temp
     * guid; the real server attachment downloads separately once the echo lands.
     */
    fun cacheLocal(context: Context, guid: String, bytes: ByteArray) {
        runCatching { File(context.cacheDir, "att_" + safeName(guid)).writeBytes(bytes) }
    }

    /** Decode the file with `inSampleSize` chosen to bring it under [MAX_DIM],
     *  then apply the EXIF orientation — BitmapFactory ignores it, so a portrait
     *  phone photo (commonly tagged "rotate 90°") would otherwise render sideways. */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path, opts) }.getOrNull() ?: return null
        return applyOrientation(bitmap, file)
    }

    /** Rotate/flip [bitmap] per the file's EXIF orientation tag (a no-op for the
     *  common upright case, so we don't needlessly copy the bitmap). */
    private fun applyOrientation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap // ORIENTATION_NORMAL / undefined — already upright
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** Attachment guids can contain `/`, `:`, etc. — flatten to a safe filename. */
    private fun safeName(guid: String): String = guid.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}

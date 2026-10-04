package com.aurorafotos.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.OutputStream

/**
 * Saves session output under DCIM/AuroraFotos/<session>/ through MediaStore, so no
 * storage permission is needed and the files show up in Gallery immediately.
 */
class MediaSaver(context: Context, val sessionName: String) {
    private val resolver: ContentResolver = context.contentResolver
    val relativePath = "DCIM/AuroraFotos/$sessionName"

    fun saveImage(fileName: String, mime: String, writer: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed for $fileName")
        try {
            resolver.openOutputStream(uri)!!.use(writer)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    fun saveJpeg(fileName: String, bytes: ByteArray): Uri = saveImage(fileName, "image/jpeg") { it.write(bytes) }

    fun saveDng(fileName: String, writer: (OutputStream) -> Unit): Uri = saveImage(fileName, "image/x-adobe-dng", writer)

    fun saveText(fileName: String, text: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore insert failed for $fileName")
        resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        return uri
    }

    /** Opens a pending video entry; call [finishVideo] when the muxer is done. */
    fun openVideo(fileName: String): Pair<Uri, ParcelFileDescriptor> {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed for $fileName")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("openFileDescriptor failed")
        return uri to pfd
    }

    fun finishVideo(uri: Uri, ok: Boolean) {
        if (ok) resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        else resolver.delete(uri, null, null)
    }

    fun readBytes(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }
}

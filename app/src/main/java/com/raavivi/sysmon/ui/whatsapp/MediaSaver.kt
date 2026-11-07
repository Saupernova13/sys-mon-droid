package com.raavivi.sysmon.ui.whatsapp

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.raavivi.sysmon.core.model.WaMessage
import java.io.File
import java.io.InputStream

/**
 * Stores a downloaded image in the Pictures collection. API 29+ goes through
 * MediaStore (no storage permission needed); 26-28 write into the app's own
 * external Pictures dir, which also needs none.
 */
object MediaSaver {
    private const val ALBUM = "SysMon"

    /** The message's filename, else `whatsapp-<contact>-<ts>.<ext from mime>`. */
    fun fileName(msg: WaMessage, mime: String?): String {
        msg.filename?.takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        val who = sanitize(msg.chatName.ifBlank { msg.chat.substringBefore('@') }).ifBlank { "chat" }
        return "whatsapp-$who-${msg.ts.toLong()}.$ext"
    }

    private fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")

    /** Writes [data] and returns a short human description of where it went. */
    fun save(context: Context, name: String, mime: String?, data: InputStream): String {
        val type = mime ?: "image/jpeg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, type)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create the file")
            try {
                resolver.openOutputStream(uri)?.use { out -> data.use { it.copyTo(out) } }
                    ?: error("could not open the file")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return "Pictures/$ALBUM/$name"
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { out -> data.use { it.copyTo(out) } }
        return file.absolutePath
    }
}

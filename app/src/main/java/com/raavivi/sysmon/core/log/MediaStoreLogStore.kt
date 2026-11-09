package com.raavivi.sysmon.core.log

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.InputStream

/**
 * API 29+: files in `Download/sys-mon/logs/` through MediaStore.Downloads (no permission
 * needed for files this app created). Files left by a previous install are not ours and
 * are invisible here; a new file with the same name is then created by the system.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreLogStore(context: Context) : LogStore {
    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    private val uris = HashMap<String, Uri>()

    override fun list(): List<String> {
        val out = ArrayList<String>()
        uris.clear()
        query("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("${LogRules.PREFIX}%")) { name, uri, _ ->
            out += name
            uris[name] = uri
        }
        return out
    }

    override fun size(name: String): Long {
        var size = 0L
        query("${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name)) { n, uri, s ->
            uris[n] = uri
            size = s
        }
        return size
    }

    override fun append(name: String, data: ByteArray) {
        val uri = uris[name] ?: findUri(name) ?: create(name) ?: return
        resolver.openOutputStream(uri, "wa")?.use { it.write(data) }
    }

    override fun delete(name: String) {
        val uri = uris.remove(name) ?: findUri(name) ?: return
        resolver.delete(uri, null, null)
        uris.remove(name)
    }

    override fun open(name: String): InputStream? {
        val uri = uris[name] ?: findUri(name) ?: return null
        return resolver.openInputStream(uri)
    }

    private fun findUri(name: String): Uri? {
        var found: Uri? = null
        query("${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name)) { n, uri, _ ->
            found = uri
            uris[n] = uri
        }
        return found
    }

    private fun create(name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // Not text/plain: the system would append ".txt" to an unknown extension.
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        return resolver.insert(collection, values)?.also { uris[name] = it }
    }

    private fun query(selection: String, args: Array<String>, row: (String, Uri, Long) -> Unit) {
        val sel = "($selection) AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
        val allArgs = args + arrayOf(RELATIVE_PATH, RELATIVE_PATH.trimEnd('/'))
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        resolver.query(collection, projection, sel, allArgs, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                row(name, ContentUris.withAppendedId(collection, c.getLong(0)), c.getLong(2))
            }
        }
    }

    companion object {
        const val RELATIVE_PATH = "Download/sys-mon/logs/"
    }
}

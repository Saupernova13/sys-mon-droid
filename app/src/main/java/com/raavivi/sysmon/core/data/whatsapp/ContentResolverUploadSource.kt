package com.raavivi.sysmon.core.data.whatsapp

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [UploadSource] over Android content Uris, read through the [ContentResolver]. */
class ContentResolverUploadSource(private val resolver: ContentResolver) : UploadSource {

    override fun displayName(ref: String): String = queryOpenable(Uri.parse(ref)).first ?: DEFAULT_UPLOAD_NAME

    override suspend fun describe(ref: String): UploadFile = withContext(Dispatchers.IO) {
        val uri = Uri.parse(ref)
        val (name, size) = queryOpenable(uri)
        UploadFile(
            name = name ?: DEFAULT_UPLOAD_NAME,
            size = size,
            mimeType = resolver.getType(uri),
            open = { resolver.openInputStream(uri) },
        )
    }

    /** Display name and size in bytes of a content Uri; either may be unknown. */
    private fun queryOpenable(uri: Uri): Pair<String?, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
        }
        return name to size
    }

    private companion object {
        const val DEFAULT_UPLOAD_NAME = "upload.bin"
    }
}

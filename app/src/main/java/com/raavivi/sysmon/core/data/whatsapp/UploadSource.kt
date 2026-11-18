package com.raavivi.sysmon.core.data.whatsapp

import java.io.InputStream

/**
 * A picked file ready to upload. [size] is null when unknown; [open] yields a fresh stream per call so the
 * body is streamed and a large file never sits in memory (null when the file cannot be opened).
 */
class UploadFile(
    val name: String,
    val size: Long?,
    val mimeType: String?,
    val open: () -> InputStream?,
)

/** Resolves an opaque reference to a picked file (a content Uri string on Android) into an [UploadFile]. */
interface UploadSource {
    /** Display name for the confirm dialog; cheap enough to call from composition. */
    fun displayName(ref: String): String

    /** Name, size and type of the file behind [ref]; may block on I/O, so implementations switch dispatcher. */
    suspend fun describe(ref: String): UploadFile
}

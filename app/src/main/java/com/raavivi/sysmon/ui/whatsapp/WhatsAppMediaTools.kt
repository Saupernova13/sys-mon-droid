package com.raavivi.sysmon.ui.whatsapp

import android.content.Context
import coil.ImageLoader
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.model.WaMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The Android-bound media chores of the WhatsApp screen: warming image caches and saving to the gallery. */
interface WhatsAppMediaTools {
    /**
     * Warm Coil's caches for the media of a page that just arrived (newest 24), with the very requests
     * the bubbles build, so photos and stickers are there when they scroll in.
     */
    fun prefetch(page: List<WaMessage>)

    /** Download an image message into the public Pictures collection; returns where it went, throws on failure. */
    suspend fun saveImage(msg: WaMessage): String
}

class AndroidWhatsAppMediaTools(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val client: OkHttpClient,
    private val repository: WhatsAppRepository,
) : WhatsAppMediaTools {

    override fun prefetch(page: List<WaMessage>) {
        val density = context.resources.displayMetrics.density
        val now = System.currentTimeMillis() / 1000.0
        page.asReversed().asSequence()
            .filter { !it.revoked && !it.isReaction && shouldAutoLoadImage(it.downloaded, it.ts, now) }
            .mapNotNull { m ->
                when (m.mediaKind()) {
                    WaMediaKind.IMAGE -> {
                        val box = waMediaBox(m.width, m.height)
                        waImageRequest(context, mediaUrl(m, true), (box.w * density).toInt(), (box.h * density).toInt())
                    }
                    WaMediaKind.STICKER -> {
                        val px = (WA_STICKER_BOX * density).toInt()
                        waImageRequest(context, mediaUrl(m, false), px, px)
                    }
                    else -> null
                }
            }
            .take(24)
            .forEach { imageLoader.enqueue(it) }
    }

    override suspend fun saveImage(msg: WaMessage): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(mediaUrl(msg, false)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
            val body = resp.body ?: error("empty response")
            val name = MediaSaver.fileName(msg, mime)
            MediaSaver.save(context, name, mime, body.byteStream())
        }
    }

    private fun mediaUrl(m: WaMessage, thumb: Boolean) = repository.mediaUrl(m.chat, m.id, thumb)
}

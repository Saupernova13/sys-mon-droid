package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime

/** `services/whatsapp.py:status()` snapshot for the widget header. */
@Serializable
data class WaStatus(
    val enabled: Boolean = false,
    val binaryFound: Boolean = false,
    val binary: String = "",
    val authenticated: Boolean = false,
    val daemonRunning: Boolean = false,
    val lastHeartbeat: String? = null,
    val storeDir: String = "",
    val unreadTotal: Int = 0,
    val error: String? = null,
    val daemonPhase: String? = null,
    val daemonUptimeS: Double? = null,
)

// wacli returns loosely-shaped rows (PascalCase or snake_case), so the list
// endpoints are parsed as raw JSON objects and normalised in Kotlin — mirroring
// the web frontend's normChat/normMsg.
@Serializable
data class WaChatsResponse(val chats: List<JsonObject> = emptyList())

@Serializable
data class WaMessagesResponse(val messages: List<JsonObject> = emptyList())

/** One calendar day (server local time) that has messages; `firstTs` is its first message in unix seconds. */
@Serializable
data class WaTimelineDay(val day: String, val count: Int, val firstTs: Long)

@Serializable
data class WaTimelineResponse(val days: List<WaTimelineDay> = emptyList())

@Serializable
data class WaContactsResponse(val contacts: List<JsonObject> = emptyList())

@Serializable
data class WaSendText(val to: String, val message: String, val replyTo: String? = null)

@Serializable
data class WaPinReq(val chat: String, val pinned: Boolean = true)

@Serializable
data class WaLatestReq(val chat: String, val mediaLimit: Int = 50)

@Serializable
data class WaMarkRead(val chat: String)

@Serializable
data class WaAvatarsRefreshReq(val jids: List<String>, val limit: Int = 40)

/** `POST /api/whatsapp/avatars/refresh`; [updated] are the jids whose avatar is now cached. */
@Serializable
data class WaAvatarsRefreshResponse(
    val ok: Boolean = true,
    val updated: List<String> = emptyList(),
    val attempted: Int = 0,
    val limit: Int = 0,
)

/** Initials for an avatar placeholder: first letter of the first two words, uppercase; "#" if none. */
fun waInitials(name: String): String =
    name.trim().split(Regex("\\s+")).take(2)
        .mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "#" }

@Serializable
data class WaBackfill(val chat: String, val count: Int = 50, val requests: Int = 1)

/** React to a message; an empty [emoji] removes the reaction. [sender] is the target's original
 *  sender JID, required for group chats. */
@Serializable
data class WaReact(val chat: String, val id: String, val emoji: String, val sender: String? = null)

@Serializable
data class WaForward(val chat: String, val id: String, val to: String)

/** Delete a message: `everyone` revokes one of my sent messages, otherwise it is deleted for me. */
@Serializable
data class WaDelete(val chat: String, val id: String, val everyone: Boolean = false)

@Serializable
data class WaEdit(val chat: String, val id: String, val message: String)

/** Pin or unpin a chat in sys-mon's own list; wacli and WhatsApp never see these. */
@Serializable
data class WaLocalPinReq(val chat: String, val pinned: Boolean = true)

/** `POST /api/whatsapp/chat-name`: a blank [name] goes back to WhatsApp's. */
@Serializable
data class WaChatNameReq(val chat: String, val name: String = "")

/** The stored sys-mon name, or null once removed. */
@Serializable
data class WaChatNameResponse(val ok: Boolean = false, val chat: String = "", val name: String? = null)

// ── normalised view models ───────────────────────────────────────────────────

data class WaChat(
    val jid: String,
    val name: String,
    val lastTs: Double,
    val unread: Int,
    val pinned: Boolean,
    /** Last-message preview (sys-mon #293); all empty against an older server, and the row then shows none. */
    val preview: String = "",
    val previewType: String = "",
    val previewFromMe: Boolean = false,
    val previewSender: String = "",
    val previewRevoked: Boolean = false,
    /** A sys-mon-owned pin (wacli/WhatsApp cannot change it); [pinRank] orders them. */
    val localPin: Boolean = false,
    val pinRank: Int = 0,
    /** WhatsApp's own pin state as the server last reported it, so dropping the sys-mon pin can
     *  restore it (present on sys-mon-pinned rows only). */
    val waPinned: Boolean = false,
    /** Named in sys-mon (wacli/WhatsApp cannot change it): [name] is that name, [waName] WhatsApp's own
     *  ("" if it has none). */
    val renamed: Boolean = false,
    val waName: String = "",
)

data class WaMessage(
    val id: String,
    val chat: String,
    val chatName: String,
    val sender: String,
    val senderName: String,
    val fromMe: Boolean,
    val ts: Double,
    val text: String,
    val mediaType: String?,
    val filename: String?,
    val downloaded: Boolean,
    val mime: String = "",
    /** Pixel size of a downloaded image/sticker (0 until the server reports it). */
    val width: Int = 0,
    val height: Int = 0,
    val replyToId: String = "",
    val replyToSender: String = "",
    val replyToText: String = "",
    val forwarded: Boolean = false,
    val forwardScore: Int = 0,
    val edited: Boolean = false,
    val starred: Boolean = false,
    val revoked: Boolean = false,
    val deletedForMe: Boolean = false,
    /** A reaction is its own row: target message id + emoji (empty = removed). Never a list item. */
    val reactionToId: String = "",
    val reactionEmoji: String = "",
    /** Server-stamped deadlines (unix seconds) for Edit / Delete for everyone; 0 when absent. */
    val editUntil: Long = 0,
    val revokeUntil: Long = 0,
) {
    val isReaction: Boolean get() = reactionToId.isNotBlank()
}

data class WaContact(val jid: String, val name: String)

fun JsonObject.toWaChat(): WaChat {
    val jid = pickJid("jid", "JID", "chat_jid", "ChatJID", "Chat", "id")
    val pinned = pickBool("pinned", "Pinned")
    return WaChat(
        jid = jid,
        name = pickStr("name", "Name", "chat_name", "ChatName", "display_name") ?: jid,
        lastTs = pickTs("last_message_ts", "LastMessageTS", "last_ts", "Timestamp", "ts"),
        unread = pickInt("unread_count", "UnreadCount").takeIf { it > 0 }
            ?: if (pickBool("unread", "Unread")) 1 else 0,
        pinned = pinned,
        preview = pickStr("preview", "Preview") ?: "",
        previewType = pickStr("preview_type", "PreviewType") ?: "",
        previewFromMe = pickBool("preview_from_me", "PreviewFromMe"),
        previewSender = pickStr("preview_sender", "PreviewSender") ?: "",
        previewRevoked = pickStr("preview_type", "PreviewType") == "revoked" ||
            pickBool("preview_revoked", "PreviewRevoked"),
        localPin = pickBool("local_pin", "LocalPin"),
        pinRank = pickInt("pin_rank", "PinRank"),
        waPinned = pickBool("wa_pinned", "WaPinned") ?: pinned,
        renamed = containsKey("wa_name"),
        waName = pickStr("wa_name") ?: "",
    )
}

/**
 * Split a `messages list` row's text. Replies embed the quoted text as "> " lines inside DisplayText
 * while Text holds the clean reply body. Returns (body, quoted); mirrors the web normMsg.
 */
fun splitQuotedDisplay(rawText: String, display: String, caption: String = "", knownQuote: String = ""): Pair<String, String> {
    var body = rawText
    var quoted = knownQuote
    if (quoted.isEmpty() && display.isNotEmpty() && display != rawText && display.contains('>')) {
        val q = ArrayList<String>()
        val b = ArrayList<String>()
        for (ln in display.split('\n')) {
            when {
                ln.startsWith("> ") -> q.add(ln.substring(2))
                ln.startsWith(">") -> q.add(ln.substring(1))
                else -> b.add(ln)
            }
        }
        if (q.isNotEmpty()) {
            quoted = q.joinToString("\n")
            if (body.isEmpty()) body = b.joinToString("\n").trim()
        }
    }
    if (body.isEmpty()) body = display.ifEmpty { caption }
    return body to quoted
}

fun JsonObject.toWaMessage(): WaMessage {
    val (body, quoted) = splitQuotedDisplay(
        rawText = pickStr("Text", "text") ?: "",
        display = pickStr("DisplayText", "display_text") ?: "",
        caption = pickStr("MediaCaption", "caption", "Body", "body") ?: "",
        knownQuote = pickStr("ReplyToDisplay", "reply_to_display") ?: "",
    )
    return WaMessage(
        id = pickStr("MsgID", "msg_id", "ID", "id", "message_id") ?: "",
        chat = pickJid("ChatJID", "chat_jid", "Chat", "chat", "jid"),
        chatName = pickStr("ChatName", "chat_name") ?: "",
        sender = pickJid("SenderJID", "sender_jid", "sender"),
        senderName = pickStr("SenderName", "sender_name", "PushName", "pushName", "notify") ?: "",
        fromMe = pickBool("FromMe", "from_me", "fromMe"),
        ts = pickTs("Timestamp", "ts", "timestamp"),
        text = body,
        mediaType = pickStr("media_type", "MediaType"),
        filename = pickStr("Filename", "filename"),
        downloaded = pickBool("media_ready") || pickStr("LocalPath", "local_path") != null,
        mime = pickStr("mime", "MimeType", "mime_type") ?: "",
        width = pickInt("width", "Width", "media_width"),
        height = pickInt("height", "Height", "media_height"),
        replyToId = pickStr("ReplyToID", "reply_to_id", "quoted_msg_id") ?: "",
        replyToSender = pickJid("ReplyToSenderJID", "reply_to_sender_jid", "quoted_sender_jid"),
        replyToText = quoted,
        forwarded = pickBool("IsForwarded", "is_forwarded", "forwarded"),
        forwardScore = pickInt("ForwardingScore", "forwarding_score"),
        edited = pickBool("Edited", "edited"),
        starred = pickBool("Starred", "starred"),
        revoked = pickBool("Revoked", "revoked"),
        deletedForMe = pickBool("DeletedForMe", "deleted_for_me"),
        reactionToId = pickStr("ReactionToID", "reaction_to_id") ?: "",
        reactionEmoji = pickStr("ReactionEmoji", "reaction_emoji") ?: "",
        editUntil = pickLong("edit_until"),
        revokeUntil = pickLong("revoke_until"),
    )
}

fun JsonObject.toWaContact(): WaContact {
    val jid = pickJid("jid", "JID", "id")
    return WaContact(jid = jid, name = pickStr("name", "Name", "PushName", "pushName") ?: jid)
}

// ── loose-JSON helpers ───────────────────────────────────────────────────────

private fun JsonElement.asContent(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun jidOf(e: JsonElement?): String {
    return when (e) {
        is JsonPrimitive -> e.contentOrNull ?: ""
        is JsonObject -> {
            val user = e["User"]?.asContent()
            val server = e["Server"]?.asContent()
            if (!user.isNullOrBlank() && !server.isNullOrBlank()) "$user@$server"
            else e["JID"]?.asContent() ?: e["jid"]?.asContent() ?: ""
        }
        else -> ""
    }
}

private fun JsonObject.pickStr(vararg keys: String): String? {
    for (k in keys) {
        val s = this[k]?.asContent()
        if (!s.isNullOrBlank()) return s
    }
    return null
}

private fun JsonObject.pickJid(vararg keys: String): String {
    for (k in keys) {
        val j = jidOf(this[k])
        if (j.isNotBlank()) return j
    }
    return ""
}

private fun JsonObject.pickBool(vararg keys: String): Boolean {
    for (k in keys) {
        val p = this[k] as? JsonPrimitive ?: continue
        p.booleanOrNull?.let { return it }
        val c = p.contentOrNull
        if (c == "1" || c.equals("true", ignoreCase = true)) return true
        if (c == "0" || c.equals("false", ignoreCase = true)) return false
    }
    return false
}

private fun JsonObject.pickInt(vararg keys: String): Int {
    for (k in keys) {
        val c = (this[k] as? JsonPrimitive)?.contentOrNull ?: continue
        (c.toIntOrNull() ?: c.toDoubleOrNull()?.toInt())?.let { return it }
    }
    return 0
}

private fun JsonObject.pickLong(vararg keys: String): Long {
    for (k in keys) {
        val c = (this[k] as? JsonPrimitive)?.contentOrNull ?: continue
        (c.toLongOrNull() ?: c.toDoubleOrNull()?.toLong())?.let { return it }
    }
    return 0L
}

/** Timestamps arrive as RFC3339 strings or epoch numbers; normalise to seconds. */
private fun JsonObject.pickTs(vararg keys: String): Double {
    for (k in keys) {
        val p = this[k] as? JsonPrimitive ?: continue
        p.doubleOrNull?.let { return if (it > 1e12) it / 1000.0 else it }
        val c = p.contentOrNull ?: continue
        c.toDoubleOrNull()?.let { return if (it > 1e12) it / 1000.0 else it }
        runCatching { OffsetDateTime.parse(c).toEpochSecond().toDouble() }.getOrNull()?.let { return it }
        runCatching { Instant.parse(c).epochSecond.toDouble() }.getOrNull()?.let { return it }
    }
    return 0.0
}

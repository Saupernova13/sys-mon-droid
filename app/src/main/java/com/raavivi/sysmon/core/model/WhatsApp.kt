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
)

// wacli returns loosely-shaped rows (PascalCase or snake_case), so the list
// endpoints are parsed as raw JSON objects and normalised in Kotlin — mirroring
// the web frontend's normChat/normMsg.
@Serializable
data class WaChatsResponse(val chats: List<JsonObject> = emptyList())

@Serializable
data class WaMessagesResponse(val messages: List<JsonObject> = emptyList())

@Serializable
data class WaContactsResponse(val contacts: List<JsonObject> = emptyList())

@Serializable
data class WaSendText(val to: String, val message: String, val replyTo: String? = null)

@Serializable
data class WaPinReq(val chat: String, val pinned: Boolean = true)

@Serializable
data class WaBackfill(val chat: String, val count: Int = 50, val requests: Int = 1)

// ── normalised view models ───────────────────────────────────────────────────

data class WaChat(
    val jid: String,
    val name: String,
    val lastTs: Double,
    val unread: Int,
    val pinned: Boolean,
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
)

data class WaContact(val jid: String, val name: String)

fun JsonObject.toWaChat(): WaChat {
    val jid = pickJid("jid", "JID", "chat_jid", "ChatJID", "Chat", "id")
    return WaChat(
        jid = jid,
        name = pickStr("name", "Name", "chat_name", "ChatName", "display_name") ?: jid,
        lastTs = pickTs("last_message_ts", "LastMessageTS", "last_ts", "Timestamp", "ts"),
        unread = pickInt("unread_count", "UnreadCount").takeIf { it > 0 }
            ?: if (pickBool("unread", "Unread")) 1 else 0,
        pinned = pickBool("pinned", "Pinned"),
    )
}

fun JsonObject.toWaMessage(): WaMessage = WaMessage(
    id = pickStr("MsgID", "msg_id", "ID", "id", "message_id") ?: "",
    chat = pickJid("ChatJID", "chat_jid", "Chat", "chat", "jid"),
    chatName = pickStr("ChatName", "chat_name") ?: "",
    sender = pickJid("SenderJID", "sender_jid", "sender"),
    senderName = pickStr("SenderName", "sender_name", "PushName", "pushName", "notify") ?: "",
    fromMe = pickBool("FromMe", "from_me", "fromMe"),
    ts = pickTs("Timestamp", "ts", "timestamp"),
    text = pickStr("Text", "text", "DisplayText", "display_text", "Body", "body") ?: "",
    mediaType = pickStr("media_type", "MediaType"),
    filename = pickStr("Filename", "filename"),
    downloaded = pickBool("media_ready") || pickStr("LocalPath", "local_path") != null,
)

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
        c.toIntOrNull()?.let { return it }
    }
    return 0
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

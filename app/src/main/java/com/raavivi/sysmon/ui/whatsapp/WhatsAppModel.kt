package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaChat
import com.raavivi.sysmon.core.model.WaMessage
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure message-model decisions for the WhatsApp screen (no Android types): grouping, day breaks, the
 * list-row model, reaction aggregation, media kinds, boxes and the chat-list preview. Mirrors
 * sys-mon's frontend/js/features/whatsapp-model.js, so keep the two in step.
 */

fun waIsGroup(jid: String?): Boolean = jid?.endsWith("@g.us") == true

/** `+<digits>` from a JID's user part; blank for groups / unknown. */
fun waPhoneSubtitle(jid: String?): String {
    if (jid == null || waIsGroup(jid)) return ""
    val user = jid.substringBefore('@').substringBefore(':')
    return if (user.isNotEmpty() && user.all { it.isDigit() }) "+$user" else ""
}

// -- days ---------------------------------------------------------------------

/** Calendar day (device local time) of [ts] as `yyyy-MM-dd`. */
fun waDayKey(ts: Double, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(ts.toLong()).atZone(zone).toLocalDate().toString()

private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/**
 * "Today", "Yesterday", the weekday name for the previous 6 days, else "12 March" (this year) or
 * "12 March 2025". [short] abbreviates the weekday and month for the chat list.
 */
fun waDayLabel(ts: Double, nowSeconds: Double, zone: ZoneId = ZoneId.systemDefault(), short: Boolean = false): String {
    val d = Instant.ofEpochSecond(ts.toLong()).atZone(zone).toLocalDate()
    val n = Instant.ofEpochSecond(nowSeconds.toLong()).atZone(zone).toLocalDate()
    val ago = ChronoUnit.DAYS.between(d, n)
    if (ago == 0L) return "Today"
    if (ago == 1L) return "Yesterday"
    if (ago in 2..6) {
        val w = WEEKDAYS[d.dayOfWeek.value - 1]
        return if (short) w.take(3) else w
    }
    var month = MONTH_NAMES[d.monthValue - 1]
    if (short) month = month.take(3)
    val base = "${d.dayOfMonth} $month"
    return if (d.year == n.year) base else "$base ${d.year}"
}

// -- grouping + rows ----------------------------------------------------------

const val WA_GROUP_GAP_SEC = 300.0

/** Whether a message is a list item: reactions and "deleted for me" rows never are. */
fun isVisibleMessage(m: WaMessage): Boolean = !m.isReaction && !m.deletedForMe

/**
 * Do [a] and [b] (adjacent messages, either may be null) belong to one group: same side, same
 * sender, within 5 minutes and the same calendar day.
 */
fun waSameGroup(a: WaMessage?, b: WaMessage?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (a == null || b == null) return false
    if (a.fromMe != b.fromMe) return false
    if (!a.fromMe && a.sender != b.sender) return false
    if (a.ts <= 0 || b.ts <= 0) return false
    if (abs(b.ts - a.ts) > WA_GROUP_GAP_SEC) return false
    return waDayKey(a.ts, zone) == waDayKey(b.ts, zone)
}

/** A day separator belongs before [cur] when the calendar day changes (or it is the first). */
fun waNeedsDaySep(prev: WaMessage?, cur: WaMessage, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (cur.ts <= 0) return false
    if (prev == null) return true
    if (prev.ts <= 0) return false
    return waDayKey(prev.ts, zone) != waDayKey(cur.ts, zone)
}

/** One LazyColumn item: a day separator or a message with its place in its group. */
sealed interface WaRow {
    val key: String

    /** Message time (a day row carries its day's first message), for the timeline scrubber. */
    val ts: Double
}

data class DayRow(val dayKey: String, override val ts: Double) : WaRow {
    override val key: String get() = "day:$dayKey"
}

data class MsgRow(val msg: WaMessage, val groupFirst: Boolean, val groupLast: Boolean) : WaRow {
    override val key: String get() = msg.id.ifBlank { "${msg.ts}-${msg.text.hashCode()}" }
    override val ts: Double get() = msg.ts
}

/** Messages (oldest first) to list rows: day headers plus messages flagged first / last of their group. */
fun buildRows(messages: List<WaMessage>, zone: ZoneId = ZoneId.systemDefault()): List<WaRow> {
    val vis = messages.filter(::isVisibleMessage)
    val rows = ArrayList<WaRow>(vis.size + 8)
    for ((i, m) in vis.withIndex()) {
        val prev = vis.getOrNull(i - 1)
        val next = vis.getOrNull(i + 1)
        if (waNeedsDaySep(prev, m, zone)) rows.add(DayRow(waDayKey(m.ts, zone), m.ts))
        rows.add(MsgRow(m, groupFirst = !waSameGroup(prev, m, zone), groupLast = !waSameGroup(m, next, zone)))
    }
    return rows
}

/** Index of the row to show for message [id] (for "scroll to quoted message"), or -1. */
fun waRowIndexOf(rows: List<WaRow>, id: String): Int =
    if (id.isBlank()) -1 else rows.indexOfFirst { it is MsgRow && it.msg.id == id }

// -- reactions ----------------------------------------------------------------

/** One reactor's current reaction; an empty emoji is a removal, kept as a tombstone so an older row cannot resurrect it. */
data class WaReaction(val emoji: String, val ts: Double)

/** targetMessageId -> reactorKey -> reaction. */
typealias WaReactions = Map<String, Map<String, WaReaction>>

fun waReactorKey(m: WaMessage): String = if (m.fromMe) "me" else m.sender

/** Fold one reaction in; a later [ts] wins, an older one never overrides. Returns [store] itself when nothing changed. */
fun waApplyReaction(store: WaReactions, targetId: String, key: String, emoji: String, ts: Double): WaReactions {
    if (targetId.isBlank() || key.isBlank()) return store
    val per = store[targetId].orEmpty()
    val cur = per[key]
    if (cur != null && cur.ts > ts) return store
    if (cur != null && cur.emoji == emoji && cur.ts == ts) return store
    return store + (targetId to (per + (key to WaReaction(emoji, ts))))
}

/** Fold every reaction row of [rows] into [base]. Order independent (the later timestamp wins). */
fun waAggregateReactions(rows: List<WaMessage>, base: WaReactions = emptyMap()): WaReactions {
    var out = base
    for (m in rows) if (m.isReaction) out = waApplyReaction(out, m.reactionToId, waReactorKey(m), m.reactionEmoji, m.ts)
    return out
}

data class EmojiCount(val emoji: String, val count: Int)
data class Reactor(val key: String, val emoji: String, val ts: Double)

/** Chip data for one target: up to 3 distinct emoji (most frequent first, ties by first reaction), total, reactors ("me" first). */
data class ReactionSummary(val total: Int, val top: List<EmojiCount>, val reactors: List<Reactor>, val mine: String) {
    companion object { val Empty = ReactionSummary(0, emptyList(), emptyList(), "") }
}

fun waSummarizeReactions(per: Map<String, WaReaction>?): ReactionSummary {
    val live = per.orEmpty().filter { it.value.emoji.isNotEmpty() }.map { Reactor(it.key, it.value.emoji, it.value.ts) }
    if (live.isEmpty()) return ReactionSummary.Empty
    val counts = LinkedHashMap<String, Int>()
    val first = HashMap<String, Double>()
    for (r in live.sortedBy { it.ts }) {
        counts[r.emoji] = (counts[r.emoji] ?: 0) + 1
        if (r.emoji !in first) first[r.emoji] = r.ts
    }
    val top = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { first.getValue(it.key) })
        .take(3).map { EmojiCount(it.key, it.value) }
    val reactors = live.sortedWith(compareBy<Reactor> { if (it.key == "me") 0 else 1 }.thenBy { it.ts })
    return ReactionSummary(live.size, top, reactors, per?.get("me")?.emoji.orEmpty())
}

/** Changes whenever the chip should pop. */
fun waReactionSig(sum: ReactionSummary): String =
    if (sum.total == 0) "" else "${sum.total}|${sum.top.joinToString("") { it.emoji }}"

// -- live edits / revokes -----------------------------------------------------

/**
 * A live frame that carries an existing message's id with Edited/Revoked set updates that message in
 * place (never a second bubble). Null when [update] is not an edit/revoke or the message is not loaded.
 */
fun waApplyLiveUpdate(messages: List<WaMessage>, update: WaMessage): List<WaMessage>? {
    if (update.id.isBlank() || !(update.edited || update.revoked)) return null
    val idx = messages.indexOfFirst { it.id == update.id }
    if (idx < 0) return null
    val old = messages[idx]
    val merged = if (update.revoked) {
        old.copy(revoked = true)
    } else {
        old.copy(
            text = update.text.ifEmpty { old.text },
            edited = true,
            starred = old.starred || update.starred,
        )
    }
    return messages.toMutableList().also { it[idx] = merged }
}

// -- media --------------------------------------------------------------------

enum class WaMediaKind(val label: String) {
    IMAGE("Photo"), STICKER("Sticker"), GIF("GIF"), VIDEO("Video"), VOICE("Voice message"), DOCUMENT("Document"),
}

/** The kind of media a message carries, null for none. Tolerant of wacli's naming (contains, case-insensitive). */
fun waMediaKind(mediaType: String?, mime: String = ""): WaMediaKind? {
    val t = mediaType?.lowercase() ?: return null
    val mm = mime.lowercase()
    return when {
        t.contains("sticker") -> WaMediaKind.STICKER
        t == "gif" || t.contains("gif") -> WaMediaKind.GIF
        mm.startsWith("image/") || t.contains("image") -> WaMediaKind.IMAGE
        mm.startsWith("video/") || t.contains("video") -> WaMediaKind.VIDEO
        mm.startsWith("audio/") || t.contains("audio") || t.contains("ptt") || t.contains("voice") -> WaMediaKind.VOICE
        else -> WaMediaKind.DOCUMENT
    }
}

fun WaMessage.mediaKind(): WaMediaKind? = waMediaKind(mediaType, mime)

/** wacli / server `preview_type` names to a kind; null for text or empty. */
fun waKindFromType(type: String?): WaMediaKind? {
    val t = type?.lowercase().orEmpty()
    if (t.isEmpty() || t == "text" || t == "revoked") return null
    return waMediaKind(t)
}

private val PLACEHOLDER_RE = Regex(
    """^\[?\s*(image|photo|picture|video|audio|voice( message| note)?|ptt|sticker|gif|document|file|media)\s*\]?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * The caption shown under media: the message text unless it is only a type placeholder ("[image]")
 * or just the document's own file name. For a text message, the text itself.
 */
fun waCaptionOf(m: WaMessage): String {
    val t = m.text.trim()
    if (t.isEmpty()) return ""
    if (m.mediaType == null) return t
    if (PLACEHOLDER_RE.matches(t)) return ""
    if (m.filename != null && t == m.filename) return ""
    return m.text
}

/** The reserved size of a bubble image in dp. [known] is false when the row has no pixel size yet. */
data class MediaBox(val w: Float, val h: Float, val known: Boolean)

const val WA_BUBBLE_MAX_W = 260f
const val WA_BUBBLE_MAX_H = 320f
const val WA_BUBBLE_MIN_W = 140f
const val WA_STICKER_BOX = 160f

/**
 * The box a bubble image is reserved at before it loads: the pixel size scaled to fit 260x320dp, then
 * lifted to the 140dp minimum width (the picture is cropped to the box). Unknown size reserves 4:3.
 */
fun waMediaBox(width: Int, height: Int): MediaBox {
    if (width <= 0 || height <= 0) return MediaBox(240f, 180f, known = false)
    val scale = min(WA_BUBBLE_MAX_W / width, WA_BUBBLE_MAX_H / height)
    var bw = (width * scale).roundToInt().toFloat()
    val bh = (height * scale).roundToInt().toFloat()
    if (bw < WA_BUBBLE_MIN_W) bw = WA_BUBBLE_MIN_W
    return MediaBox(max(1f, bw), max(1f, bh), known = true)
}

/** "Forwarded" / "Forwarded many times" / "". */
fun waForwardLabel(m: WaMessage): String = when {
    !m.forwarded -> ""
    m.forwardScore >= 5 -> "Forwarded many times"
    else -> "Forwarded"
}

// -- quoted replies -----------------------------------------------------------

/** What a quote card shows. [author] is "You" for my own messages; [mediaKind] when the quoted message was media. */
data class QuoteView(val author: String, val authorJid: String, val fromMe: Boolean, val text: String, val mediaKind: WaMediaKind?)

/** Sender JID -> display name, from the loaded messages (last non-blank wins). */
fun waNameBySender(messages: List<WaMessage>): Map<String, String> {
    val out = HashMap<String, String>()
    for (m in messages) if (!m.fromMe && m.sender.isNotBlank() && m.senderName.isNotBlank()) out[m.sender] = m.senderName
    return out
}

/** Name for a sender: the push name when known, else the phone number, else a blank. */
fun waSenderLabel(jid: String, names: Map<String, String>): String =
    names[jid]?.takeIf { it.isNotBlank() } ?: waPhoneSubtitle(jid).ifEmpty { jid.substringBefore('@') }

/** The quote card for [m], or null when it is not a reply. Uses the quoted message when it is loaded. */
fun waQuoteView(m: WaMessage, byId: Map<String, WaMessage>, names: Map<String, String>): QuoteView? {
    if (m.replyToId.isBlank() && m.replyToText.isBlank()) return null
    val target = byId[m.replyToId]
    val fromMe = target?.fromMe ?: false
    val jid = target?.sender ?: m.replyToSender
    val author = when {
        fromMe -> "You"
        jid.isNotBlank() -> waSenderLabel(jid, names)
        else -> "Message"
    }
    val kind = target?.mediaKind()
    val text = m.replyToText.ifBlank { target?.let { waCaptionOf(it) }.orEmpty() }
    return QuoteView(author, if (fromMe) "me" else jid, fromMe, text.replace(Regex("\\s+"), " ").trim(), kind)
}

// -- chat list ----------------------------------------------------------------

/** What the second line of a chat row shows: [prefix] ("You: ", "Sam: "), an optional [kind] icon, the [text]. */
data class PreviewLine(val prefix: String, val kind: WaMediaKind?, val text: String, val revoked: Boolean)

/** Null when the server sent no preview (an older server): the row then shows no second line. */
fun waPreviewLine(chat: WaChat, group: Boolean): PreviewLine? {
    val kind = waKindFromType(chat.previewType)
    val text = chat.preview.replace(Regex("\\s+"), " ").trim()
    if (!chat.previewRevoked && text.isEmpty() && kind == null) return null
    val prefix = when {
        chat.previewFromMe -> "You: "
        group && chat.previewSender.isNotBlank() -> "${chat.previewSender}: "
        else -> ""
    }
    if (chat.previewRevoked) return PreviewLine(prefix, null, "This message was deleted", revoked = true)
    if (kind != null && text.isEmpty()) return PreviewLine(prefix, kind, kind.label, revoked = false)
    return PreviewLine(prefix, kind, text, revoked = false)
}

/** Preview fields derived from one message (live message), to update a chat row locally. */
fun waPreviewFromMessage(chat: WaChat, m: WaMessage): WaChat {
    val kind = m.mediaKind()
    return chat.copy(
        preview = if (m.revoked) "" else waCaptionOf(m),
        previewType = if (m.revoked) "" else kind?.name?.lowercase().orEmpty(),
        previewFromMe = m.fromMe,
        previewSender = if (m.fromMe) "" else m.senderName,
        previewRevoked = m.revoked,
    )
}

/** Time on a chat row: clock time today ("10:42"), else "Yesterday" / weekday / "12 Mar". */
fun waChatTime(ts: Double, nowSeconds: Double, zone: ZoneId = ZoneId.systemDefault()): String {
    val label = waDayLabel(ts, nowSeconds, zone, short = true)
    if (label != "Today") return label
    val t = Instant.ofEpochSecond(ts.toLong()).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

// -- inline meta placement ----------------------------------------------------

/** Where the time/edited/star meta goes relative to the message text, in px. */
data class MetaPlan(val inline: Boolean, val width: Int, val height: Int, val metaX: Int, val metaY: Int)

/**
 * The meta shares the text's last line (bottom-right) when `lastLineRight + gap + metaW <= maxW`,
 * else it wraps to its own line, right-aligned. The bubble is as wide as the text or the meta
 * requires, whichever is larger. [lastLineRight] is null when unknown, which wraps.
 */
fun waPlanMeta(textW: Int, textH: Int, lastLineRight: Float?, metaW: Int, metaH: Int, maxW: Int, gap: Int): MetaPlan {
    if (lastLineRight != null) {
        val need = ceil(lastLineRight).toInt() + gap + metaW
        if (need <= maxW) {
            val w = max(textW, need)
            val h = max(textH, metaH)
            return MetaPlan(inline = true, width = w, height = h, metaX = w - metaW, metaY = h - metaH)
        }
    }
    val w = max(textW, metaW)
    return MetaPlan(inline = false, width = w, height = textH + metaH, metaX = w - metaW, metaY = textH)
}

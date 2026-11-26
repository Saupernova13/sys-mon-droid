package com.raavivi.sysmon.ui.chatindex

import com.raavivi.sysmon.core.data.chatindex.ChatIndexError
import com.raavivi.sysmon.core.data.chatindex.chatIndexErrorOf
import com.raavivi.sysmon.core.model.ChatOpenRequest
import com.raavivi.sysmon.core.model.CiCardRow
import com.raavivi.sysmon.core.model.CiDb
import com.raavivi.sysmon.core.model.CiFamily
import com.raavivi.sysmon.core.model.CiMessage
import com.raavivi.sysmon.core.model.CiMoment
import com.raavivi.sysmon.core.model.CiNode
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.WaPos
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.common.formatBytes
import com.raavivi.sysmon.ui.whatsapp.WaMediaKind
import com.raavivi.sysmon.ui.whatsapp.mediaKind
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The decisions behind the Chat index screen, with no UI: tabs, the overview and storage readouts, time text for
 * `{ts, rowid}` spans, paging, the Browse drill-down and how a message reads. Chat content is private: nothing
 * here logs.
 */

/** The screen's tabs, in order. */
enum class CiTab(val label: String) { OVERVIEW("Overview"), BROWSE("Browse"), LEXICON("Lexicon"), STORAGE("Storage") }

// -- overview ---------------------------------------------------------------------------------------

/** A number with thousands separators ("12,345"). */
fun countText(n: Long): String = String.format(Locale.US, "%,d", n)

/** One cell of the overview grid: a big [value] over its [label]. */
data class OverviewCell(val label: String, val value: String)

/** The counts grid, in reading order. */
fun overviewCells(o: CiOverview): List<OverviewCell> = listOf(
    OverviewCell("Chats", countText(o.families)),
    OverviewCell("Sessions", countText(o.nodes.sessions)),
    OverviewCell("Topics", countText(o.nodes.topics)),
    OverviewCell("Sub-topics", countText(o.nodes.subtopics)),
    OverviewCell("Messages indexed", countText(o.messagesIndexed)),
    OverviewCell("Vectors", countText(o.vectors)),
    OverviewCell("Node vectors", countText(o.nodeVectors)),
    OverviewCell("Cards done", countText(o.cards.done)),
    OverviewCell("Cards failed", countText(o.cards.failed)),
    OverviewCell("Cards outdated", countText(o.cards.outdated)),
)

/** A titled group of overview cells ("Index", "Search data", "Cards"). */
data class OverviewGroup(val title: String, val cells: List<OverviewCell>)

fun overviewGroups(o: CiOverview): List<OverviewGroup> {
    val c = overviewCells(o)
    return listOf(
        OverviewGroup("Index", c.subList(0, 4)),
        OverviewGroup("Search data", c.subList(4, 7)),
        OverviewGroup("Cards", c.subList(7, 10)),
    )
}

const val NO_INDEX_TEXT = "No index has been built yet."

/** Shown over the whole screen while the server has chat search off (the routes answer 404). */
const val CHAT_SEARCH_OFF_TEXT = "Turn on WhatsApp chat search to use this."

// -- storage ----------------------------------------------------------------------------------------

/** Whether a database file is there and readable. */
enum class DbStatus(val label: String) { PRESENT("Present"), MISSING("Missing"), UNREADABLE("Unreadable") }

data class StorageTable(val name: String, val rows: String)

/** One database card: its file, state, size and tables (a null row count reads "virtual"). */
data class StorageCard(val key: String, val file: String, val status: DbStatus, val size: String, val tables: List<StorageTable>)

fun storageCard(db: CiDb): StorageCard {
    val status = when {
        !db.exists -> DbStatus.MISSING
        db.error != null -> DbStatus.UNREADABLE
        else -> DbStatus.PRESENT
    }
    return StorageCard(
        key = db.key,
        file = db.file,
        status = status,
        size = if (db.sizeBytes == null) "—" else formatBytes(db.sizeBytes),
        tables = db.tables.map { StorageTable(it.name, it.rows?.let(::countText) ?: "virtual") },
    )
}

/** "Present · 4.2 MB", "Missing", "Unreadable · 12 KB": the one-line state of a database. */
fun storageLine(c: StorageCard): String =
    if (c.status == DbStatus.MISSING || c.size == "—") c.status.label else "${c.status.label} · ${c.size}"

// -- time -------------------------------------------------------------------------------------------

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun two(v: Int) = v.toString().padStart(2, '0')

/** A span end's unix seconds. */
fun tsOf(p: WaPos): Long = p.ts

private fun dayOf(ts: Long, zone: ZoneId): String {
    val d = Instant.ofEpochSecond(ts).atZone(zone)
    return "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
}

private fun clockOf(ts: Long, zone: ZoneId): String {
    val d = Instant.ofEpochSecond(ts).atZone(zone)
    return "${two(d.hour)}:${two(d.minute)}"
}

/** Unix seconds as local "12 Mar 2026 14:05"; an em dash for no time. */
fun momentText(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (ts <= 0) "—" else "${dayOf(ts, zone)} ${clockOf(ts, zone)}"

/** A span in local time: the day once when both ends share it ("12 Mar 2026 14:05–15:30"). */
fun rangeText(start: WaPos, end: WaPos, zone: ZoneId = ZoneId.systemDefault()): String {
    val s = start.ts
    if (s <= 0) return "—"
    val e = if (end.ts > 0) end.ts else s
    return if (dayOf(s, zone) == dayOf(e, zone)) {
        "${dayOf(s, zone)} ${clockOf(s, zone)}–${clockOf(e, zone)}"
    } else {
        "${dayOf(s, zone)} ${clockOf(s, zone)} – ${dayOf(e, zone)} ${clockOf(e, zone)}"
    }
}

// -- paging -----------------------------------------------------------------------------------------

/** One page of rows and the offset of the next, null on the last. */
data class PageOf<T>(val items: List<T>, val next: Int?)

/** The rows after [page] was read at [offset]: offset 0 replaces, a later page is appended without repeating a [key]. */
fun <T> mergePage(items: List<T>, page: PageOf<T>, offset: Int, key: (T) -> Any): PageOf<T> {
    val base = if (offset == 0) emptyList() else items
    val seen = base.mapTo(HashSet(), key)
    val merged = base.toMutableList()
    for (r in page.items) if (seen.add(key(r))) merged += r
    return PageOf(merged, page.next)
}

// -- browse drill-down ------------------------------------------------------------------------------

val LEVEL_NAMES = listOf("Session", "Topic", "Sub-topic")

fun levelName(level: Int): String = LEVEL_NAMES.getOrElse(level) { "Node" }

/** What the children of a node of [level] are called ("topics" under a session). */
fun childrenName(level: Int): String = if (level == 0) "topics" else "sub-topics"

fun familyLabel(f: CiFamily): String = f.name.ifBlank { f.jids.firstOrNull().orEmpty() }.ifBlank { "Chat ${f.id}" }

/** The chat being browsed. */
data class FamilyRef(val id: Long, val label: String)

/** One node drilled into (a session, then a topic): its [label] is its time range. */
data class TrailStep(val id: Long, val level: Int, val label: String)

/** What the chat level lists: the chat's sessions or its cards. */
enum class BrowseMode(val label: String) { SESSIONS("Sessions"), CARDS("Cards") }

/** What the open detail view shows: one node, or one card. */
sealed interface BrowseDetail {
    data class Node(val node: CiNode) : BrowseDetail
    data class Card(val id: Long, val title: String, val manual: Boolean) : BrowseDetail
}

/**
 * Where Browse is: the chat list ([family] null), a chat's sessions or cards, the nodes drilled into ([trail]),
 * and an open [detail]. Immutable: each move returns the next nav.
 */
data class BrowseNav(
    val family: FamilyRef? = null,
    val mode: BrowseMode = BrowseMode.SESSIONS,
    val trail: List<TrailStep> = emptyList(),
    val detail: BrowseDetail? = null,
) {
    /** There is somewhere to go back to inside Browse (else Back leaves the screen). */
    val canGoBack: Boolean get() = family != null

    fun intoFamily(f: CiFamily): BrowseNav = BrowseNav(family = FamilyRef(f.id, familyLabel(f)))

    fun withMode(m: BrowseMode): BrowseNav = copy(mode = m, trail = emptyList(), detail = null)

    /** Open a node's detail (facts, card, messages). */
    fun openNode(n: CiNode): BrowseNav = copy(detail = BrowseDetail.Node(n))

    /** Open a card's detail. */
    fun openCard(c: CiCardRow): BrowseNav =
        copy(detail = BrowseDetail.Card(c.id, c.title, c.isManual))

    /** From a node's detail, drill into its children: the node joins the trail and the detail closes. */
    fun drill(n: CiNode, zone: ZoneId = ZoneId.systemDefault()): BrowseNav =
        copy(trail = trail + TrailStep(n.id, n.level, rangeText(n.start, n.end, zone)), detail = null)

    /** One step back: close the detail, else leave the last node, else leave the chat. */
    fun back(): BrowseNav = when {
        detail != null -> copy(detail = null)
        trail.isNotEmpty() -> copy(trail = trail.dropLast(1))
        family != null -> BrowseNav()
        else -> this
    }

    /** What the list shows. */
    val list: BrowseList
        get() = when {
            family == null -> BrowseList.Chats
            mode == BrowseMode.CARDS -> BrowseList.Cards(family.id)
            trail.isEmpty() -> BrowseList.Sessions(family.id)
            else -> BrowseList.Children(trail.last().id, trail.last().level)
        }
}

/** The list a [BrowseNav] asks for. */
sealed interface BrowseList {
    data object Chats : BrowseList
    data class Sessions(val family: Long) : BrowseList
    data class Cards(val family: Long) : BrowseList
    data class Children(val parent: Long, val parentLevel: Int) : BrowseList
}

/** One crumb of the breadcrumb; tapping it makes [nav] the position. */
data class Crumb(val label: String, val nav: BrowseNav, val current: Boolean)

/** The breadcrumb: Chats, the chat, Cards (when listing them), each node drilled into, then the open detail. */
fun crumbs(nav: BrowseNav): List<Crumb> {
    val out = mutableListOf(Crumb("Chats", BrowseNav(), current = nav.family == null))
    val family = nav.family ?: return out
    val root = BrowseNav(family = family)
    val listing = nav.detail == null
    out += Crumb(family.label, root, current = listing && nav.trail.isEmpty() && nav.mode == BrowseMode.SESSIONS)
    if (nav.mode == BrowseMode.CARDS) {
        out += Crumb("Cards", root.withMode(BrowseMode.CARDS), current = listing)
    }
    nav.trail.forEachIndexed { i, t ->
        out += Crumb(
            "${levelName(t.level)} ${t.label}",
            root.copy(trail = nav.trail.take(i + 1)),
            current = listing && i == nav.trail.lastIndex,
        )
    }
    nav.detail?.let { d ->
        val label = when (d) {
            is BrowseDetail.Node -> "${levelName(d.node.level)} ${rangeText(d.node.start, d.node.end)}"
            is BrowseDetail.Card -> d.title.ifBlank { "Card" }
        }
        out += Crumb(label, nav, current = true)
    }
    return out
}

// -- rows -------------------------------------------------------------------------------------------

/** "1 message" / "N messages". */
fun messagesText(n: Int): String = if (n == 1) "1 message" else "$n messages"

/** A node row's headline: its card's title when it has one, else its time range. */
fun nodeLabel(n: CiNode, zone: ZoneId = ZoneId.systemDefault()): String =
    n.card?.title?.takeIf { it.isNotBlank() } ?: rangeText(n.start, n.end, zone)

/** A node row's second line: the range (when the headline is a title), the size and what it holds. */
fun nodeSubtitle(n: CiNode, zone: ZoneId = ZoneId.systemDefault()): String = buildList {
    if (!n.card?.title.isNullOrBlank()) add(rangeText(n.start, n.end, zone))
    add(messagesText(n.nMsgs))
    if (n.nChildren > 0) add("${n.nChildren} ${childrenName(n.level)}")
}.joinToString(" · ")

/** The spoken form of a node row's vector dot (the dot alone says nothing to TalkBack). */
fun vectorLabel(hasVector: Boolean): String = if (hasVector) "Has a vector" else "No vector"

/** A node detail's facts line: "Topic, 42 messages, has a vector". */
fun nodeFacts(n: CiNode): String =
    "${levelName(n.level)}, ${messagesText(n.nMsgs)}, ${if (n.hasVector) "has a vector" else "no vector"}"

/** What a card row's headline says. */
fun cardRowLabel(c: CiCardRow): String = when {
    c.state == "failed" -> "Failed card"
    else -> c.title.ifBlank { "Untitled card" }
}

/** A chat row's second line: its messages and what the index holds of it. */
fun familySubtitle(f: CiFamily): String =
    "${messagesText(f.messages.toInt())} · ${f.nodes.topics} topics · ${f.nodes.subtopics} sub-topics · ${f.cards} cards"

private fun mediaWord(k: WaMediaKind): String = when (k) {
    WaMediaKind.IMAGE -> "photo"
    WaMediaKind.STICKER -> "sticker"
    WaMediaKind.VOICE -> "voice message"
    WaMediaKind.VIDEO -> "video"
    WaMediaKind.GIF -> "GIF"
    WaMediaKind.DOCUMENT -> "document"
}

/** One message of a node as shown: who, when, the text, and a "[photo]" style tag for media. */
data class MessageView(val key: Long, val sender: String, val time: String, val text: String, val media: String)

fun messageView(m: CiMessage, zone: ZoneId = ZoneId.systemDefault()): MessageView {
    val msg = m.message
    val kind = msg.mediaKind()
    val who = when {
        msg.fromMe -> "You"
        msg.senderName.isNotBlank() -> msg.senderName
        msg.sender.isNotBlank() -> msg.sender.substringBefore('@')
        else -> "Unknown"
    }
    return MessageView(
        key = m.rowid,
        sender = who,
        time = momentText(msg.ts.toLong(), zone),
        text = msg.text,
        media = kind?.let { "[${mediaWord(it)}]" }.orEmpty(),
    )
}

/** A card detail's facts line: "12 messages · done · model · prompt 3". */
fun cardFacts(nMsgs: Int, state: String, model: String?, promptVersion: Long?): String =
    listOfNotNull(messagesText(nMsgs), state.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() }, promptVersion?.let { "prompt $it" })
        .joinToString(" · ")

/** Whether Delete card is offered: only a card the owner asked for can be deleted. */
fun canDeleteCard(origin: String): Boolean = origin == "manual"

/** The request to open the WhatsApp chat at a moment's message; null when the card kept no message for it. */
fun momentRequest(m: CiMoment, chatName: String = ""): ChatOpenRequest? {
    val ref = m.message ?: return null
    if (ref.chatJid.isBlank() || ref.msgId.isBlank() || ref.ts <= 0) return null
    return ChatOpenRequest(jid = ref.chatJid, ts = ref.ts, msgId = ref.msgId, name = chatName)
}

/** What a failed read says in a status line. The server's reasons are fixed sentences, safe to show. */
fun loadErrorText(err: ApiResult.Err): String = when (val e = chatIndexErrorOf(err)) {
    ChatIndexError.Off -> CHAT_SEARCH_OFF_TEXT
    ChatIndexError.Missing -> "It is not there any more."
    ChatIndexError.Unavailable -> "The messages cannot be read right now."
    is ChatIndexError.Refused -> sentence(e.reason)
    else -> err.message.ifBlank { "Something went wrong." }
}

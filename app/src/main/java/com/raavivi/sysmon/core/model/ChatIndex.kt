package com.raavivi.sysmon.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire shapes of the chat index explorer (`GET /api/whatsapp/chat-search/explore/...`) and the private-words
// glossary (`.../lexicon`), docs/API.md. Everything here is read from the owner's conversations: nothing is ever
// logged. A span end is a [WaPos] (`ts` unix seconds, `rowid` of the wacli row).

@Serializable
data class CiNodeCounts(val sessions: Long = 0, val topics: Long = 0, val subtopics: Long = 0)

@Serializable
data class CiCardCounts(val done: Long = 0, val failed: Long = 0, val outdated: Long = 0)

/** `explore/overview`; no index file gives zeros and [indexExists] false. */
@Serializable
data class CiOverview(
    val indexExists: Boolean = false,
    val families: Long = 0,
    val nodes: CiNodeCounts = CiNodeCounts(),
    val messagesIndexed: Long = 0,
    val vectors: Long = 0,
    val nodeVectors: Long = 0,
    val cards: CiCardCounts = CiCardCounts(),
)

/** One chat (a family of jids) of the index; [name] is "" when none of its jids has one. */
@Serializable
data class CiFamily(
    val id: Long = 0,
    val jids: List<String> = emptyList(),
    val name: String = "",
    val messages: Long = 0,
    val nodes: CiNodeCounts = CiNodeCounts(),
    val cards: Long = 0,
)

/** The done card whose range is exactly a topic node's. */
@Serializable
data class CiCardRef(val id: Long = 0, val title: String = "")

/** A session (level 0), topic (1) or sub-topic (2). */
@Serializable
data class CiNode(
    val id: Long = 0,
    val familyId: Long = 0,
    val parentId: Long? = null,
    val level: Int = 0,
    val start: WaPos = WaPos(),
    val end: WaPos = WaPos(),
    val nMsgs: Int = 0,
    val hasVector: Boolean = false,
    val nChildren: Int = 0,
    val card: CiCardRef? = null,
)

@Serializable
data class CiPathStep(val id: Long = 0, val level: Int = 0)

@Serializable
data class CiNodeDetail(val node: CiNode = CiNode(), val path: List<CiPathStep> = emptyList())

/** One row of a chat's card list; [title] is "" for a failed card, [origin] `auto` or `manual`. */
@Serializable
data class CiCardRow(
    val id: Long = 0,
    val title: String = "",
    val start: WaPos = WaPos(),
    val end: WaPos = WaPos(),
    val nMsgs: Int = 0,
    val state: String = "done",
    val updatedAt: Long = 0,
    val origin: String = "auto",
    /** Unix seconds of the owner's last edit; null for a card never edited. */
    val editedAt: Long? = null,
) {
    val isManual: Boolean get() = origin == "manual"
}

/** The message a moment points at; the whole thing is null when the card kept no rowid or the message is gone. */
@Serializable
data class CiMessageRef(
    val rowid: Long = 0,
    val chatJid: String = "",
    val msgId: String = "",
    val ts: Long = 0,
)

@Serializable
data class CiMoment(
    val kind: String = "",
    val oneLine: String = "",
    val said: String = "",
    val msgIndex: Int = 0,
    val message: CiMessageRef? = null,
)

/** `explore/cards/{id}`: a card with its facts. [card] is null for a failed card. */
@Serializable
data class CiCardFull(
    val id: Long = 0,
    val familyJids: List<String> = emptyList(),
    val start: WaPos = WaPos(),
    val end: WaPos = WaPos(),
    val nMsgs: Int = 0,
    val state: String = "done",
    val model: String? = null,
    val promptVersion: Long? = null,
    val failReason: String? = null,
    val updatedAt: Long = 0,
    /** Unix seconds of the owner's last edit; null for a card never edited. */
    val editedAt: Long? = null,
    val nodeId: Long? = null,
    val origin: String = "auto",
    val instruction: String? = null,
    val msgIds: List<String> = emptyList(),
    val card: WaTopicCard? = null,
    val subtopics: List<WaSubCard> = emptyList(),
    val moments: List<CiMoment> = emptyList(),
) {
    val isManual: Boolean get() = origin == "manual"
}

// -- pages (`nextOffset` null on the last) -----------------------------------------------------------

@Serializable
data class CiFamiliesPage(val families: List<CiFamily> = emptyList(), val nextOffset: Int? = null)

@Serializable
data class CiNodesPage(val nodes: List<CiNode> = emptyList(), val nextOffset: Int? = null)

@Serializable
data class CiCardsPage(val cards: List<CiCardRow> = emptyList(), val nextOffset: Int? = null)

/** The wire page of node messages: wacli's JSON rows plus `rowid`; the repository maps them to [CiMessage]. */
@Serializable
data class CiMessagesWire(val nodeId: Long = 0, val messages: List<JsonObject> = emptyList(), val nextOffset: Int? = null)

/** A message of a node with its wacli row id (the paging key). */
data class CiMessage(val rowid: Long, val message: WaMessage)

data class CiMessagesPage(val nodeId: Long, val messages: List<CiMessage>, val nextOffset: Int?)

// -- storage -----------------------------------------------------------------------------------------

/** [rows] is null for a virtual table (full-text and vector indexes are not counted). */
@Serializable
data class CiTable(val name: String = "", val rows: Long? = null)

/** One chat-search database: [file] is a file name only; [error] is null or `unreadable`. */
@Serializable
data class CiDb(
    val key: String = "",
    val file: String = "",
    val exists: Boolean = false,
    val sizeBytes: Long? = null,
    val tables: List<CiTable> = emptyList(),
    val error: String? = null,
)

@Serializable
data class CiStorage(val dbs: List<CiDb> = emptyList())

// -- lexicon -----------------------------------------------------------------------------------------

/** One meaning with the 1-10 spellings that share it; [version] is what an edit or delete sends back. */
@Serializable
data class LexEntry(
    val id: Long = 0,
    val version: Long = 1,
    val terms: List<String> = emptyList(),
    val meaning: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class LexRejected(val term: String = "", val createdAt: Long = 0)

/** A private word the card writer listed that the glossary neither holds nor rejected. */
@Serializable
data class LexSuggestion(
    val term: String = "",
    val meaning: String? = null,
    val seen: Int = 0,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
)

/** The one-time import of the legacy glossary file. */
@Serializable
data class LexImport(
    val done: Boolean = false,
    val source: String = "",
    val imported: Int = 0,
    val skipped: Int = 0,
    val error: String? = null,
)

@Serializable
data class Lexicon(
    val entries: List<LexEntry> = emptyList(),
    val rejected: List<LexRejected> = emptyList(),
    val suggestions: List<LexSuggestion> = emptyList(),
    @SerialName("import") val legacyImport: LexImport? = null,
)

/** `POST .../lexicon/entries` body; `PUT` adds [version]. */
@Serializable
data class LexEntryReq(val terms: List<String>, val meaning: String)

@Serializable
data class LexEntryPutReq(val terms: List<String>, val meaning: String, val version: Long)

@Serializable
data class LexTermReq(val term: String)

// -- card edit ---------------------------------------------------------------------------------------

/** How many recall queries a card must have. */
@Serializable
data class CiRange(val min: Int = 3, val max: Int = 5)

/** `GET .../cards/edit-options`: what a card edit may name, for the editor's pickers. */
@Serializable
data class CiEditOptions(
    val facets: List<String> = emptyList(),
    val detailKinds: List<String> = emptyList(),
    val detailKindsMax: Int = 3,
    val recallQueries: CiRange = CiRange(),
)

// The PUT body. Its parts have no defaults on purpose: the app's JSON leaves default values out, and the server
// wants every field of an edited card (an empty list or a false flag included).

@Serializable
data class CiEditAct(val kind: String, val intensity: Int, val resolved: Boolean)

@Serializable
data class CiEditFacet(val name: String, val confidence: Double)

@Serializable
data class CiEditDetail(val kinds: List<String>, val who: String, val text: String)

@Serializable
data class CiEditLex(val term: String, val meaning: String)

@Serializable
data class CiEditSub(
    val title: String,
    val summary: String,
    val said: List<String>,
    val meant: List<String>,
    val acts: List<CiEditAct>,
    val facets: List<CiEditFacet>,
    val details: List<CiEditDetail>,
)

@Serializable
data class CiEditTopic(
    val title: String,
    val summary: String,
    val said: List<String>,
    val meant: List<String>,
    val topics: List<String>,
    val acts: List<CiEditAct>,
    val facets: List<CiEditFacet>,
    val entities: List<String>,
    val recallQueries: List<String>,
    val details: List<CiEditDetail>,
    val lexicon: List<CiEditLex>,
)

/**
 * `PUT .../cards/{id}`: the edited card, its sub-topic cards, the indexes of the moments to keep (each once, in
 * order; moments can only be removed) and the [updatedAt] of the card as it was read.
 */
@Serializable
data class CiCardEdit(
    val card: CiEditTopic,
    val subtopics: List<CiEditSub>,
    val keepMoments: List<Int>,
    val updatedAt: Long,
)

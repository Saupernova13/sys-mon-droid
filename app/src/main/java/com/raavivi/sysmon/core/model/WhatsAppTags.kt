package com.raavivi.sysmon.core.model

import kotlinx.serialization.Serializable

// Wire shapes of the tag-search routes (`/api/whatsapp/chat-search/tags*`). Tags are chat-derived: a tag's
// value and label, and the card text beside them, are private and never logged.

/** A tag as the server sends it with a result: [value] goes back in a query, [label] is what is shown. */
@Serializable
data class WaTag(val cat: String = "", val value: String = "", val label: String = "")

/** A tag in a query: only what the server reads. */
@Serializable
data class WaTagRef(val cat: String, val value: String)

/**
 * `POST /api/whatsapp/chat-search/tags/query`. The lists, [sort], [limit] and [offset] have no defaults so
 * they are always sent (the JSON config leaves defaults out); the filters are left out when null.
 * [since] is inclusive and [until] exclusive, both unix seconds on the topic's start.
 */
@Serializable
data class WaTagsReq(
    val all: List<WaTagRef>,
    val any: List<WaTagRef>,
    val none: List<WaTagRef>,
    val sort: String,
    val limit: Int,
    val offset: Int,
    val chat: String? = null,
    val since: Long? = null,
    val until: Long? = null,
    val minIntensity: Int? = null,
    val unresolved: Boolean? = null,
)

/** One conversation the tag query found. [nodeId] is null when its topic no longer exists: open it by [cardId]. */
@Serializable
data class WaTagResult(
    val cardId: Long = 0,
    val nodeId: Long? = null,
    val chat: String = "",
    val family: List<String> = emptyList(),
    val chatName: String = "",
    val startTs: Long = 0,
    val startRowid: Long = 0,
    val endTs: Long = 0,
    val endRowid: Long = 0,
    val nMsgs: Int = 0,
    val title: String = "",
    val summary: String = "",
    val matched: List<WaTag> = emptyList(),
    val tags: List<WaTag> = emptyList(),
)

/** A tag with how many cards carry it (a facet entry or a type-ahead suggestion). */
@Serializable
data class WaTagCount(val cat: String = "", val value: String = "", val label: String = "", val count: Int = 0)

/** `facets` keys the eight categories; the entries carry no `cat` (it is the key). */
@Serializable
data class WaTagFacet(val value: String = "", val label: String = "", val count: Int = 0)

/** The query's answer: [total] counts every matching card, [results] is the page. */
@Serializable
data class WaTagsResponse(
    val total: Int = 0,
    val results: List<WaTagResult> = emptyList(),
    val facets: Map<String, List<WaTagFacet>> = emptyMap(),
)

/** How many distinct tags and cards a category has. */
@Serializable
data class WaTagCategory(val cat: String = "", val distinct: Int = 0, val cards: Int = 0)

/** `GET /api/whatsapp/chat-search/tags`: type-ahead. */
@Serializable
data class WaTagSuggestResponse(
    val tags: List<WaTagCount> = emptyList(),
    val categories: List<WaTagCategory> = emptyList(),
)

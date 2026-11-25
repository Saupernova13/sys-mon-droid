package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaConvCard
import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaTag
import com.raavivi.sysmon.core.model.WaTagFacet
import com.raavivi.sysmon.core.model.WaTagRef
import com.raavivi.sysmon.core.model.WaTagResult
import com.raavivi.sysmon.core.model.WaTagsReq
import java.time.LocalDate
import java.time.ZoneId

/**
 * The decisions behind the Tags search screen, with no UI: the category words, the selection (must have /
 * any of / exclude) and how a tap moves a tag between the groups, the filters, the query body, the facet
 * trimming and the result helpers. Port of the web whatsapp-tags-state.js. Tag values and labels are
 * chat-derived and private: nothing here logs.
 */

/** Category ids in the order they show, with the words the UI uses for them. */
val TAG_CATS: List<Pair<String, String>> = listOf(
    "topic" to "Topics",
    "entity" to "People, places & things",
    "who" to "Who",
    "facet" to "Themes",
    "act" to "What happened",
    "moment" to "Moments",
    "detail" to "Detail kinds",
    "term" to "Private words",
)

/** The words for category [cat]; an id the app does not know reads as itself. */
fun catLabel(cat: String): String = TAG_CATS.firstOrNull { it.first == cat }?.second ?: cat

/** The three groups a selected tag sits in, in the order a tap cycles them. The glyph keeps a group off colour alone. */
enum class TagGroup(val label: String, val glyph: String) {
    ALL("Must have", "+"),
    ANY("Any of", "~"),
    NONE("Exclude", "−"),
}

/** Results per page. */
const val TAGS_PAGE = 30

/** Facet tags a category shows, and the most it shows once expanded. */
const val FACET_SHOWN = 12
const val FACET_MAX = 30

/** Suggestions asked for per keystroke burst. */
const val SUGGEST_LIMIT = 8

/** Quiet time before the type-ahead and the query run. */
const val TAGS_SUGGEST_MS = 250L
const val TAGS_QUERY_MS = 250L

/** Other tags a result card shows beside the matched ones. */
const val OTHER_TAGS_SHOWN = 6

/** The sorts the server takes, with their words. */
enum class TagSort(val wire: String, val label: String) {
    NEWEST("newest", "Newest"),
    OLDEST("oldest", "Oldest"),
    LONGEST("longest", "Longest"),
    MATCHES("matches", "Most matched"),
}

/** The minimum act intensities the filter offers (0 = any). */
val TAG_INTENSITIES = listOf(2, 3, 4, 5)

/** A tag the user can select; [label] is what is shown, [value] what the server matches. */
data class TagPick(val cat: String, val value: String, val label: String = value) {
    val key: String get() = "$cat|$value"
}

fun WaTag.pick() = TagPick(cat, value, label.ifBlank { value })

/** The selected tags in their three groups. Immutable: every change returns a new one. */
data class TagSelection(
    val all: List<TagPick> = emptyList(),
    val any: List<TagPick> = emptyList(),
    val none: List<TagPick> = emptyList(),
) {
    fun of(g: TagGroup): List<TagPick> = when (g) {
        TagGroup.ALL -> all
        TagGroup.ANY -> any
        TagGroup.NONE -> none
    }

    val size: Int get() = all.size + any.size + none.size
    val isEmpty: Boolean get() = size == 0

    /** Which group [tag] is in, or null. */
    fun groupOf(tag: TagPick): TagGroup? = TagGroup.entries.firstOrNull { g -> of(g).any { it.key == tag.key } }

    /** A selection without [tag]. */
    fun without(tag: TagPick): TagSelection = TagSelection(
        all.filterNot { it.key == tag.key },
        any.filterNot { it.key == tag.key },
        none.filterNot { it.key == tag.key },
    )

    /** [tag] in [group] (moved there when it is elsewhere). */
    fun with(tag: TagPick, group: TagGroup = TagGroup.ALL): TagSelection {
        val base = without(tag)
        return when (group) {
            TagGroup.ALL -> base.copy(all = base.all + tag)
            TagGroup.ANY -> base.copy(any = base.any + tag)
            TagGroup.NONE -> base.copy(none = base.none + tag)
        }
    }

    /** Must have, then any of, then exclude, then must have again; a tag not selected stays out. */
    fun cycled(tag: TagPick): TagSelection {
        val g = groupOf(tag) ?: return this
        return with(tag, TagGroup.entries[(g.ordinal + 1) % TagGroup.entries.size])
    }
}

/** The filters of the bottom sheet. [since] and [until] are whole days; [minIntensity] 0 means any. */
data class TagFilters(
    val chat: String = "",
    val since: LocalDate? = null,
    val until: LocalDate? = null,
    val minIntensity: Int = 0,
    val unresolved: Boolean = false,
    val sort: TagSort = TagSort.NEWEST,
)

/** Sorting by matches means nothing without any-of tags: it falls back to newest. */
fun effectiveSort(sel: TagSelection, filters: TagFilters): TagSort =
    if (filters.sort == TagSort.MATCHES && sel.any.isEmpty()) TagSort.NEWEST else filters.sort

/** How many filters are set, for the "Filters" button's badge. */
fun filterCount(f: TagFilters): Int {
    val d = TagFilters()
    return listOf(
        f.chat != d.chat, f.since != d.since, f.until != d.until,
        f.minIntensity != d.minIntensity, f.unresolved != d.unresolved, f.sort != d.sort,
    ).count { it }
}

/** The POST body for the tags query. `until` is exclusive, so the chosen day is included. */
fun buildTagsQuery(
    sel: TagSelection,
    f: TagFilters,
    offset: Int = 0,
    limit: Int = TAGS_PAGE,
    zone: ZoneId = ZoneId.systemDefault(),
): WaTagsReq {
    fun refs(l: List<TagPick>) = l.map { WaTagRef(it.cat, it.value) }
    return WaTagsReq(
        all = refs(sel.all),
        any = refs(sel.any),
        none = refs(sel.none),
        sort = effectiveSort(sel, f).wire,
        limit = limit,
        offset = offset,
        chat = f.chat.ifBlank { null },
        since = f.since?.atStartOfDay(zone)?.toEpochSecond(),
        until = f.until?.plusDays(1)?.atStartOfDay(zone)?.toEpochSecond(),
        minIntensity = f.minIntensity.takeIf { it >= 1 },
        unresolved = if (f.unresolved) true else null,
    )
}

/** A facet entry as a selectable tag of [cat]. */
fun WaTagFacet.pick(cat: String) = TagPick(cat, value, label.ifBlank { value })

/** The facet tags a category shows: 12, or up to 30 once expanded; [more] is how many "Show more" adds. */
data class FacetWindow(val shown: List<WaTagFacet>, val more: Int)

fun facetWindow(tags: List<WaTagFacet>, expanded: Boolean): FacetWindow {
    val shown = tags.take(if (expanded) FACET_MAX else FACET_SHOWN)
    return FacetWindow(shown, if (!expanded && tags.size > FACET_SHOWN) minOf(tags.size, FACET_MAX) - FACET_SHOWN else 0)
}

/** The categories the browse list shows: just [cat] when a category chip is on, else all of them. */
fun browseCats(cat: String): List<String> = if (cat.isBlank()) TAG_CATS.map { it.first } else listOf(cat)

/** The tags of [r] worth a chip beside the matched ones: not the matched, at most [n]. */
fun otherTags(r: WaTagResult, n: Int = OTHER_TAGS_SHOWN): List<WaTag> {
    val have = r.matched.map { it.cat to it.value }.toSet()
    return r.tags.filter { (it.cat to it.value) !in have }.take(n)
}

/** Chats seen so far, for the chat filter: jid to name, in the order first seen. */
fun noteChats(known: Map<String, String>, results: List<WaTagResult>): Map<String, String> {
    val out = LinkedHashMap(known)
    for (r in results) if (r.chat.isNotBlank() && r.chat !in out) out[r.chat] = r.chatName.ifBlank { r.chat }
    return out
}

/** The header over the results. */
fun tagsTotalText(n: Int): String = if (n == 1) "1 conversation" else "$n conversations"

/** Whether another page can be asked for: the last page had rows and not everything is loaded yet. */
fun hasMoreTags(loaded: Int, total: Int, lastPage: Int): Boolean = lastPage > 0 && loaded < total

/** The result as the conversation the card sheet and "Open conversation" take; its card text is the tag result's own. */
fun WaTagResult.asConv(): WaConvResult = WaConvResult(
    nodeId = nodeId ?: 0,
    chat = chat,
    family = family,
    chatName = chatName,
    startTs = startTs,
    startRowid = startRowid,
    endTs = endTs,
    endRowid = endRowid,
    nMsgs = nMsgs,
    card = WaConvCard(id = cardId, title = title.trim(), summary = summary.trim()),
)

/** The guide's rows: a line and its hint. */
val TagsGuide = listOf(
    GuideRow("Pick tags to narrow it down", "Type a tag or tap one below; each one you add must be on the conversation"),
    GuideRow("Choose what a tap does", "The Tap adds row sets Must have, Any of or Exclude for the next tag you pick"),
    GuideRow("Change your mind under the field", "Tap a chip to drop it; Filters moves it between the groups"),
    GuideRow("Open a result for its full card", "Check it's the right one, then Show in chat"),
)

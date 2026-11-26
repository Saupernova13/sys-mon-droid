package com.raavivi.sysmon.ui.chatindex

import com.raavivi.sysmon.core.model.CiEditOptions

/**
 * What the card editor says about a draft before Save, and which values a picker offers. Hints never block
 * Save: the server is the authority and answers with its own reason. Card text is private: nothing here logs.
 */

/** The hints of one draft: by part id for titles and summaries, by item id for items, plus the recall count. */
data class EditHints(
    val titles: Map<Long, String> = emptyMap(),
    val summaries: Map<Long, String> = emptyMap(),
    val items: Map<Long, String> = emptyMap(),
    val recall: String? = null,
) {
    val count: Int get() = titles.size + summaries.size + items.size + (if (recall != null) 1 else 0)
}

/** Confidence is a share: 0 to 1. */
val CONFIDENCE_RANGE = 0.0..1.0

fun hintsOf(d: CardDraft, o: CiEditOptions): EditHints {
    val titles = HashMap<Long, String>()
    val summaries = HashMap<Long, String>()
    val items = HashMap<Long, String>()
    for (p in listOf(d.main) + d.subs) {
        if (p.title.isBlank()) titles[p.id] = "Add a title"
        if (p.summary.isBlank()) summaries[p.id] = "Add a summary"
        for (f in p.facets) facetHint(f)?.let { items[f.id] = it }
        for (x in p.details) if (x.kinds.isEmpty() && x.who.isBlank() && x.text.isBlank()) {
            items[x.id] = "A detail needs a kind, a person or some text"
        }
    }
    for (w in d.words) when {
        w.term.isBlank() && w.meaning.isBlank() -> items[w.id] = "Add the word and what it means"
        w.term.isBlank() -> items[w.id] = "Add the word"
        w.meaning.isBlank() -> items[w.id] = "Add what it means"
    }
    val n = d.recall.count { it.text.isNotBlank() }
    val recall = if (n in o.recallQueries.min..o.recallQueries.max) null else "${o.recallQueries.min} to ${o.recallQueries.max} lines needed, there are $n"
    return EditHints(titles, summaries, items, recall)
}

private fun facetHint(f: FacetDraft): String? = when {
    f.name.isBlank() -> "Pick a facet"
    f.confidence !in CONFIDENCE_RANGE -> "Confidence must be between 0 and 1"
    else -> null
}

/** The facets a picker offers: the configured ones, then those this card holds that are not configured. */
fun facetChoices(o: CiEditOptions, d: CardDraft): List<String> =
    (o.facets + (listOf(d.main) + d.subs).flatMap { p -> p.facets.map { it.name } }).filter { it.isNotBlank() }.distinct()

/** The detail kinds a picker offers: the configured ones, then those the card holds that are not configured. */
fun kindChoices(o: CiEditOptions, d: CardDraft): List<String> =
    (o.detailKinds + (listOf(d.main) + d.subs).flatMap { p -> p.details.flatMap { it.kinds } }).filter { it.isNotBlank() }.distinct()

/** Pick or unpick [kind]; picking past [max] changes nothing. */
fun toggleKind(kinds: List<String>, kind: String, max: Int): List<String> = when {
    kind in kinds -> kinds - kind
    kinds.size >= max -> kinds
    else -> kinds + kind
}

/** Whether no more kinds can be picked, so the unpicked chips are off. */
fun kindsFull(kinds: List<String>, max: Int): Boolean = kinds.size >= max

/** "Up to 3 kinds". */
fun kindsLimitText(max: Int): String = "Up to $max ${if (max == 1) "kind" else "kinds"}"

/** A section header's count ("Said · 251"); no count shows the title alone. */
fun sectionTitle(title: String, count: Int?): String = if (count == null) title else "$title · $count"

/** What a collapsed section shows under its header: the first non-blank line, else nothing. */
fun previewOf(lines: List<String>): String = lines.firstOrNull { it.isNotBlank() }.orEmpty()

/** A confidence as the slider's monospace label ("0.80"). */
fun confidenceText(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

/** The slider's value snapped to its 0.05 steps. */
fun snapConfidence(v: Double): Double = Math.round(v.coerceIn(0.0, 1.0) * 20) / 20.0

/** The Edited chip's label: "Edited · 12 Mar 2026 14:05", or just "Edited" when the time is not known. */
fun editedLabel(editedAt: Long?): String = if (editedAt == null || editedAt <= 0) "Edited" else "Edited · ${momentText(editedAt)}"

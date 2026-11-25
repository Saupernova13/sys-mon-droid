package com.raavivi.sysmon.ui.whatsapp

/**
 * The Search view's folded filters, as pure decisions: which modes have any, how many are set (the badge on
 * the "Filters" pill) and the compact chips for the selected tags. No UI here.
 */

/** Conversations has no filters; Messages has its chips, Tags its category and refinements. */
fun modeHasFilters(mode: SearchMode): Boolean = mode != SearchMode.CONVERSATIONS

/** Messages filters set: the sender chip and the type chip (0 to 2). */
fun messageFilterCount(sel: ChipSel): Int = listOfNotNull(sel.sender, sel.media).size

/** Tags filters set: the sheet's refinements plus the category when one is chosen. */
fun tagsFilterCount(f: TagFilters, cat: String): Int = filterCount(f) + if (cat.isNotBlank()) 1 else 0

/** How many filters [mode] has set, for the badge. */
fun activeFilterCount(mode: SearchMode, chips: ChipSel, tagFilters: TagFilters, tagCat: String): Int = when (mode) {
    SearchMode.CONVERSATIONS -> 0
    SearchMode.MESSAGES -> messageFilterCount(chips)
    SearchMode.TAGS -> tagsFilterCount(tagFilters, tagCat)
}

/** The pill's text: "Filters", or "Filters · 2" with filters set. */
fun filtersLabel(count: Int): String = if (count > 0) "Filters · $count" else "Filters"

/** What a screen reader says for the pill. */
fun filtersDescription(count: Int): String = when (count) {
    0 -> "Filters"
    1 -> "Filters, 1 set"
    else -> "Filters, $count set"
}

/** A selected tag with the group it sits in, one chip of the compact row. */
data class SelectedTagChip(val tag: TagPick, val group: TagGroup)

/** The selected tags as one flat row of chips: Must have, then Any of, then Exclude. */
fun selectedTagChips(sel: TagSelection): List<SelectedTagChip> =
    TagGroup.entries.flatMap { g -> sel.of(g).map { SelectedTagChip(it, g) } }

/** A compact chip's text: the group's glyph keeps Must have / Any of / Exclude apart without colour. */
fun tagChipText(c: SelectedTagChip): String = "${c.group.glyph} ${c.tag.label}"

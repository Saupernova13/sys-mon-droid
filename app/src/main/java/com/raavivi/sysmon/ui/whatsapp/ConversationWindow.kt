package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaMessage

/** What a live frame did to the window; anything but [ROW_UPDATED] / [REACTION_FOLDED] also touches the chat list. */
enum class LiveEffect { REACTION_FOLDED, ROW_UPDATED, APPENDED, NONE }

/** A live frame applied to a window; [effect] tells the caller what else to refresh. */
data class LiveApplied(val window: ConversationWindow, val effect: LiveEffect)

/** A fetched page folded into a window; [added] is how many of its rows were new. */
data class PageMerged(val window: ConversationWindow, val added: Int)

/**
 * The loaded slice of one conversation and the rules for changing it. Immutable and Android-free: every
 * function returns the next window, so paging, jumps and live frames unit-test on the JVM. The view model
 * only orchestrates requests and assigns the result.
 *
 * [atLatest]: the slice includes the chat's newest message. [reachedFirst]: its oldest message is loaded.
 * [freshIds]: rows that arrived live or were just sent, the only ones that animate in.
 */
data class ConversationWindow(
    val messages: List<WaMessage> = emptyList(),
    val reactions: WaReactions = emptyMap(),
    val freshIds: Set<String> = emptySet(),
    val atLatest: Boolean = true,
    val reachedFirst: Boolean = false,
) {
    /** Timestamp of the oldest loaded row, the base of the older cursor; null when there is none to page from. */
    val oldestTs: Double? get() = messages.firstOrNull()?.takeIf { it.ts > 0 }?.ts

    /** Timestamp of the newest loaded row, the base of the newer cursor. */
    val newestTs: Double? get() = messages.lastOrNull()?.takeIf { it.ts > 0 }?.ts

    /** The same chat reopened: rows stay until the first page replaces them, everything else starts over. */
    fun reopened(): ConversationWindow =
        copy(reactions = emptyMap(), freshIds = emptySet(), atLatest = true, reachedFirst = false)

    /**
     * The chat's newest page replaces the slice. While following the bottom, the few genuinely new
     * rows (at most five) become fresh so they animate in.
     */
    fun replacedByNewest(page: List<WaMessage>): ConversationWindow {
        val known = if (atLatest) messages.mapTo(HashSet()) { it.id } else null
        val sorted = page.sortedBy { it.ts }
        val added = if (known == null || known.isEmpty()) emptyList()
        else sorted.filter { it.id.isNotBlank() && it.id !in known && isVisibleMessage(it) }
        return ConversationWindow(
            messages = sorted,
            reactions = waAggregateReactions(page),
            freshIds = if (added.size <= MAX_FRESH) added.mapTo(HashSet()) { it.id } else emptySet(),
            atLatest = true,
            reachedFirst = false,
        )
    }

    /** The newest page folded into the slice (catching up live rows missed while browsing elsewhere). */
    fun mergedWithNewest(page: List<WaMessage>): ConversationWindow =
        merged(page).window.copy(atLatest = true)

    /**
     * A page fetched from an arbitrary point (the oldest page, or the page of a chosen day) replaces the
     * slice. It is the latest only if it came back short.
     */
    fun replacedByPage(page: List<WaMessage>, reachedFirst: Boolean): ConversationWindow =
        ConversationWindow(
            messages = page.sortedBy { it.ts },
            reactions = waAggregateReactions(page),
            freshIds = emptySet(),
            atLatest = page.size < WA_PAGE_LIMIT,
            reachedFirst = reachedFirst,
        )

    /** An older or newer page folded in: rows deduped by id, ordered by time, its reactions folded. */
    fun merged(page: List<WaMessage>): PageMerged {
        val (rows, added) = mergeMessages(messages, page)
        return PageMerged(copy(messages = rows, reactions = waAggregateReactions(page, reactions)), added)
    }

    fun withReachedFirst(): ConversationWindow = copy(reachedFirst = true)

    fun clearedFresh(): ConversationWindow = copy(freshIds = emptySet())

    /** Apply one live frame; [inOpenChat] is whether it belongs to the conversation this window shows. */
    fun applyLive(msg: WaMessage, inOpenChat: Boolean): LiveApplied {
        // A reaction is a fact about another message: it updates its chip, never a row.
        if (msg.isReaction) {
            val next = if (inOpenChat) {
                copy(reactions = waApplyReaction(reactions, msg.reactionToId, waReactorKey(msg), msg.reactionEmoji, msg.ts))
            } else this
            return LiveApplied(next, LiveEffect.REACTION_FOLDED)
        }
        // An edit or a delete carries the original id: update that row in place, never add a second.
        if ((msg.edited || msg.revoked) && msg.id.isNotBlank()) {
            val next = if (inOpenChat) waApplyLiveUpdate(messages, msg)?.let { copy(messages = it) } ?: this else this
            return LiveApplied(next, LiveEffect.ROW_UPDATED)
        }
        // Append only while the slice reaches the newest message; otherwise loadNewer's final merge picks it up.
        if (inOpenChat && atLatest && messages.none { it.id == msg.id && it.id.isNotBlank() }) {
            val next = copy(
                messages = (messages + msg).sortedBy { it.ts },
                freshIds = if (msg.id.isNotBlank()) freshIds + msg.id else freshIds,
            )
            return LiveApplied(next, LiveEffect.APPENDED)
        }
        return LiveApplied(this, LiveEffect.NONE)
    }

    /** Optimistic reaction ([emoji] empty removes it). */
    fun withReaction(targetId: String, reactor: String, emoji: String, ts: Double): ConversationWindow =
        copy(reactions = waApplyReaction(reactions, targetId, reactor, emoji, ts))

    /** Optimistic delete: revoked for everyone (its reactions go too), or hidden for me. */
    fun withRemoved(id: String, everyone: Boolean): ConversationWindow = copy(
        messages = messages.map {
            when {
                it.id != id -> it
                everyone -> it.copy(revoked = true)
                else -> it.copy(deletedForMe = true)
            }
        },
        reactions = if (everyone) reactions - id else reactions,
    )

    /** Optimistic edit: new text and the "edited" flag. */
    fun withEdited(id: String, text: String): ConversationWindow =
        copy(messages = messages.map { if (it.id == id) it.copy(text = text, edited = true) else it })

    companion object {
        private const val MAX_FRESH = 5

        /** `before=` for the page older than [oldestTs]: inclusive first, strict on the retry. */
        fun olderCursor(oldestTs: Double, strict: Boolean): String =
            if (strict) waBeforeCursor(oldestTs) else waInclusiveBefore(oldestTs)

        /** `after=` for the page newer than [newestTs]: inclusive first, strict on the retry. */
        fun newerCursor(newestTs: Double, strict: Boolean): String =
            if (strict) waBeforeCursor(newestTs) else waInclusiveAfter(newestTs)

        /**
         * How a paging request ended. [PageOutcome.STALLED] on the first attempt means retry once with the
         * strict cursor; on the retry it is the end, so callers only ever see MORE or END after that.
         */
        fun settle(rawRows: Int, added: Int, strictRetry: Boolean): PageOutcome {
            val outcome = pageOutcome(rawRows, added, WA_PAGE_LIMIT)
            return if (outcome == PageOutcome.STALLED && strictRetry) PageOutcome.END else outcome
        }
    }
}

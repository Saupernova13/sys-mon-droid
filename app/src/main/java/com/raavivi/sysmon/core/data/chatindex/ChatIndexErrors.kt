package com.raavivi.sysmon.core.data.chatindex

import com.raavivi.sysmon.core.net.ApiResult

/**
 * The typed reason behind a failed chat-index call (`docs/API.md`, "explore" and "lexicon"). The server's
 * reasons are fixed sentences about the request, never about a value, so [Refused] may show its text.
 */
sealed interface ChatIndexError {
    /** 404 "chat search is off". */
    data object Off : ChatIndexError

    /** 404 for a node, card or entry that is not there (any more). */
    data object Missing : ChatIndexError

    /** 409 `stale`: the entry changed since the version the client read. */
    data object Stale : ChatIndexError

    /** 409 `duplicate`: a word is already taken (the server never says which). */
    data object Duplicate : ChatIndexError

    /** 409 `not_manual`: only a card the owner asked for can be deleted. */
    data object NotManual : ChatIndexError

    /** 409 `not_done`: a failed card has nothing to edit. */
    data object NotDone : ChatIndexError

    /** 500 "the edited card could not be read back": the edit was saved, the reply could not be built. */
    data object ReadBack : ChatIndexError

    /** 400, with the server's reason. */
    data class Refused(val reason: String) : ChatIndexError

    /** 503 `messages unavailable`: the message store cannot be read right now. */
    data object Unavailable : ChatIndexError

    data class Other(val message: String) : ChatIndexError
}

private val OFF_WORD = Regex("""\boff\b""", RegexOption.IGNORE_CASE)

/** True for the 404 that means chat search is off on the server, not that one row is missing. */
fun isChatSearchOff(err: ApiResult.Err): Boolean = err.code == 404 && OFF_WORD.containsMatchIn(err.message)

fun chatIndexErrorOf(err: ApiResult.Err): ChatIndexError = when (err.code) {
    400 -> ChatIndexError.Refused(err.message.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") } ?: "The request was refused")
    404 -> if (isChatSearchOff(err)) ChatIndexError.Off else ChatIndexError.Missing
    409 -> when (err.apiCode) {
        "stale" -> ChatIndexError.Stale
        "duplicate" -> ChatIndexError.Duplicate
        "not_manual" -> ChatIndexError.NotManual
        "not_done" -> ChatIndexError.NotDone
        else -> ChatIndexError.Other(err.message)
    }
    503 -> ChatIndexError.Unavailable
    500 -> if (err.message.contains("read back", ignoreCase = true)) ChatIndexError.ReadBack else ChatIndexError.Other(err.message)
    else -> ChatIndexError.Other(err.message)
}

/** What a failed card edit says, in one sentence (the server's reasons are fixed sentences, never the text). */
fun editErrorText(err: ApiResult.Err): String = when (val e = chatIndexErrorOf(err)) {
    ChatIndexError.Stale -> "This card changed since you opened it. Reload it to see the latest, then edit again."
    ChatIndexError.NotDone -> "A failed card has nothing to edit."
    ChatIndexError.Missing -> "This card is no longer there."
    ChatIndexError.Off -> "Chat search is off on the server."
    ChatIndexError.ReadBack ->
        "The edit was saved, but the card could not be read back. Reload the card to see it; do not save again."
    ChatIndexError.Unavailable -> "The card store cannot be used right now. Try again in a moment."
    is ChatIndexError.Refused -> e.reason.trim().replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }
    else -> when {
        err.code == null -> "Could not reach the server. Check the connection and try again."
        else -> "The card could not be saved. Try again."
    }
}

/** Whether a failed edit should offer Reload card (the card moved on, or the edit landed without a reply). */
fun editNeedsReload(err: ApiResult.Err): Boolean = chatIndexErrorOf(err).let { it == ChatIndexError.Stale || it == ChatIndexError.ReadBack }

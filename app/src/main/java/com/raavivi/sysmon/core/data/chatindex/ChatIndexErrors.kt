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
        else -> ChatIndexError.Other(err.message)
    }
    503 -> ChatIndexError.Unavailable
    else -> ChatIndexError.Other(err.message)
}

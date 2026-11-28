package com.raavivi.sysmon.ui.whatsapp

import com.raavivi.sysmon.core.model.WaConvResult
import com.raavivi.sysmon.core.model.WaSimilarResponse
import com.raavivi.sysmon.core.net.ApiResult

/** "More like this" for one shown card: asking, answered, or failed. */
enum class SimilarPhase { LOADING, DONE, ERROR }

/** The conversations most like a card ([items], none is a real answer) or why they could not be found ([message]). */
data class SimilarList(
    val phase: SimilarPhase,
    val items: List<WaConvResult> = emptyList(),
    val message: String? = null,
)

/** Shown when a failed request has no words of its own. */
const val SIMILAR_FAILED = "Could not find similar conversations"

/** How many rows the list shows at most (the server sends at most this many too). */
const val SIMILAR_MAX = 8

/** One conversation opened from "More like this": [conv] is the card shown, [prev] the sheet to come back to. */
data class SimilarFrame(val conv: WaConvResult, val prev: CardSheet)

/** The state a request's answer leaves: the server's top rows, or its own reason (else [SIMILAR_FAILED]) as an error. */
fun similarListOf(r: ApiResult<WaSimilarResponse>): SimilarList = when (r) {
    is ApiResult.Ok -> SimilarList(SimilarPhase.DONE, r.value.results.take(SIMILAR_MAX))
    is ApiResult.Err -> SimilarList(SimilarPhase.ERROR, message = r.message.trim().ifBlank { SIMILAR_FAILED })
}

/** A row's title: the card's title, or the chat's name when the result has no card. */
fun similarRowTitle(r: WaConvResult, chatName: String): String =
    r.card?.title?.takeIf { it.isNotBlank() } ?: chatName

/** A row's second line: "<chat name> · <range>". */
fun similarRowMeta(chatName: String, range: String): String = "$chatName · $range"

/** "Found it" belongs to the result list: a card opened from "More like this" has none. */
fun foundOffered(similarDepth: Int): Boolean = similarDepth == 0

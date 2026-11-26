package com.raavivi.sysmon

import com.raavivi.sysmon.core.data.chatindex.ChatIndexRepository
import com.raavivi.sysmon.core.model.CiCardFull
import com.raavivi.sysmon.core.model.CiCardsPage
import com.raavivi.sysmon.core.model.CiFamiliesPage
import com.raavivi.sysmon.core.model.CiMessagesPage
import com.raavivi.sysmon.core.model.CiNodeDetail
import com.raavivi.sysmon.core.model.CiNodesPage
import com.raavivi.sysmon.core.model.CiOverview
import com.raavivi.sysmon.core.model.CiStorage
import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexRejected
import com.raavivi.sysmon.core.model.Lexicon
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.ui.chatindex.foldTerm

/** What a 404 "chat search is off" looks like to a client (the envelope's message, no `code`). */
val OFF_ERR = ApiResult.Err("chat search is off", 404)

data class SavedEntry(val id: Long, val version: Long, val terms: List<String>, val meaning: String)

/**
 * Scriptable in-memory [ChatIndexRepository]. The explore reads answer from the `*Responder` / `*Result`
 * properties; the glossary is a small real store (versions, duplicate words across entries and the rejected
 * list) so 409 handling is exercised the way the server behaves. [failNext] makes the next write fail.
 */
class FakeChatIndexRepository : ChatIndexRepository {
    var overviewResult: ApiResult<CiOverview> = ApiResult.Ok(CiOverview(indexExists = true))
    var familiesResponder: (Int) -> ApiResult<CiFamiliesPage> = { ApiResult.Ok(CiFamiliesPage()) }
    var sessionsResponder: (Long, Int) -> ApiResult<CiNodesPage> = { _, _ -> ApiResult.Ok(CiNodesPage()) }
    var childrenResponder: (Long, Int) -> ApiResult<CiNodesPage> = { _, _ -> ApiResult.Ok(CiNodesPage()) }
    var nodeResult: ApiResult<CiNodeDetail> = ApiResult.Ok(CiNodeDetail())
    var messagesResponder: (Long, Int) -> ApiResult<CiMessagesPage> = { id, _ -> ApiResult.Ok(CiMessagesPage(id, emptyList(), null)) }
    var cardsResponder: (Long, Int) -> ApiResult<CiCardsPage> = { _, _ -> ApiResult.Ok(CiCardsPage()) }
    val cardResults = HashMap<Long, ApiResult<CiCardFull>>()
    var storageResult: ApiResult<CiStorage> = ApiResult.Ok(CiStorage())
    var deleteCardResult: ApiResult<Unit> = ApiResult.Ok(Unit)

    val familiesCalls = mutableListOf<Int>()
    val sessionsCalls = mutableListOf<Pair<Long, Int>>()
    val childrenCalls = mutableListOf<Pair<Long, Int>>()
    val messagesCalls = mutableListOf<Pair<Long, Int>>()
    val cardsCalls = mutableListOf<Pair<Long, Int>>()
    val cardCalls = mutableListOf<Long>()
    val deletedCards = mutableListOf<Long>()
    var overviewCalls = 0
    var storageCalls = 0

    // -- the glossary store ---------------------------------------------------------------------

    val entries = mutableListOf<LexEntry>()
    val rejectedTerms = mutableListOf<LexRejected>()
    var suggestions = mutableListOf<com.raavivi.sysmon.core.model.LexSuggestion>()
    var lexiconReadResult: ApiResult.Err? = null
    var failNext: ApiResult.Err? = null
    private var nextId = 1L

    val added = mutableListOf<Pair<List<String>, String>>()
    val saved = mutableListOf<SavedEntry>()
    val deletedEntries = mutableListOf<Pair<Long, Long>>()
    val rejectCalls = mutableListOf<String>()
    val unrejectCalls = mutableListOf<String>()
    var lexiconReads = 0

    /** Put an entry in the store as it would be after another client added it. */
    fun seed(terms: List<String>, meaning: String): LexEntry {
        val e = LexEntry(id = nextId++, version = 1, terms = terms, meaning = meaning)
        entries += e
        return e
    }

    /** Another client edits [id]: the version goes up. */
    fun bump(id: Long) {
        val i = entries.indexOfFirst { it.id == id }
        entries[i] = entries[i].copy(version = entries[i].version + 1)
    }

    private fun taken(terms: List<String>, ignore: Long?): Boolean {
        val held = HashSet<String>()
        entries.filter { it.id != ignore }.forEach { e -> e.terms.forEach { held += foldTerm(it) } }
        rejectedTerms.forEach { held += foldTerm(it.term) }
        return terms.any { foldTerm(it) in held }
    }

    private fun failure(): ApiResult.Err? = failNext.also { failNext = null }

    override suspend fun overview(): ApiResult<CiOverview> {
        overviewCalls++
        return overviewResult
    }

    override suspend fun families(offset: Int, limit: Int): ApiResult<CiFamiliesPage> {
        familiesCalls += offset
        return familiesResponder(offset)
    }

    override suspend fun sessions(family: Long, offset: Int, limit: Int): ApiResult<CiNodesPage> {
        sessionsCalls += family to offset
        return sessionsResponder(family, offset)
    }

    override suspend fun children(parent: Long, offset: Int, limit: Int): ApiResult<CiNodesPage> {
        childrenCalls += parent to offset
        return childrenResponder(parent, offset)
    }

    override suspend fun node(id: Long): ApiResult<CiNodeDetail> = nodeResult

    override suspend fun nodeMessages(id: Long, offset: Int, limit: Int): ApiResult<CiMessagesPage> {
        messagesCalls += id to offset
        return messagesResponder(id, offset)
    }

    override suspend fun cards(family: Long, offset: Int, limit: Int): ApiResult<CiCardsPage> {
        cardsCalls += family to offset
        return cardsResponder(family, offset)
    }

    override suspend fun card(id: Long): ApiResult<CiCardFull> {
        cardCalls += id
        return cardResults[id] ?: ApiResult.Err("no such card", 404)
    }

    override suspend fun storage(): ApiResult<CiStorage> {
        storageCalls++
        return storageResult
    }

    override suspend fun deleteCard(id: Long): ApiResult<Unit> {
        deletedCards += id
        return deleteCardResult
    }

    override suspend fun lexicon(): ApiResult<Lexicon> {
        lexiconReads++
        lexiconReadResult?.let { return it }
        return ApiResult.Ok(Lexicon(entries.toList(), rejectedTerms.toList(), suggestions.toList(), null))
    }

    override suspend fun addEntry(terms: List<String>, meaning: String): ApiResult<LexEntry> {
        added += terms to meaning
        failure()?.let { return it }
        if (taken(terms, null)) return ApiResult.Err("a word is already taken", 409, "duplicate")
        return ApiResult.Ok(seed(terms, meaning))
    }

    override suspend fun saveEntry(id: Long, version: Long, terms: List<String>, meaning: String): ApiResult<LexEntry> {
        saved += SavedEntry(id, version, terms, meaning)
        failure()?.let { return it }
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return ApiResult.Err("no such entry", 404)
        if (entries[i].version != version) return ApiResult.Err("the entry changed", 409, "stale")
        if (taken(terms, id)) return ApiResult.Err("a word is already taken", 409, "duplicate")
        entries[i] = entries[i].copy(terms = terms, meaning = meaning, version = version + 1)
        return ApiResult.Ok(entries[i])
    }

    override suspend fun deleteEntry(id: Long, version: Long): ApiResult<Unit> {
        deletedEntries += id to version
        failure()?.let { return it }
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return ApiResult.Err("no such entry", 404)
        if (entries[i].version != version) return ApiResult.Err("the entry changed", 409, "stale")
        entries.removeAt(i)
        return ApiResult.Ok(Unit)
    }

    override suspend fun reject(term: String): ApiResult<LexRejected> {
        rejectCalls += term
        failure()?.let { return it }
        if (taken(listOf(term), null) && rejectedTerms.none { foldTerm(it.term) == foldTerm(term) }) {
            return ApiResult.Err("a word is already taken", 409, "duplicate")
        }
        suggestions.removeAll { foldTerm(it.term) == foldTerm(term) }
        val r = LexRejected(term, 1)
        rejectedTerms += r
        return ApiResult.Ok(r)
    }

    override suspend fun unreject(term: String): ApiResult<Unit> {
        unrejectCalls += term
        failure()?.let { return it }
        if (!rejectedTerms.removeAll { foldTerm(it.term) == foldTerm(term) }) return ApiResult.Err("not rejected", 404)
        return ApiResult.Ok(Unit)
    }
}

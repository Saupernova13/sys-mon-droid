package com.raavivi.sysmon.ui.chatindex

import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexImport
import com.raavivi.sysmon.core.model.LexRejected
import com.raavivi.sysmon.core.model.LexSuggestion
import java.util.Locale

/**
 * The private-words glossary editor's decisions, with no UI. The server is the authority on what a term may be
 * (docs/API.md, "lexicon"); these rules mirror it so the form can say no before a round trip. A term holds ANY
 * printable character, commas included, so a term is only ever added whole, never split. Words and meanings are
 * private: nothing here logs.
 */

const val LEX_MAX_TERM = 80
const val LEX_MAX_MEANING = 300
const val LEX_MAX_TERMS = 10

private val LINE_BREAK = Regex("[\\n\\r\\u0085\\u2028\\u2029]")
private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]")
private val BIDI = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")
private val INVISIBLE = Regex(
    "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180E\\u200B-\\u200F\\u2060-\\u2064" +
        "\\u206A-\\u206F\\u3164\\uFE00-\\uFE0F\\uFEFF\\uFFA0]",
)
private val WHITESPACE = Regex("[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

/** Which kind of text is being checked; it picks the noun and the length limit. */
enum class LexKind(val noun: String, val max: Int) {
    WORD("a word", LEX_MAX_TERM),
    MEANING("a meaning", LEX_MAX_MEANING),
}

/** Trimmed, every run of whitespace one space. */
fun normText(s: String): String = s.replace(WHITESPACE, " ").trim(' ')

/** The key two terms are compared by: case-insensitive, invisible characters ignored. */
fun foldTerm(s: String): String = normText(s).replace(INVISIBLE, "").lowercase(Locale.ROOT)

/** "A word cannot be empty": the first letter up. */
fun sentence(s: String): String = if (s.isEmpty()) s else s.replaceFirstChar { it.uppercase() }

/** Why [raw] cannot be a word or a meaning, in the server's wording; "" when it can. */
fun textProblem(raw: String, kind: LexKind): String {
    val noun = kind.noun
    if (LINE_BREAK.containsMatchIn(raw)) return "$noun cannot contain a line break"
    if (CONTROL.containsMatchIn(raw)) return "$noun cannot contain control characters"
    if (BIDI.containsMatchIn(raw)) return "$noun cannot contain text-direction override characters"
    val t = normText(raw)
    if (t.replace(INVISIBLE, "").isEmpty()) return "$noun cannot be empty"
    if (t.codePointCount(0, t.length) > kind.max) return "$noun is too long (at most ${kind.max} characters)"
    return ""
}

/** The result of adding one typed word to a draft: the [terms] after it and an [error] ("" for none). */
data class AddTermResult(val terms: List<String>, val error: String = "")

/**
 * Add one typed term whole. On a refusal the terms are unchanged and [AddTermResult.error] says why. Typing
 * nothing is a no-op, not an error. A comma is just a character: this never splits.
 */
fun addTerm(terms: List<String>, raw: String): AddTermResult {
    if (raw.isBlank()) return AddTermResult(terms)
    val bad = textProblem(raw, LexKind.WORD)
    if (bad.isNotEmpty()) return AddTermResult(terms, sentence(bad))
    if (terms.size >= LEX_MAX_TERMS) return AddTermResult(terms, "An entry holds at most $LEX_MAX_TERMS words")
    val t = normText(raw)
    if (terms.any { foldTerm(it) == foldTerm(t) }) return AddTermResult(terms, "That word is already in this entry")
    return AddTermResult(terms + t)
}

fun removeTerm(terms: List<String>, index: Int): List<String> = terms.filterIndexed { i, _ -> i != index }

/** An entry as the form holds it. */
data class LexDraft(val terms: List<String> = emptyList(), val meaning: String = "")

fun draftOfEntry(e: LexEntry) = LexDraft(e.terms, e.meaning)

/** Accepting a suggestion prefills the add form with its word and the meaning the writer gave. */
fun draftOfSuggestion(s: LexSuggestion) = LexDraft(listOf(s.term), s.meaning.orEmpty())

/** What a form may not be submitted with: a reason per field, "" for fine. */
data class EntryProblems(val terms: String, val meaning: String) {
    val any: Boolean get() = terms.isNotEmpty() || meaning.isNotEmpty()
}

fun entryProblems(d: LexDraft): EntryProblems = EntryProblems(
    terms = if (d.terms.isEmpty()) "An entry needs at least one word" else "",
    meaning = sentence(textProblem(d.meaning, LexKind.MEANING)),
)

/** The trimmed words and meaning an add or an edit sends. */
fun entryBody(d: LexDraft): LexDraft = LexDraft(d.terms.map(::normText), normText(d.meaning))

/** The form field a failure belongs under. */
enum class LexField { TERMS, MEANING, FORM }

/** Which field of the form a server reason belongs under. */
fun fieldOf(reason: String): LexField {
    val r = reason.lowercase(Locale.ROOT)
    return when {
        "meaning" in r -> LexField.MEANING
        "word" in r || "term" in r || "entry" in r -> LexField.TERMS
        else -> LexField.FORM
    }
}

/**
 * The first of [terms] that another entry (not [ignoreId]) or the rejected list already holds, as typed; null
 * when none. The server's 409 never says which.
 */
fun takenTerm(terms: List<String>, entries: List<LexEntry>, rejected: List<LexRejected>, ignoreId: Long? = null): String? {
    val held = HashSet<String>()
    for (e in entries) if (e.id != ignoreId) for (t in e.terms) held += foldTerm(t)
    for (r in rejected) held += foldTerm(r.term)
    return terms.firstOrNull { foldTerm(it) in held }
}

/** Entries whose words or meaning contain [query] (case-insensitive); all of them for a blank query. */
fun filterEntries(entries: List<LexEntry>, query: String): List<LexEntry> {
    val q = foldTerm(query)
    if (q.isEmpty()) return entries
    return entries.filter { e -> foldTerm(e.meaning).contains(q) || e.terms.any { foldTerm(it).contains(q) } }
}

const val LEX_STALE_TEXT = "Changed elsewhere — reloaded"
const val LEX_TAKEN_NOTE = "(in an entry or rejected)"

/** What a 409 duplicate says; it names the word when the loaded lists can tell which one it was. */
fun takenText(term: String?): String =
    if (term == null) "A word is already taken $LEX_TAKEN_NOTE" else "“$term” is already taken $LEX_TAKEN_NOTE"

private fun importReason(kind: String?): String = when (kind) {
    "glossary_missing" -> "the glossary file is missing"
    "glossary_unreadable" -> "the glossary file cannot be read"
    "glossary_not_utf8" -> "the glossary file is not UTF-8 text"
    else -> "the file cannot be read"
}

/** The one-line status of the legacy glossary import; "" when the server sent none. */
fun importLine(imp: LexImport?): String = when {
    imp == null -> ""
    imp.error != null -> "Glossary import is waiting: ${importReason(imp.error)}."
    !imp.done -> "No legacy glossary file to import."
    else -> "Imported ${imp.imported} words from the legacy glossary file, skipped ${imp.skipped}."
}

/** "seen 1 time" / "seen 5 times". */
fun seenText(n: Int): String = "seen $n time${if (n == 1) "" else "s"}"

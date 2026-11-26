package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.LexEntry
import com.raavivi.sysmon.core.model.LexImport
import com.raavivi.sysmon.core.model.LexRejected
import com.raavivi.sysmon.core.model.LexSuggestion
import com.raavivi.sysmon.ui.chatindex.LEX_MAX_MEANING
import com.raavivi.sysmon.ui.chatindex.LEX_MAX_TERM
import com.raavivi.sysmon.ui.chatindex.LEX_MAX_TERMS
import com.raavivi.sysmon.ui.chatindex.LexDraft
import com.raavivi.sysmon.ui.chatindex.LexField
import com.raavivi.sysmon.ui.chatindex.LexKind
import com.raavivi.sysmon.ui.chatindex.addTerm
import com.raavivi.sysmon.ui.chatindex.draftOfEntry
import com.raavivi.sysmon.ui.chatindex.draftOfSuggestion
import com.raavivi.sysmon.ui.chatindex.entryBody
import com.raavivi.sysmon.ui.chatindex.entryProblems
import com.raavivi.sysmon.ui.chatindex.fieldOf
import com.raavivi.sysmon.ui.chatindex.filterEntries
import com.raavivi.sysmon.ui.chatindex.foldTerm
import com.raavivi.sysmon.ui.chatindex.importLine
import com.raavivi.sysmon.ui.chatindex.normText
import com.raavivi.sysmon.ui.chatindex.removeTerm
import com.raavivi.sysmon.ui.chatindex.seenText
import com.raavivi.sysmon.ui.chatindex.takenTerm
import com.raavivi.sysmon.ui.chatindex.takenText
import com.raavivi.sysmon.ui.chatindex.textProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The glossary rules the form checks before a round trip; they mirror the server's (docs/API.md, "lexicon"). */
class LexiconLogicTest {

    // Every punctuation character the old text file could not hold, and then some.
    private val punctuation = "=!#/\\'\".,?&():".toList() + listOf(';', '-', '_', '*', '@', '+', '%')

    @Test
    fun `a term may contain any punctuation character, alone or inside a word`() {
        for (c in punctuation) {
            for (word in listOf("$c", "w${c}x", "${c}1", "ok$c", "a $c b")) {
                assertEquals("'$word' is refused", "", textProblem(word, LexKind.WORD))
                assertEquals(listOf(normText(word)), addTerm(emptyList(), word).terms)
            }
        }
    }

    @Test
    fun `the old file-syntax words are accepted`() {
        for (w in listOf("w/e", "#1", "ok!", "a = b", "!x", "# not a comment", "back\\slash", "say \"hi\"", "it's")) {
            val r = addTerm(emptyList(), w)
            assertEquals("", r.error)
            assertEquals(listOf(w), r.terms)
        }
    }

    @Test
    fun `emoji and any script are accepted in terms and meanings`() {
        for (w in listOf("🦊", "🦊 zorp", "ゾルプ", "中文", "مرحبا", "שלום", "Éclair", "ñandú", "😀😀")) {
            assertEquals("'$w'", "", textProblem(w, LexKind.WORD))
            assertEquals("'$w'", "", textProblem(w, LexKind.MEANING))
        }
    }

    @Test
    fun `line breaks are refused in the server's words`() {
        for (c in listOf('\n', '\r', '\u0085', ' ', ' ')) {
            assertEquals("a word cannot contain a line break", textProblem("zo${c}rp", LexKind.WORD))
            assertEquals("a meaning cannot contain a line break", textProblem("a cuddle${c}", LexKind.MEANING))
        }
        assertEquals("A word cannot contain a line break", addTerm(emptyList(), "a\nb").error)
    }

    @Test
    fun `control characters and text direction overrides are refused`() {
        for (c in listOf('\u0000', '\u0007', '\u001B', '\u007F', '\u009F')) {
            assertEquals("a word cannot contain control characters", textProblem("a${c}b", LexKind.WORD))
        }
        for (c in listOf('‪', '‮', '⁦', '⁩')) {
            assertEquals("a word cannot contain text-direction override characters", textProblem("a${c}b", LexKind.WORD))
        }
    }

    @Test
    fun `a tab is whitespace, not a control character`() {
        assertEquals("", textProblem("a\tb", LexKind.WORD))
        assertEquals(listOf("a b"), addTerm(emptyList(), "a\tb").terms)
    }

    @Test
    fun `empty and invisible-only text is refused`() {
        assertEquals("a word cannot be empty", textProblem("   ", LexKind.WORD))
        assertEquals("a word cannot be empty", textProblem("​​", LexKind.WORD))
        assertEquals("a meaning cannot be empty", textProblem("", LexKind.MEANING))
    }

    @Test
    fun `length limits count characters, not code units`() {
        assertEquals("", textProblem("a".repeat(LEX_MAX_TERM), LexKind.WORD))
        assertEquals("a word is too long (at most 80 characters)", textProblem("a".repeat(LEX_MAX_TERM + 1), LexKind.WORD))
        // 80 emoji are 160 UTF-16 units but 80 characters: fine.
        assertEquals("", textProblem("😀".repeat(LEX_MAX_TERM), LexKind.WORD))
        assertEquals("a word is too long (at most 80 characters)", textProblem("😀".repeat(LEX_MAX_TERM + 1), LexKind.WORD))
        assertEquals("", textProblem("m".repeat(LEX_MAX_MEANING), LexKind.MEANING))
        assertEquals("a meaning is too long (at most 300 characters)", textProblem("m".repeat(LEX_MAX_MEANING + 1), LexKind.MEANING))
    }

    @Test
    fun `typing a comma never splits a word`() {
        assertEquals(listOf("a, b, c"), addTerm(emptyList(), "a, b, c").terms)
        assertEquals(listOf("x", "zorp,zorpy"), addTerm(listOf("x"), "zorp,zorpy").terms)
        assertEquals(listOf(","), addTerm(emptyList(), ",").terms)
    }

    @Test
    fun `adding trims, collapses spaces, and a blank is a quiet no-op`() {
        assertEquals(listOf("a b"), addTerm(emptyList(), "  a    b  ").terms)
        val none = addTerm(listOf("x"), "   ")
        assertEquals(listOf("x"), none.terms)
        assertEquals("", none.error)
    }

    @Test
    fun `a word already in the entry is refused whatever its case`() {
        val r = addTerm(listOf("Zorp"), "zORP")
        assertEquals(listOf("Zorp"), r.terms)
        assertEquals("That word is already in this entry", r.error)
        // An invisible character does not make a different word.
        assertEquals("That word is already in this entry", addTerm(listOf("zorp"), "zo​rp").error)
    }

    @Test
    fun `an entry holds at most ten words`() {
        val full = (1..LEX_MAX_TERMS).map { "w$it" }
        val r = addTerm(full, "one more")
        assertEquals(full, r.terms)
        assertEquals("An entry holds at most 10 words", r.error)
    }

    @Test
    fun `removing a word keeps the others in order`() {
        assertEquals(listOf("a", "c"), removeTerm(listOf("a", "b", "c"), 1))
    }

    @Test
    fun `a form needs a word and a meaning`() {
        val none = entryProblems(LexDraft(emptyList(), "a cuddle"))
        assertEquals("An entry needs at least one word", none.terms)
        assertEquals("", none.meaning)
        val noMeaning = entryProblems(LexDraft(listOf("zorp"), "  "))
        assertEquals("A meaning cannot be empty", noMeaning.meaning)
        assertTrue(noMeaning.any)
        assertFalse(entryProblems(LexDraft(listOf("zorp"), "a cuddle")).any)
    }

    @Test
    fun `the body is trimmed`() {
        assertEquals(LexDraft(listOf("a b"), "x y"), entryBody(LexDraft(listOf(" a   b "), " x  y ")))
    }

    @Test
    fun `a server reason is shown under the field it names`() {
        assertEquals(LexField.MEANING, fieldOf("a meaning cannot be empty"))
        assertEquals(LexField.TERMS, fieldOf("a word is too long (at most 80 characters)"))
        assertEquals(LexField.TERMS, fieldOf("an entry needs at least one word"))
        assertEquals(LexField.FORM, fieldOf("unknown field"))
    }

    @Test
    fun `comparing terms ignores case and invisible characters`() {
        assertEquals(foldTerm("Éclair"), foldTerm("éclair"))
        assertEquals(foldTerm("zorp"), foldTerm("zo​rp"))
        assertFalse(foldTerm("é") == foldTerm("é")) // no Unicode normalisation, like the server
    }

    private val entries = listOf(
        LexEntry(1, 1, listOf("zorp", "zorpy"), "a cuddle"),
        LexEntry(2, 3, listOf("w/e"), "whatever"),
    )
    private val rejected = listOf(LexRejected("blip", 1))

    @Test
    fun `a taken word is found in another entry or in the rejected list`() {
        assertEquals("ZORPY", takenTerm(listOf("new", "ZORPY"), entries, rejected))
        assertEquals("Blip", takenTerm(listOf("Blip"), entries, rejected))
        assertEquals(null, takenTerm(listOf("fresh"), entries, rejected))
        // Editing entry 1 may keep its own words.
        assertEquals(null, takenTerm(listOf("zorp"), entries, rejected, ignoreId = 1))
        assertEquals("zorp", takenTerm(listOf("zorp"), entries, rejected, ignoreId = 2))
    }

    @Test
    fun `a duplicate says a word is taken, naming it when it is known`() {
        assertEquals("A word is already taken (in an entry or rejected)", takenText(null))
        assertEquals("“zorp” is already taken (in an entry or rejected)", takenText("zorp"))
    }

    @Test
    fun `the filter matches words or meanings, any case`() {
        assertEquals(entries, filterEntries(entries, ""))
        assertEquals(listOf(entries[0]), filterEntries(entries, "CUDDLE"))
        assertEquals(listOf(entries[1]), filterEntries(entries, "w/"))
        assertEquals(emptyList<LexEntry>(), filterEntries(entries, "nope"))
    }

    @Test
    fun `accepting a suggestion prefills the form with its word and meaning`() {
        assertEquals(LexDraft(listOf("noodle"), "a pet name"), draftOfSuggestion(LexSuggestion("noodle", "a pet name", 5)))
        assertEquals(LexDraft(listOf("noodle"), ""), draftOfSuggestion(LexSuggestion("noodle", null, 5)))
        assertEquals(LexDraft(listOf("zorp", "zorpy"), "a cuddle"), draftOfEntry(entries[0]))
    }

    @Test
    fun `the import line says what happened to the legacy file`() {
        assertEquals("", importLine(null))
        assertEquals("No legacy glossary file to import.", importLine(LexImport(done = false)))
        assertEquals(
            "Imported 12 words from the legacy glossary file, skipped 3.",
            importLine(LexImport(done = true, imported = 12, skipped = 3)),
        )
        assertEquals(
            "Glossary import is waiting: the glossary file is not UTF-8 text.",
            importLine(LexImport(error = "glossary_not_utf8")),
        )
        assertEquals(
            "Glossary import is waiting: the file cannot be read.",
            importLine(LexImport(error = "something_new")),
        )
    }

    @Test
    fun `seen counts pluralise`() {
        assertEquals("seen 1 time", seenText(1))
        assertEquals("seen 5 times", seenText(5))
    }
}

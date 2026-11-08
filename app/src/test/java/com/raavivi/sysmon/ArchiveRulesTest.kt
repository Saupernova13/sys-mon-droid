package com.raavivi.sysmon

import com.raavivi.sysmon.core.model.ArchiveCaps
import com.raavivi.sysmon.core.model.ArchiveEntry
import com.raavivi.sysmon.core.model.ArchiveListResponse
import com.raavivi.sysmon.core.net.ApiResult
import com.raavivi.sysmon.core.net.SysMonJson
import com.raavivi.sysmon.ui.files.ArchiveTree
import com.raavivi.sysmon.ui.files.PasswordOutcome
import com.raavivi.sysmon.ui.files.archiveStem
import com.raavivi.sysmon.ui.files.compressFormError
import com.raavivi.sysmon.ui.files.crumbs
import com.raavivi.sysmon.ui.files.defaultArchiveName
import com.raavivi.sysmon.ui.files.ensureArchiveExt
import com.raavivi.sysmon.ui.files.isArchiveName
import com.raavivi.sysmon.ui.files.joinPath
import com.raavivi.sysmon.ui.files.parentPath
import com.raavivi.sysmon.ui.files.withArchiveExt
import com.raavivi.sysmon.ui.files.withPassword
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRulesTest {
    private fun file(path: String, size: Long = 10, enc: Boolean = false) =
        ArchiveEntry(path = path, name = path.substringAfterLast('/'), size = size, packed = size / 2, encrypted = enc)

    private fun dir(path: String) = ArchiveEntry(path = path, name = path.substringAfterLast('/'), dir = true)

    @Test fun impliedParentsAreCreated() {
        val tree = ArchiveTree.build(listOf(file("a/b/c.txt"), file("a/d.txt"), file("top.txt")))
        assertEquals(listOf("a", "top.txt"), tree.list("").map { it.name })
        assertEquals(listOf("b", "d.txt"), tree.list("a").map { it.name })
        assertEquals(listOf("c.txt"), tree.list("a/b").map { it.name })
        assertTrue(tree.has("a/b"))
        assertFalse(tree.has("nope"))
    }

    @Test fun explicitAndImplicitDirsMerge() {
        val tree = ArchiveTree.build(listOf(dir("a/"), file("a/x.txt"), dir("empty/")))
        val root = tree.list("")
        assertEquals(listOf("a", "empty"), root.map { it.name })
        assertTrue(root.all { it.dir })
        // Folder keeps the listing's own spelling when it was listed.
        assertEquals("a/", root.first { it.name == "a" }.entryPath)
        assertEquals(listOf("x.txt"), tree.list("a").map { it.name })
    }

    @Test fun backslashPathsAndTrailingSlashes() {
        val tree = ArchiveTree.build(listOf(file("dir\\sub\\f.bin"), file("dir/sub/g.bin")))
        assertEquals(listOf("f.bin", "g.bin"), tree.list("dir/sub").map { it.name })
        assertEquals("dir\\sub\\f.bin", tree.list("dir/sub").first().entryPath)
        assertEquals(listOf("sub"), tree.list("dir\\").map { it.name })
    }

    @Test fun foldersFirstSortedAndTotalled() {
        val tree = ArchiveTree.build(listOf(file("b.txt"), file("Z/one", 30, enc = true), file("Z/two", 20), file("a.txt")))
        val root = tree.list("")
        assertEquals(listOf("Z", "a.txt", "b.txt"), root.map { it.name })
        val z = root.first()
        assertEquals(50L, z.size)
        assertEquals(25L, z.packed)
        assertTrue(z.encrypted)
    }

    @Test fun navigationHelpers() {
        assertEquals("a/b", parentPath("a/b/c"))
        assertEquals("", parentPath("a"))
        assertEquals(listOf("/" to "", "a" to "a", "b" to "a/b"), crumbs("a/b"))
    }

    @Test fun archiveDetectionUsesTheLastExtension() {
        val ext = listOf("zip", "gz", "7z")
        assertTrue(isArchiveName("a.zip", ext))
        assertTrue(isArchiveName("a.tar.gz", ext))
        assertTrue(isArchiveName("A.ZIP", ext))
        assertFalse(isArchiveName("a.tar", ext))
        assertFalse(isArchiveName("zip", ext))
        assertFalse(isArchiveName("a.txt", emptyList()))
        assertEquals("a", archiveStem("a.tar.gz"))
        assertEquals("a", archiveStem("a.zip"))
        assertEquals("noext", archiveStem("noext"))
    }

    @Test fun compressNames() {
        assertEquals("photo.zip", defaultArchiveName(listOf("C:\\x\\photo.jpg"), "C:\\x", "zip"))
        assertEquals("folder.7z", defaultArchiveName(listOf("C:\\x\\folder"), "C:\\x", "7z"))
        assertEquals("x.zip", defaultArchiveName(listOf("a", "b"), "C:\\x", "zip"))
        assertEquals("archive.zip", defaultArchiveName(listOf("a", "b"), "", "zip"))
        assertEquals("a.7z", withArchiveExt("a.zip", "7z"))
        assertEquals("a.b.zip", withArchiveExt("a.b", "zip"))
        assertEquals("a.zip", ensureArchiveExt("a", "zip"))
        assertEquals("a.ZIP", ensureArchiveExt(" a.ZIP ", "zip"))
    }

    @Test fun compressValidation() {
        assertNull(compressFormError("a.zip", "pw", "pw"))
        assertNull(compressFormError("a.zip", "", ""))
        assertTrue(compressFormError("", "", "") != null)
        assertTrue(compressFormError("a/b.zip", "", "") != null)
        assertTrue(compressFormError("a:b.zip", "", "") != null)
        assertEquals("The passwords do not match", compressFormError("a.zip", "x", "y"))
    }

    @Test fun joinUsesHostSeparator() {
        assertEquals("C:\\a\\b", joinPath("C:\\a", "b", "\\"))
        assertEquals("C:\\b", joinPath("C:\\", "b", "\\"))
        assertEquals("/a/b", joinPath("/a", "b", "/"))
        assertEquals("/b", joinPath("/", "b", "/"))
    }

    @Test fun passwordLoopRetriesUntilItWorks() = runBlocking {
        val asked = mutableListOf<String>()
        val answers = ArrayDeque(listOf("bad", "good"))
        val out = withPassword(
            name = "x.zip",
            initial = null,
            ask = { msg -> asked += msg; answers.removeFirst() },
            run = { pw ->
                when (pw) {
                    null -> ApiResult.Err("pw", 422, CODE_REQ)
                    "bad" -> ApiResult.Err("wrong", 422, CODE_WRONG)
                    else -> ApiResult.Ok("opened")
                }
            },
        )
        assertEquals(listOf("Password for x.zip", "Wrong password, try again"), asked)
        assertEquals(PasswordOutcome.Done("opened", "good"), out)
    }

    @Test fun passwordCancelEndsQuietlyAndOtherErrorsFail() = runBlocking {
        val cancelled = withPassword<String>("x", null, ask = { null }, run = { ApiResult.Err("pw", 422, CODE_REQ) })
        assertEquals(PasswordOutcome.Cancelled, cancelled)
        val failed = withPassword<String>("x", null, ask = { "p" }, run = { ApiResult.Err("boom", 500) })
        assertTrue(failed is PasswordOutcome.Failed)
    }

    @Test fun decodesListAndCapsResponses() {
        val list = SysMonJson.decodeFromString(
            ArchiveListResponse.serializer(),
            """{"entries":[{"path":"a/b.txt","name":"b.txt","dir":false,"size":5,"packed":3,
                "modified":"2024-01-02 03:04:05","encrypted":true},
                {"path":"a","name":"a","dir":true,"size":0,"packed":0,"modified":null,"encrypted":false}],
                "encrypted":true}""",
        )
        assertTrue(list.encrypted)
        assertEquals(2, list.entries.size)
        assertEquals("2024-01-02 03:04:05", list.entries[0].modified)
        assertTrue(list.entries[1].dir)

        val caps = SysMonJson.decodeFromString(
            ArchiveCaps.serializer(),
            """{"available":true,"seven_zip":"C:\\7z.exe","rar":null,"create_formats":["zip","7z","tar"],"extensions":["zip","gz"]}""",
        )
        assertTrue(caps.available)
        assertEquals(listOf("zip", "7z", "tar"), caps.createFormats)
        assertEquals(listOf("zip", "gz"), caps.extensions)
    }

    private companion object {
        const val CODE_REQ = "password_required"
        const val CODE_WRONG = "wrong_password"
    }
}

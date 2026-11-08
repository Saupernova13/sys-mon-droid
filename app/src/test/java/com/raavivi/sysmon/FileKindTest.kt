package com.raavivi.sysmon

import com.raavivi.sysmon.ui.files.FileKind
import com.raavivi.sysmon.ui.files.MediaType
import com.raavivi.sysmon.ui.files.archiveBadge
import com.raavivi.sysmon.ui.files.fileKind
import com.raavivi.sysmon.ui.files.mayHaveThumbnail
import com.raavivi.sysmon.ui.files.mediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindTest {
    @Test fun kindsFollowTheWebSets() {
        assertEquals(FileKind.Dir, fileKind("photos", true))
        assertEquals(FileKind.Dir, fileKind("x.zip", true))
        assertEquals(FileKind.Media, fileKind("a.PNG", false))
        assertEquals(FileKind.Media, fileKind("a.mkv", false))
        assertEquals(FileKind.Media, fileKind("a.flac", false))
        assertEquals(FileKind.Archive, fileKind("a.7z", false))
        assertEquals(FileKind.Archive, fileKind("a.apk", false))
        assertEquals(FileKind.Exec, fileKind("setup.exe", false))
        assertEquals(FileKind.Exec, fileKind("run.cmd", false))
        assertEquals(FileKind.Code, fileKind("main.rs", false))
        assertEquals(FileKind.Code, fileKind("cfg.json", false))
        assertEquals(FileKind.Doc, fileKind("notes.txt", false))
        assertEquals(FileKind.Doc, fileKind("readme.md", false))
        assertEquals(FileKind.Doc, fileKind("paper.pdf", false))
        assertEquals(FileKind.Doc, fileKind("noext", false))
        assertEquals(FileKind.Doc, fileKind("dir.d/file", false))
    }

    @Test fun mediaSubTypes() {
        assertEquals(MediaType.Image, mediaType("a.jpg"))
        assertEquals(MediaType.Video, mediaType("a.mp4"))
        assertEquals(MediaType.Audio, mediaType("a.wav"))
        assertNull(mediaType("a.txt"))
    }

    @Test fun archiveBadges() {
        assertEquals("ZIP", archiveBadge("a.zip"))
        assertEquals("7Z", archiveBadge("a.7z"))
        assertEquals("TAR.GZ", archiveBadge("a.tar.gz"))
        assertEquals("TAR.ZST", archiveBadge("A.TAR.ZST"))
        assertEquals("GZ", archiveBadge("a.gz"))
        assertEquals("TGZ", archiveBadge("a.tgz"))
        assertNull(archiveBadge("a.txt"))
        assertNull(archiveBadge("noext"))
    }

    @Test fun thumbnailEligibility() {
        assertTrue(mayHaveThumbnail("a.jpg", false))
        assertTrue(mayHaveThumbnail("a.PDF", false))
        assertTrue(mayHaveThumbnail("a.exe", false))
        assertFalse(mayHaveThumbnail("a.txt", false))
        assertFalse(mayHaveThumbnail("a.jpg", true))
        assertFalse(mayHaveThumbnail("folder", true))
    }
}

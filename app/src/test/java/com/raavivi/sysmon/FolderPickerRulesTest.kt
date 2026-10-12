package com.raavivi.sysmon

import com.raavivi.sysmon.ui.files.canTransferTo
import com.raavivi.sysmon.ui.files.isCurrentParent
import com.raavivi.sysmon.ui.files.isSelfOrInside
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPickerRulesTest {
    @Test fun selfAndDescendantsAreRejected() {
        assertTrue(isSelfOrInside("C:\\a\\b", "C:\\a\\b"))
        assertTrue(isSelfOrInside("C:\\a\\b", "C:\\a\\b\\c"))
        assertTrue(isSelfOrInside("/a/b", "/a/b/c"))
        assertTrue(isSelfOrInside("C:\\A\\b", "c:/a/b/"))
    }

    @Test fun siblingsWithSharedPrefixAreAllowed() {
        assertFalse(isSelfOrInside("C:\\a\\b", "C:\\a\\bc"))
        assertFalse(isSelfOrInside("C:\\a\\b", "C:\\a"))
    }

    @Test fun parentDetection() {
        assertTrue(isCurrentParent("C:\\a\\b", "C:\\a"))
        assertTrue(isCurrentParent("C:\\a", "C:\\"))
        assertTrue(isCurrentParent("/a/b", "/a"))
        assertFalse(isCurrentParent("C:\\a\\b", "C:\\x"))
    }

    @Test fun transferGate() {
        assertFalse(canTransferTo("C:\\a\\b", "", false))
        assertTrue(canTransferTo("C:\\a\\b", "C:\\a", false))
        assertFalse(canTransferTo("C:\\a\\b", "C:\\a", true))
        assertFalse(canTransferTo("C:\\a\\b", "C:\\a\\b\\c", true))
        assertTrue(canTransferTo("C:\\a\\b", "D:\\", true))
    }
}

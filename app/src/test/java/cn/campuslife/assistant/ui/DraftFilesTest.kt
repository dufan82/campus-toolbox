package cn.campuslife.assistant.ui

import cn.campuslife.assistant.image.DraftFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DraftFilesTest {
    @Test fun failedPointerCommitPreservesTheOldDraftAndNewPhoto() {
        val folder=Files.createTempDirectory("campus-draft-").toFile()
        val old=File(folder,"old.img").apply { writeText("old photo") }
        val next=File(folder,"new.img").apply { writeText("new photo") }
        try {
            try { DraftFiles.commit(next,old,folder) { throw IOException("disk full") }; fail("Expected storage failure") }
            catch(_: IOException) { assertEquals("old photo",old.readText()); assertEquals("new photo",next.readText()) }
        } finally { old.delete(); next.delete(); folder.delete() }
    }
    @Test fun oldDraftIsRemovedOnlyAfterNewPointerHasBeenSaved() {
        val folder=Files.createTempDirectory("campus-draft-").toFile()
        val old=File(folder,"old.img").apply { writeText("old photo") }
        val next=File(folder,"new.img").apply { writeText("new photo") }
        var pointer="old.img"
        try {
            DraftFiles.commit(next,old,folder) { assertTrue(old.exists()); pointer=it }
            assertEquals("new.img",pointer); assertFalse(old.exists()); assertTrue(next.exists())
        } finally { old.delete(); next.delete(); folder.delete() }
    }
}

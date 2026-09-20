package com.ryanpudd.photobooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadQueueManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun attemptCount_returnsZero_whenNoSuffix() {
        assertEquals(0, UploadQueueManager.attemptCount("IMG_20260912_193045_001.jpg"))
    }

    @Test
    fun attemptCount_parsesSuffix() {
        assertEquals(1, UploadQueueManager.attemptCount("IMG_20260912_193045_001__u1.jpg"))
        assertEquals(5, UploadQueueManager.attemptCount("IMG_20260912_193045_001__u5.jpg"))
    }

    @Test
    fun withAttemptSuffix_and_stripAttemptSuffix_roundTrip() {
        val base = "IMG_20260912_193045_001.jpg"
        val withSuffix = UploadQueueManager.withAttemptSuffix(base, 3)
        assertEquals("IMG_20260912_193045_001__u3.jpg", withSuffix)
        assertEquals(3, UploadQueueManager.attemptCount(withSuffix))
        assertEquals(base, UploadQueueManager.stripAttemptSuffix(withSuffix))
    }

    @Test
    fun withAttemptSuffix_replacesExistingSuffix() {
        val onceFailed = "IMG_20260912_193045_001__u1.jpg"
        val incremented = UploadQueueManager.withAttemptSuffix(onceFailed, 2)
        assertEquals("IMG_20260912_193045_001__u2.jpg", incremented)
    }

    @Test
    fun backoffMillisForAttempt_returnsExpectedSchedule() {
        assertEquals(30_000L, UploadQueueManager.backoffMillisForAttempt(1))
        assertEquals(60_000L, UploadQueueManager.backoffMillisForAttempt(2))
        assertEquals(120_000L, UploadQueueManager.backoffMillisForAttempt(3))
        assertEquals(300_000L, UploadQueueManager.backoffMillisForAttempt(4))
    }

    @Test
    fun backoffMillisForAttempt_doesNotIndexOutOfBoundsAtOrPastMaxAttempts() {
        // attempt 5 is the give-up point, but the schedule must not throw if ever queried
        assertEquals(300_000L, UploadQueueManager.backoffMillisForAttempt(5))
        assertEquals(300_000L, UploadQueueManager.backoffMillisForAttempt(99))
        assertEquals(0L, UploadQueueManager.backoffMillisForAttempt(0))
    }

    @Test
    fun findNextPendingFile_picksOldestByName() {
        val dir = tempFolder.newFolder("pending")
        File(dir, "IMG_20260912_193102_001.jpg").createNewFile()
        File(dir, "IMG_20260912_193045_001.jpg").createNewFile()
        File(dir, "not_a_photo.tmp").createNewFile()

        val next = UploadQueueManager.findNextPendingFile(dir)
        assertEquals("IMG_20260912_193045_001.jpg", next?.name)
    }

    @Test
    fun findNextPendingFile_returnsNull_whenEmpty() {
        val dir = tempFolder.newFolder("pending-empty")
        assertNull(UploadQueueManager.findNextPendingFile(dir))
    }

    @Test
    fun findNextDueRetryFile_skipsFilesNotYetDue() {
        val dir = tempFolder.newFolder("retry")
        val notDue = File(dir, "IMG_a__u1.jpg").apply { createNewFile() }
        val now = System.currentTimeMillis()
        notDue.setLastModified(now) // attempt 1 backoff is 30s, so "now" is not due yet

        assertNull(UploadQueueManager.findNextDueRetryFile(dir, now))
    }

    @Test
    fun findNextDueRetryFile_returnsDueFile() {
        val dir = tempFolder.newFolder("retry-due")
        val due = File(dir, "IMG_a__u1.jpg").apply { createNewFile() }
        val now = System.currentTimeMillis()
        due.setLastModified(now - 31_000) // past the 30s backoff for attempt 1

        val result = UploadQueueManager.findNextDueRetryFile(dir, now)
        assertEquals(due.name, result?.name)
    }

    @Test
    fun moveToRetry_incrementsAttemptAndMovesFile() {
        val pending = tempFolder.newFolder("pending2")
        val retry = tempFolder.newFolder("retry2")
        val file = File(pending, "IMG_b.jpg").apply { createNewFile() }

        val moved = UploadQueueManager.moveToRetry(file, retry)

        assertTrue(!file.exists())
        assertEquals("IMG_b__u1.jpg", moved.name)
        assertEquals(retry, moved.parentFile)
    }

    @Test
    fun moveToFailed_movesFileWithoutRenaming() {
        val retry = tempFolder.newFolder("retry3")
        val failed = tempFolder.newFolder("failed3")
        val file = File(retry, "IMG_c__u5.jpg").apply { createNewFile() }

        val moved = UploadQueueManager.moveToFailed(file, failed)

        assertTrue(!file.exists())
        assertEquals("IMG_c__u5.jpg", moved.name)
        assertEquals(failed, moved.parentFile)
    }

    @Test
    fun nextAvailableFile_normalMode_hasNoDemoPrefix() {
        val dir = tempFolder.newFolder("pending")
        val file = UploadQueueManager.nextAvailableFile(dir, demo = false)
        assertTrue(file.name.startsWith("IMG_"))
        assertFalse(UploadQueueManager.isDemoFile(file.name))
    }

    @Test
    fun nextAvailableFile_demoMode_isMarkedAsDemo() {
        val dir = tempFolder.newFolder("pending")
        val file = UploadQueueManager.nextAvailableFile(dir, demo = true)
        assertTrue(file.name.startsWith("DEMO_IMG_"))
        assertTrue(UploadQueueManager.isDemoFile(file.name))
    }
}

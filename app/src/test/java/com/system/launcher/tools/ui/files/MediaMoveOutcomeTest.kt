package com.system.launcher.tools.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMoveOutcomeTest {

    @Test
    fun allCopiedSourcesDeleted_isCompleteMove() {
        val outcome = MediaMoveOutcome(copied = 3, copyFailed = 0, sourceDeleted = 3, sourceDeleteFailed = 0)

        assertEquals(3, outcome.moved)
        assertEquals(0, outcome.copiedButRetained)
        assertFalse(outcome.hasFailures)
    }

    @Test
    fun deletionFailure_keepsCopiedSource() {
        val outcome = MediaMoveOutcome(copied = 3, copyFailed = 0, sourceDeleted = 2, sourceDeleteFailed = 1)

        assertEquals(2, outcome.moved)
        assertEquals(1, outcome.copiedButRetained)
        assertTrue(outcome.hasFailures)
    }

    @Test
    fun copyFailure_isNeverCountedAsMoved() {
        val outcome = MediaMoveOutcome(copied = 2, copyFailed = 1, sourceDeleted = 2, sourceDeleteFailed = 0)

        assertEquals(2, outcome.moved)
        assertEquals(0, outcome.copiedButRetained)
        assertTrue(outcome.hasFailures)
    }

    @Test
    fun deletedCountCannotExceedCopiedCount() {
        val outcome = MediaMoveOutcome(copied = 1, copyFailed = 0, sourceDeleted = 3, sourceDeleteFailed = 0)

        assertEquals(1, outcome.moved)
        assertEquals(0, outcome.copiedButRetained)
    }
}

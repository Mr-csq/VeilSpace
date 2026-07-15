package com.system.launcher.tools.ui.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMediaCopyVerifierTest {
    @Test
    fun verifiesMatchingStream() {
        assertTrue(
            ProfileMediaCopyVerifier.isVerified(
                expectedSize = 4,
                copiedBytes = 4,
                sourceBytes = 4,
                copiedSha256 = byteArrayOf(1, 2),
                sourceSha256 = byteArrayOf(1, 2)
            )
        )
    }

    @Test
    fun rejectsSizeMismatch() {
        assertFalse(
            ProfileMediaCopyVerifier.isVerified(
                expectedSize = 5,
                copiedBytes = 4,
                sourceBytes = 4,
                copiedSha256 = byteArrayOf(1, 2),
                sourceSha256 = byteArrayOf(1, 2)
            )
        )
    }

    @Test
    fun rejectsDigestMismatch() {
        assertFalse(
            ProfileMediaCopyVerifier.isVerified(
                expectedSize = 4,
                copiedBytes = 4,
                sourceBytes = 4,
                copiedSha256 = byteArrayOf(1, 2),
                sourceSha256 = byteArrayOf(2, 1)
            )
        )
    }
}

package com.system.launcher.tools.ui.files

object ProfileMediaCopyVerifier {
    fun isVerified(
        expectedSize: Long,
        copiedBytes: Long,
        sourceBytes: Long,
        copiedSha256: ByteArray,
        sourceSha256: ByteArray
    ): Boolean {
        if (copiedBytes < 0L || sourceBytes < 0L || copiedBytes != sourceBytes) return false
        if (expectedSize > 0L && copiedBytes != expectedSize) return false
        return copiedSha256.contentEquals(sourceSha256)
    }
}

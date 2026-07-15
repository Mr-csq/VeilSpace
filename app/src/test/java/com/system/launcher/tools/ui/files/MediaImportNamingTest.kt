package com.system.launcher.tools.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImportNamingTest {

    @Test
    fun sanitizeDisplayName_removesPathAndControlCharacters() {
        val result = MediaImportNaming.sanitizeDisplayName(
            "folder\\nested/IMG_20\u00000714.jpg",
            "fallback.jpg"
        )

        assertEquals("IMG_200714.jpg", result)
    }

    @Test
    fun sanitizeDisplayName_preservesExtensionWhenTruncated() {
        val result = MediaImportNaming.sanitizeDisplayName("a".repeat(240) + ".jpeg", "fallback.jpg")

        assertEquals(180, result.length)
        assertTrue(result.endsWith(".jpeg"))
    }

    @Test
    fun sanitizeDisplayName_usesFallbackForBlankName() {
        assertEquals("fallback.mp4", MediaImportNaming.sanitizeDisplayName("  ", "fallback.mp4"))
    }

    @Test
    fun numberedDisplayName_placesSuffixBeforeExtension() {
        assertEquals("holiday (2).mp4", MediaImportNaming.numberedDisplayName("holiday.mp4", 2))
        assertEquals("README (1)", MediaImportNaming.numberedDisplayName("README", 1))
    }
}

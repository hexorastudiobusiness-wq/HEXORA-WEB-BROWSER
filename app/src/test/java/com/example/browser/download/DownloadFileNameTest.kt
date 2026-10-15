package com.example.browser.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadFileNameTest {

    @Test
    fun `extracts quoted filename from content disposition`() {
        assertEquals(
            "report.pdf",
            DownloadFileName.fromContentDisposition("attachment; filename=\"report.pdf\""),
        )
    }

    @Test
    fun `extracts rfc5987 encoded filename`() {
        assertEquals(
            "résumé.pdf",
            DownloadFileName.fromContentDisposition(
                "attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf",
            ),
        )
    }

    @Test
    fun `missing header returns null`() {
        assertNull(DownloadFileName.fromContentDisposition(null))
        assertNull(DownloadFileName.fromContentDisposition("attachment"))
    }

    @Test
    fun `falls back to last url segment`() {
        assertEquals(
            "setup.apk",
            DownloadFileName.fromUrl("https://example.com/files/setup.apk?token=abc"),
        )
        assertEquals(
            "download",
            DownloadFileName.fromUrl("https://example.com/"),
        )
    }

    @Test
    fun `sanitize strips path traversal`() {
        assertEquals("evil.apk", DownloadFileName.sanitize("../../evil.apk"))
        assertEquals("download", DownloadFileName.sanitize("   "))
    }
}

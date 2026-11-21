package com.cdg.browser.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialYtDlpReleaseParserTest {
    @Test
    fun acceptsTheOfficialAssetAndItsSha256Digest() {
        val release = OfficialYtDlpReleaseParser.parse(
            """
            {
              "tag_name":"2026.11.21",
              "assets":[{
                "name":"yt-dlp",
                "browser_download_url":"https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.21/yt-dlp",
                "digest":"sha256:${"a".repeat(64)}"
              }]
            }
            """.trimIndent()
        )

        assertEquals("2026.11.21", release.tag)
        assertEquals(
            "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.21/yt-dlp",
            release.assetUrl
        )
        assertEquals("a".repeat(64), release.sha256)
    }

    @Test
    fun rejectsMissingDigestsAndNonOfficialAssetUrls() {
        val noDigest = """
            {"tag_name":"2026.11.21","assets":[{
              "name":"yt-dlp",
              "browser_download_url":"https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.21/yt-dlp"
            }]}
        """.trimIndent()
        val foreignHost = """
            {"tag_name":"2026.11.21","assets":[{
              "name":"yt-dlp",
              "browser_download_url":"https://downloads.example/yt-dlp",
              "digest":"sha256:${"a".repeat(64)}"
            }]}
        """.trimIndent()

        assertThrows( java.io.IOException::class.java) {
            OfficialYtDlpReleaseParser.parse(noDigest)
        }
        assertThrows(java.io.IOException::class.java) {
            OfficialYtDlpReleaseParser.parse(foreignHost)
        }
    }
}

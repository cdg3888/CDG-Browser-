package com.cdg.browser.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpFormatsTest {
    @Test
    fun parsesMetadataAndCombinedFormats() {
        val media = YtDlpFormats.parse(
            """
            {
              "title": "Sample video",
              "duration": 95,
              "thumbnail": "https://images.example/thumb.jpg",
              "formats": [{
                "format_id": "combined-720",
                "url": "https://media.example/video.mp4?signature=abc",
                "ext": "mp4",
                "height": 720,
                "vcodec": "avc1.64001f",
                "acodec": "mp4a.40.2",
                "tbr": 1400
              }]
            }
            """.trimIndent()
        )

        assertEquals("Sample video", media?.title)
        assertEquals(95, media?.durationSeconds)
        assertEquals("https://images.example/thumb.jpg", media?.thumbnailUrl)
        assertEquals(1, media?.formats?.size)
        assertEquals("combined-720", media?.formats?.single()?.selector)
        assertEquals(720, media?.formats?.single()?.height)
        assertEquals(1400, media?.formats?.single()?.bitrateKbps)
        assertTrue(media?.formats?.single()?.title?.contains("1400 kb/s") == true)
        assertTrue(media?.formats?.single()?.details?.contains("audio y video") == true)
        assertTrue(media?.formats?.single()?.details?.contains("avc1.64001f") == true)
    }

    @Test
    fun combinesSeparateVideoAndAudioFormatsAtEachResolution() {
        val media = YtDlpFormats.parse(
            """
            {
              "title": "Separated streams",
              "formats": [
                {"format_id":"audio","url":"https://media.example/audio.m4a","ext":"m4a",
                 "vcodec":"none","acodec":"mp4a.40.2","abr":128},
                {"format_id":"video-720","url":"https://media.example/video-720.mp4","ext":"mp4",
                 "height":720,"vcodec":"avc1.64001f","acodec":"none","tbr":1200}
              ]
            }
            """.trimIndent()
        )

        val video = media?.formats?.single { !it.audioOnly }
        val audio = media?.formats?.single { it.audioOnly }
        assertEquals("video-720+audio", video?.selector)
        assertEquals("https://media.example/audio.m4a", video?.audioUrl)
        assertTrue(video?.details?.contains("video + audio separado") == true)
        assertEquals("audio", audio?.selector)
    }

    @Test
    fun ignoresUnsafeOrNonMediaFormatUrlsAndInvalidJson() {
        val media = YtDlpFormats.parse(
            """
            {
              "title":"Unsafe",
              "formats":[
                {"format_id":"bad","url":"file:///etc/passwd","ext":"mp4","height":720},
                {"format_id":"credentials","url":"https://user:pass@media.example/a.mp4",
                 "ext":"mp4","height":720}
              ]
            }
            """.trimIndent()
        )

        assertNull(media)
        assertNull(YtDlpFormats.parse("{not json"))
    }

    @Test
    fun excludesFormatIdentifiersThatCouldAlterTheSelector() {
        val media = YtDlpFormats.parse(
            """
            {
              "title":"Bad selector",
              "formats":[{
                "format_id":"video+audio",
                "url":"https://media.example/video.mp4",
                "ext":"mp4",
                "height":720
              }]
            }
            """.trimIndent()
        )

        assertFalse(media?.formats?.isNotEmpty() == true)
    }
}

package com.cdg.browser.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainExtractorTest {
    @Test
    fun recognizesDirectFilesButLeavesManifestsForUniversalExtraction() {
        val direct = DirectMediaExtractor()

        assertTrue(direct.canHandle("https://cdn.example/video.mp4?token=abc"))
        assertFalse(direct.canHandle("https://cdn.example/live.m3u8"))
        assertFalse(direct.canHandle("file:///video.mp4"))
        assertFalse(direct.canHandle("https://user:pass@cdn.example/video.mp4"))
    }

    @Test
    fun fallsThroughNativeFailureToUniversalExtractor() {
        val events = mutableListOf<String>()
        val native = FakeExtractor(
            canHandle = true,
            extract = { callback -> callback.onError("native reader unavailable") }
        )
        val universal = FakeExtractor(
            canHandle = true,
            extract = { callback ->
                callback.onStarted("lookup-id")
                callback.onResult(mediaInfo())
            }
        )
        val chain = ChainExtractor(DirectMediaExtractor(), listOf(native), universal)

        chain.extract("https://site.example/watch", null, object : MediaExtractor.Callback {
            override fun onStarted(processId: String) {
                events += "started:$processId"
            }

            override fun onResult(info: YtDlpMediaInfo) {
                events += "result:${info.title}"
            }

            override fun onNotHandled() {
                events += "not-handled"
            }

            override fun onError(message: String) {
                events += "error:$message"
            }
        })

        assertEquals(listOf("started:lookup-id", "result:Resolved"), events)
    }

    @Test
    fun reportsTheLastFailureWhenNoExtractorCanResolveThePage() {
        val failed = FakeExtractor(
            canHandle = true,
            extract = { callback -> callback.onError("site is unavailable") }
        )
        val chain = ChainExtractor(DirectMediaExtractor(), emptyList(), failed)
        val errors = mutableListOf<String>()

        chain.extract("https://site.example/watch", null, object : MediaExtractor.Callback {
            override fun onStarted(processId: String) = Unit
            override fun onResult(info: YtDlpMediaInfo) = Unit
            override fun onNotHandled() = Unit
            override fun onError(message: String) {
                errors += message
            }
        })

        assertEquals(listOf("site is unavailable"), errors)
    }

    private fun mediaInfo() = YtDlpMediaInfo("Resolved", 0, null, emptyList())

    private class FakeExtractor(
        private val canHandle: Boolean,
        private val extract: (MediaExtractor.Callback) -> Unit
    ) : MediaExtractor {
        override fun canHandle(url: String): Boolean = canHandle
        override fun extract(url: String, referer: String?, callback: MediaExtractor.Callback) {
            extract(callback)
        }
    }
}

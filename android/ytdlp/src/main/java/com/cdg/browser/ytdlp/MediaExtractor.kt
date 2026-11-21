package com.cdg.browser.ytdlp

import android.net.Uri
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface MediaExtractor {
    fun canHandle(url: String): Boolean

    fun extract(url: String, referer: String?, callback: Callback)

    interface Callback {
        fun onStarted(processId: String)
        fun onResult(info: YtDlpMediaInfo)
        fun onNotHandled()
        fun onError(message: String)
    }
}

class DirectMediaExtractor : MediaExtractor {
    override fun canHandle(url: String): Boolean {
        val uri = httpUri(url) ?: return false
        val extension = uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in VIDEO_EXTENSIONS || extension in AUDIO_EXTENSIONS
    }

    override fun extract(url: String, referer: String?, callback: MediaExtractor.Callback) {
        val uri = httpUri(url)
        val extension = uri?.path?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        val mediaExtension = extension?.takeIf {
            it in VIDEO_EXTENSIONS || it in AUDIO_EXTENSIONS
        }
        if (uri == null || mediaExtension == null) {
            callback.onNotHandled()
            return
        }
        val filename = uri.path.orEmpty().substringAfterLast('/')
            .ifBlank { "media.$mediaExtension" }
        val title = Uri.decode(filename)
        val audioOnly = mediaExtension in AUDIO_EXTENSIONS
        callback.onResult(
            YtDlpMediaInfo(
                title = title,
                durationSeconds = 0,
                thumbnailUrl = null,
                formats = listOf(
                    YtDlpFormatOption(
                        selector = "best",
                        title = "Archivo original · ${mediaExtension.uppercase(Locale.ROOT)}",
                        details = if (audioOnly) "Archivo de audio directo" else "Archivo de video directo",
                        videoUrl = if (audioOnly) null else url,
                        audioUrl = if (audioOnly) url else null,
                        audioOnly = audioOnly
                    )
                )
            )
        )
    }

    private fun httpUri(value: String): URI? = try {
        URI(value).takeIf {
            it.host != null && it.userInfo == null
                && (it.scheme.equals("https", ignoreCase = true)
                || it.scheme.equals("http", ignoreCase = true))
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf(
            "3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm"
        )
        val AUDIO_EXTENSIONS = setOf("flac", "m4a", "mp3", "opus", "wav")
    }
}

class YtDlpUniversalExtractor(private val engine: YtDlpEngine) : MediaExtractor {
    override fun canHandle(url: String): Boolean = isHttpUrl(url)

    override fun extract(url: String, referer: String?, callback: MediaExtractor.Callback) {
        if (!engine.isInitialized) {
            callback.onError("El motor yt-dlp todavía no está listo.")
            return
        }
        val processId = engine.getInfo(url, referer, object : YtDlpEngine.InfoCallback {
            override fun onInfo(info: YtDlpMediaInfo) {
                callback.onResult(info)
            }

            override fun onError(message: String) {
                callback.onError(message)
            }
        })
        if (processId != null) {
            callback.onStarted(processId)
        }
    }

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.host != null && uri.userInfo == null
            && (uri.scheme.equals("https", ignoreCase = true)
            || uri.scheme.equals("http", ignoreCase = true))
    } catch (_: Exception) {
        false
    }
}

class ChainExtractor(
    directExtractor: MediaExtractor,
    nativeExtractors: List<MediaExtractor>,
    universalExtractor: MediaExtractor
) {
    private val extractors = listOf(directExtractor) + nativeExtractors + universalExtractor

    fun isDirectMedia(url: String): Boolean = extractors.first().canHandle(url)

    fun extract(url: String, referer: String?, callback: MediaExtractor.Callback) {
        fun attempt(index: Int, lastError: String?) {
            if (index >= extractors.size) {
                callback.onError(lastError ?: "No hay un extractor compatible para esta dirección.")
                return
            }
            val extractor = extractors[index]
            if (!extractor.canHandle(url)) {
                attempt(index + 1, lastError)
                return
            }
            val completed = AtomicBoolean(false)
            try {
                extractor.extract(url, referer, object : MediaExtractor.Callback {
                    override fun onStarted(processId: String) {
                        if (!completed.get()) {
                            callback.onStarted(processId)
                        }
                    }

                    override fun onResult(info: YtDlpMediaInfo) {
                        if (completed.compareAndSet(false, true)) {
                            callback.onResult(info)
                        }
                    }

                    override fun onNotHandled() {
                        if (completed.compareAndSet(false, true)) {
                            attempt(index + 1, lastError)
                        }
                    }

                    override fun onError(message: String) {
                        if (completed.compareAndSet(false, true)) {
                            attempt(index + 1, message)
                        }
                    }
                })
            } catch (exception: Exception) {
                if (completed.compareAndSet(false, true)) {
                    attempt(index + 1, exception.message ?: lastError)
                }
            }
        }
        attempt(0, null)
    }
}

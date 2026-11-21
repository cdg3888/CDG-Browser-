package com.cdg.browser.ytdlp

import com.farimarwat.commons.VideoFormat
import com.farimarwat.commons.VideoInfo
import com.farimarwat.library.YoutubeDL
import java.net.URI

data class YtDlpFormatOption(
    val selector: String,
    val title: String,
    val details: String,
    val videoUrl: String?,
    val audioUrl: String?,
    val audioOnly: Boolean,
    val height: Int = 0,
    val bitrateKbps: Int = 0
)

data class YtDlpMediaInfo(
    val title: String,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val formats: List<YtDlpFormatOption>
)

internal object YtDlpFormats {
    private val videoExtensions = setOf(
        "3gp", "avi", "flv", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm"
    )

    fun parse(json: String): YtDlpMediaInfo? {
        val info = try {
            YoutubeDL.getInstance().objectMapper.readValue(json, VideoInfo::class.java)
        } catch (_: Exception) {
            return null
        }
        return fromVideoInfo(info)
    }

    internal fun fromVideoInfo(info: VideoInfo): YtDlpMediaInfo? {
        val title = info.title?.takeIf { it.isNotBlank() } ?: info.id?.takeIf { it.isNotBlank() }
            ?: return null
        val sourceFormats = (info.formats.orEmpty() + info.requestedFormats.orEmpty())
            .distinctBy { it.formatId }
            .mapNotNull(::toFormat)
        if (sourceFormats.isEmpty()) {
            return null
        }

        val audioFormats = sourceFormats.filter { it.hasAudio && !it.hasVideo }
        val bestAudio = audioFormats.maxWithOrNull(
            compareBy<ParsedFormat>({ it.abr }, { it.tbr }, { it.formatId })
        )
        val options = mutableListOf<YtDlpFormatOption>()

        sourceFormats.filter { it.hasVideo }
            .groupBy { if (it.height > 0) "height:${it.height}" else "format:${it.formatId}" }
            .values
            .mapNotNull { formats ->
                val muxed = formats.filter { it.hasAudio }
                    .maxWithOrNull(compareBy<ParsedFormat>({ it.height }, { it.tbr }))
                if (muxed != null) {
                    muxed to null
                } else {
                    val video = formats.maxWithOrNull(
                        compareBy<ParsedFormat>({ it.height }, { it.tbr }, { it.formatId })
                    ) ?: return@mapNotNull null
                    video to bestAudio
                }
            }
            .sortedWith(
                compareByDescending<Pair<ParsedFormat, ParsedFormat?>> { it.first.height }
                    .thenByDescending { it.first.tbr }
            )
            .forEach { (video, audio) ->
                val selector = if (audio == null) {
                    video.formatId
                } else {
                    "${video.formatId}+${audio.formatId}"
                }
                val resolution = video.height.takeIf { it > 0 }?.let { "${it}p" }
                    ?: video.formatNote?.takeIf { it.isNotBlank() }
                    ?: "Resolución no especificada"
                val codecs = listOfNotNull(
                    video.vcodec?.takeUnless { it.equals("none", ignoreCase = true) },
                    audio?.acodec
                        ?: video.acodec?.takeUnless { it.equals("none", ignoreCase = true) }
                ).joinToString(" + ").ifBlank { "códecs no indicados" }
                val container = video.ext?.uppercase()?.takeIf { it.isNotBlank() } ?: "FORMATO"
                val bitrate = video.tbr.takeIf { it > 0 }?.let { " · ${it} kb/s" }.orEmpty()
                val audioDescription = if (audio == null) {
                    if (video.hasAudio) "audio y video" else "solo video"
                } else {
                    "video + audio separado"
                }
                options += YtDlpFormatOption(
                    selector = selector,
                    title = "$resolution · $container$bitrate",
                    details = "$audioDescription · $codecs",
                    videoUrl = video.url,
                    audioUrl = audio?.url,
                    audioOnly = false,
                    height = video.height,
                    bitrateKbps = video.tbr
                )
            }

        if (audioFormats.isNotEmpty()) {
            val audio = bestAudio!!
            val bitrate = audio.abr.takeIf { it > 0 }?.let { " · ${it} kb/s" }.orEmpty()
            options += YtDlpFormatOption(
                selector = audio.formatId,
                title = "Solo audio · ${audio.ext.uppercase()}$bitrate",
                details = audio.acodec ?: "Códec de audio no indicado",
                videoUrl = null,
                audioUrl = audio.url,
                audioOnly = true,
                bitrateKbps = audio.abr
            )
        }

        val thumbnail = info.thumbnail?.takeIf(::isHttpUrl)
            ?: info.thumbnails.orEmpty().asSequence()
                .mapNotNull { it.url?.takeIf(::isHttpUrl) }
                .firstOrNull()
        return YtDlpMediaInfo(
            title = title,
            durationSeconds = info.duration.coerceAtLeast(0),
            thumbnailUrl = thumbnail,
            formats = options
        )
    }

    private fun toFormat(format: VideoFormat): ParsedFormat? {
        val id = format.formatId?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: return null
        val url = format.url?.takeIf(::isHttpUrl) ?: return null
        val extension = format.ext?.lowercase().orEmpty()
        if (extension == "mhtml") {
            return null
        }
        val videoCodec = format.vcodec?.takeUnless { it.equals("none", ignoreCase = true) }
        val audioCodec = format.acodec?.takeUnless { it.equals("none", ignoreCase = true) }
        val hasVideo = videoCodec != null || (format.vcodec == null
            && (format.height > 0 || extension in videoExtensions))
        val hasAudio = audioCodec != null || (format.acodec == null && videoCodec == null)
        if (!hasVideo && !hasAudio) {
            return null
        }
        return ParsedFormat(
            formatId = id,
            url = url,
            ext = extension.ifBlank { "unknown" },
            height = format.height.coerceAtLeast(0),
            formatNote = format.formatNote,
            vcodec = videoCodec,
            acodec = audioCodec,
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            abr = format.abr.coerceAtLeast(0),
            tbr = format.tbr.coerceAtLeast(0)
        )
    }

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.host != null && uri.userInfo == null
            && (uri.scheme.equals("https", ignoreCase = true)
            || uri.scheme.equals("http", ignoreCase = true))
    } catch (_: Exception) {
        false
    }

    private data class ParsedFormat(
        val formatId: String,
        val url: String,
        val ext: String,
        val height: Int,
        val formatNote: String?,
        val vcodec: String?,
        val acodec: String?,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val abr: Int,
        val tbr: Int
    )
}

package com.cdg.browser.ytdlp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farimarwat.commons.UpdateChannel
import com.farimarwat.commons.UpdateStatus
import com.farimarwat.commons.YoutubeDLRequest
import com.farimarwat.library.YoutubeDL
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class YtDlpEngine(context: Context) {
    interface ResultCallback {
        fun onReady()
        fun onError(message: String)
    }

    interface InfoCallback {
        fun onInfo(title: String, durationSeconds: Int)
        fun onError(message: String)
    }

    interface DownloadCallback {
        fun onStarted(processId: String)
        fun onProgress(percent: Float, etaSeconds: Long, status: String)
        fun onComplete(file: File)
        fun onError(message: String)
    }

    interface UpdateCallback {
        fun onComplete(version: String, updated: Boolean)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initializationLock = Any()
    private val initializationCallbacks = mutableListOf<ResultCallback>()
    private var initialized = false
    private var initializing = false

    fun initialize(callback: ResultCallback) {
        val startInitialization = synchronized(initializationLock) {
            if (initialized) {
                mainHandler.post { callback.onReady() }
                false
            } else {
                initializationCallbacks.add(callback)
                if (initializing) {
                    false
                } else {
                    initializing = true
                    true
                }
            }
        }
        if (!startInitialization) return

        YoutubeDL.init(
            appContext,
            withFfmpeg = true,
            withAria2c = false,
            onSuccess = {
                val callbacks = synchronized(initializationLock) {
                    initialized = true
                    initializing = false
                    initializationCallbacks.toList().also { initializationCallbacks.clear() }
                }
                callbacks.forEach { it.onReady() }
            },
            onError = { error ->
                val callbacks = synchronized(initializationLock) {
                    initializing = false
                    initializationCallbacks.toList().also { initializationCallbacks.clear() }
                }
                val message = error.message ?: "No se pudo iniciar yt-dlp."
                mainHandler.post { callbacks.forEach { it.onError(message) } }
            }
        )
    }

    fun getInfo(url: String, callback: InfoCallback) {
        if (!initialized) {
            mainHandler.post { callback.onError("El motor yt-dlp todavía no está listo.") }
            return
        }
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
        YoutubeDL.getInfo(
            request,
            onSuccess = { info ->
                callback.onInfo(info.title ?: url, info.duration)
            },
            onError = { error ->
                mainHandler.post {
                    callback.onError(error.message ?: "No se pudo analizar esta dirección.")
                }
            }
        )
    }

    fun version(): String {
        if (!initialized) {
            return "No inicializado"
        }
        return YoutubeDL.versionName(appContext) ?: "Desconocida"
    }

    fun update(callback: UpdateCallback) {
        if (!initialized) {
            mainHandler.post { callback.onError("El motor yt-dlp todavía no está listo.") }
            return
        }
        if (YoutubeDL.getInProgressDownloadsCount() > 0) {
            mainHandler.post {
                callback.onError("Espera a que terminen las descargas antes de actualizar yt-dlp.")
            }
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val previousVersion = YoutubeDL.versionName(appContext) ?: "Desconocida"
                var updateError: Throwable? = null
                var updateStatus: UpdateStatus? = null
                YoutubeDL.updateYoutubeDL(
                    appContext,
                    UpdateChannel.STABLE,
                    { status -> updateStatus = status },
                    { error -> updateError = error }
                )
                if (updateError != null) {
                    val message = updateError?.message ?: "No se pudo actualizar yt-dlp."
                    mainHandler.post { callback.onError(message) }
                    return@launch
                }
                val version = YoutubeDL.versionName(appContext) ?: "Desconocida"
                mainHandler.post {
                    callback.onComplete(
                        version,
                        updateStatus == UpdateStatus.DONE || version != previousVersion
                    )
                }
            } catch (error: Exception) {
                val message = error.message ?: "No se pudo actualizar yt-dlp."
                mainHandler.post { callback.onError(message) }
            }
        }
    }

    fun download(
        url: String,
        outputDirectory: File,
        formatSelector: String,
        audioOnly: Boolean,
        includeSubtitles: Boolean,
        callback: DownloadCallback
    ) {
        if (!initialized) {
            mainHandler.post { callback.onError("El motor yt-dlp todavía no está listo.") }
            return
        }
        if ((!outputDirectory.exists() && !outputDirectory.mkdirs()) ||
            !outputDirectory.isDirectory
        ) {
            mainHandler.post { callback.onError("No se pudo crear el directorio temporal.") }
            return
        }

        val processId = UUID.randomUUID().toString()
        val request = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("-f", formatSelector)
            .addOption("-o", File(outputDirectory, "%(title).80B.%(ext)s").absolutePath)
        if (audioOnly) {
            request.addOption("-x")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "0")
        } else {
            request.addOption("--merge-output-format", "mp4/mkv")
            if (includeSubtitles) {
                request.addOption("--write-subs")
                    .addOption("--embed-subs")
                    .addOption("--sub-langs", "all,-live_chat")
            }
        }

        YoutubeDL.download(
            request,
            pId = processId,
            progressCallBack = { percent, etaSeconds, line ->
                mainHandler.post { callback.onProgress(percent, etaSeconds, line) }
            },
            onStartProcess = { id ->
                callback.onStarted(id)
            },
            onEndProcess = {
                val file = outputDirectory.listFiles()
                    ?.filter {
                        it.isFile && it.extension.lowercase() in
                                setOf(
                                    "mp4", "m4v", "mov", "webm", "mkv", "avi", "mpeg", "mpg", "3gp",
                                    "mp3", "m4a", "opus", "flac", "wav"
                                )
                    }
                    ?.maxByOrNull { it.lastModified() }
                if (file == null) {
                    callback.onError("yt-dlp terminó sin producir un archivo de video.")
                } else {
                    callback.onComplete(file)
                }
            },
            onError = { error ->
                val message = if (error is YoutubeDL.CanceledException) {
                    "Descarga cancelada."
                } else {
                    error.message ?: "Falló la descarga con yt-dlp."
                }
                mainHandler.post { callback.onError(message) }
            }
        )
    }

    fun cancel(processId: String): Boolean = YoutubeDL.destroyProcessById(processId)
}

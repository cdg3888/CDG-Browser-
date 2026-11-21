package com.cdg.browser.ytdlp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farimarwat.commons.YoutubeDLRequest
import com.farimarwat.library.YoutubeDL
import java.io.File
import java.util.UUID

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

    fun download(url: String, outputDirectory: File, callback: DownloadCallback) {
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
            .addOption("-f", "bestvideo*+bestaudio/best")
            .addOption("--merge-output-format", "mp4/mkv")
            .addOption("-o", File(outputDirectory, "%(title).80B.%(ext)s").absolutePath)

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
                            setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "mpeg", "mpg", "3gp")
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

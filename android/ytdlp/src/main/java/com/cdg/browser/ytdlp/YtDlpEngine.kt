package com.cdg.browser.ytdlp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farimarwat.commons.YoutubeDLRequest
import com.farimarwat.library.YoutubeDL
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YtDlpEngine(context: Context) {
    interface ResultCallback {
        fun onReady()
        fun onError(message: String)
    }

    interface InfoCallback {
        fun onInfo(info: YtDlpMediaInfo)
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
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationLock = Any()
    private val processLock = Any()
    private val initializationCallbacks = mutableListOf<ResultCallback>()
    private val infoOperations = ConcurrentHashMap<String, InfoOperation>()
    private val activeProcessIds = ConcurrentHashMap.newKeySet<String>()
    private val updateInProgress = AtomicBoolean(false)
    private val verifiedUpdater = VerifiedYtDlpUpdater(appContext)
    private var initialized = false
    private var initializing = false
    val isInitialized: Boolean
        get() = synchronized(initializationLock) { initialized }

    private class InfoOperation(val callback: InfoCallback) {
        val finished = AtomicBoolean(false)
        @Volatile var job: Job? = null
        @Volatile var timeout: Runnable? = null
    }

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
                mainHandler.post { callbacks.forEach { it.onReady() } }
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

    fun getInfo(url: String, callback: InfoCallback): String? = getInfo(url, null, callback)

    fun getInfo(url: String, referer: String?, callback: InfoCallback): String? {
        if (!initialized) {
            mainHandler.post { callback.onError("El motor yt-dlp todavía no está listo.") }
            return null
        }
        if (!isHttpUrl(url)) {
            mainHandler.post { callback.onError("Solo se admiten direcciones HTTP o HTTPS válidas.") }
            return null
        }
        val processId = UUID.randomUUID().toString()
        val operation = InfoOperation(callback)
        synchronized(processLock) {
            if (updateInProgress.get()) {
                mainHandler.post {
                    callback.onError("yt-dlp se está actualizando. Inténtalo de nuevo.")
                }
                return null
            }
            infoOperations[processId] = operation
            activeProcessIds.add(processId)
        }
        operation.timeout = Runnable {
            operation.job?.cancel()
            YoutubeDL.destroyProcessById(processId)
            finishInfo(processId, operation, error = "El análisis superó el tiempo máximo.")
        }
        mainHandler.postDelayed(operation.timeout!!, INFO_TIMEOUT_MS)

        val request = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--skip-download")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", NETWORK_TIMEOUT_SECONDS)
        if (isHttpUrl(referer)) {
            request.addOption("--referer", referer!!)
        }
        try {
            operation.job = YoutubeDL.download(
                request,
                pId = processId,
                progressCallBack = { _, _, _ -> },
                onStartProcess = {},
                onEndProcess = { response ->
                    val info = if (response.exitCode == 0) {
                        YtDlpFormats.parse(response.out)
                    } else {
                        null
                    }
                    finishInfo(
                        processId,
                        operation,
                        info = info,
                        error = if (response.exitCode == 0) {
                            "yt-dlp no devolvió formatos reproducibles."
                        } else {
                            "yt-dlp no pudo analizar esta dirección."
                        }
                    )
                },
                onError = { error ->
                    finishInfo(
                        processId,
                        operation,
                        error = error.message ?: "No se pudo analizar esta dirección."
                    )
                }
            )
        } catch (error: Exception) {
            finishInfo(
                processId,
                operation,
                error = error.message ?: "No se pudo iniciar el análisis."
            )
            return null
        }
        if (operation.finished.get()) {
            operation.job?.cancel()
            YoutubeDL.destroyProcessById(processId)
        }
        return processId
    }

    private fun finishInfo(
        processId: String,
        operation: InfoOperation,
        info: YtDlpMediaInfo? = null,
        error: String
    ) {
        if (!operation.finished.compareAndSet(false, true)) {
            return
        }
        synchronized(processLock) {
            infoOperations.remove(processId, operation)
            activeProcessIds.remove(processId)
        }
        operation.timeout?.let(mainHandler::removeCallbacks)
        mainHandler.post {
            if (info != null) {
                operation.callback.onInfo(info)
            } else {
                operation.callback.onError(error)
            }
        }
    }

    fun version(): String {
        if (!initialized) {
            return "No inicializado"
        }
        return verifiedUpdater.currentVersion()
    }

    fun update(callback: UpdateCallback) {
        if (!initialized) {
            mainHandler.post { callback.onError("El motor yt-dlp todavía no está listo.") }
            return
        }
        val canStart = synchronized(processLock) {
            if (updateInProgress.get()
                || activeProcessIds.isNotEmpty()
                || YoutubeDL.getInProgressDownloadsCount() > 0
            ) {
                false
            } else {
                updateInProgress.set(true)
                true
            }
        }
        if (!canStart) {
            mainHandler.post {
                callback.onError("Espera a que terminen los análisis y descargas antes de actualizar yt-dlp.")
            }
            return
        }
        ioScope.launch {
            try {
                val (version, updated) = verifiedUpdater.update()
                mainHandler.post {
                    callback.onComplete(version, updated)
                }
            } catch (error: Exception) {
                val message = error.message ?: "No se pudo actualizar yt-dlp."
                mainHandler.post { callback.onError(message) }
            } finally {
                synchronized(processLock) {
                    updateInProgress.set(false)
                }
            }
        }
    }

    fun download(
        url: String,
        outputDirectory: File,
        formatSelector: String,
        audioOnly: Boolean,
        includeSubtitles: Boolean,
        referer: String?,
        callback: DownloadCallback
    ) {
        if (!initialized) {
            mainHandler.post { callback.onError("El motor yt-dlp todavía no está listo.") }
            return
        }
        if (!isHttpUrl(url) || formatSelector.isBlank()) {
            mainHandler.post { callback.onError("La dirección o el formato de descarga no son válidos.") }
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
            .addOption("--socket-timeout", NETWORK_TIMEOUT_SECONDS)
        if (isHttpUrl(referer)) {
            request.addOption("--referer", referer!!)
        }
        if (audioOnly) {
            request.addOption("-x")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "0")
        } else {
            if (includeSubtitles) {
                request.addOption("--write-subs")
                    .addOption("--embed-subs")
                    .addOption("--sub-langs", "all,-live_chat")
            }
        }

        synchronized(processLock) {
            if (updateInProgress.get()) {
                mainHandler.post {
                    callback.onError("yt-dlp se está actualizando. Inténtalo de nuevo.")
                }
                return
            }
            activeProcessIds.add(processId)
        }
        try {
            YoutubeDL.download(
                request,
                pId = processId,
                progressCallBack = { percent, etaSeconds, line ->
                    mainHandler.post { callback.onProgress(percent, etaSeconds, line) }
                },
                onStartProcess = { id ->
                    mainHandler.post { callback.onStarted(id) }
                },
                onEndProcess = { response ->
                    synchronized(processLock) {
                        activeProcessIds.remove(processId)
                    }
                    if (response.exitCode != 0) {
                        mainHandler.post {
                            callback.onError("yt-dlp no pudo completar la descarga.")
                        }
                    } else {
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
                            mainHandler.post {
                                callback.onError("yt-dlp terminó sin producir un archivo multimedia.")
                            }
                        } else {
                            mainHandler.post { callback.onComplete(file) }
                        }
                    }
                },
                onError = { error ->
                    synchronized(processLock) {
                        activeProcessIds.remove(processId)
                    }
                    val message = if (error is YoutubeDL.CanceledException) {
                        "Descarga cancelada."
                    } else {
                        error.message ?: "Falló la descarga con yt-dlp."
                    }
                    mainHandler.post { callback.onError(message) }
                }
            )
        } catch (error: Exception) {
            synchronized(processLock) {
                activeProcessIds.remove(processId)
            }
            mainHandler.post {
                callback.onError(error.message ?: "No se pudo iniciar la descarga con yt-dlp.")
            }
        }
    }

    fun cancel(processId: String): Boolean = YoutubeDL.destroyProcessById(processId)

    fun cancelInfo(processId: String): Boolean {
        val operation = infoOperations[processId] ?: return false
        val wasActive = !operation.finished.get()
        operation.job?.cancel()
        YoutubeDL.destroyProcessById(processId)
        finishInfo(processId, operation, error = "Análisis cancelado.")
        return wasActive
    }

    private fun isHttpUrl(value: String?): Boolean = try {
        val uri = value?.let(::URI) ?: return false
        uri.host != null && uri.userInfo == null
            && (uri.scheme.equals("https", ignoreCase = true)
            || uri.scheme.equals("http", ignoreCase = true))
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val NETWORK_TIMEOUT_SECONDS = 20
        const val INFO_TIMEOUT_MS = 45_000L
    }
}

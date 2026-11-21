package com.cdg.browser.ytdlp

import android.content.Context
import com.farimarwat.commons.YoutubeDLRequest
import com.farimarwat.library.YoutubeDL
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal data class OfficialYtDlpRelease(
    val tag: String,
    val assetUrl: String,
    val sha256: String
)

internal object OfficialYtDlpReleaseParser {
    private val digestPattern = Regex("sha256:([a-fA-F0-9]{64})")

    fun parse(json: String): OfficialYtDlpRelease {
        val root = YoutubeDL.getInstance().objectMapper.readTree(json)
            ?: throw IOException("La respuesta de GitHub no contiene datos de release.")
        val tag = root.path("tag_name").asText()
        if (!tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}"))) {
            throw IOException("La release de yt-dlp no tiene una etiqueta válida.")
        }
        val asset = root.path("assets").firstOrNull {
            it.path("name").asText() == "yt-dlp"
        } ?: throw IOException("La release no incluye el ejecutable yt-dlp esperado.")
        val assetUrl = asset.path("browser_download_url").asText()
        val uri = try {
            URI(assetUrl)
        } catch (_: Exception) {
            throw IOException("La URL del ejecutable de yt-dlp no es válida.")
        }
        val expectedPath = "/yt-dlp/yt-dlp/releases/download/$tag/yt-dlp"
        if (!uri.scheme.equals("https", ignoreCase = true)
            || !uri.host.equals("github.com", ignoreCase = true)
            || (uri.port != -1 && uri.port != 443)
            || uri.userInfo != null || uri.query != null || uri.fragment != null
            || uri.path != expectedPath
        ) {
            throw IOException("El ejecutable no procede de la release oficial de yt-dlp.")
        }
        val digest = digestPattern.matchEntire(asset.path("digest").asText())
            ?: throw IOException("GitHub no proporcionó un digest SHA-256 verificable.")
        return OfficialYtDlpRelease(tag, assetUrl, digest.groupValues[1].lowercase())
    }
}

internal class VerifiedYtDlpUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun currentVersion(): String = preferences.getString(VERIFIED_VERSION_KEY, null)
        ?: YoutubeDL.versionName(appContext)
        ?: "Desconocida"

    fun update(): Pair<String, Boolean> {
        val release = fetchRelease()
        val directory = File(
            File(appContext.noBackupFilesDir, "youtubeDl-android"),
            "yt-dlp"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("No se pudo crear el directorio privado de yt-dlp.")
        }
        if (!directory.isDirectory) {
            throw IOException("El destino privado de yt-dlp no es un directorio.")
        }
        val target = File(directory, "yt-dlp")
        if (!target.isFile) {
            throw IOException("No se encontró el ejecutable instalado para actualizar.")
        }
        if (sha256(target) == release.sha256) {
            return release.tag to false
        }

        val staged = File(directory, "yt-dlp.verified-new")
        val backup = File(directory, "yt-dlp.verified-previous")
        val previousVersion = preferences.getString(VERIFIED_VERSION_KEY, null)
        try {
            downloadAsset(release, staged)
            validateZipapp(staged)
            if (!staged.setExecutable(true, true)) {
                throw IOException("No se pudo marcar el ejecutable actualizado como ejecutable.")
            }
            backupFile(target, backup)

            var swapped = false
            try {
                Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                swapped = true
                val installedVersion = verifyInstalledVersion()
                if (installedVersion.removePrefix("v") != release.tag.removePrefix("v")) {
                    throw IOException("La versión instalada no coincide con la release verificada.")
                }
                if (!preferences.edit().putString(VERIFIED_VERSION_KEY, installedVersion).commit()) {
                    throw IOException("No se pudo guardar la versión verificada de yt-dlp.")
                }
                Files.deleteIfExists(backup.toPath())
                return installedVersion to true
            } catch (error: Exception) {
                if (swapped) {
                    try {
                        if (backup.isFile) {
                            Files.move(backup.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                        } else {
                            Files.deleteIfExists(target.toPath())
                        }
                        if (!preferences.edit()
                                .putString(VERIFIED_VERSION_KEY, previousVersion)
                                .commit()
                        ) {
                            throw IOException("No se pudo restaurar la versión anterior guardada.")
                        }
                    } catch (rollbackError: Exception) {
                        val rollbackFailure = IOException(
                            "La actualización falló y la restauración del ejecutable anterior no terminó.",
                            error
                        )
                        rollbackFailure.addSuppressed(rollbackError)
                        throw rollbackFailure
                    }
                }
                throw error
            }
        } finally {
            Files.deleteIfExists(staged.toPath())
        }
    }

    private fun fetchRelease(): OfficialYtDlpRelease {
        val connection = httpsConnection(RELEASE_API_URL, API_HOSTS)
        try {
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                throw IOException("GitHub respondió HTTP ${connection.responseCode}.")
            }
            val json = connection.inputStream.use { readLimited(it, MAX_API_BYTES) }
            return OfficialYtDlpReleaseParser.parse(String(json, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAsset(release: OfficialYtDlpRelease, destination: File) {
        val connection = httpsConnection(release.assetUrl, ASSET_HOSTS)
        try {
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                throw IOException("La descarga del ejecutable respondió HTTP ${connection.responseCode}.")
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_ASSET_BYTES) {
                throw IOException("El ejecutable de yt-dlp excede el tamaño permitido.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val startedAt = System.nanoTime()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        bytes += count
                        if (bytes > MAX_ASSET_BYTES
                            || System.nanoTime() - startedAt > MAX_DOWNLOAD_NANOS
                        ) {
                            throw IOException("La descarga del ejecutable excedió sus límites.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (bytes == 0L || digest.digest().toHex() != release.sha256) {
                throw IOException("El SHA-256 del ejecutable no coincide con la release oficial.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpsConnection(url: String, allowedHosts: Set<String>): HttpsURLConnection {
        var uri = try {
            URI(url)
        } catch (_: Exception) {
            throw IOException("La URL de actualización no es válida.")
        }
        repeat(MAX_REDIRECTS + 1) { redirect ->
            validateHttpsUri(uri, allowedHosts)
            val connection = URL(uri.toString()).openConnection() as? HttpsURLConnection
                ?: throw IOException("No se pudo establecer una conexión HTTPS.")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            if (url == RELEASE_API_URL) {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "CDG-Browser")
            }
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 300..399) {
                return connection
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (redirect == MAX_REDIRECTS || location.isNullOrBlank()) {
                throw IOException("GitHub redirigió demasiadas veces o sin destino válido.")
            }
            uri = try {
                uri.resolve(location)
            } catch (_: Exception) {
                throw IOException("GitHub redirigió a una URL no válida.")
            }
        }
        throw IOException("GitHub redirigió demasiadas veces.")
    }

    private fun validateHttpsUri(uri: URI, allowedHosts: Set<String>) {
        if (!uri.scheme.equals("https", ignoreCase = true)
            || uri.host?.lowercase() !in allowedHosts
            || (uri.port != -1 && uri.port != 443)
            || uri.userInfo != null
        ) {
            throw IOException("GitHub redirigió fuera de los hosts HTTPS autorizados.")
        }
    }

    private fun validateZipapp(file: File) {
        if (file.length() < MIN_ASSET_BYTES || file.length() > MAX_ASSET_BYTES) {
            throw IOException("El archivo descargado no tiene el tamaño esperado.")
        }
        val header = ByteArray(4096)
        val count = FileInputStream(file).use { it.read(header) }
        val hasZipHeader = (0 until (count - 1).coerceAtLeast(0)).any {
            header[it] == 'P'.code.toByte() && header[it + 1] == 'K'.code.toByte()
        }
        if (count < 2 || header[0] != '#'.code.toByte() || header[1] != '!'.code.toByte()
            || !hasZipHeader
        ) {
            throw IOException("El archivo no tiene el formato esperado del zipapp de yt-dlp.")
        }
    }

    private fun backupFile(source: File, backup: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(backup).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!backup.setExecutable(source.canExecute(), true)) {
            Files.deleteIfExists(backup.toPath())
            throw IOException("No se pudo conservar una copia de seguridad del ejecutable.")
        }
    }

    private fun verifyInstalledVersion(): String {
        val processId = "yt-dlp-version-check-${System.nanoTime()}"
        val complete = CountDownLatch(1)
        val settled = AtomicBoolean(false)
        val version = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        YoutubeDL.download(
            YoutubeDLRequest(emptyList()).addOption("--version"),
            pId = processId,
            progressCallBack = { _, _, _ -> },
            onStartProcess = {},
            onEndProcess = { response ->
                if (settled.compareAndSet(false, true)) {
                    if (response.exitCode == 0) {
                        version.set(response.out.trim().lineSequence().firstOrNull().orEmpty())
                    } else {
                        failure.set(IOException("yt-dlp no pudo verificar la versión instalada."))
                    }
                    complete.countDown()
                }
            },
            onError = { error ->
                if (settled.compareAndSet(false, true)) {
                    failure.set(error)
                    complete.countDown()
                }
            }
        )
        if (!complete.await(VERSION_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            YoutubeDL.destroyProcessById(processId)
            throw IOException("La verificación del ejecutable superó el tiempo máximo.")
        }
        failure.get()?.let { throw IOException("No se pudo verificar el ejecutable actualizado.", it) }
        return version.get()?.takeIf { it.isNotBlank() }
            ?: throw IOException("yt-dlp devolvió una versión vacía.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                return output.toByteArray()
            }
            if (output.size() + count > maxBytes) {
                throw IOException("La respuesta de la release excede el tamaño permitido.")
            }
            output.write(buffer, 0, count)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

    private companion object {
        const val PREFERENCES = "ytdlp"
        const val VERIFIED_VERSION_KEY = "verified_version"
        const val RELEASE_API_URL = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val VERSION_CHECK_TIMEOUT_SECONDS = 30L
        const val MAX_REDIRECTS = 5
        const val MAX_API_BYTES = 2 * 1024 * 1024
        const val MAX_ASSET_BYTES = 50L * 1024 * 1024
        const val MIN_ASSET_BYTES = 1_000_000L
        const val MAX_DOWNLOAD_NANOS = 3L * 60L * 1_000_000_000L
        val API_HOSTS = setOf("api.github.com")
        val ASSET_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )
    }
}

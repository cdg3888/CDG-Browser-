package com.cdg.browser.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VideoDownloadManager implements AutoCloseable {
    private static final long MAX_IMAGE_BYTES = 40L * 1024L * 1024L;
    private static final int MAX_IMAGE_REDIRECTS = 4;
    private static final int IMAGE_CONNECT_TIMEOUT_MS = 15_000;
    private static final int IMAGE_READ_TIMEOUT_MS = 30_000;

    public interface Listener {
        void onProgress(long bytes);

        void onComplete(String location, String filename, long bytes);

        void onError(String message);
    }

    private static final class DownloadTask {
        final String id = UUID.randomUUID().toString();
        volatile InputStream input;
        final Listener listener;
        final AtomicBoolean completed = new AtomicBoolean();
        volatile Future<?> future;
        volatile OutputStream output;
        volatile Uri mediaUri;
        volatile HttpURLConnection connection;
        volatile File localFile;
        volatile boolean customFolder;
        volatile boolean cancelled;
        long maximumBytes;

        DownloadTask(InputStream input, Listener listener) {
            this.input = input;
            this.listener = listener;
        }
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();

    public VideoDownloadManager(Context context) {
        appContext = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    public static String mimeTypeForResponse(Map<String, String> headers) {
        String mimeType = getHeader(headers, "content-type");
        if (mimeType == null) {
            return null;
        }
        int parameters = mimeType.indexOf(';');
        return (parameters < 0 ? mimeType : mimeType.substring(0, parameters)).trim();
    }

    public static String filenameForResponse(String uri, Map<String, String> headers) {
        String disposition = getHeader(headers, "content-disposition");
        if (disposition != null) {
            Matcher encoded = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);
            Matcher plain = Pattern.compile("filename=\"?([^\";]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(disposition);
            try {
                if (encoded.find()) {
                    return sanitizeFilename(URLDecoder.decode(encoded.group(1), "UTF-8"));
                }
                if (plain.find()) {
                    return sanitizeFilename(plain.group(1).trim());
                }
            } catch (Exception ignored) {
                // Use the URL path if the server provided a malformed filename.
            }
        }
        String path = uri == null || uri.isEmpty()
                ? null : Uri.parse(uri).getLastPathSegment();
        return sanitizeFilename(path == null || path.isEmpty() ? "video.mp4" : path);
    }

    public static boolean supportsDirectVideo(String mimeType, String filename) {
        return isDirectVideoMime(mimeType) || isVideoFilename(filename);
    }

    public static String normalizedVideoMimeType(String mimeType, String filename) {
        if (isDirectVideoMime(mimeType)) {
            return mimeType.toLowerCase(Locale.ROOT);
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".avi")) {
            return "video/x-msvideo";
        }
        if (lower.endsWith(".3gp")) {
            return "video/3gpp";
        }
        if (lower.endsWith(".mpeg") || lower.endsWith(".mpg")) {
            return "video/mpeg";
        }
        return "video/mp4";
    }

    public static String withVideoExtension(String filename, String mimeType) {
        if (filename == null || filename.isEmpty()) {
            filename = "video";
        }
        if (isVideoFilename(filename) || mimeType == null) {
            return filename;
        }
        String extension;
        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "video/webm":
                extension = ".webm";
                break;
            case "video/quicktime":
                extension = ".mov";
                break;
            case "video/x-matroska":
                extension = ".mkv";
                break;
            case "video/x-msvideo":
                extension = ".avi";
                break;
            case "video/3gpp":
                extension = ".3gp";
                break;
            case "video/mpeg":
                extension = ".mpeg";
                break;
            default:
                extension = ".mp4";
        }
        return filename + extension;
    }

    public static boolean supportsDownloadFile(String filename) {
        return isVideoFilename(filename) || isAudioFilename(filename)
                || isImageFilename(filename);
    }

    public static String normalizedMediaMimeType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".avif")) {
            return "image/avif";
        }
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
            return "image/heic";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        return normalizedVideoMimeType(null, filename);
    }

    public String downloadImageFromUrl(
            String imageUrl,
            String pageUrl,
            String suggestedFilename,
            Uri customFolderUri,
            Listener listener) {
        Objects.requireNonNull(listener, "listener");
        URI parsed = parseHttpUri(imageUrl);
        if (parsed == null) {
            listener.onError("La dirección de imagen no es una URL pública HTTP o HTTPS válida.");
            return null;
        }
        DownloadTask task = new DownloadTask(null, listener);
        task.maximumBytes = MAX_IMAGE_BYTES;
        tasks.put(task.id, task);
        try {
            task.future = executor.submit(() ->
                    fetchAndSaveImage(task, parsed, pageUrl, suggestedFilename, customFolderUri));
            return task.id;
        } catch (RejectedExecutionException exception) {
            tasks.remove(task.id);
            listener.onError("El gestor de descargas ya está cerrado.");
            return null;
        }
    }

    private void fetchAndSaveImage(
            DownloadTask task,
            URI initialUri,
            String pageUrl,
            String suggestedFilename,
            Uri customFolderUri) {
        URI uri = initialUri;
        try {
            HttpURLConnection connection = null;
            for (int redirect = 0; redirect <= MAX_IMAGE_REDIRECTS; redirect++) {
                if (task.cancelled || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Descarga cancelada.");
                }
                if (!isPublicHttpUri(uri)) {
                    throw new IOException("La imagen redirigió a una dirección local o no válida.");
                }
                connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
                task.connection = connection;
                connection.setConnectTimeout(IMAGE_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(IMAGE_READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/*;q=0.9,*/*;q=0.1");
                if (isPublicHttpUri(parsePublicHttpUri(pageUrl))) {
                    connection.setRequestProperty("Referer", pageUrl);
                }
                int responseCode = connection.getResponseCode();
                if (responseCode >= 300 && responseCode <= 399) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    task.connection = null;
                    if (redirect == MAX_IMAGE_REDIRECTS || location == null) {
                        throw new IOException("La imagen redirigió demasiadas veces.");
                    }
                    URI next = uri.resolve(location);
                    if ("https".equalsIgnoreCase(uri.getScheme())
                            && !"https".equalsIgnoreCase(next.getScheme())) {
                        throw new IOException("Se rechazó una redirección de HTTPS a HTTP.");
                    }
                    uri = next;
                    continue;
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("El servidor de imagen respondió HTTP " + responseCode + ".");
                }
                String mimeType = mimeTypeForResponse(
                        java.util.Collections.singletonMap(
                                "Content-Type", connection.getContentType()));
                if (!isSupportedImageMime(mimeType)) {
                    throw new IOException("La URL no devolvió una imagen compatible.");
                }
                if ("image/jpg".equalsIgnoreCase(mimeType)) {
                    mimeType = "image/jpeg";
                }
                if (connection.getContentLengthLong() > MAX_IMAGE_BYTES) {
                    throw new IOException("La imagen excede el tamaño máximo de descarga.");
                }
                String filename = withMediaExtension(
                        sanitizeFilename(suggestedFilename), mimeType);
                task.input = connection.getInputStream();
                writeDownload(task, filename, mimeType, customFolderUri);
                return;
            }
            throw new IOException("No se pudo resolver la dirección final de la imagen.");
        } catch (Exception exception) {
            String message = task.cancelled ? "Descarga cancelada."
                    : exception.getMessage() == null ? "Falló la descarga de la imagen."
                    : exception.getMessage();
            if (task.completed.compareAndSet(false, true)) {
                task.listener.onError(message);
            }
            tasks.remove(task.id);
        } finally {
            HttpURLConnection connection = task.connection;
            if (connection != null) {
                connection.disconnect();
                task.connection = null;
            }
        }
    }

    public String download(
            InputStream input,
            String filename,
            String mimeType,
            Listener listener) {
        return download(input, filename, mimeType, null, listener);
    }

    public String download(
            InputStream input,
            String filename,
            String mimeType,
            Uri customFolderUri,
            Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (input == null || !(supportsDirectVideo(mimeType, filename)
                || isSupportedImageMime(mimeType) || isImageFilename(filename)
                || isAudioMime(mimeType) || isAudioFilename(filename))) {
            closeQuietly(input);
            listener.onError("El formato de descarga no es compatible.");
            return null;
        }
        String safeFilename = withMediaExtension(sanitizeFilename(filename), mimeType);
        String safeMimeType = isImageFilename(safeFilename) || isSupportedImageMime(mimeType)
                ? (mimeType != null && isSupportedImageMime(mimeType)
                        ? ("image/jpg".equalsIgnoreCase(mimeType)
                                ? "image/jpeg" : mimeType.toLowerCase(Locale.ROOT))
                        : normalizedMediaMimeType(safeFilename))
                : isAudioFilename(safeFilename)
                        ? normalizedMediaMimeType(safeFilename)
                        : normalizedVideoMimeType(mimeType, safeFilename);
        DownloadTask task = new DownloadTask(input, listener);
        tasks.put(task.id, task);
        try {
            task.future = executor.submit(() ->
                    writeDownload(task, safeFilename, safeMimeType, customFolderUri));
            return task.id;
        } catch (RejectedExecutionException exception) {
            tasks.remove(task.id);
            closeQuietly(input);
            listener.onError("El gestor de descargas ya está cerrado.");
            return null;
        }
    }

    public boolean cancel(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task == null || !task.completed.compareAndSet(false, true)) {
            return false;
        }
        task.cancelled = true;
        closeQuietly(task.input);
        closeQuietly(task.output);
        HttpURLConnection connection = task.connection;
        if (connection != null) {
            connection.disconnect();
        }
        Future<?> future = task.future;
        if (future != null) {
            future.cancel(true);
        }
        String cleanupError = deletePartialDownload(task);
        tasks.remove(task.id);
        task.listener.onError(cleanupError == null
                ? "Descarga cancelada."
                : "Descarga cancelada. " + cleanupError);
        return true;
    }

    private void writeDownload(
            DownloadTask task,
            String filename,
            String mimeType,
            Uri customFolderUri) {
        long bytes = 0;
        try (InputStream source = task.input) {
            if (task.cancelled) {
                throw new IOException("Descarga cancelada.");
            }
            OutputStream output;
            String savedFilename = filename;
            if (customFolderUri != null) {
                task.customFolder = true;
                String safe = uniqueDocumentFilename(customFolderUri, filename);
                task.mediaUri = DocumentsContract.createDocument(
                        appContext.getContentResolver(),
                        customFolderUri,
                        mimeType,
                        safe);
                if (task.mediaUri == null) {
                    throw new IOException("No se pudo crear el archivo en la carpeta elegida.");
                }
                savedFilename = safe;
                output = appContext.getContentResolver().openOutputStream(task.mediaUri);
                if (output == null) {
                    throw new IOException("No se pudo escribir en la carpeta elegida.");
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/CDG Browser/";
                savedFilename = uniqueMediaStoreFilename(filename, relativePath);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, savedFilename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                task.mediaUri = appContext.getContentResolver().insert(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                if (task.mediaUri == null) {
                    throw new IOException("No se pudo crear el archivo de destino.");
                }
                output = appContext.getContentResolver().openOutputStream(task.mediaUri);
                if (output == null) {
                    throw new IOException("No se pudo abrir el archivo de destino.");
                }
            } else {
                File directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (directory == null || (!directory.exists() && !directory.mkdirs())) {
                    throw new IOException("No se pudo crear la carpeta de descargas.");
                }
                savedFilename = uniqueFileName(directory, filename);
                task.localFile = new File(directory, savedFilename);
                output = new FileOutputStream(task.localFile);
            }
            task.output = output;

            try (OutputStream destination = output) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                task.listener.onProgress(0);
                while ((count = source.read(buffer)) != -1) {
                    if (task.cancelled || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Descarga cancelada.");
                    }
                    destination.write(buffer, 0, count);
                    bytes += count;
                    if (task.maximumBytes > 0 && bytes > task.maximumBytes) {
                        throw new IOException("La imagen excede el tamaño máximo de descarga.");
                    }
                    if (bytes % (512 * 1024) < buffer.length) {
                        task.listener.onProgress(bytes);
                    }
                }
            }
            task.output = null;
            if (task.cancelled) {
                throw new IOException("Descarga cancelada.");
            }

            String location;
            if (task.mediaUri != null && !task.customFolder) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                appContext.getContentResolver().update(task.mediaUri, values, null, null);
                location = task.mediaUri.toString();
            } else {
                location = task.localFile.getAbsolutePath();
            }
            if (task.completed.compareAndSet(false, true)) {
                task.listener.onComplete(location, savedFilename, bytes);
            }
        } catch (Exception exception) {
            String cleanupError = deletePartialDownload(task);
            String message = task.cancelled ? "Descarga cancelada." : exception.getMessage();
            if (cleanupError != null) {
                message = (message == null || message.isEmpty()
                        ? "Falló la descarga." : message) + " " + cleanupError;
            }
            if (task.completed.compareAndSet(false, true)) {
                task.listener.onError(message == null || message.isEmpty()
                        ? "Falló la descarga del video." : message);
            }
        } finally {
            task.output = null;
            tasks.remove(task.id);
        }
    }

    private String deletePartialDownload(DownloadTask task) {
        String error = null;
        if (task.mediaUri != null) {
            try {
                appContext.getContentResolver().delete(task.mediaUri, null, null);
            } catch (RuntimeException exception) {
                error = "No se pudo eliminar el archivo incompleto: " + exception.getMessage();
            }
        }
        if (task.localFile != null && task.localFile.exists() && !task.localFile.delete()) {
            error = "No se pudo eliminar la descarga incompleta.";
        }
        return error;
    }

    private String uniqueDocumentFilename(Uri folderUri, String filename) {
        String candidate = sanitizeFilename(filename);
        if (!documentNameExists(folderUri, candidate)) {
            return candidate;
        }
        int dot = candidate.lastIndexOf('.');
        String stem = dot > 0 ? candidate.substring(0, dot) : candidate;
        String extension = dot > 0 ? candidate.substring(dot) : "";
        for (int suffix = 2; suffix < 10000; suffix++) {
            String next = stem + " (" + suffix + ")" + extension;
            if (!documentNameExists(folderUri, next)) {
                return next;
            }
        }
        return candidate;
    }

    private boolean documentNameExists(Uri folderUri, String name) {
        try (Cursor cursor = appContext.getContentResolver().query(
                DocumentsContract.buildChildDocumentsUriUsingTree(
                        folderUri, DocumentsContract.getTreeDocumentId(folderUri)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor == null) {
                return false;
            }
            int nameColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cursor.moveToNext()) {
                if (nameColumn >= 0
                        && name.equalsIgnoreCase(cursor.getString(nameColumn))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String getHeader(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (String key : headers.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                return headers.get(key);
            }
        }
        return null;
    }

    private static boolean isDirectVideoMime(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        return normalized.equals("video/mp4")
                || normalized.equals("video/webm")
                || normalized.equals("video/quicktime")
                || normalized.equals("video/x-matroska")
                || normalized.equals("video/x-msvideo")
                || normalized.equals("video/3gpp")
                || normalized.equals("video/mpeg");
    }

    private static boolean isVideoFilename(String filename) {
        if (filename == null) {
            return false;
        }
        return filename.toLowerCase(Locale.ROOT)
                .matches(".*\\.(mp4|m4v|mov|webm|mkv|avi|mpeg|mpg|3gp)$");
    }

    private static boolean isAudioFilename(String filename) {
        if (filename == null) {
            return false;
        }
        return filename.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|opus|flac|wav)$");
    }

    private static boolean isAudioMime(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        return normalized.equals("audio/mpeg")
                || normalized.equals("audio/mp4")
                || normalized.equals("audio/ogg")
                || normalized.equals("audio/flac")
                || normalized.equals("audio/wav")
                || normalized.equals("audio/x-wav");
    }

    private static boolean isImageFilename(String filename) {
        if (filename == null) {
            return false;
        }
        return filename.toLowerCase(Locale.ROOT)
                .matches(".*\\.(avif|bmp|gif|heic|heif|jpe?g|png|webp)$");
    }

    private static boolean isSupportedImageMime(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg":
            case "image/jpg":
            case "image/png":
            case "image/webp":
            case "image/gif":
            case "image/avif":
            case "image/bmp":
            case "image/heic":
            case "image/heif":
                return true;
            default:
                return false;
        }
    }

    private static URI parsePublicHttpUri(String value) {
        URI uri = parseHttpUri(value);
        if (uri == null || !isPublicInternetHost(uri.getHost())) {
            return null;
        }
        return uri;
    }

    private static URI parseHttpUri(String value) {
        if (value == null || value.length() > 8192) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                return null;
            }
            return uri;
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean isPublicHttpUri(URI uri) {
        return uri != null && parsePublicHttpUri(uri.toString()) != null;
    }

    private static boolean isPublicInternetHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.equals("localhost") || normalized.endsWith(".localhost")
                || normalized.endsWith(".local") || normalized.endsWith(".internal")) {
            return false;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(normalized)) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                        || isCarrierGradeNatAddress(bytes)) {
                    return false;
                }
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean isCarrierGradeNatAddress(byte[] address) {
        if (address.length != 4) {
            return false;
        }
        int first = address[0] & 0xff;
        int second = address[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    private static String imageExtension(String mimeType) {
        switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/webp":
                return ".webp";
            case "image/gif":
                return ".gif";
            case "image/avif":
                return ".avif";
            case "image/bmp":
                return ".bmp";
            case "image/heic":
            case "image/heif":
                return ".heic";
            default:
                throw new IllegalArgumentException("Tipo de imagen no admitido.");
        }
    }

    private static String withMediaExtension(String filename, String mimeType) {
        if (isVideoFilename(filename) || isAudioFilename(filename) || mimeType == null) {
            return filename;
        }
        if (isSupportedImageMime(mimeType)) {
            if (isImageFilename(filename)) {
                String expectedExtension = imageExtension(mimeType);
                String lower = filename.toLowerCase(Locale.ROOT);
                if (lower.endsWith(expectedExtension)
                        || (("image/jpeg".equalsIgnoreCase(mimeType)
                        || "image/jpg".equalsIgnoreCase(mimeType))
                        && lower.endsWith(".jpeg"))) {
                    return filename;
                }
                return filename.replaceFirst(
                        "(?i)\\.(avif|bmp|gif|heic|heif|jpe?g|png|webp)$", "")
                        + expectedExtension;
            }
            return filename + imageExtension(mimeType);
        }
        if (isImageFilename(filename)) {
            return filename;
        }
        if (isAudioMime(mimeType)) {
            String extension;
            switch (mimeType.toLowerCase(Locale.ROOT)) {
                case "audio/mp4":
                    extension = ".m4a";
                    break;
                case "audio/ogg":
                    extension = ".opus";
                    break;
                case "audio/flac":
                    extension = ".flac";
                    break;
                case "audio/wav":
                case "audio/x-wav":
                    extension = ".wav";
                    break;
                default:
                    extension = ".mp3";
            }
            return filename + extension;
        }
        return withVideoExtension(filename, mimeType);
    }

    private String uniqueMediaStoreFilename(String filename, String relativePath)
            throws IOException {
        String stem = filename;
        String extension = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            stem = filename.substring(0, dot);
            extension = filename.substring(dot);
        }
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        for (int suffix = 0; suffix < 1000; suffix++) {
            String candidate = suffix == 0
                    ? filename : stem + " (" + suffix + ")" + extension;
            try (Cursor cursor = appContext.getContentResolver().query(
                    collection,
                    new String[]{MediaStore.Downloads.DISPLAY_NAME},
                    MediaStore.Downloads.DISPLAY_NAME + " = ? AND "
                            + MediaStore.Downloads.RELATIVE_PATH + " = ?",
                    new String[]{candidate, relativePath},
                    null)) {
                if (cursor == null) {
                    throw new IOException("No se pudo comprobar si el nombre ya existe.");
                }
                if (!cursor.moveToFirst()) {
                    return candidate;
                }
            }
        }
        throw new IOException("No se encontró un nombre libre para el archivo.");
    }

    private static String uniqueFileName(File directory, String filename) throws IOException {
        String stem = filename;
        String extension = "";
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            stem = filename.substring(0, dot);
            extension = filename.substring(dot);
        }
        for (int suffix = 0; suffix < 1000; suffix++) {
            String candidate = suffix == 0
                    ? filename : stem + " (" + suffix + ")" + extension;
            if (!new File(directory, candidate).exists()) {
                return candidate;
            }
        }
        throw new IOException("No se encontró un nombre libre para el archivo.");
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "video";
        }
        String safe = filename.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            return "video";
        }
        return safe.length() > 120 ? safe.substring(safe.length() - 120) : safe;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The input is discarded when a download cannot be queued.
        }
    }

    private static void closeQuietly(OutputStream output) {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException ignored) {
            // The output is discarded after cancellation.
        }
    }

    @Override
    public void close() {
        for (String taskId : new ArrayList<>(tasks.keySet())) {
            cancel(taskId);
        }
        executor.shutdown();
    }
}

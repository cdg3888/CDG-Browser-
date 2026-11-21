package com.cdg.browser.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    public interface Listener {
        void onProgress(long bytes);

        void onComplete(String location, String filename, long bytes);

        void onError(String message);
    }

    private static final class DownloadTask {
        final String id = UUID.randomUUID().toString();
        final InputStream input;
        final Listener listener;
        final AtomicBoolean completed = new AtomicBoolean();
        volatile Future<?> future;
        volatile OutputStream output;
        volatile Uri mediaUri;
        volatile File localFile;
        volatile boolean cancelled;

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
        return isVideoFilename(filename) || isAudioFilename(filename);
    }

    public static String normalizedMediaMimeType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
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

    public String download(
            InputStream input,
            String filename,
            String mimeType,
            Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (input == null || !(supportsDirectVideo(mimeType, filename)
                || isAudioMime(mimeType) || isAudioFilename(filename))) {
            closeQuietly(input);
            listener.onError("El formato de descarga no es compatible.");
            return null;
        }
        String safeFilename = withMediaExtension(sanitizeFilename(filename), mimeType);
        String safeMimeType = isAudioFilename(safeFilename)
                ? normalizedMediaMimeType(safeFilename)
                : normalizedVideoMimeType(mimeType, safeFilename);
        DownloadTask task = new DownloadTask(input, listener);
        tasks.put(task.id, task);
        try {
            task.future = executor.submit(() -> writeDownload(task, safeFilename, safeMimeType));
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
            String mimeType) {
        long bytes = 0;
        try (InputStream source = task.input) {
            if (task.cancelled) {
                throw new IOException("Descarga cancelada.");
            }
            OutputStream output;
            String savedFilename = filename;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            if (task.mediaUri != null) {
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

    private static String withMediaExtension(String filename, String mimeType) {
        if (isVideoFilename(filename) || isAudioFilename(filename) || mimeType == null) {
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

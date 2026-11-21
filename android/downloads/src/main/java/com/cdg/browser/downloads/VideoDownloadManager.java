package com.cdg.browser.downloads;

import android.content.ContentValues;
import android.content.Context;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VideoDownloadManager implements AutoCloseable {
    public interface Listener {
        void onProgress(long bytes);

        void onComplete(String location, long bytes);

        void onError(String message);
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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

    public void download(
            InputStream input,
            String filename,
            String mimeType,
            Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (input == null || !supportsDirectVideo(mimeType, filename)) {
            closeQuietly(input);
            listener.onError("Solo se admiten archivos de video directos compatibles.");
            return;
        }
        String safeFilename = withVideoExtension(sanitizeFilename(filename), mimeType);
        String safeMimeType = normalizedVideoMimeType(mimeType, safeFilename);
        try {
            executor.execute(() -> writeDownload(input, safeFilename, safeMimeType, listener));
        } catch (RejectedExecutionException exception) {
            closeQuietly(input);
            listener.onError("El gestor de descargas ya está cerrado.");
        }
    }

    private void writeDownload(
            InputStream input,
            String filename,
            String mimeType,
            Listener listener) {
        Uri mediaUri = null;
        File localFile = null;
        long bytes = 0;
        try (InputStream source = input) {
            OutputStream output;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/CDG Browser");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                mediaUri = appContext.getContentResolver().insert(
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                if (mediaUri == null) {
                    throw new IOException("No se pudo crear el archivo de destino.");
                }
                output = appContext.getContentResolver().openOutputStream(mediaUri);
                if (output == null) {
                    throw new IOException("No se pudo abrir el archivo de destino.");
                }
            } else {
                File directory = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (directory == null || (!directory.exists() && !directory.mkdirs())) {
                    throw new IOException("No se pudo crear la carpeta de descargas.");
                }
                localFile = new File(directory, filename);
                output = new FileOutputStream(localFile);
            }

            try (OutputStream destination = output) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                listener.onProgress(0);
                while ((count = source.read(buffer)) != -1) {
                    destination.write(buffer, 0, count);
                    bytes += count;
                    if (bytes % (512 * 1024) < buffer.length) {
                        listener.onProgress(bytes);
                    }
                }
            }

            String location;
            if (mediaUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                appContext.getContentResolver().update(mediaUri, values, null, null);
                location = mediaUri.toString();
            } else {
                location = localFile.getAbsolutePath();
            }
            listener.onComplete(location, bytes);
        } catch (Exception exception) {
            if (mediaUri != null) {
                appContext.getContentResolver().delete(mediaUri, null, null);
            }
            if (localFile != null && localFile.exists() && !localFile.delete()) {
                exception.addSuppressed(new IOException("No se pudo eliminar la descarga incompleta."));
            }
            String message = exception.getMessage();
            listener.onError(message == null || message.isEmpty()
                    ? "Falló la descarga del video." : message);
        }
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

    @Override
    public void close() {
        executor.shutdown();
    }
}

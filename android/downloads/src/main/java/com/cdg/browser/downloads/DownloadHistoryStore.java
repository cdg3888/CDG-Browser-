package com.cdg.browser.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class DownloadHistoryStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "cdg_download_history.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "completed_downloads";

    public static final class DownloadRecord {
        public final String sourceUrl;
        public final String filename;
        public final String location;
        public final long completedAt;

        DownloadRecord(String sourceUrl, String filename, String location, long completedAt) {
            this.sourceUrl = sourceUrl;
            this.filename = filename;
            this.location = location;
            this.completedAt = completedAt;
        }
    }

    public DownloadHistoryStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE " + TABLE + " ("
                + "url_key TEXT NOT NULL, "
                + "format_key TEXT NOT NULL, "
                + "source_url TEXT NOT NULL, "
                + "filename TEXT NOT NULL, "
                + "location TEXT NOT NULL, "
                + "completed_at INTEGER NOT NULL, "
                + "PRIMARY KEY (url_key, format_key))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("No se admite una migración desconocida del historial.");
    }

    public synchronized boolean contains(String sourceUrl, String formatSelector) {
        String normalizedUrl = normalizeUrl(sourceUrl);
        if (normalizedUrl == null) {
            return false;
        }
        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{"url_key"},
                "url_key = ? AND format_key = ?",
                new String[]{sha256(normalizedUrl), normalizeFormat(formatSelector)},
                null,
                null,
                null,
                "1")) {
            return cursor != null && cursor.moveToFirst();
        }
    }

    public synchronized void record(
            String sourceUrl, String formatSelector, String filename, String location) {
        String normalizedUrl = normalizeUrl(sourceUrl);
        if (normalizedUrl == null || filename == null || location == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("url_key", sha256(normalizedUrl));
        values.put("format_key", normalizeFormat(formatSelector));
        values.put("source_url", normalizedUrl);
        values.put("filename", filename);
        values.put("location", location);
        values.put("completed_at", System.currentTimeMillis());
        SQLiteDatabase database = getWritableDatabase();
        database.insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        database.execSQL("DELETE FROM " + TABLE + " WHERE rowid NOT IN "
                + "(SELECT rowid FROM " + TABLE + " ORDER BY completed_at DESC LIMIT 10000)");
    }

    public synchronized List<DownloadRecord> recent(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<DownloadRecord> records = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{"source_url", "filename", "location", "completed_at"},
                null,
                null,
                null,
                null,
                "completed_at DESC",
                String.valueOf(boundedLimit))) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    records.add(new DownloadRecord(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getLong(3)));
                }
            }
        }
        return records;
    }

    public synchronized void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    private String normalizeUrl(String value) {
        if (value == null || value.length() > 8192) {
            return null;
        }
        try {
            URI uri = new URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            return new URI(
                    uri.getScheme().toLowerCase(java.util.Locale.ROOT),
                    null,
                    uri.getHost().toLowerCase(java.util.Locale.ROOT),
                    uri.getPort(),
                    uri.getRawPath(),
                    uri.getRawQuery(),
                    null).toASCIIString();
        } catch (Exception exception) {
            return null;
        }
    }

    private String normalizeFormat(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible en Android.", exception);
        }
    }
}

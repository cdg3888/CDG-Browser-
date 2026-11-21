package com.cdg.browser.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DownloadCenterStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "cdg_download_center.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "download_entries";

    public static final class Entry {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String mimeType = "video/*";
        public String status = "En cola";
        public String location = "";
        public String engine = "";
        public String url = "";
        public String referer = "";
        public String formatSelector = "";
        public String processId;
        public boolean audioOnly;
        public boolean includeSubtitles;
        public boolean copying;
        public long bytes;
    }

    public DownloadCenterStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE " + TABLE + " ("
                + "id TEXT PRIMARY KEY NOT NULL, "
                + "name TEXT NOT NULL, "
                + "mime_type TEXT NOT NULL, "
                + "status TEXT NOT NULL, "
                + "location TEXT NOT NULL, "
                + "engine TEXT NOT NULL, "
                + "url TEXT NOT NULL, "
                + "referer TEXT NOT NULL, "
                + "format_selector TEXT NOT NULL, "
                + "process_id TEXT, "
                + "audio_only INTEGER NOT NULL, "
                + "include_subtitles INTEGER NOT NULL, "
                + "copying INTEGER NOT NULL, "
                + "bytes INTEGER NOT NULL, "
                + "position INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("No se admite una migración desconocida de descargas.");
    }

    public synchronized List<Entry> recent(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{
                        "id", "name", "mime_type", "status", "location", "engine",
                        "url", "referer", "format_selector", "process_id", "audio_only",
                        "include_subtitles", "copying", "bytes"
                },
                null,
                null,
                null,
                null,
                "position ASC",
                String.valueOf(boundedLimit))) {
            while (cursor.moveToNext()) {
                Entry entry = new Entry();
                entry.id = cursor.getString(0);
                entry.name = cursor.getString(1);
                entry.mimeType = cursor.getString(2);
                entry.status = cursor.getString(3);
                entry.location = cursor.getString(4);
                entry.engine = cursor.getString(5);
                entry.url = cursor.getString(6);
                entry.referer = cursor.getString(7);
                entry.formatSelector = cursor.getString(8);
                entry.processId = cursor.getString(9);
                entry.audioOnly = cursor.getInt(10) != 0;
                entry.includeSubtitles = cursor.getInt(11) != 0;
                entry.copying = cursor.getInt(12) != 0;
                entry.bytes = cursor.getLong(13);
                entries.add(entry);
            }
        }
        return entries;
    }

    public synchronized void replaceAll(List<Entry> entries, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete(TABLE, null, null);
            int count = Math.min(entries.size(), boundedLimit);
            for (int position = 0; position < count; position++) {
                Entry entry = entries.get(position);
                database.insertOrThrow(TABLE, null, toValues(entry, position));
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public synchronized void remove(String id) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{id});
    }

    private ContentValues toValues(Entry entry, int position) {
        ContentValues values = new ContentValues();
        values.put("id", entry.id);
        values.put("name", entry.name);
        values.put("mime_type", entry.mimeType);
        values.put("status", entry.status);
        values.put("location", entry.location);
        values.put("engine", entry.engine);
        values.put("url", entry.url);
        values.put("referer", entry.referer);
        values.put("format_selector", entry.formatSelector);
        values.put("process_id", entry.processId);
        values.put("audio_only", entry.audioOnly);
        values.put("include_subtitles", entry.includeSubtitles);
        values.put("copying", entry.copying);
        values.put("bytes", entry.bytes);
        values.put("position", position);
        return values;
    }
}

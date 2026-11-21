package com.cdg.browser.media;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MediaGalleryActivity extends Activity {
    public static final String EXTRA_FOLDER_URI = "com.cdg.browser.media.folder_uri";
    private static final int MAX_ITEMS = 300;
    private static final int BACKGROUND = 0xff0a0b0e;
    private static final int SURFACE = 0xff14151a;
    private static final int TEXT = 0xfff6f1eb;
    private static final int MUTED = 0xffa49b94;

    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final List<MediaFile> files = new ArrayList<>();
    private LinearLayout itemsContainer;
    private String loadError = "";

    private static final class MediaFile {
        final Uri uri;
        final String name;
        final String mimeType;
        final long size;

        MediaFile(Uri uri, String name, String mimeType, long size) {
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(BACKGROUND);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("BIBLIOTECA DE VIDEOS");
        title.setTextColor(TEXT);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button refresh = new Button(this);
        refresh.setText("Actualizar");
        refresh.setOnClickListener(view -> loadFiles());
        heading.addView(refresh);
        root.addView(heading);

        ScrollView scroll = new ScrollView(this);
        itemsContainer = new LinearLayout(this);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(itemsContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        loadFiles();
    }

    @Override
    protected void onDestroy() {
        thumbnailExecutor.shutdownNow();
        super.onDestroy();
    }

    private void loadFiles() {
        files.clear();
        loadError = "";
        Set<String> seen = new HashSet<>();
        addMediaStoreFiles(seen);
        String folder = getIntent().getStringExtra(EXTRA_FOLDER_URI);
        if (folder != null && !folder.isBlank()) {
            addTreeFiles(Uri.parse(folder), seen);
        }
        renderFiles();
    }

    private void addMediaStoreFiles(Set<String> seen) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
                || files.size() >= MAX_ITEMS) {
            return;
        }
        Uri collection = MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] columns = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.RELATIVE_PATH
        };
        try (Cursor cursor = getContentResolver().query(
                collection, columns,
                MediaStore.Downloads.RELATIVE_PATH + " LIKE ? AND "
                        + MediaStore.Downloads.MIME_TYPE + " LIKE ?",
                new String[]{"%CDG Browser%", "video/%"},
                MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndex(MediaStore.Downloads._ID);
            int nameColumn = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(MediaStore.Downloads.MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(MediaStore.Downloads.SIZE);
            while (cursor.moveToNext() && files.size() < MAX_ITEMS) {
                long id = idColumn >= 0 ? cursor.getLong(idColumn) : 0;
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "Video";
                String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : "video/*";
                long size = sizeColumn >= 0 ? cursor.getLong(sizeColumn) : 0;
                if (id > 0) {
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    addFile(new MediaFile(uri, name, mime, size), seen);
                }
            }
        } catch (SecurityException | IllegalArgumentException exception) {
            loadError = "No se pudo consultar la biblioteca multimedia del dispositivo.";
            Log.w("CDGMediaGallery", loadError, exception);
        }
    }

    private void addTreeFiles(Uri treeUri, Set<String> seen) {
        if (files.size() >= MAX_ITEMS) {
            return;
        }
        try {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, treeDocumentId);
            try (Cursor cursor = getContentResolver().query(
                    childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE
                    }, null, null, null)) {
                if (cursor == null) {
                    return;
                }
                int idColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_SIZE);
                while (cursor.moveToNext() && files.size() < MAX_ITEMS) {
                    String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : "";
                    String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "Video";
                    String documentId = idColumn >= 0 ? cursor.getString(idColumn) : null;
                    if (documentId == null || !mime.startsWith("video/")) {
                        continue;
                    }
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    long size = sizeColumn >= 0 ? cursor.getLong(sizeColumn) : 0;
                    addFile(new MediaFile(uri, name, mime, size), seen);
                }
            }
        } catch (SecurityException | IllegalArgumentException exception) {
            loadError = "No se pudo leer la carpeta de descargas elegida.";
            Log.w("CDGMediaGallery", loadError, exception);
        }
    }

    private void addFile(MediaFile file, Set<String> seen) {
        if (seen.add(file.uri.toString())) {
            files.add(file);
        }
    }

    private void renderFiles() {
        itemsContainer.removeAllViews();
        if (!loadError.isEmpty()) {
            TextView error = new TextView(this);
            error.setText(loadError);
            error.setTextColor(MUTED);
            error.setTextSize(15);
            error.setPadding(dp(16), dp(12), dp(16), dp(12));
            itemsContainer.addView(error);
        }
        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(loadError.isEmpty()
                    ? "Aún no hay videos descargados en la carpeta de CDG Browser."
                    : "No se encontraron elementos que se puedan mostrar.");
            empty.setTextColor(MUTED);
            empty.setTextSize(16);
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            itemsContainer.addView(empty);
            return;
        }
        for (int index = 0; index < files.size(); index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            MediaFile first = files.get(index);
            row.addView(createTile(first), new LinearLayout.LayoutParams(0, -2, 1));
            if (index + 1 < files.size()) {
                row.addView(createTile(files.get(index + 1)),
                        new LinearLayout.LayoutParams(0, -2, 1));
            } else {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, -2, 1));
            }
            itemsContainer.addView(row);
        }
    }

    private View createTile(MediaFile file) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setBackgroundColor(SURFACE);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(-1, -2);
        tileParams.setMargins(dp(6), dp(6), dp(6), dp(6));
        tile.setLayoutParams(tileParams);

        ImageView thumbnail = new ImageView(this);
        thumbnail.setBackgroundColor(0xff202126);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(thumbnail, new LinearLayout.LayoutParams(-1, dp(170)));
        TextView label = new TextView(this);
        label.setText(file.name + "\n" + formatBytes(file.size));
        label.setTextColor(TEXT);
        label.setTextSize(14);
        label.setMaxLines(2);
        label.setPadding(dp(12), dp(8), dp(12), dp(12));
        tile.addView(label);
        tile.setOnClickListener(view -> play(file));
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = extractThumbnail(file.uri);
            if (bitmap != null && !isFinishing()) {
                runOnUiThread(() -> thumbnail.setImageBitmap(bitmap));
            }
        });
        return tile;
    }

    private Bitmap extractThumbnail(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            return retriever.getFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException exception) {
            Log.w("CDGMediaGallery", "No se pudo crear la miniatura del video.", exception);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (java.io.IOException exception) {
                Log.w("CDGMediaGallery", "No se pudo liberar el extractor de miniaturas.",
                        exception);
            }
        }
    }

    private void play(MediaFile file) {
        Intent intent = new Intent(this, MediaPlayerActivity.class)
                .setDataAndType(file.uri, file.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB",
                bytes / (1024.0 * 1024.0));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

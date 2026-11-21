package com.cdg.browser.media;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public final class MediaPlayerActivity extends Activity {
    private Uri mediaUri;
    private PlayerView playerView;
    private ExoPlayer player;
    private boolean errorDialogShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mediaUri = getIntent().getData();
        if (mediaUri == null) {
            finish();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setFocusable(true);
        playerView.setContentDescription("Reproductor de video. Usa las flechas y OK para controlar.");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mediaUri == null || player != null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                showUnsupportedMedia(error);
            }
        });
        player.setMediaItem(MediaItem.fromUri(mediaUri));
        player.prepare();
        player.play();
    }

    @Override
    protected void onStop() {
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }

    private void showUnsupportedMedia(PlaybackException error) {
        if (errorDialogShown || isFinishing()) {
            return;
        }
        errorDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle("No se puede reproducir internamente")
                .setMessage("El dispositivo no dispone de un decodificador compatible. "
                        + "Puedes probar con otra aplicación.")
                .setNegativeButton("Cerrar", (dialog, which) -> finish())
                .setPositiveButton("Abrir otra app", (dialog, which) -> openExternalPlayer())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void openExternalPlayer() {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(mediaUri, getIntent().getType() == null
                        ? "*/*" : getIntent().getType())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
            finish();
        } catch (android.content.ActivityNotFoundException exception) {
            Toast.makeText(this, "No hay otro reproductor compatible instalado.",
                    Toast.LENGTH_LONG).show();
        }
    }
}

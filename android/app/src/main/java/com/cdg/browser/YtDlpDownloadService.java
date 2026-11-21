package com.cdg.browser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class YtDlpDownloadService extends Service {
    static final String ACTION_START = "com.cdg.browser.action.YTDLP_START";
    static final String ACTION_FINISH = "com.cdg.browser.action.YTDLP_FINISH";

    private static final String CHANNEL_ID = "ytdlp_downloads";
    private static final int NOTIFICATION_ID = 4702;
    private int activeDownloads;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Descargas yt-dlp", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            activeDownloads++;
            startForeground(NOTIFICATION_ID, buildNotification());
        } else if (ACTION_FINISH.equals(action)) {
            activeDownloads = Math.max(0, activeDownloads - 1);
            if (activeDownloads == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelfResult(startId);
            } else {
                getSystemService(NotificationManager.class)
                        .notify(NOTIFICATION_ID, buildNotification());
            }
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Descargas de video")
                .setContentText(activeDownloads + " descarga(s) con yt-dlp en segundo plano")
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

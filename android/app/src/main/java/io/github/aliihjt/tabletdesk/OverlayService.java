package io.github.aliihjt.tabletdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;

/** Keeps the menu bar and Touch Bar floating above every app (including the VNC viewer). */
public class OverlayService extends Service {
    static final String ACTION_STOP = "io.github.aliihjt.tabletdesk.STOP";
    static final String PREFS = "tabletdesk";
    private static final String CHANNEL = "tabletdesk";
    static volatile boolean running;

    private WindowManager wm;
    private MenuBar menuBar;
    private TouchBar touchBar;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, OverlayService.class));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, OverlayService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        HostLink.get().start();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();  // every startForegroundService() must be answered
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        removeBars();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("touchbar", true)) {
            touchBar = new TouchBar(this, wm);
            wm.addView(touchBar.view(), touchBar.params());
        }
        if (prefs.getBoolean("menubar", true)) {
            menuBar = new MenuBar(this, () -> {
                if (touchBar != null) touchBar.toggleCollapsed();
            });
            wm.addView(menuBar.view(), menuBar.params());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeBars();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void removeBars() {
        if (menuBar != null) {
            menuBar.destroy();
            wm.removeView(menuBar.view());
            menuBar = null;
        }
        if (touchBar != null) {
            touchBar.destroy();
            wm.removeView(touchBar.view());
            touchBar = null;
        }
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Menu bar & Touch Bar", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("TabletDesk is running")
                .setContentText("Menu bar and Touch Bar are shown over all apps")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }
}

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
    private static OverlayService instance;

    private WindowManager wm;
    private MenuBar menuBar;
    private TouchBar touchBar;
    private PointerOverlay pointer;

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
        instance = this;
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
        Ui.loadScale(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pointer = new PointerOverlay(this);
        wm.addView(pointer, pointer.params());
        if (prefs.getBoolean("touchbar", true)) {
            touchBar = new TouchBar(this, wm, prefs.getBoolean("autohide", true));
            wm.addView(touchBar.view(), touchBar.params());
        }
        if (prefs.getBoolean("menubar", true)) {
            menuBar = new MenuBar(this, wm, prefs.getBoolean("autohide", true), () -> {
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
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Hides both bars (and lets touches through) while a TabletDesk screen with
     * its own controls, like the trackpad, is in front.
     */
    static void setBarsHidden(boolean hidden) {
        OverlayService s = instance;
        if (s == null) return;
        if (s.menuBar != null) s.hide(s.menuBar.view(), s.menuBar.params(), hidden);
        if (s.touchBar != null) s.hide(s.touchBar.view(), s.touchBar.params(), hidden);
    }

    private void hide(android.view.View v, WindowManager.LayoutParams lp, boolean hidden) {
        if (!v.isAttachedToWindow()) return;
        v.setVisibility(hidden ? android.view.View.GONE : android.view.View.VISIBLE);
        if (hidden) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wm.updateViewLayout(v, lp);
    }

    private void removeBars() {
        if (pointer != null) {
            pointer.destroy();
            wm.removeView(pointer);
            pointer = null;
        }
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

package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Everything Fedora Pad shows over the Fedora screen: one small floating
 * button (tap it for Keyboard, Trackpad, Media, Home), the floating keyboard or
 * trackpad, and the laptop's mouse pointer when it moves onto the tablet.
 */
public class OverlayService extends Service {
    static final String ACTION_STOP = "io.github.aliihjt.tabletdesk.STOP";
    static final String ACTION_PANEL = "io.github.aliihjt.tabletdesk.PANEL";
    static final String EXTRA_MODE = "mode";
    static final String PREFS = "tabletdesk";
    private static final String CHANNEL = "tabletdesk";
    static volatile boolean running;
    private static OverlayService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;
    private PointerOverlay pointer;
    private View bubble;
    private WindowManager.LayoutParams bubbleParams;
    private View menu;
    private FloatingPanel panel;
    private String panelMode;
    private final Runnable fade = () -> {
        if (bubble != null) bubble.animate().alpha(0.45f).setDuration(400);
    };

    static void start(Context c) {
        c.startForegroundService(new Intent(c, OverlayService.class));
    }

    /** Opens the floating keyboard or trackpad (or closes it if it is already open). */
    static void showPanel(Context c, String mode) {
        c.startForegroundService(new Intent(c, OverlayService.class)
                .setAction(ACTION_PANEL).putExtra(EXTRA_MODE, mode));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, OverlayService.class).setAction(ACTION_STOP));
    }

    /** Fedora Pad's own pages hide the floating button while they are open. */
    static void setBubbleHidden(boolean hidden) {
        OverlayService s = instance;
        if (s == null || s.bubble == null) return;
        s.bubble.setVisibility(hidden ? View.GONE : View.VISIBLE);
        s.hideMenu();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = getSystemService(WindowManager.class);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        HostLink.get().attach(this);
        HostLink.get().start();
        running = true;
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();  // every startForegroundService() must be answered
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Ui.loadScale(this);
        if (pointer == null) {
            pointer = new PointerOverlay(this);
            wm.addView(pointer, pointer.params());
        }
        if (bubble == null && prefs.getBoolean("bubble", true)) addBubble();
        if (ACTION_PANEL.equals(action)) togglePanel(intent.getStringExtra(EXTRA_MODE));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        closePanel();
        hideMenu();
        if (bubble != null) wm.removeView(bubble);
        if (pointer != null) {
            pointer.destroy();
            wm.removeView(pointer);
        }
        bubble = null;
        pointer = null;
        running = false;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- floating button ----

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        Theme th = new Theme(this);
        TextView b = th.text("⌨️", 22, 0xFFFFFFFF, false);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = Theme.round(0xD91C1C1E, th.dp(20));
        bg.setStroke(th.dp(2), 0x59FFFFFF);
        b.setBackground(bg);
        b.setElevation(th.dp(8));
        bubble = b;

        int size = th.dp(58);
        bubbleParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        bubbleParams.x = prefs.getInt("bubble_x", screenW - size - th.dp(16));
        bubbleParams.y = prefs.getInt("bubble_y", screenH / 3);

        float[] start = new float[4];
        boolean[] dragged = {false};
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(fade);
                    v.setAlpha(1f);
                    start[0] = ev.getRawX();
                    start[1] = ev.getRawY();
                    start[2] = bubbleParams.x;
                    start[3] = bubbleParams.y;
                    dragged[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - start[0], dy = ev.getRawY() - start[1];
                    if (Math.hypot(dx, dy) > th.dp(8)) dragged[0] = true;
                    if (dragged[0]) {
                        hideMenu();
                        bubbleParams.x = Math.round(start[2] + dx);
                        bubbleParams.y = Math.max(0, Math.round(start[3] + dy));
                        wm.updateViewLayout(v, bubbleParams);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragged[0]) {  // snap to the nearest side, like AssistiveTouch
                        bubbleParams.x = bubbleParams.x + size / 2 < screenW / 2
                                ? th.dp(8) : screenW - size - th.dp(8);
                        wm.updateViewLayout(v, bubbleParams);
                        prefs.edit().putInt("bubble_x", bubbleParams.x)
                                .putInt("bubble_y", bubbleParams.y).apply();
                    } else {
                        Ui.haptic(v);
                        if (menu == null) showMenu();
                        else hideMenu();
                    }
                    handler.postDelayed(fade, 3000);
                    return true;
                default:
                    return true;
            }
        });
        wm.addView(b, bubbleParams);
        handler.postDelayed(fade, 3000);
    }

    /** The small menu next to the button. */
    @SuppressLint("ClickableViewAccessibility")
    private void showMenu() {
        Theme th = new Theme(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = th.dp(8);
        row.setPadding(p, p, p, p);
        android.graphics.drawable.GradientDrawable bg = Theme.round(0xEB1C1C1E, th.dp(22));
        bg.setStroke(th.dp(1), 0x40FFFFFF);
        row.setBackground(bg);
        row.setElevation(th.dp(10));
        row.addView(menuItem(th, "⌨️", "Keyboard", () -> togglePanel(FloatingPanel.KEYBOARD)));
        row.addView(menuItem(th, "🖱", "Trackpad", () -> togglePanel(FloatingPanel.TRACKPAD)));
        row.addView(menuItem(th, "🎵", "Media", () -> openPage(MediaActivity.class)));
        row.addView(menuItem(th, "⚡", "Shortcuts", () -> openPage(ShortcutsActivity.class)));
        row.addView(menuItem(th, "⌂", "Home", () -> openPage(MainActivity.class)));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        boolean leftSide = bubbleParams.x < screenW / 2;
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        lp.x = leftSide ? bubbleParams.x + bubbleParams.width + th.dp(8)
                : bubbleParams.x - row.getMeasuredWidth() - th.dp(8);
        lp.y = Math.max(0, bubbleParams.y + bubbleParams.height / 2 - row.getMeasuredHeight() / 2);
        row.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) hideMenu();
            return false;
        });
        menu = row;
        wm.addView(row, lp);
    }

    private View menuItem(Theme th, String symbol, String label, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(th.dp(10), th.dp(6), th.dp(10), th.dp(6));
        item.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40FFFFFF), null,
                Theme.round(0xFFFFFFFF, th.dp(14))));
        TextView icon = th.text(symbol, 22, 0xFFFFFFFF, false);
        icon.setGravity(Gravity.CENTER);
        item.addView(icon);
        TextView name = th.text(label, 11.5f, 0xCCFFFFFF, true);
        name.setGravity(Gravity.CENTER);
        item.addView(name, th.fullWidth(4));
        th.click(item, v -> {
            hideMenu();
            action.run();
        });
        return item;
    }

    private void hideMenu() {
        if (menu != null) {
            wm.removeView(menu);
            menu = null;
        }
    }

    private void openPage(Class<?> page) {
        startActivity(new Intent(this, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // ---- floating keyboard / trackpad ----

    private void togglePanel(String mode) {
        if (mode == null) return;
        boolean same = mode.equals(panelMode);
        closePanel();
        if (same) return;
        panel = new FloatingPanel(this, wm, mode, this::closePanel);
        panelMode = mode;
        wm.addView(panel.view(), panel.params());
    }

    private void closePanel() {
        if (panel != null) {
            wm.removeView(panel.view());
            panel = null;
            panelMode = null;
        }
    }

    // ---- foreground notification ----

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Fedora Pad", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Fedora Pad is ready")
                .setContentText("Tap the floating button for the keyboard and trackpad")
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

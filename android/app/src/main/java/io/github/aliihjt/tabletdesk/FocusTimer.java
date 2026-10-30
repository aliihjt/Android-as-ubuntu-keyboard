package io.github.aliihjt.tabletdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Focus timer: while it runs the PC is on Do Not Disturb (and the tablet too,
 * if allowed). At the end: a sound, a vibration, a notification, and both go
 * back to normal. The end time is saved, so leaving the page doesn't stop it.
 */
final class FocusTimer {
    private static final String END = "focus_end", TOTAL = "focus_total", DONE = "focus_done_day",
            TABLET_DND = "focus_tablet_dnd", OLD_FILTER = "focus_old_filter";
    private static final String CHANNEL = "focus";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static final Runnable ring = () -> finish(true);

    private FocusTimer() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    static long remaining(Context c) {
        long end = prefs(c).getLong(END, 0);
        return end == 0 ? 0 : Math.max(0, end - System.currentTimeMillis());
    }

    static long total(Context c) {
        return prefs(c).getLong(TOTAL, 25 * 60_000L);
    }

    static boolean running(Context c) {
        return prefs(c).getLong(END, 0) != 0;
    }

    /** Sessions finished today. */
    static int doneToday(Context c) {
        String day = String.valueOf(System.currentTimeMillis() / 86_400_000L);
        String saved = prefs(c).getString(DONE, "");
        return saved.startsWith(day + ":") ? Integer.parseInt(saved.substring(day.length() + 1)) : 0;
    }

    static void start(Context c, int minutes) {
        app = c.getApplicationContext();
        long ms = minutes * 60_000L;
        prefs(c).edit().putLong(END, System.currentTimeMillis() + ms).putLong(TOTAL, ms).apply();
        HostLink.get().set("dnd", 1);
        if (prefs(c).getBoolean(TABLET_DND, false)) tabletQuiet(c, true);
        schedule(c);
    }

    /** Re-arms the end after the app was away (called when pages open). */
    static void schedule(Context c) {
        app = c.getApplicationContext();
        main.removeCallbacks(ring);
        if (!running(c)) return;
        long left = remaining(c);
        if (left == 0) finish(true);
        else main.postDelayed(ring, left);
    }

    static void stop(Context c) {
        app = c.getApplicationContext();
        finish(false);
    }

    private static void finish(boolean completed) {
        Context c = app;
        if (c == null || !running(c)) return;
        main.removeCallbacks(ring);
        SharedPreferences.Editor e = prefs(c).edit().putLong(END, 0);
        if (completed) {
            String day = String.valueOf(System.currentTimeMillis() / 86_400_000L);
            e.putString(DONE, day + ":" + (doneToday(c) + 1));
        }
        e.apply();
        HostLink.get().set("dnd", 0);
        tabletQuiet(c, false);
        if (completed) celebrate(c);
    }

    // ---- the tablet's own Do Not Disturb (needs the user's permission once) ----

    static boolean canQuietTablet(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
    }

    static void setTabletDnd(Context c, boolean on) {
        prefs(c).edit().putBoolean(TABLET_DND, on).apply();
    }

    static boolean tabletDnd(Context c) {
        return prefs(c).getBoolean(TABLET_DND, false);
    }

    private static void tabletQuiet(Context c, boolean quiet) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) return;
        if (quiet) {
            prefs(c).edit().putInt(OLD_FILTER, nm.getCurrentInterruptionFilter()).apply();
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        } else if (prefs(c).contains(OLD_FILTER)) {
            nm.setInterruptionFilter(prefs(c).getInt(OLD_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL));
            prefs(c).edit().remove(OLD_FILTER).apply();
        }
    }

    private static void celebrate(Context c) {
        try {
            Ringtone r = RingtoneManager.getRingtone(c, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            if (r != null) r.play();
        } catch (RuntimeException ignored) {
        }
        Vibrator v = c.getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 150, 250, 150, 400}, -1));
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Focus timer", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(c, 3, new Intent(c, FocusActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Focus time is over 🎉")
                .setContentText("Take a short break. Done today: " + doneToday(c))
                .setContentIntent(open).setAutoCancel(true).build();
        try {
            nm.notify(7, n);
        } catch (SecurityException ignored) {  // notifications turned off
        }
    }
}

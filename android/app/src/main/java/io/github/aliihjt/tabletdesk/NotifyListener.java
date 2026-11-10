package io.github.aliihjt.tabletdesk;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The tablet's notifications on the iPhone and the PC (Devices › Show notifications
 * on other devices), with replies to messages sent back through the app's own
 * reply button. Needs Android's "notification access", which the user turns on.
 */
public class NotifyListener extends NotificationListenerService {
    static final String PREF = "notif_mirror";
    private static volatile NotifyListener instance;
    private String lastKey = "", lastText = "";

    static boolean allowed(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return on != null && on.contains(new ComponentName(c, NotifyListener.class).flattenToString());
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).getBoolean(PREF, false) && allowed(c);
    }

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).getBoolean(PREF, false)) return;
        HostLink link = HostLink.get();
        if (!link.isConnected() || sbn.getPackageName().equals(getPackageName()) || sbn.isOngoing()) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || n.visibility == Notification.VISIBILITY_SECRET) return;
        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = x.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        String t = text == null ? "" : text.toString();
        if (sbn.getKey().equals(lastKey) && t.equals(lastText)) return;  // the same one updated again
        lastKey = sbn.getKey();
        lastText = t;
        String app;
        try {
            PackageManager pm = getPackageManager();
            app = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            app = sbn.getPackageName();
        }
        try {
            link.send(new JSONObject().put("t", "notif").put("key", sbn.getKey()).put("app", app)
                    .put("title", title == null ? "" : title.toString()).put("text", t)
                    .put("reply", replyAction(n) != null).put("time", sbn.getPostTime()));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).getBoolean(PREF, false)) return;
        try {
            HostLink.get().send(new JSONObject().put("t", "notifgone").put("key", sbn.getKey()));
        } catch (JSONException ignored) {
        }
    }

    /** The app's own "Reply" button, if it has one that takes typed text. */
    private static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] in = a.getRemoteInputs();
            if (in == null) continue;
            for (RemoteInput r : in) if (r.getAllowFreeFormInput()) return a;
        }
        return null;
    }

    /** This tablet's notifications now (empty without notification access). */
    static java.util.List<StatusBarNotification> active() {
        java.util.List<StatusBarNotification> out = new java.util.ArrayList<>();
        NotifyListener l = instance;
        if (l == null) return out;
        try {
            for (StatusBarNotification s : l.getActiveNotifications()) {
                if (!s.isOngoing() && !s.getPackageName().equals(l.getPackageName())
                        && (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0) out.add(s);
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    private static StatusBarNotification find(String key) {
        NotifyListener l = instance;
        if (l == null) return null;
        try {
            for (StatusBarNotification s : l.getActiveNotifications()) if (s.getKey().equals(key)) return s;
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /** A reply typed on another device, sent with the app's own reply button. */
    static boolean reply(Context c, String key, String text) {
        StatusBarNotification s = find(key);
        Notification.Action a = s == null ? null : replyAction(s.getNotification());
        if (a == null) return false;
        Intent fill = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput r : a.getRemoteInputs()) results.putCharSequence(r.getResultKey(), text);
        RemoteInput.addResultsToIntent(a.getRemoteInputs(), fill, results);
        try {
            a.actionIntent.send(c, 0, fill);
            return true;
        } catch (PendingIntent.CanceledException e) {
            return false;
        }
    }

    static void dismiss(String key) {
        NotifyListener l = instance;
        if (l != null) {
            try {
                l.cancelNotification(key);
            } catch (RuntimeException ignored) {
            }
        }
    }
}

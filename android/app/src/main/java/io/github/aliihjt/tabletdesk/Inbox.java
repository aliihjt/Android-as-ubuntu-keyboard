package io.github.aliihjt.tabletdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AirDrop from the PC: files arrive in pieces and land in Downloads › Fedora Pad
 * with a notification that opens them; links and text are copied and can be
 * opened from the notification. Called from HostLink's reader thread.
 */
final class Inbox {
    private static final String CHANNEL = "airdrop";
    private static final Map<String, OutputStream> open = new HashMap<>();
    private static int notifyId = 100;

    private Inbox() {
    }

    static void receive(Context c, JSONObject m) {
        if (c == null) return;
        try {
            if ("pushtext".equals(m.optString("t"))) text(c, m.optString("s"));
            else file(c, m);
        } catch (IOException | RuntimeException e) {
            notify(c, "Couldn't receive a file", String.valueOf(e.getMessage()), null);
        }
    }

    private static void text(Context c, String s) {
        if (s.isEmpty()) return;
        c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("From the PC", s));
        String t = s.trim();
        boolean link = t.matches("(?i)https?://\\S+");
        Intent open = link ? new Intent(Intent.ACTION_VIEW, Uri.parse(t))
                : new Intent(c, ClipboardActivity.class);
        notify(c, link ? "🔗 Link from the PC" : "📋 Text from the PC (copied)",
                t.length() > 120 ? t.substring(0, 117) + "…" : t, open);
    }

    private static void file(Context c, JSONObject m) throws IOException {
        String id = m.optString("id");
        if (id.isEmpty()) return;
        File part = new File(new File(c.getCacheDir(), "inbox"), id.replaceAll("[^a-zA-Z0-9]", "") + ".part");
        OutputStream out = open.get(id);
        if (out == null) {
            part.getParentFile().mkdirs();
            out = new FileOutputStream(part);
            open.put(id, out);
        }
        out.write(Base64.decode(m.optString("data"), Base64.DEFAULT));
        if (!m.optBoolean("last")) return;
        out.close();
        open.remove(id);
        String name = new File(m.optString("name", "file")).getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || name.startsWith(".")) name = "file" + name;
        Uri saved = save(c, part, name);
        part.delete();
        // Android 9 and older: a file:// link can't be handed to other apps, so no tap-to-open there
        Intent view = Build.VERSION.SDK_INT < 29 ? null : new Intent(Intent.ACTION_VIEW)
                .setDataAndType(saved, mime(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        notify(c, "📥 " + name, Build.VERSION.SDK_INT < 29 ? "From the PC · in Fedora Pad's Downloads folder"
                : "From the PC · tap to open · in Downloads › Fedora Pad", view);
    }

    /** Into the tablet's Downloads › Fedora Pad (Android 10+), or the app's own Downloads before. */
    private static Uri save(Context c, File part, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime(name));
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fedora Pad");
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("no room in Downloads");
            try (InputStream in = new FileInputStream(part); OutputStream out = c.getContentResolver().openOutputStream(u)) {
                copy(in, out);
            }
            return u;
        }
        File dir = new File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Fedora Pad");
        dir.mkdirs();
        File dest = new File(dir, name);
        try (InputStream in = new FileInputStream(part); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        return Uri.fromFile(dest);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "application/octet-stream" : m;
    }

    private static void notify(Context c, String title, String text, Intent tap) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Files and links from the PC",
                NotificationManager.IMPORTANCE_HIGH));
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text).setAutoCancel(true)
                .setStyle(new Notification.BigTextStyle().bigText(text));
        if (tap != null) {
            b.setContentIntent(PendingIntent.getActivity(c, notifyId, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        try {
            nm.notify(notifyId++, b.build());
        } catch (SecurityException ignored) {  // notifications turned off for Fedora Pad
        }
    }
}

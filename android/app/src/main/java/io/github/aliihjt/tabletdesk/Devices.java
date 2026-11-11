package io.github.aliihjt.tabletdesk;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The other devices connected to the same PC (iPad, iPhone, another tablet),
 * which the PC passes messages between (host/hub.py): the list, one clipboard
 * for all of them, things they ask this tablet to do, and the library shared
 * with them.
 */
final class Devices {
    /** SecretStore name of the laptop's unlock PIN. */
    static final String UNLOCK_PIN = "unlock_pin";
    /** Show the laptop's notifications on this tablet (on unless switched off in Devices). */
    static final String PC_NOTIFS = "pc_notifs";

    // ---- Handoff: the page open in Chrome on the laptop (host/chrome-handoff)
    private static volatile JSONObject pcTab;
    private static volatile long pcTabAt;
    /** Runs on the main thread when the laptop's page changes (the iPad home's Dock). */
    static Runnable tabListener;

    /** {url, title} of the laptop's page from the last 30 minutes, or null. */
    static JSONObject handoff() {
        JSONObject t = pcTab;
        return t == null || System.currentTimeMillis() - pcTabAt > 30 * 60_000L ? null : t;
    }

    static void askHandoff() {
        try {
            HostLink.get().send(new JSONObject().put("t", "pctab?"));
        } catch (JSONException ignored) {
        }
    }

    static void openHandoff(Context c) {
        JSONObject t = handoff();
        if (t == null) return;
        try {
            c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(t.optString("url"))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(c, "No browser on the tablet", Toast.LENGTH_SHORT).show();
        }
    }

    static final class Device {
        final String id, name, kind;

        Device(JSONObject o) {
            id = o.optString("id");
            name = o.optString("name", "Device");
            kind = o.optString("kind", "browser");
        }

        String icon() {
            return kind.equals("android") ? "📱" : kind.equals("ipad") ? "📲" : kind.equals("iphone") ? "📱"
                    : kind.equals("phone") ? "📱" : "🌐";
        }
    }

    /** What other devices and the PC sent, for the home screen's Notification Center. */
    static final class Event {
        final String icon, title, text;
        final long time;

        Event(String icon, String title, String text) {
            this.icon = icon;
            this.title = title;
            this.text = text == null ? "" : text;
            time = System.currentTimeMillis();
        }
    }

    private static final java.util.LinkedList<Event> events = new java.util.LinkedList<>();

    static void remember(String icon, String title, String text) {
        synchronized (events) {
            events.addFirst(new Event(icon, title, text));
            while (events.size() > 40) events.removeLast();
        }
    }

    static List<Event> history() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    static void clearHistory() {
        synchronized (events) {
            events.clear();
        }
    }

    static volatile List<Device> list = new ArrayList<>();
    static volatile String me = "", pc = "";
    static volatile boolean uclip = true;
    /** The last text that went round: not sent again when this tablet sees it on its clipboard. */
    static volatile String lastClip;
    private static boolean clipKnown;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Ringtone ringing;
    private static final Runnable syncNow = Devices::shareAll;
    private static Context appCtx;

    private Devices() {
    }

    static String myName(Context c) {
        String saved = prefs(c).getString("dev_name", "");
        if (!saved.isEmpty()) return saved;
        String n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        return n == null || n.isEmpty() ? Build.MODEL : n;
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    /** The PC said hello: tell it who we are and share the library. */
    static void hello(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();  // not the page itself: it would stay in memory after closing
        try {
            HostLink.get().send(new JSONObject().put("t", "iam").put("name", myName(c)).put("kind", "android"));
        } catch (JSONException ignored) {
        }
        syncSoon(c);
    }

    static List<Device> others() {
        List<Device> out = new ArrayList<>();
        for (Device d : list) if (!d.id.equals(me)) out.add(d);
        return out;
    }

    static void update(JSONObject m) {
        List<Device> next = new ArrayList<>();
        JSONArray a = m.optJSONArray("list");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) next.add(new Device(o));
        }
        list = next;
        me = m.optString("me", me);
        pc = m.optString("pc", pc);
        uclip = m.optBoolean("uclip", true);
    }

    /** From the PC's reader thread. */
    static void message(Context c, JSONObject m) {
        if (c == null) return;
        String t = m.optString("t");
        if (t.equals("devices")) update(m);
        else if (t.equals("uclip")) main.post(() -> pasteboard(c, m.optString("s"), m.optString("from")));
        else if (t.equals("relayfail")) main.post(() -> Toast.makeText(c, "That device isn't connected any more",
                Toast.LENGTH_SHORT).show());
        else if (t.equals("remote")) main.post(() -> remote(c, m));
        else if (t.equals("notes") && m.optJSONArray("list") != null) {  // changed on the iPhone or the PC
            prefs(c).edit().putString("notes", m.optJSONArray("list").toString()).apply();
        } else if (t.equals("pcevent")) {
            remember(m.optString("icon"), m.optString("title"), m.optString("text"));
            Inbox.notify(c, m.optString("icon") + " " + m.optString("title"), m.optString("text"), null);
        } else if (t.equals("pctab")) {  // Handoff: the page open in Chrome on the laptop
            pcTab = m;
            pcTabAt = System.currentTimeMillis();
            main.post(() -> {
                if (tabListener != null) tabListener.run();
            });
        } else if (t.equals("pcnotif")) {  // a notification shown on the laptop
            if (prefs(c).getBoolean(PC_NOTIFS, true)) {
                String app = m.optString("app"), title = m.optString("title");
                Inbox.notify(c, "💻 " + (app.isEmpty() ? m.optString("pc", "Laptop") : app)
                        + (title.isEmpty() ? "" : " · " + title), m.optString("text"), null);
            }
        } else if (t.equals("unlockres")) {
            // a saved PIN that the laptop refuses is forgotten, so the next try asks again
            if (!m.optBoolean("ok") && "unlock".equals(m.optString("what"))
                    && m.optString("error").startsWith("Wrong PIN")) SecretStore.remove(c, UNLOCK_PIN);
            String text = m.optBoolean("ok") ? ("lock".equals(m.optString("what")) ? "🔒 Laptop locked" : "🔓 Laptop unlocked")
                    : "⚠️ " + m.optString("error", "Didn't work");
            main.post(() -> Toast.makeText(c, text, Toast.LENGTH_LONG).show());
        }
    }

    /** The book just closed in the reader: the iPhone offers to continue it. */
    static void shareLastRead(Context c, String uri, String title, String type, int pos, int total) {
        try {
            HostLink.get().send(new JSONObject().put("t", "share").put("key", "lastread").put("value",
                    new JSONObject().put("uri", uri).put("title", title).put("type", type).put("pos", pos)
                            .put("total", total).put("time", System.currentTimeMillis())));
        } catch (JSONException ignored) {
        }
    }

    // ---- one clipboard for every device

    private static void pasteboard(Context c, String text, String from) {
        if (text.isEmpty()) return;
        lastClip = text;
        remember("📋", "Copied on " + from, text);
        c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("From " + from, text));
        Toast.makeText(c, "📋 Copied on " + from, Toast.LENGTH_SHORT).show();
    }

    /**
     * Android only lets the app in front read the clipboard: whenever a Fedora Pad
     * screen comes to the front, what was copied on the tablet goes to the others.
     * The first look after starting only remembers it (it may be old).
     */
    static void checkClipboard(Context c) {
        HostLink link = HostLink.get();
        if (!link.isConnected() || !uclip) return;
        try {
            ClipboardManager cm = c.getSystemService(ClipboardManager.class);
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            ClipDescription d = clip.getDescription();
            if (Build.VERSION.SDK_INT >= 33 && d.getExtras() != null
                    && d.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) return;  // passwords
            CharSequence cs = clip.getItemAt(0).getText();
            if (cs == null) return;
            String text = cs.toString();
            boolean first = !clipKnown;
            clipKnown = true;
            if (text.equals(lastClip)) return;
            lastClip = text;
            if (first || text.trim().isEmpty() || text.length() > 20000) return;
            link.send(new JSONObject().put("t", "uclip").put("s", text));
        } catch (RuntimeException | JSONException ignored) {
        }
    }

    // ---- what another device asks this tablet to do

    private static void remote(Context c, JSONObject m) {
        String who = m.optString("from", "Another device");
        switch (m.optString("a")) {
            case "ring":
                ring(c, who);
                break;
            case "notice":
                remember("💬", who, m.optString("text"));
                Inbox.notify(c, "💬 " + who, m.optString("text"), new Intent(c, DevicesActivity.class));
                break;
            case "media":
                media(c, m.optString("key"));
                break;
            case "reply":
                boolean sent = NotifyListener.reply(c, m.optString("key"), m.optString("text"));
                Toast.makeText(c, sent ? "↩️ Reply sent from " + who : "Couldn't reply: the message is gone", Toast.LENGTH_SHORT).show();
                break;
            case "dismiss":
                NotifyListener.dismiss(m.optString("key"));
                break;
            case "sendbook":
                sendBook(c, m.optString("uri"), m.optString("fromid"), who);
                break;
            case "open":
                Intent i = page(c, m.optString("page"), m.optJSONObject("arg"));
                if (i == null) return;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Settings.canDrawOverlays(c)) {  // allowed to come to the front by itself
                    try {
                        c.startActivity(i);
                        Toast.makeText(c, "📲 From " + who, Toast.LENGTH_SHORT).show();
                        return;
                    } catch (RuntimeException ignored) {
                    }
                }
                Inbox.notify(c, "📲 " + who + " sent you something", "Tap to open it", i);
                break;
            default:
        }
    }

    /** The page names the web app and the tablet both know. */
    static Intent page(Context c, String page, JSONObject arg) {
        switch (page) {
            case "home":
                return new Intent(c, MainActivity.class);
            case "standby":
                return new Intent(c, StandByActivity.class);
            case "focus":
                return new Intent(c, FocusActivity.class);
            case "faal":
                return new Intent(c, PoemActivity.class).putExtra("mode", "faal");
            case "poem":
                if (arg == null) return null;
                return new Intent(c, PoemActivity.class).putExtra("mode", "poem")
                        .putExtra("id", String.valueOf(arg.optInt("poem"))).putExtra("title", arg.optString("title"));
            case "books":
            case "reading":
                return new Intent(c, BooksActivity.class);
            case "notes":
                return new Intent(c, NotesActivity.class);
            case "devices":
                return new Intent(c, DevicesActivity.class);
            case "getbook":
                if (arg == null) return null;
                return new Intent(c, GetBooksActivity.class).putExtra("dl_id", arg.optInt("id"))
                        .putExtra("dl_title", arg.optString("title")).putExtra("dl_url", arg.optString("epub"));
            case "url":
                String u = arg == null ? "" : arg.optString("u");
                return u.startsWith("http") ? new Intent(Intent.ACTION_VIEW, Uri.parse(u)) : null;
            default:
                return null;
        }
    }

    static void ring(Context c, String who) {
        stopRing();
        Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        ringing = RingtoneManager.getRingtone(c, tone);
        if (ringing != null) ringing.play();
        Vibrator v = c.getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 300, 400, 300, 400}, -1));
        main.postDelayed(Devices::stopRing, 20_000);
        Intent stop = new Intent(c, DevicesActivity.class).putExtra("stop_ring", true);
        Inbox.notify(c, "📍 " + who + " is looking for this tablet", "Tap to stop the sound", stop);
        if (Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(new Intent(stop).putExtra("ring_from", who).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
    }

    static void stopRing() {
        if (ringing != null) ringing.stop();
        ringing = null;
    }

    private static void media(Context c, String key) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (key.equals("volup") || key.equals("voldown")) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, key.equals("volup") ? AudioManager.ADJUST_RAISE
                    : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return;
        }
        int code = key.equals("next") ? KeyEvent.KEYCODE_MEDIA_NEXT : key.equals("previous")
                ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    /** Another device asked for a book from the library: send the file to it. */
    private static void sendBook(Context c, String uri, String to, String who) {
        for (BooksActivity.Book b : BooksActivity.library(c)) {
            if (!b.uri.equals(uri)) continue;
            String ext = b.type.equals("pdf") ? ".pdf" : b.type.equals("epub") ? ".epub" : ".txt";
            sendFile(c, Uri.parse(uri), b.title.replaceAll("[\\\\/:*?\"<>|]", " ").trim() + ext, to, null);
            Toast.makeText(c, "📤 Sending “" + b.title + "” to " + who, Toast.LENGTH_SHORT).show();
            return;
        }
    }

    // ---- sending to another device

    interface Progress {
        void on(int percent);
    }

    static String displayName(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (RuntimeException ignored) {
        }
        String s = u.getLastPathSegment();
        return s == null ? "file" : s;
    }

    /** A file to another device, piece by piece, in the background. */
    static void sendFile(Context c, Uri uri, String name, String to, Progress progress) {
        HostLink link = HostLink.get();
        new Thread(() -> {
            String id = UUID.randomUUID().toString();
            long size = -1;
            try (android.content.res.AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (fd != null) size = fd.getLength();
            } catch (Exception ignored) {
            }
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[48 * 1024];
                long sent = 0;
                int n = readFully(in, buf);
                while (true) {
                    byte[] chunk = java.util.Arrays.copyOf(buf, Math.max(n, 0));
                    int next = n == buf.length ? readFully(in, buf) : -1;
                    boolean last = next <= 0;
                    sent += Math.max(n, 0);
                    JSONObject m = new JSONObject().put("t", "pushfile").put("id", id).put("name", name)
                            .put("size", size).put("data", Base64.encodeToString(chunk, Base64.NO_WRAP)).put("last", last);
                    if (!link.relayBlocking(to, m)) throw new java.io.IOException("not connected");
                    if (progress != null && size > 0) {
                        int pct = (int) Math.min(100, sent * 100 / size);
                        main.post(() -> progress.on(pct));
                    }
                    if (last) break;
                    n = next;
                }
                if (progress != null) main.post(() -> progress.on(100));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(c, "Couldn't send: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "send-to-device").start();
    }

    private static int readFully(InputStream in, byte[] buf) throws java.io.IOException {
        int got = 0, n;
        while (got < buf.length && (n = in.read(buf, got, buf.length - got)) > 0) got += n;
        return got;
    }

    /** Choose one of the other devices (no question when there is only one). */
    static void pick(android.app.Activity a, String title, java.util.function.Consumer<Device> then) {
        List<Device> others = others();
        if (!HostLink.get().isConnected() || others.isEmpty()) {
            Toast.makeText(a, HostLink.get().isConnected() ? "No other device is connected — open Fedora Pad on it"
                    : "Not connected to the PC", Toast.LENGTH_LONG).show();
            return;
        }
        if (others.size() == 1) {
            then.accept(others.get(0));
            return;
        }
        String[] names = new String[others.size()];
        for (int i = 0; i < names.length; i++) names[i] = others.get(i).icon() + "  " + others.get(i).name;
        new android.app.AlertDialog.Builder(a).setTitle(title).setItems(names, (d, w) -> then.accept(others.get(w))).show();
    }

    /** Opens a page on another device ("Continue on iPad"). */
    static void handoff(android.app.Activity a, String page, JSONObject arg, String what) {
        pick(a, "Continue on…", d -> {
            try {
                remoteAction(d.id, new JSONObject().put("a", "open").put("page", page).put("arg", arg));
                Toast.makeText(a, "📲 " + what + " → " + d.name, Toast.LENGTH_SHORT).show();
            } catch (JSONException ignored) {
            }
        });
    }

    static void sendText(String to, String text) {
        try {
            HostLink.get().relay(to, new JSONObject().put("t", "pushtext").put("s", text));
        } catch (JSONException ignored) {
        }
    }

    static void remoteAction(String to, JSONObject m) {
        try {
            HostLink.get().relay(to, m.put("t", "remote"));
        } catch (JSONException ignored) {
        }
    }

    // ---- the library, highlights and words, kept on the PC for the other devices

    static void syncSoon(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();
        main.removeCallbacks(syncNow);
        main.postDelayed(syncNow, 2000);
    }

    private static void shareAll() {
        Context c = appCtx;
        HostLink link = HostLink.get();
        if (c == null || !link.isConnected()) return;
        try {
            JSONArray books = new JSONArray();
            for (BooksActivity.Book b : BooksActivity.library(c)) {
                books.put(new JSONObject().put("title", b.title).put("type", b.type).put("pos", b.position)
                        .put("total", b.total).put("uri", b.uri));
            }
            JSONArray hl = new JSONArray();
            List<ReadingData.Highlight> all = ReadingData.highlights(c, null);
            for (int i = Math.max(0, all.size() - 200); i < all.size(); i++) {
                ReadingData.Highlight h = all.get(i);
                hl.put(new JSONObject().put("title", h.title).put("text", h.text).put("color", h.color)
                        .put("note", h.note).put("part", h.part));
            }
            JSONArray words = new JSONArray();
            List<ReadingData.Word> ws = ReadingData.words(c);
            for (int i = 0; i < ws.size() && i < 300; i++) {
                ReadingData.Word w = ws.get(i);
                words.put(new JSONObject().put("word", w.word).put("sentence", w.sentence).put("book", w.book));
            }
            link.send(new JSONObject().put("t", "share").put("key", "reading").put("value", books));
            link.send(new JSONObject().put("t", "share").put("key", "highlights").put("value", hl));
            link.send(new JSONObject().put("t", "share").put("key", "words").put("value", words));
        } catch (JSONException ignored) {
        }
    }
}

package io.github.aliihjt.tabletdesk;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Connection to the Linux host daemon. The tablet dials 127.0.0.1:7777, which
 * `adb reverse` tunnels over the USB-C cable to the laptop. Reconnects forever.
 */
public final class HostLink {
    public interface Listener {
        void onStatus(boolean connected, String host);

        void onApp(String name);
    }

    /** Laptop settings (volume, brightness, dark mode, ...) for the Control Center. */
    public interface StateListener {
        void onState(JSONObject state);
    }

    public static final int PORT = 7777;
    private static final HostLink INSTANCE = new HostLink();

    public static HostLink get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(1024);
    private boolean started;
    private volatile boolean connected;
    private volatile String host = "Fedora";
    private volatile String app = "Fedora";
    private volatile StateListener stateListener;
    /** favourite Fedora apps for the Dock: {id, name} */
    private volatile List<String[]> apps = new java.util.ArrayList<>();

    private HostLink() {
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(this::loop, "HostLink");
        t.setDaemon(true);
        t.start();
    }

    public boolean isConnected() {
        return connected;
    }

    public String host() {
        return host;
    }

    public String app() {
        return app;
    }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onStatus(connected, host);
        l.onApp(app);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- messages to the host ----

    public void move(float dx, float dy) {
        send(String.format(Locale.US, "{\"t\":\"move\",\"dx\":%.2f,\"dy\":%.2f}", dx, dy));
    }

    public void scroll(float dx, float dy) {
        send(String.format(Locale.US, "{\"t\":\"scroll\",\"dx\":%.2f,\"dy\":%.2f}", dx, dy));
    }

    /** b: left/right/middle, s: click/down/up */
    public void button(String b, String s) {
        send(obj("t", "btn", "b", b, "s", s));
    }

    /** e.g. "ctrl+shift+z", "f5", "super" */
    public void combo(String keys) {
        send(obj("t", "combo", "keys", keys));
    }

    /** s: down/up/click */
    public void key(String k, String s) {
        send(obj("t", "key", "k", k, "s", s));
    }

    public void text(String s) {
        if (!s.isEmpty()) send(obj("t", "text", "s", s));
    }

    /** extend / mirror / input: remembered on the laptop for every connection. */
    public void mode(String m) {
        send(obj("t", "mode", "m", m));
    }

    public List<String[]> apps() {
        return apps;
    }

    public void requestApps() {
        send("{\"t\":\"apps?\"}");
    }

    public void setStateListener(StateListener l) {
        stateListener = l;
    }

    /** Ask the laptop for its current volume / brightness / toggles. */
    public void requestState() {
        send("{\"t\":\"state?\"}");
    }

    /** what: volume / brightness (0-100), dark / dnd (0 or 1). */
    public void set(String what, int value) {
        send(String.format(Locale.US, "{\"t\":\"set\",\"what\":%s,\"value\":%d}",
                JSONObject.quote(what), value));
    }

    public void command(String name) {
        send(obj("t", "cmd", "name", name));
    }

    /** Runs a "kind:value" action string used by the menu bar and Touch Bar. */
    public void action(String action) {
        int i = action.indexOf(':');
        String kind = i < 0 ? "combo" : action.substring(0, i);
        String value = i < 0 ? action : action.substring(i + 1);
        switch (kind) {
            case "cmd":
                command(value);
                break;
            case "text":
                text(value);
                break;
            case "launch":
                send(obj("t", "launch", "id", value));
                break;
            case "cursor":
                send("{\"t\":\"cursor\"}");
                break;
            default:
                combo(value);
        }
    }

    private static String obj(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    private void send(String msg) {
        if (connected) queue.offer(msg);
    }

    // ---- connection loop ----

    private void loop() {
        while (true) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 2000);
                s.setTcpNoDelay(true);
                queue.clear();
                Thread reader = new Thread(() -> readLoop(s), "HostLink-reader");
                reader.setDaemon(true);
                reader.start();
                OutputStream out = s.getOutputStream();
                while (!s.isClosed()) {
                    String msg = queue.poll(1, TimeUnit.SECONDS);
                    if (msg == null) msg = "{\"t\":\"ping\"}";
                    out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException | InterruptedException ignored) {
            }
            setConnected(false);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void readLoop(Socket s) {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject msg = new JSONObject(line);
                String t = msg.optString("t");
                if ("hello".equals(t)) {
                    host = msg.optString("host", "Fedora");
                    // adb accepts the tunnel even if nothing listens on the laptop,
                    // so only the host's hello means we are really connected
                    setConnected(true);
                    requestApps();
                } else if ("apps".equals(t)) {
                    org.json.JSONArray list = msg.optJSONArray("list");
                    List<String[]> next = new java.util.ArrayList<>();
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject a = list.optJSONObject(i);
                        if (a != null) next.add(new String[]{a.optString("id"), a.optString("name")});
                    }
                    apps = next;
                } else if ("state".equals(t)) {
                    StateListener l = stateListener;
                    if (l != null) main.post(() -> l.onState(msg));
                } else if ("app".equals(t)) {
                    String name = msg.optString("name", "Fedora");
                    app = name;
                    main.post(() -> {
                        for (Listener l : listeners) l.onApp(name);
                    });
                }
            }
        } catch (IOException | JSONException ignored) {
        }
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private void setConnected(boolean c) {
        if (connected == c) return;
        connected = c;
        String h = host;
        main.post(() -> {
            for (Listener l : listeners) l.onStatus(c, h);
        });
    }
}

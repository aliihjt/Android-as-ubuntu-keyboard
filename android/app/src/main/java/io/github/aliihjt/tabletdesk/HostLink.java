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

    /** Laptop pointer on the tablet's screen: x, y in 0..1, or -1, -1 when it's elsewhere. */
    public interface PointerListener {
        void onPointer(float x, float y);
    }

    /** Text from the PC clipboard. */
    public interface ClipListener {
        void onClip(String text);
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
    private volatile android.content.SharedPreferences prefs;
    private volatile String via = "USB";
    private volatile String wifiIp = "";
    private volatile String user = "";
    private volatile String os = "linux";
    private volatile PointerListener pointerListener;
    private volatile ClipListener clipListener;
    /** one listener per reply type ("media", "stats", ...) for the live panels */
    private final java.util.Map<String, java.util.function.Consumer<JSONObject>> typed =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** last laptop settings received (volume, laptopkbd, touch, ...) */
    private volatile JSONObject lastState = new JSONObject();
    /** favourite Fedora apps for the Dock: {id, name} */
    private volatile List<String[]> apps = new java.util.ArrayList<>();

    private HostLink() {
    }

    /** Gives HostLink somewhere to keep the Wi-Fi pairing. Call before start(). */
    public void attach(android.content.Context c) {
        if (prefs == null) {
            prefs = c.getApplicationContext().getSharedPreferences("tabletdesk", android.content.Context.MODE_PRIVATE);
        }
    }

    /** The laptop's address when connected over Wi-Fi ("" over the cable). */
    public String wifiIp() {
        return "Wi-Fi".equals(via) ? wifiIp : "";
    }

    /** The Fedora user name (for Remote Desktop). */
    public String user() {
        return user;
    }

    /** "windows" when the PC runs Fedora Pad for Windows, else "linux". */
    public String os() {
        return os;
    }

    /** "USB" or "Wi-Fi": how the tablet is connected right now. */
    public String via() {
        return via;
    }

    /** True once the PC has paired this tablet for Wi-Fi mode (over the cable). */
    public boolean wifiPaired() {
        return prefs != null && prefs.contains("wifi_token");
    }

    public void forgetWifi() {
        if (prefs != null) {
            prefs.edit().remove("wifi_token").remove("wifi_fp").remove("wifi_ips").remove("wifi_port").apply();
        }
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

    /** Fetch the PC clipboard; the answer arrives once at l. */
    public void requestClip(ClipListener l) {
        clipListener = l;
        send("{\"t\":\"clip?\"}");
    }

    /** Put text on the PC clipboard. */
    public void setClip(String text) {
        send(obj("t", "setclip", "s", text));
    }

    /** Listen for replies of one type; null stops listening. */
    public void on(String type, java.util.function.Consumer<JSONObject> l) {
        if (l == null) typed.remove(type);
        else typed.put(type, l);
    }

    /** Ask the PC for a reply of this type, e.g. ask("stats"). */
    public void ask(String type) {
        send(obj("t", type + "?"));
    }

    /**
     * One piece of a file for the PC. Waits while the send queue is full, so
     * call it from a background thread. Returns false if the PC is not connected.
     */
    public boolean sendFileChunk(String id, String name, String base64, boolean last)
            throws InterruptedException {
        if (!connected) return false;
        JSONObject o = new JSONObject();
        try {
            o.put("t", "file").put("id", id).put("name", name).put("data", base64).put("last", last);
        } catch (JSONException e) {
            return false;
        }
        queue.put(o.toString());
        return true;
    }

    /** Window switcher: bring a Fedora window to the front, or close it. */
    public void window(String id, boolean close) {
        send(obj("t", close ? "winclose" : "winfocus", "id", id));
    }

    /** Moves a Fedora window to the tablet's screen, or back to the laptop. */
    public void moveWindow(String id, boolean toTablet) {
        send(obj("t", toTablet ? "wintablet" : "winlaptop", "id", id));
    }

    /** Terminal Pad: runs one of the saved commands on Fedora by its name. */
    public void runCommand(String name) {
        send(obj("t", "termrun", "name", name));
    }

    /** Fedora's keyboard language for the Persian keys: "fa" or "en". */
    public void language(String id) {
        send(obj("t", "lang", "id", id));
    }

    /** Terminal Pad: stops the command that is running. */
    public void stopCommand() {
        send("{\"t\":\"termstop\"}");
    }

    /** Sends any message, e.g. the whole notes list. */
    public void send(JSONObject o) {
        send(o.toString());
    }

    /** play-pause, next or previous on the PC's media player. */
    public void media(String action) {
        send(obj("t", "mediactl", "a", action));
    }

    public JSONObject lastState() {
        return lastState;
    }

    public void setPointerListener(PointerListener l) {
        pointerListener = l;
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
            case "url":
                send(obj("t", "url", "u", value));
                break;
            case "app":
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
            try (Socket s = open()) {
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

    /** The cable first (adb reverse); if it isn't there, the paired PC over Wi-Fi. */
    private Socket open() throws IOException {
        Socket usb = new Socket();
        try {
            usb.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            via = "USB";
            return usb;
        } catch (IOException e) {
            usb.close();
        }
        android.content.SharedPreferences p = prefs;
        if (p == null || !p.contains("wifi_token") || !p.getBoolean("wifi_use", true)) {
            throw new IOException("not connected");
        }
        int port = p.getInt("wifi_port", 7778);
        for (String ip : p.getString("wifi_ips", "").split(",")) {
            if (ip.isEmpty()) continue;
            Socket raw = new Socket();
            try {
                raw.connect(new InetSocketAddress(ip, port), 1500);
                javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) pinned(p.getString("wifi_fp", ""))
                        .createSocket(raw, ip, port, true);
                tls.startHandshake();
                JSONObject auth = new JSONObject().put("t", "auth").put("token", p.getString("wifi_token", ""));
                tls.getOutputStream().write((auth + "\n").getBytes(StandardCharsets.UTF_8));
                via = "Wi-Fi";
                wifiIp = ip;
                return tls;
            } catch (IOException | JSONException e) {
                raw.close();
            }
        }
        throw new IOException("PC not found on Wi-Fi");
    }

    /** TLS that trusts exactly one certificate: the PC's, learned over the cable. */
    private static javax.net.ssl.SSLSocketFactory pinned(String fingerprint) throws IOException {
        javax.net.ssl.X509TrustManager tm = new javax.net.ssl.X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String auth) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String auth)
                    throws java.security.cert.CertificateException {
                try {
                    byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder hex = new StringBuilder();
                    for (byte b : d) hex.append(String.format(Locale.US, "%02x", b));
                    if (!hex.toString().equals(fingerprint)) {
                        throw new java.security.cert.CertificateException("not the paired PC");
                    }
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new java.security.cert.CertificateException(e);
                }
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{tm}, null);
            return ctx.getSocketFactory();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
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
                java.util.function.Consumer<JSONObject> typedListener = typed.get(t);
                if (typedListener != null) main.post(() -> typedListener.accept(msg));
                if ("pair".equals(t) && prefs != null && "USB".equals(via)) {
                    // only ever accepted over the cable
                    org.json.JSONArray ips = msg.optJSONArray("ips");
                    StringBuilder list = new StringBuilder();
                    for (int i = 0; ips != null && i < ips.length(); i++) {
                        if (list.length() > 0) list.append(',');
                        list.append(ips.optString(i));
                    }
                    prefs.edit().putString("wifi_token", msg.optString("token"))
                            .putString("wifi_fp", msg.optString("fp"))
                            .putString("wifi_ips", list.toString())
                            .putInt("wifi_port", msg.optInt("port", 7778)).apply();
                } else if ("hello".equals(t)) {
                    host = msg.optString("host", "Fedora");
                    user = msg.optString("user", "");
                    os = msg.optString("os", "linux");
                    // adb accepts the tunnel even if nothing listens on the laptop,
                    // so only the host's hello means we are really connected
                    setConnected(true);
                    requestApps();
                    requestState();
                } else if ("clip".equals(t)) {
                    ClipListener l = clipListener;
                    clipListener = null;
                    String text = msg.optString("s");
                    if (l != null) main.post(() -> l.onClip(text));
                } else if ("ptr".equals(t)) {
                    PointerListener l = pointerListener;
                    float x = (float) msg.optDouble("x", -1), y = (float) msg.optDouble("y", -1);
                    if (l != null) main.post(() -> l.onPointer(x, y));
                } else if ("apps".equals(t)) {
                    org.json.JSONArray list = msg.optJSONArray("list");
                    List<String[]> next = new java.util.ArrayList<>();
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject a = list.optJSONObject(i);
                        if (a != null) next.add(new String[]{a.optString("id"), a.optString("name")});
                    }
                    apps = next;
                } else if ("state".equals(t)) {
                    lastState = msg;
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

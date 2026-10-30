package io.github.aliihjt.tabletdesk;

import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Wake & Sleep: wake the sleeping PC with a "magic packet" over the home
 * network, put it to sleep now, in a while, or every night (with a minute's
 * warning on the PC that can be cancelled here).
 */
public class WakeActivity extends Page {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView wakeInfo, sleepState;
    private JSONObject sleep = new JSONObject();

    @Override
    protected String title() {
        return "Wake & Sleep";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);

        LinearLayout card = th.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(th.dp(18), th.dp(20), th.dp(18), th.dp(18));
        card.addView(th.text("⏰", 48, th.text, false));
        wakeInfo = th.caption("");
        wakeInfo.setGravity(Gravity.CENTER);
        card.addView(th.primaryButton("Wake the PC", v -> wake()), th.fullWidth(12));
        card.addView(wakeInfo, th.fullWidth(8));
        add(card, 4);

        section("Sleep");
        sleepState = th.text("", 16, th.text, true);
        add(sleepState, 2);
        LinearLayout now = new LinearLayout(this);
        now.addView(th.secondaryButton("💤 Now", v -> link.command("suspend")), th.weighted(0));
        for (int m : new int[]{30, 60, 90}) {
            now.addView(th.secondaryButton(m + " min", v -> send("sleepin", "min", m)), th.weighted(8));
        }
        add(now, 10);
        LinearLayout night = new LinearLayout(this);
        night.addView(th.secondaryButton("🌙 Every night at…", v -> pickNightly()), th.weighted(0));
        night.addView(th.secondaryButton("Cancel sleep", v -> send("sleepcancel", null, 0)), th.weighted(8));
        add(night, 10);
        add(th.caption("A minute before it sleeps, the PC shows a warning; cancel here if you're still using it."), 8);
        showWake();
    }

    // ---- waking ----

    private void showWake() {
        String mac = prefs.getString("wake_mac", "");
        wakeInfo.setText(mac.isEmpty()
                ? "Connect to the PC once (cable or Wi-Fi) so the tablet learns how to wake it."
                : "Wakes " + prefs.getString("wake_host", "the PC") + " (" + mac + ") on this Wi-Fi.\n"
                + "Works when it's asleep, on Ethernet or a Wi-Fi card with wake-on-Wi-Fi. "
                + "Run ./host/wake-setup.sh on Fedora once.");
    }

    private void remember(JSONObject m) {
        save(prefs, m);
        showWake();
    }

    /** Called on every connection too, so the tablet can wake the PC after it went to sleep. */
    static void save(SharedPreferences prefs, JSONObject m) {
        JSONArray cards = m.optJSONArray("cards");
        if (cards == null || cards.length() == 0) return;
        StringBuilder macs = new StringBuilder(), brds = new StringBuilder();
        for (int i = 0; i < cards.length(); i++) {  // the card in use first, wired before Wi-Fi
            JSONObject c = cards.optJSONObject(i);
            if (c == null || c.optString("mac").isEmpty()) continue;
            boolean first = c.optBoolean("up") && macs.length() == 0;
            String mac = c.optString("mac"), brd = c.optString("broadcast", "255.255.255.255");
            if (first) {
                macs.insert(0, mac + ",");
                brds.insert(0, brd + ",");
            } else {
                macs.append(mac).append(',');
                brds.append(brd).append(',');
            }
        }
        prefs.edit().putString("wake_mac", macs.toString().replaceAll(",$", ""))
                .putString("wake_brd", brds.toString().replaceAll(",$", ""))
                .putString("wake_host", m.optString("host", "the PC")).apply();
    }

    private void wake() {
        String macs = prefs.getString("wake_mac", ""), brds = prefs.getString("wake_brd", "");
        if (macs.isEmpty()) {
            wakeInfo.setText("Connect to the PC once first, so the tablet learns its address.");
            return;
        }
        new Thread(() -> {
            int sent = 0;
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                String[] b = brds.split(",");
                String[] m = macs.split(",");
                for (int i = 0; i < m.length; i++) {
                    byte[] packet = magic(m[i]);
                    if (packet == null) continue;
                    for (String to : new String[]{i < b.length ? b[i] : "255.255.255.255", "255.255.255.255"}) {
                        for (int port : new int[]{9, 7}) {
                            s.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(to), port));
                            sent++;
                        }
                    }
                }
            } catch (Exception e) {
                ui.post(() -> wakeInfo.setText("Couldn't send: " + e.getMessage() + ". Is the tablet on the same Wi-Fi?"));
                return;
            }
            int n = sent;
            ui.post(() -> wakeInfo.setText(n > 0 ? "✅ Wake-up sent. The PC should be up in a few seconds."
                    : "No usable address saved."));
        }, "wake").start();
    }

    /** 6 × 0xFF, then the MAC address 16 times. */
    private static byte[] magic(String mac) {
        String[] parts = mac.split("[:-]");
        if (parts.length != 6) return null;
        byte[] m = new byte[6];
        try {
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(parts[i], 16);
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] p = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int i = 6; i < p.length; i++) p[i] = m[(i - 6) % 6];
        return p;
    }

    // ---- sleeping ----

    private void send(String t, String key, int value) {
        if (!link.isConnected()) {
            sleepState.setText("Connect to the PC first");
            return;
        }
        try {
            JSONObject o = new JSONObject().put("t", t);
            if (key != null) o.put(key, value);
            link.send(o);
        } catch (JSONException ignored) {
        }
    }

    private void pickNightly() {
        String cur = sleep.optString("nightly", "");
        int h = cur.isEmpty() ? 23 : Integer.parseInt(cur.substring(0, 2));
        int m = cur.isEmpty() ? 30 : Integer.parseInt(cur.substring(3));
        TimePickerDialog d = new TimePickerDialog(this, (v, hh, mm) -> {
            try {
                link.send(new JSONObject().put("t", "sleepnightly").put("at", String.format(Locale.US, "%02d:%02d", hh, mm)));
            } catch (JSONException ignored) {
            }
        }, h, m, true);
        d.setButton(TimePickerDialog.BUTTON_NEUTRAL, "Turn off", (dd, w) -> {
            try {
                link.send(new JSONObject().put("t", "sleepnightly").put("at", ""));
            } catch (JSONException ignored) {
            }
        });
        d.show();
    }

    private void showSleep(JSONObject m) {
        sleep = m;
        long warn = m.optLong("warning_until"), until = m.optLong("in_until");
        String nightly = m.optString("nightly", "");
        String text;
        if (warn > 0) text = "⚠️ Going to sleep in " + Math.max(0, (warn - System.currentTimeMillis()) / 1000) + " s";
        else if (until > 0) text = "💤 Sleeps at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(until));
        else text = "No sleep timer";
        if (!nightly.isEmpty()) text += "   ·   🌙 every night at " + nightly;
        sleepState.setText(text);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (link.isConnected()) link.ask("sleep");
            ui.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        link.on("wake", this::remember);
        link.on("sleep", this::showSleep);
        link.ask("wake");
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        link.on("wake", null);
        link.on("sleep", null);
        super.onPause();
    }
}

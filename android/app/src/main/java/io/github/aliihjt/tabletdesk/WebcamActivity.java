package io.github.aliihjt.tabletdesk;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The tablet as the PC's webcam and microphone, over the USB cable. The PC
 * runs scrcpy (host/webcam-setup.sh); Zoom, Meet or OBS then pick
 * "Fedora Pad Camera" and "Fedora Pad Microphone". Needs Android 12+.
 */
public class WebcamActivity extends Page {
    private SharedPreferences prefs;
    private TextView state, startStop;
    private boolean on;

    @Override
    protected String title() {
        return "Webcam";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        LinearLayout card = th.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(th.dp(18), th.dp(22), th.dp(18), th.dp(18));
        card.addView(th.text("📷", 56, th.text, false));
        state = th.text("Off", 20, th.text, true);
        state.setGravity(Gravity.CENTER);
        card.addView(state, th.fullWidth(8));
        TextView how = th.caption("In Zoom, Meet or OBS on the PC, choose “Fedora Pad Camera” and “Fedora Pad Microphone”.");
        how.setGravity(Gravity.CENTER);
        card.addView(how, th.fullWidth(6));
        add(card, 4);

        section("Camera");
        add(th.segmented(new String[][]{{"front", "🤳 Front"}, {"back", "📷 Back"}},
                prefs.getString("cam_facing", "front"), v -> {
                    prefs.edit().putString("cam_facing", v).apply();
                    if (on) send(true);  // switch cameras right away
                }));
        Switch mic = th.toggle(prefs.getBoolean("cam_mic", true), v -> prefs.edit().putBoolean("cam_mic", v).apply());
        add(th.group(th.row("🎤", Theme.RED, "Use the tablet's microphone too", null, mic)), 12);

        startStop = th.primaryButton("Start", v -> send(!on));
        add(startStop, 18);
        add(th.caption("Works over the USB cable with Android 12 or newer. First time: run "
                + "./host/webcam-setup.sh on Fedora. Prop the tablet up like a laptop camera."), 10);
    }

    private void send(boolean start) {
        if (!link.isConnected()) {
            state.setText("Connect the tablet to the PC first");
            return;
        }
        try {
            link.send(new JSONObject().put("t", "webcam").put("on", start).put("src", "android")
                    .put("facing", prefs.getString("cam_facing", "front"))
                    .put("mic", prefs.getBoolean("cam_mic", true)));
        } catch (JSONException ignored) {
        }
        state.setText(start ? "Starting…" : "Stopping…");
    }

    private void show(JSONObject m) {
        on = m.optBoolean("on");
        String error = m.optString("error", "");
        state.setText(!error.isEmpty() ? "⚠️ " + error : on ? "🔴 On: the PC sees this camera" : "Off");
        state.setTextColor(!error.isEmpty() ? Theme.ORANGE : on ? Theme.RED : th.text);
        startStop.setText(on ? "Stop" : "Start");
        startStop.setBackground(th.pressable(on ? Theme.RED : th.accent, th.dp(14)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("webcam", this::show);
        link.ask("webcam");
    }

    @Override
    protected void onPause() {
        link.on("webcam", null);
        super.onPause();
    }
}

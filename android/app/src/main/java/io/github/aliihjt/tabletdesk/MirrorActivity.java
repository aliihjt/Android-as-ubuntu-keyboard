package io.github.aliihjt.tabletdesk;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Show on PC: this tablet's screen in a window on the PC (scrcpy over the USB
 * cable; use it with the PC's mouse and keyboard), and an AirPlay receiver on
 * Fedora for an iPhone / iPad.
 */
public class MirrorActivity extends Page {
    private SharedPreferences prefs;
    private TextView tabletState, tabletButton, airState, airButton;
    private boolean tabletOn, airOn;

    @Override
    protected String title() {
        return "Show on PC";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);

        section("This tablet on the PC");
        LinearLayout card = th.card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(th.dp(18), th.dp(18), th.dp(18), th.dp(18));
        card.addView(th.text("📱➜💻", 34, th.text, false));
        tabletState = th.text("Off", 17, th.text, true);
        tabletState.setGravity(Gravity.CENTER);
        card.addView(tabletState, th.fullWidth(8));
        tabletButton = th.primaryButton("Show this tablet on the PC", v -> send("mirror", !tabletOn));
        card.addView(tabletButton, th.fullWidth(12));
        add(card);
        Switch audio = th.toggle(prefs.getBoolean("mirror_audio", true), on -> prefs.edit().putBoolean("mirror_audio", on).apply());
        add(th.group(th.row("🔊", Theme.PINK, "Play the tablet's sound on the PC", "Android 11 or newer", audio)), 10);
        add(th.caption("Over the USB cable. In the window, click and type to use the tablet with the PC's mouse "
                + "and keyboard; drag files onto it to copy them here. Fedora: run ./host/mirror-setup.sh once. "
                + "Windows: Setup.bat adds it."), 8);

        section("iPhone or iPad on the PC (Fedora)");
        LinearLayout air = th.card();
        air.setGravity(Gravity.CENTER_HORIZONTAL);
        air.setPadding(th.dp(18), th.dp(18), th.dp(18), th.dp(18));
        airState = th.text("AirPlay receiver is off", 16, th.text, true);
        airState.setGravity(Gravity.CENTER);
        air.addView(airState);
        airButton = th.secondaryButton("Start AirPlay on the PC", v -> send("airplay", !airOn));
        air.addView(airButton, th.fullWidth(12));
        add(air);
        add(th.caption("Then on the iPhone: Control Center › Screen Mirroring › pick the PC. Same Wi-Fi."), 8);
    }

    private void send(String t, boolean on) {
        if (!link.isConnected()) {
            (t.equals("mirror") ? tabletState : airState).setText("Connect to the PC first");
            return;
        }
        try {
            link.send(new JSONObject().put("t", t).put("on", on).put("audio", prefs.getBoolean("mirror_audio", true)));
        } catch (JSONException ignored) {
        }
        (t.equals("mirror") ? tabletState : airState).setText(on ? "Starting…" : "Stopping…");
    }

    private void showTablet(JSONObject m) {
        tabletOn = m.optBoolean("on");
        String err = m.optString("error", "");
        tabletState.setText(tabletOn ? "🟢 Showing on the PC" : !err.isEmpty() ? "⚠️ " + err : "Off");
        tabletState.setTextColor(!tabletOn && !err.isEmpty() ? Theme.ORANGE : th.text);
        tabletButton.setText(tabletOn ? "Stop" : "Show this tablet on the PC");
        tabletButton.setBackground(th.pressable(tabletOn ? Theme.RED : th.accent, th.dp(14)));
    }

    private void showAir(JSONObject m) {
        airOn = m.optBoolean("on");
        String err = m.optString("error", "");
        airState.setText(airOn ? "🟢 Ready: on the iPhone pick “" + m.optString("name") + "”\nFirst time code: "
                + m.optString("pin") : !err.isEmpty() ? "⚠️ " + err : "AirPlay receiver is off");
        airState.setTextColor(!airOn && !err.isEmpty() ? Theme.ORANGE : th.text);
        airButton.setText(airOn ? "Stop AirPlay" : "Start AirPlay on the PC");
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("mirror", this::showTablet);
        link.on("airplay", this::showAir);
        link.ask("mirror");
        link.ask("airplay");
    }

    @Override
    protected void onPause() {
        link.on("mirror", null);
        link.on("airplay", null);
        super.onPause();
    }
}

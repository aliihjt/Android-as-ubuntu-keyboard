package io.github.aliihjt.tabletdesk;

import android.widget.LinearLayout;
import android.widget.Switch;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Fedora's quick settings: brightness, volume, switches, power mode, and session. */
public class ControlsActivity extends Page implements HostLink.StateListener {
    private final Map<String, Switch> switches = new HashMap<>();
    private boolean applying;

    @Override
    protected String title() {
        return "Controls";
    }

    @Override
    protected void build() {
        JSONObject st = link.lastState();
        section("Display & sound");
        add(th.group(
                th.sliderRow("☀️", Theme.BLUE, "Brightness",
                        th.slider(100, st.optInt("brightness", 50), v -> link.set("brightness", v))),
                th.sliderRow("🔊", Theme.PINK, "Volume",
                        th.slider(100, st.optInt("volume", 50), v -> link.set("volume", v)))));

        section("Switches");
        add(th.group(
                toggle("🌙", Theme.INDIGO, "Dark mode", "dark", st),
                toggle("🌅", Theme.ORANGE, "Night Light", "nightlight", st),
                toggle("🔕", Theme.PURPLE, "Do Not Disturb", "dnd", st),
                toggle("📶", Theme.BLUE, "Wi-Fi", "wifi", st),
                toggle("🔵", Theme.BLUE, "Bluetooth", "bluetooth", st),
                toggle("🎙", Theme.RED, "Mute microphone", "micmute", st),
                toggle("☕", Theme.ORANGE, "Keep PC awake", "awake", st)));

        section("Power mode");
        add(th.segmented(new String[][]{{"0", "Battery saver"}, {"1", "Balanced"}, {"2", "Performance"}},
                String.valueOf(st.optInt("power", 1)), v -> link.set("power", Integer.parseInt(v))));

        section("Tablet");
        add(th.group(
                inverted("⌨️", Theme.GRAY, "Laptop keyboard off while connected", "laptopkbd", st),
                toggle("👆", Theme.GREEN, "Touch the Fedora screen", "touch", st)));

        section("Session");
        add(th.group(
                action("🔒", Theme.GRAY, "Lock screen", () -> link.combo("super+l")),
                action("↩️", Theme.GRAY, "Log out…", () -> link.command("logout")),
                action("🔄", Theme.ORANGE, "Restart…", () -> link.command("restart")),
                action("⏻", Theme.RED, "Shut down…", () -> link.command("poweroff"))));
    }

    private LinearLayout toggle(String sym, int color, String title, String key, JSONObject st) {
        Switch s = th.toggle(st.optBoolean(key, key.equals("touch")), on -> {
            if (!applying) link.set(key, on ? 1 : 0);
        });
        switches.put(key, s);
        return th.row(sym, color, title, null, s);
    }

    /** laptopkbd is "on" when the laptop keyboard works, the switch shows "off while connected". */
    private LinearLayout inverted(String sym, int color, String title, String key, JSONObject st) {
        Switch s = th.toggle(!st.optBoolean(key, false), on -> {
            if (!applying) link.set(key, on ? 0 : 1);
        });
        switches.put("!" + key, s);
        return th.row(sym, color, title, "The tablet becomes your keyboard", s);
    }

    private LinearLayout action(String sym, int color, String title, Runnable run) {
        LinearLayout r = th.row(sym, color, title, null, th.chevron());
        th.click(r, v -> run.run());
        return r;
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.setStateListener(this);
        link.requestState();
    }

    @Override
    protected void onPause() {
        link.setStateListener(null);
        super.onPause();
    }

    @Override
    public void onState(JSONObject st) {
        applying = true;
        for (Map.Entry<String, Switch> e : switches.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("!")) {
                if (st.has(key.substring(1))) e.getValue().setChecked(!st.optBoolean(key.substring(1)));
            } else if (st.has(key)) {
                e.getValue().setChecked(st.optBoolean(key));
            }
        }
        applying = false;
    }
}

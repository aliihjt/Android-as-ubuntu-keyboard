package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** Full-screen laptop keyboard + trackpad for Ubuntu. */
public class TrackpadActivity extends Activity implements TrackpadView.TypingListener, HostLink.Listener {
    private static final int BG = 0xFF1C1C1E;
    private static final int PAD = 0xFF2C2C2E;
    private static final int KEY = 0xFF3A3A3C;
    private static final int ACCENT = 0xFF0A84FF;

    private final HostLink link = HostLink.get();
    /** Sticky modifiers (tap Ctrl, then C = Ctrl+C), in press order. */
    private final Map<String, TextView> mods = new LinkedHashMap<>();
    private TrackpadView pad;
    private TextView status;
    private boolean keyboardShown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        link.start();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int m = Ui.dp(this, 10);
        root.setPadding(m, Ui.dp(this, 34), m, m);  // room for the menu bar

        status = Ui.label(this, "", 13, 0xFFAAAAAA, false);
        status.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
        root.addView(status);

        LinearLayout keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.addView(key("esc", v -> onKey("esc")));
        keys.addView(key("tab", v -> onKey("tab")));
        for (String mod : new String[]{"ctrl", "alt", "super", "shift"}) keys.addView(modKey(mod));
        keys.addView(key("←", v -> onKey("left")));
        keys.addView(key("↑", v -> onKey("up")));
        keys.addView(key("↓", v -> onKey("down")));
        keys.addView(key("→", v -> onKey("right")));
        keys.addView(key("home", v -> onKey("home")));
        keys.addView(key("end", v -> onKey("end")));
        keys.addView(key("del", v -> onKey("delete")));
        keys.addView(key("⌫", v -> onKey("backspace")));
        keys.addView(key("⏎", v -> onKey("enter")));
        TextView kb = key("⌨ Keyboard", v -> toggleKeyboard());
        kb.setBackground(Ui.pressable(ACCENT, Ui.dp(this, 8), 0x55FFFFFF));
        keys.addView(kb);
        HorizontalScrollView keyScroll = new HorizontalScrollView(this);
        keyScroll.setHorizontalScrollBarEnabled(false);
        keyScroll.addView(keys);
        root.addView(keyScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        FrameLayout padFrame = new FrameLayout(this);
        padFrame.setBackground(Ui.rounded(PAD, Ui.dp(this, 18)));
        TextView hint = Ui.label(this,
                "Trackpad\n1 finger: move · tap: click · tap+drag: drag\n"
                        + "2 fingers: scroll · tap: right click\n"
                        + "3 fingers: swipe up = Activities · left/right = workspaces",
                14, 0xFF6E6E73, false);
        hint.setSingleLine(false);
        hint.setGravity(Gravity.CENTER);
        padFrame.addView(hint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pad = new TrackpadView(this);
        pad.setTypingListener(this);
        padFrame.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        padLp.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(padFrame, padLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(clickButton("left"), new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        buttons.addView(clickButton("right"), new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        // leave space for the Touch Bar overlay
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = OverlayService.running ? Ui.dp(this, 56) : 0;
        root.addView(buttons, blp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pad.sensitivity = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
                .getInt("speed", 100) / 100f;
        link.addListener(this);
    }

    @Override
    protected void onPause() {
        link.removeListener(this);
        super.onPause();
    }

    @Override
    public void onStatus(boolean connected, String host) {
        status.setText(connected ? "● Connected to " + host + " over USB"
                : "○ Not connected — plug in USB-C and run host/connect.sh on Ubuntu");
        status.setTextColor(connected ? 0xFF30D158 : 0xFFFF6961);
    }

    @Override
    public void onApp(String name) {
    }

    // ---- typing ----

    @Override
    public void onTyped(String text) {
        if (mods.isEmpty()) {
            link.text(text);
            return;
        }
        for (char c : text.toCharArray()) {
            link.combo(modPrefix() + (c == '+' ? "plus" : String.valueOf(Character.toLowerCase(c))));
        }
        clearMods();
    }

    @Override
    public void onKey(String key) {
        link.combo(modPrefix() + key);
        clearMods();
    }

    /** A Bluetooth / USB keyboard attached to the tablet types straight into Ubuntu. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        InputDevice dev = ev.getDevice();
        String name = KeyNames.of(ev.getKeyCode());
        boolean physical = dev != null && ev.getDeviceId() != KeyCharacterMap.VIRTUAL_KEYBOARD
                && dev.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
        if (!physical || name == null) return super.dispatchKeyEvent(ev);
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) link.key(name, "down");
        else if (ev.getAction() == KeyEvent.ACTION_UP) link.key(name, "up");
        return true;
    }

    private String modPrefix() {
        StringBuilder sb = new StringBuilder();
        for (String m : mods.keySet()) sb.append(m).append('+');
        return sb.toString();
    }

    private void clearMods() {
        for (TextView t : mods.values()) t.setBackground(Ui.pressable(KEY, Ui.dp(this, 8), 0x55FFFFFF));
        mods.clear();
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboardShown = !keyboardShown;
        if (keyboardShown) {
            pad.requestFocus();
            imm.showSoftInput(pad, 0);
        } else {
            imm.hideSoftInputFromWindow(pad.getWindowToken(), 0);
        }
    }

    // ---- widgets ----

    private TextView key(String label, View.OnClickListener l) {
        TextView t = Ui.label(this, label, 15, 0xFFFFFFFF, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 56));
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        t.setBackground(Ui.pressable(KEY, Ui.dp(this, 8), 0x55FFFFFF));
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            l.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView modKey(String mod) {
        TextView[] self = new TextView[1];
        self[0] = key(mod, v -> {
            if (mods.remove(mod) != null) {
                self[0].setBackground(Ui.pressable(KEY, Ui.dp(this, 8), 0x55FFFFFF));
            } else {
                mods.put(mod, self[0]);
                self[0].setBackground(Ui.pressable(ACCENT, Ui.dp(this, 8), 0x55FFFFFF));
            }
        });
        return self[0];
    }

    private TextView clickButton(String which) {
        TextView t = Ui.label(this, "", 14, 0xFFFFFFFF, false);
        t.setBackground(Ui.pressable(PAD, Ui.dp(this, 14), 0x55FFFFFF));
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            link.button(which, "click");
        });
        return t;
    }
}

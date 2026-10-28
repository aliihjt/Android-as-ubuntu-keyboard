package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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

/** Full-screen laptop keyboard + trackpad for the Linux desktop. */
public class TrackpadActivity extends Activity implements TrackpadView.TypingListener, HostLink.Listener {

    private final HostLink link = HostLink.get();
    /** Sticky modifiers (tap Ctrl, then C = Ctrl+C), in press order. */
    private final Map<String, TextView> mods = new LinkedHashMap<>();
    private TrackpadView pad;
    private TextView status;
    private boolean keyboardShown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.loadScale(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        link.start();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF101A2E, 0xFF231A3A, 0xFF0E2A33}));
        int m = Ui.dp(this, 16);
        root.setPadding(m, Ui.dp(this, 28), m, m);  // room for Android's status bar

        status = Ui.label(this, "", 13, 0xFFAAAAAA, false);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        status.setBackground(Glass.chip(this, true, Ui.dp(this, 14), false));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = Ui.dp(this, 10);
        root.addView(status, slp);

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
        kb.setBackground(keyBg(true));
        keys.addView(kb);
        HorizontalScrollView keyScroll = new HorizontalScrollView(this);
        keyScroll.setHorizontalScrollBarEnabled(false);
        keyScroll.addView(keys);
        int kp = Ui.dp(this, 4);
        keyScroll.setPadding(kp, kp, kp, kp);
        keyScroll.setBackground(Glass.surface(this, true, Ui.dp(this, 18), true));
        root.addView(keyScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        // one-tap keyboard shortcuts
        String[][] shortcuts = {
                {"\uD83D\uDCCB Copy", "ctrl+c"}, {"\uD83D\uDCE5 Paste", "ctrl+v"}, {"\u2702\uFE0F Cut", "ctrl+x"},
                {"\u21A9\uFE0F Undo", "ctrl+z"}, {"\u21AA\uFE0F Redo", "ctrl+shift+z"}, {"Select all", "ctrl+a"},
                {"\uD83D\uDCBE Save", "ctrl+s"}, {"\uD83D\uDD0D Find", "ctrl+f"}, {"\u2715 Close", "ctrl+w"},
                {"\u21C4 Switch app", "alt+tab"}, {"\u25A6 Activities", "super"},
                {"\uD83D\uDCF7 Screenshot", "print"}, {"\uD83D\uDD12 Lock", "super+l"}};
        LinearLayout shortcutRow = new LinearLayout(this);
        shortcutRow.addView(key("\uD83D\uDCF1\u2192\uD83D\uDCBB Send clipboard", v -> sendClipboard()));
        shortcutRow.addView(key("\uD83D\uDCBB\u2192\uD83D\uDCF1 Get clipboard", v -> getClipboard()));
        for (String[] sc : shortcuts) {
            String combo = sc[1];
            shortcutRow.addView(key(sc[0], v -> link.combo(combo)));
        }
        HorizontalScrollView shortcutScroll = new HorizontalScrollView(this);
        shortcutScroll.setHorizontalScrollBarEnabled(false);
        shortcutScroll.addView(shortcutRow);
        shortcutScroll.setPadding(kp, kp, kp, kp);
        shortcutScroll.setBackground(Glass.surface(this, true, Ui.dp(this, 18), true));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        scLp.topMargin = Ui.dp(this, 8);
        root.addView(shortcutScroll, scLp);

        FrameLayout padFrame = new FrameLayout(this);
        padFrame.setBackground(Glass.surface(this, true, Ui.dp(this, 26), true));
        TextView hint = Ui.label(this,
                "1 finger: move  \u00B7  tap: click  \u00B7  tap + drag: drag\n"
                        + "2 fingers: scroll  \u00B7  tap: right click\n"
                        + "3 fingers \u2191 Activities  \u00B7  \u2190 \u2192 workspaces",
                13, 0xFF636366, false);
        hint.setLineSpacing(0, 1.4f);
        hint.setSingleLine(false);
        hint.setGravity(Gravity.CENTER);
        padFrame.addView(hint, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pad = new TrackpadView(this);
        pad.setTypingListener(this);
        pad.setOnTouchListener((v, ev) -> {  // fade the help text once the pad is used
            if (hint.getAlpha() == 1f) hint.animate().alpha(0f).setDuration(400);
            return false;
        });
        padFrame.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        padLp.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        root.addView(padFrame, padLp);

        // one physical-looking click bar split in two, like a laptop trackpad
        LinearLayout buttons = new LinearLayout(this);
        buttons.setBackground(Glass.surface(this, true, Ui.dp(this, 20), true));
        buttons.addView(clickButton("left"), new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        View gap = new View(this);
        gap.setBackgroundColor(0xFF3A3A3C);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 26));
        glp.gravity = Gravity.CENTER_VERTICAL;
        buttons.addView(gap, glp);
        buttons.addView(clickButton("right"), new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        // leave space for the Touch Bar overlay
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = 0;  // the Touch Bar is hidden while this screen is open
        root.addView(buttons, blp);

        setContentView(root);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0xFF0E2A33);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pad.sensitivity = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
                .getInt("speed", 100) / 100f;
        pad.naturalScroll = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
                .getBoolean("natural", true);
        link.addListener(this);
        OverlayService.setBarsHidden(true);
    }

    @Override
    protected void onPause() {
        link.removeListener(this);
        OverlayService.setBarsHidden(false);
        super.onPause();
    }

    @Override
    public void onStatus(boolean connected, String host) {
        status.setText(connected ? "● Connected to " + host + " over USB"
                : "○ Not connected — plug in USB-C and run host/connect.sh on Fedora");
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

    /** A Bluetooth / USB keyboard attached to the tablet types straight into Linux. */
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

    private void sendClipboard() {
        android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
        android.content.ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            android.widget.Toast.makeText(this, "Tablet clipboard is empty", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        link.setClip(text.toString());
        android.widget.Toast.makeText(this, "Copied to the PC \u2014 paste with Ctrl+V", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void getClipboard() {
        link.requestClip(text -> {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("PC", text));
            android.widget.Toast.makeText(this, "PC clipboard copied to the tablet", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private String modPrefix() {
        StringBuilder sb = new StringBuilder();
        for (String m : mods.keySet()) sb.append(m).append('+');
        return sb.toString();
    }

    private void clearMods() {
        for (TextView t : mods.values()) t.setBackground(keyBg(false));
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
        TextView t = Ui.label(this, label, 14, 0xFFF2F2F7, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 52));
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        t.setBackground(keyBg(false));
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
                self[0].setBackground(keyBg(false));
            } else {
                mods.put(mod, self[0]);
                self[0].setBackground(keyBg(true));
            }
        });
        return self[0];
    }

    private android.graphics.drawable.Drawable keyBg(boolean active) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x55FFFFFF),
                Glass.chip(this, true, Ui.dp(this, 12), active), null);
    }

    private TextView clickButton(String which) {
        TextView t = Ui.label(this, "", 14, 0xFFFFFFFF, false);
        t.setBackground(Ui.pressable(0x00000000, Ui.dp(this, 16), 0x33FFFFFF));
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            link.button(which, "click");
        });
        return t;
    }
}

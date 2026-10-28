package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A keyboard floating over the desktop picture: drag it by its handle, switch
 * between letters and a number pad, show a small trackpad, or shrink it.
 * Keys go straight to the PC; the tablet's own keyboard is not used.
 */
final class FloatingKeyboard {
    private static final int TEXT = 0xFFF2F2F7;

    // letters layout; "label|key" where key is sent to the PC ("" = type the label)
    private static final String[][] LETTERS = {
            {"esc|esc", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫|backspace"},
            {"tab|tab", "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "del|delete"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'", "⏎|enter"},
            {"⇧|shift", "z", "x", "c", "v", "b", "n", "m", ",", ".", "/", "↑|up"},
            {"ctrl|ctrl", "alt|alt", "⌘|super", "space|space", "-", "=", "←|left", "↓|down", "→|right"},
    };
    // number pad: sends real keypad keys (NumLock is switched on by the PC side)
    private static final String[][] NUMPAD = {
            {"esc|esc", "tab|tab", "⌫|backspace", "del|delete"},
            {"7|kp7", "8|kp8", "9|kp9", "÷|kpslash"},
            {"4|kp4", "5|kp5", "6|kp6", "×|kpasterisk"},
            {"1|kp1", "2|kp2", "3|kp3", "−|kpminus"},
            {"0|kp0", ".|kpdot", "⏎|kpenter", "+|kpplus"},
    };

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final LinearLayout body;
    private final LinearLayout keys;
    private final TrackpadView pad;
    private final WindowManager.LayoutParams params;
    private final Handler repeater = new Handler(Looper.getMainLooper());
    /** sticky modifiers: tap ctrl, then c = ctrl+c */
    private final Map<String, TextView> mods = new LinkedHashMap<>();
    private boolean numpad;
    private boolean shift;
    private boolean small;

    FloatingKeyboard(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
        prefs = ctx.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
        numpad = prefs.getBoolean("kb_numpad", false);

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(ctx, 8);
        root.setPadding(p, Ui.dp(ctx, 4), p, p);
        root.setBackground(Glass.surface(ctx, true, Ui.dp(ctx, 22), false));
        root.setElevation(Ui.dp(ctx, 10));

        root.addView(header());
        body = new LinearLayout(ctx);
        keys = new LinearLayout(ctx);
        keys.setOrientation(LinearLayout.VERTICAL);
        body.addView(keys, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pad = new TrackpadView(ctx);
        pad.setBackground(Glass.chip(ctx, true, Ui.dp(ctx, 16), false));
        pad.sensitivity = prefs.getInt("speed", 100) / 100f;
        pad.naturalScroll = prefs.getBoolean("natural", true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.dp(ctx, 200),
                LinearLayout.LayoutParams.MATCH_PARENT);
        plp.leftMargin = Ui.dp(ctx, 8);
        body.addView(pad, plp);
        pad.setVisibility(prefs.getBoolean("kb_pad", true) ? View.VISIBLE : View.GONE);
        root.addView(body);
        buildKeys();

        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        params = new WindowManager.LayoutParams(
                Math.min(screenW - Ui.dp(ctx, 16), Ui.dp(ctx, 980)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        params.x = prefs.getInt("kb_x", (screenW - params.width) / 2);
        params.y = prefs.getInt("kb_y", screenH / 2);
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    void destroy() {
        repeater.removeCallbacksAndMessages(null);
    }

    // ---- header: drag handle + buttons ----

    @SuppressLint("ClickableViewAccessibility")
    private View header() {
        LinearLayout h = new LinearLayout(ctx);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(Ui.dp(ctx, 34));

        TextView grip = Ui.label(ctx, "☰  drag to move", 12, 0x99EBEBF5, false);
        grip.setGravity(Gravity.CENTER_VERTICAL);
        float[] start = new float[4];
        grip.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = ev.getRawX();
                    start[1] = ev.getRawY();
                    start[2] = params.x;
                    start[3] = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = Math.round(start[2] + ev.getRawX() - start[0]);
                    params.y = Math.max(0, Math.round(start[3] + ev.getRawY() - start[1]));
                    if (root.isAttachedToWindow()) wm.updateViewLayout(root, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    prefs.edit().putInt("kb_x", params.x).putInt("kb_y", params.y).apply();
                    return true;
                default:
                    return true;
            }
        });
        h.addView(grip, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 34), 1f));

        h.addView(headerButton(numpad ? "ABC" : "123", v -> {
            numpad = !numpad;
            prefs.edit().putBoolean("kb_numpad", numpad).apply();
            ((TextView) v).setText(numpad ? "ABC" : "123");
            buildKeys();
        }));
        h.addView(headerButton("🖱", v -> {  // small trackpad on/off
            boolean show = pad.getVisibility() != View.VISIBLE;
            pad.setVisibility(show ? View.VISIBLE : View.GONE);
            prefs.edit().putBoolean("kb_pad", show).apply();
        }));
        h.addView(headerButton("–", v -> {  // shrink to just the header
            small = !small;
            body.setVisibility(small ? View.GONE : View.VISIBLE);
            ((TextView) v).setText(small ? "▢" : "–");
        }));
        h.addView(headerButton("✕", v -> OverlayService.toggleKeyboard(ctx)));
        return h;
    }

    private TextView headerButton(String label, View.OnClickListener l) {
        TextView t = Ui.label(ctx, label, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(ctx, 44));
        t.setBackground(keyBg(false));
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            l.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(ctx, 30));
        lp.leftMargin = Ui.dp(ctx, 6);
        t.setLayoutParams(lp);
        return t;
    }

    // ---- keys ----

    private void buildKeys() {
        keys.removeAllViews();
        mods.clear();
        shift = false;
        for (String[] row : numpad ? NUMPAD : LETTERS) {
            LinearLayout r = new LinearLayout(ctx);
            for (String k : row) {
                String[] parts = k.split("\\|", 2);
                String label = parts[0];
                String key = parts.length > 1 ? parts[1] : "";
                float weight = key.equals("space") ? 4f : (label.length() > 1 && !numpad ? 1.4f : 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(ctx, 44), weight);
                lp.setMargins(Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2));
                r.addView(key(label, key), lp);
            }
            keys.addView(r);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView key(String label, String key) {
        TextView t = Ui.label(ctx, label, label.length() > 2 ? 13 : 16, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(keyBg(false));
        boolean isMod = key.equals("ctrl") || key.equals("alt") || key.equals("super");
        boolean repeat = key.equals("backspace") || key.equals("delete") || key.equals("left")
                || key.equals("right") || key.equals("up") || key.equals("down") || key.equals("space");
        Runnable press = () -> {
            if (key.equals("shift")) {
                shift = !shift;
                t.setBackground(keyBg(shift));
            } else if (isMod) {
                if (mods.remove(key) != null) t.setBackground(keyBg(false));
                else {
                    mods.put(key, t);
                    t.setBackground(keyBg(true));
                }
            } else {
                send(label, key);
            }
        };
        Runnable again = new Runnable() {
            @Override
            public void run() {
                press.run();
                repeater.postDelayed(this, 70);
            }
        };
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    Ui.haptic(v);
                    press.run();
                    if (repeat) repeater.postDelayed(again, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    repeater.removeCallbacks(again);
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    /** Sends one key, applying shift and any sticky modifiers, then releases them. */
    private void send(String label, String key) {
        StringBuilder combo = new StringBuilder();
        for (String m : mods.keySet()) combo.append(m).append('+');
        if (key.isEmpty() && combo.length() == 0) {
            HostLink.get().text(shift ? shifted(label) : label);  // plain typing
        } else {
            if (shift) combo.append("shift+");
            String k = key.isEmpty() ? label : key;
            if (k.equals("=")) k = "equal";
            else if (k.equals("-")) k = "minus";
            HostLink.get().combo(combo + k);
        }
        if (shift) {  // one-shot, like a phone keyboard
            shift = false;
            buildShiftOff();
        }
        for (TextView t : mods.values()) t.setBackground(keyBg(false));
        mods.clear();
    }

    private void buildShiftOff() {
        for (int i = 0; i < keys.getChildCount(); i++) {
            LinearLayout r = (LinearLayout) keys.getChildAt(i);
            for (int j = 0; j < r.getChildCount(); j++) {
                TextView t = (TextView) r.getChildAt(j);
                if (t.getText().toString().equals("⇧")) t.setBackground(keyBg(false));
            }
        }
    }

    private static String shifted(String s) {
        String from = "1234567890-=;',./`[]\\";
        String to = "!@#$%^&*()_+:\"<>?~{}|";
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            int i = from.indexOf(c);
            out.append(i >= 0 ? to.charAt(i) : Character.toUpperCase(c));
        }
        return out.toString();
    }

    private RippleDrawable keyBg(boolean active) {
        return new RippleDrawable(ColorStateList.valueOf(0x66FFFFFF),
                Glass.chip(ctx, true, Ui.dp(ctx, 10), active), null);
    }
}

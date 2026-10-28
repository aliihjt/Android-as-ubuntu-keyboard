package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A laptop keyboard that types straight into Fedora: letters with a number row,
 * or a number pad ("123"). Ctrl / Alt / ⌘ are sticky (tap, then a letter),
 * ⇧ is one-shot (double tap = caps lock), arrows and ⌫ repeat while held.
 * Used by the Keyboard page, the Trackpad page and the floating keyboard.
 */
final class KeyboardView extends LinearLayout {
    // "label|key": key is sent as a key press; no key = type the label as text
    private static final String[][] LETTERS = {
            {"esc|esc", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "⌫|backspace"},
            {"tab|tab", "q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "[", "]", "\\"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", ";", "'", "return|enter"},
            {"⇧|shift", "z", "x", "c", "v", "b", "n", "m", ",", ".", "/", "↑|up"},
            {"123|numpad", "ctrl|ctrl", "alt|alt", "⌘|super", " |space", "←|left", "↓|down", "→|right"},
    };
    private static final String[][] NUMPAD = {
            {"7|kp7", "8|kp8", "9|kp9", "÷|kpslash", "⌫|backspace"},
            {"4|kp4", "5|kp5", "6|kp6", "×|kpasterisk", "tab|tab"},
            {"1|kp1", "2|kp2", "3|kp3", "−|kpminus", "esc|esc"},
            {"ABC|letters", "0|kp0", ".|kpdot", "+|kpplus", "return|kpenter"},
    };
    private static final String REPEATING = " backspace delete left right up down space ";

    private final Theme th;
    private final float keyHeightDp;
    private final HostLink link = HostLink.get();
    private final Handler repeater = new Handler(Looper.getMainLooper());
    private final Map<String, TextView> mods = new LinkedHashMap<>();
    private Runnable systemKeyboard;
    private boolean numpad;
    private boolean shift, capsLock;
    private long lastShiftTap;
    private TextView shiftKey;

    KeyboardView(Theme th, float keyHeightDp) {
        super(th.ctx);
        this.th = th;
        this.keyHeightDp = keyHeightDp;
        setOrientation(VERTICAL);
        build();
    }

    /** Adds a 🌐 key that opens the tablet's own keyboard (for Persian etc). */
    void setSystemKeyboard(Runnable open) {
        systemKeyboard = open;
        build();
    }

    void showNumpad(boolean on) {
        numpad = on;
        build();
    }

    private void build() {
        removeAllViews();
        mods.clear();
        shiftKey = null;
        for (String[] row : numpad ? NUMPAD : LETTERS) {
            LinearLayout r = new LinearLayout(th.ctx);
            for (String k : row) {
                String[] p = k.split("\\|", 2);
                String key = p.length > 1 ? p[1] : "";
                float weight = key.equals("space") ? 5f
                        : (key.equals("enter") || key.equals("shift") || key.equals("backspace")
                        || key.equals("tab")) && !numpad ? 1.6f : 1f;
                r.addView(key(p[0], key), lp(weight));
            }
            if (systemKeyboard != null && !numpad && row == LETTERS[4]) {
                r.addView(key("🌐", "ime"), lp(1f));
            }
            addView(r);
        }
    }

    private LayoutParams lp(float weight) {
        LayoutParams lp = new LayoutParams(0, th.dp(keyHeightDp), weight);
        int m = th.dp(3);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    @SuppressLint("ClickableViewAccessibility")
    private TextView key(String label, String key) {
        boolean special = !key.isEmpty() && label.length() > 1;
        TextView t = th.key(label, special ? 14 : 19);
        if (special || key.equals("shift") || key.equals("super")) {
            t.setBackground(th.pressable(th.dark ? 0xFF2C2C2E : 0xFFD1D3D9, th.dp(9)));
        }
        if (key.equals("shift")) shiftKey = t;
        boolean repeat = REPEATING.contains(" " + key + " ");
        Runnable press = () -> press(t, label, key);
        Runnable again = new Runnable() {
            @Override
            public void run() {
                press.run();
                repeater.postDelayed(this, 65);
            }
        };
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    Ui.haptic(v);
                    press.run();
                    if (repeat) repeater.postDelayed(again, 380);
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

    private void press(TextView t, String label, String key) {
        switch (key) {
            case "numpad":
                showNumpad(true);
                return;
            case "letters":
                showNumpad(false);
                return;
            case "ime":
                if (systemKeyboard != null) systemKeyboard.run();
                return;
            case "shift": {
                long now = System.currentTimeMillis();
                if (now - lastShiftTap < 350) {  // double tap = caps lock
                    capsLock = !capsLock;
                    shift = capsLock;
                } else {
                    shift = !shift;
                    capsLock = false;
                }
                lastShiftTap = now;
                paintShift();
                return;
            }
            case "ctrl":
            case "alt":
            case "super":
                if (mods.remove(key) != null) paint(t, false);
                else {
                    mods.put(key, t);
                    paint(t, true);
                }
                return;
            default:
                send(label, key);
        }
    }

    private void send(String label, String key) {
        StringBuilder combo = new StringBuilder();
        for (String m : mods.keySet()) combo.append(m).append('+');
        if (key.isEmpty() && combo.length() == 0) {
            link.text(shift ? shifted(label) : label);
        } else {
            if (shift && !key.startsWith("kp")) combo.append("shift+");
            String k = key.isEmpty() ? name(label) : key;
            link.combo(combo + k);
        }
        if (shift && !capsLock) {
            shift = false;
            paintShift();
        }
        for (TextView t : mods.values()) paint(t, false);
        mods.clear();
    }

    /** Key name for punctuation used together with ctrl / alt. */
    private static String name(String label) {
        switch (label) {
            case "-": return "minus";
            case "=": return "equal";
            case "[": return "leftbrace";
            case "]": return "rightbrace";
            case "\\": return "backslash";
            case ";": return "semicolon";
            case "'": return "apostrophe";
            case ",": return "comma";
            case ".": return "dot";
            case "/": return "slash";
            default: return label;
        }
    }

    private static String shifted(String s) {
        String from = "1234567890-=[]\\;',./`";
        String to = "!@#$%^&*()_+{}|:\"<>?~";
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            int i = from.indexOf(c);
            out.append(i >= 0 ? to.charAt(i) : Character.toUpperCase(c));
        }
        return out.toString();
    }

    private void paintShift() {
        if (shiftKey == null) return;
        paint(shiftKey, shift);
        shiftKey.setText(capsLock ? "⇪" : "⇧");
    }

    private void paint(TextView t, boolean on) {
        t.setBackground(th.pressable(on ? th.accent : (th.dark ? 0xFF2C2C2E : 0xFFD1D3D9), th.dp(9)));
        t.setTextColor(on ? 0xFFFFFFFF : th.text);
    }

    @Override
    protected void onDetachedFromWindow() {
        repeater.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}

package io.github.aliihjt.tabletdesk;

import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;

/** Android key codes -> key names understood by the Linux host (evdev KEY_* without prefix). */
final class KeyNames {
    private static final Map<Integer, String> SPECIAL = new HashMap<>();

    static {
        SPECIAL.put(KeyEvent.KEYCODE_DEL, "backspace");
        SPECIAL.put(KeyEvent.KEYCODE_FORWARD_DEL, "delete");
        SPECIAL.put(KeyEvent.KEYCODE_ENTER, "enter");
        SPECIAL.put(KeyEvent.KEYCODE_NUMPAD_ENTER, "enter");
        SPECIAL.put(KeyEvent.KEYCODE_ESCAPE, "esc");
        SPECIAL.put(KeyEvent.KEYCODE_TAB, "tab");
        SPECIAL.put(KeyEvent.KEYCODE_SPACE, "space");
        SPECIAL.put(KeyEvent.KEYCODE_DPAD_LEFT, "left");
        SPECIAL.put(KeyEvent.KEYCODE_DPAD_RIGHT, "right");
        SPECIAL.put(KeyEvent.KEYCODE_DPAD_UP, "up");
        SPECIAL.put(KeyEvent.KEYCODE_DPAD_DOWN, "down");
        SPECIAL.put(KeyEvent.KEYCODE_MOVE_HOME, "home");
        SPECIAL.put(KeyEvent.KEYCODE_MOVE_END, "end");
        SPECIAL.put(KeyEvent.KEYCODE_PAGE_UP, "pageup");
        SPECIAL.put(KeyEvent.KEYCODE_PAGE_DOWN, "pagedown");
        SPECIAL.put(KeyEvent.KEYCODE_INSERT, "insert");
        SPECIAL.put(KeyEvent.KEYCODE_CTRL_LEFT, "leftctrl");
        SPECIAL.put(KeyEvent.KEYCODE_CTRL_RIGHT, "rightctrl");
        SPECIAL.put(KeyEvent.KEYCODE_SHIFT_LEFT, "leftshift");
        SPECIAL.put(KeyEvent.KEYCODE_SHIFT_RIGHT, "rightshift");
        SPECIAL.put(KeyEvent.KEYCODE_ALT_LEFT, "leftalt");
        SPECIAL.put(KeyEvent.KEYCODE_ALT_RIGHT, "rightalt");
        SPECIAL.put(KeyEvent.KEYCODE_META_LEFT, "leftmeta");
        SPECIAL.put(KeyEvent.KEYCODE_META_RIGHT, "rightmeta");
        SPECIAL.put(KeyEvent.KEYCODE_CAPS_LOCK, "capslock");
        SPECIAL.put(KeyEvent.KEYCODE_SYSRQ, "sysrq");
        SPECIAL.put(KeyEvent.KEYCODE_COMMA, "comma");
        SPECIAL.put(KeyEvent.KEYCODE_PERIOD, "dot");
        SPECIAL.put(KeyEvent.KEYCODE_MINUS, "minus");
        SPECIAL.put(KeyEvent.KEYCODE_EQUALS, "equal");
        SPECIAL.put(KeyEvent.KEYCODE_SEMICOLON, "semicolon");
        SPECIAL.put(KeyEvent.KEYCODE_APOSTROPHE, "apostrophe");
        SPECIAL.put(KeyEvent.KEYCODE_SLASH, "slash");
        SPECIAL.put(KeyEvent.KEYCODE_BACKSLASH, "backslash");
        SPECIAL.put(KeyEvent.KEYCODE_LEFT_BRACKET, "leftbrace");
        SPECIAL.put(KeyEvent.KEYCODE_RIGHT_BRACKET, "rightbrace");
        SPECIAL.put(KeyEvent.KEYCODE_GRAVE, "grave");
    }

    private KeyNames() {
    }

    /** Host key name, or null for keys the tablet should keep (volume, back, ...). */
    static String of(int code) {
        String s = SPECIAL.get(code);
        if (s != null) return s;
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('a' + code - KeyEvent.KEYCODE_A));
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + code - KeyEvent.KEYCODE_0));
        }
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) {
            return "f" + (code - KeyEvent.KEYCODE_F1 + 1);
        }
        return null;
    }
}

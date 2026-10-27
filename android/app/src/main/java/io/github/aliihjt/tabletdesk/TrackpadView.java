package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * Laptop trackpad surface that also receives the soft keyboard.
 *
 * 1 finger move = pointer, tap = click, tap then hold-and-drag = drag
 * 2 fingers move = scroll, tap = right click
 * 3 fingers tap = middle click, swipe up = Activities, swipe left/right = workspaces
 */
public class TrackpadView extends View {
    /** Called for typed text so the activity can apply sticky modifiers. */
    interface TypingListener {
        void onTyped(String text);

        void onKey(String key);
    }

    private static final long TAP_MS = 220;
    private static final long DOUBLE_TAP_MS = 260;

    private final HostLink link = HostLink.get();
    private final float density;
    private final float slop;
    private TypingListener typing;
    float sensitivity = 1f;

    private float lastX, lastY, startX, startY;
    private int lastCount, maxPointers;
    private long downTime, lastTapUp;
    private boolean moved, dragging, gestureFired;

    public TrackpadView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        slop = 8 * density;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setTypingListener(TypingListener l) {
        typing = l;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int count = ev.getPointerCount();
        float cx = 0, cy = 0;
        for (int i = 0; i < count; i++) {
            cx += ev.getX(i);
            cy += ev.getY(i);
        }
        cx /= count;
        cy /= count;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                long now = ev.getEventTime();
                moved = false;
                gestureFired = false;
                maxPointers = 1;
                downTime = now;
                startX = lastX = cx;
                startY = lastY = cy;
                lastCount = 1;
                if (now - lastTapUp < DOUBLE_TAP_MS) {  // tap, then touch again = drag
                    dragging = true;
                    link.button("left", "down");
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, count);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (count != lastCount) {  // finger added/removed: re-anchor, no jump
                    lastCount = count;
                    lastX = cx;
                    lastY = cy;
                    startX = cx;
                    startY = cy;
                    return true;
                }
                float dx = cx - lastX, dy = cy - lastY;
                lastX = cx;
                lastY = cy;
                if (Math.hypot(cx - startX, cy - startY) > slop) moved = true;
                if (count == 1) {
                    float ddx = dx / density, ddy = dy / density;
                    float speed = (float) Math.hypot(ddx, ddy);
                    // pointer acceleration: slow = precise, fast = travel far
                    float gain = sensitivity * (1.2f + Math.min(speed / 6f, 2.5f));
                    link.move(ddx * gain, ddy * gain);
                } else if (count == 2) {
                    link.scroll(dx / density * 1.4f, dy / density * 1.4f);
                } else if (count >= 3 && !gestureFired) {
                    float tx = (cx - startX) / density, ty = (cy - startY) / density;
                    if (ty < -80) fire("super");
                    else if (tx < -100) fire("ctrl+alt+right");
                    else if (tx > 100) fire("ctrl+alt+left");
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                long now = ev.getEventTime();
                if (dragging) {
                    dragging = false;
                    link.button("left", "up");
                    lastTapUp = 0;
                } else if (!moved && now - downTime < TAP_MS * (maxPointers > 1 ? 2 : 1)) {
                    link.button(maxPointers == 1 ? "left" : maxPointers == 2 ? "right" : "middle", "click");
                    lastTapUp = maxPointers == 1 ? now : 0;
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                } else {
                    lastTapUp = 0;
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (dragging) link.button("left", "up");
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private void fire(String combo) {
        gestureFired = true;
        moved = true;
        link.combo(combo);
    }

    // ---- soft keyboard ----

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        // visible-password = no autocorrect / composing, so every key is sent at once
        out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new KeyConnection(this);
    }

    private final class KeyConnection extends BaseInputConnection {
        private String composing = "";

        KeyConnection(View v) {
            super(v, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            replaceComposing(text.toString());
            composing = "";
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            replaceComposing(text.toString());
            composing = text.toString();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) typing.onKey("backspace");
            for (int i = 0; i < after; i++) typing.onKey("delete");
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            typing.onKey("enter");
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent ev) {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
            int code = ev.getKeyCode();
            int unicode = ev.getUnicodeChar();
            if (unicode == 0 || code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_TAB) {
                String name = KeyNames.of(code);
                if (name != null) typing.onKey(name);
            } else {
                typing.onTyped(String.valueOf((char) unicode));
            }
            return true;
        }

        /** Sends only the difference between what was typed and the new text. */
        private void replaceComposing(String now) {
            int p = 0;
            int max = Math.min(composing.length(), now.length());
            while (p < max && composing.charAt(p) == now.charAt(p)) p++;
            for (int i = p; i < composing.length(); i++) typing.onKey("backspace");
            if (p < now.length()) typing.onTyped(now.substring(p));
        }
    }
}

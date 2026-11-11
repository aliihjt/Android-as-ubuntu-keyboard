package io.github.aliihjt.tabletdesk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Universal Control, the tablet half (host/universal.py is the laptop half):
 * when the laptop's pointer crosses its screen edge, this draws a pointer here
 * and turns the laptop's mouse, touchpad and keyboard into taps, drags, scrolls
 * and typing on the tablet. Android only allows this to an accessibility
 * service the user turns on (Settings › Accessibility › Fedora Pad).
 * Moving off the edge the pointer came in from hands control back.
 */
public class UniversalService extends AccessibilityService {
    private static UniversalService running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HostLink link = HostLink.get();
    private WindowManager wm;
    private Arrow arrow;
    private WindowManager.LayoutParams arrowLp;
    private boolean active;
    private String side = "right";        // the laptop screen's edge the tablet is on
    private float x, y;                   // pointer, in screen pixels
    private int width, height;
    private float scale = 1f;             // laptop pixels -> tablet pixels
    private GestureDescription.StrokeDescription held;  // a button held down: a drag in progress
    private float heldX, heldY;
    private long lastDragAt;
    private float scrollX, scrollY;
    private int meta;                     // shift / ctrl / alt / meta held on the laptop keyboard

    /** True when the user has turned the helper on (Settings › Universal Control shows it). */
    static boolean on() {
        return running != null;
    }

    /** Tell the laptop the helper is there (after every (re)connection). */
    static void announce() {
        UniversalService s = running;
        if (s != null) s.main.post(s::ready);
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        wm = getSystemService(WindowManager.class);
        link.attach(this);
        link.start();
        link.on("uc", this::message);
        ready();
    }

    private void ready() {
        try {
            link.send(new JSONObject().put("t", "ucready").put("on", true));
        } catch (JSONException ignored) {
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stop(false);
        try {
            link.send(new JSONObject().put("t", "ucready").put("on", false));
        } catch (JSONException ignored) {
        }
        link.on("uc", null);
        running = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // ---- messages from the laptop

    private void message(JSONObject m) {
        switch (m.optString("a")) {
            case "enter":
                start(m.optString("side", "right"), (float) m.optDouble("y", 0.5));
                break;
            case "leave":
                stop(false);
                break;
            case "move":
                if (active) move((float) m.optDouble("dx"), (float) m.optDouble("dy"));
                break;
            case "button":
                if (active) button(m.optString("b"), m.optInt("v") == 1);
                break;
            case "scroll":
                if (active) scroll((float) m.optDouble("dx"), (float) m.optDouble("dy"));
                break;
            case "key":
                if (active) key(m.optString("k"), m.optInt("v"));
                break;
            default:
                break;
        }
    }

    private void start(String fromSide, float fy) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        width = dm.widthPixels;
        height = dm.heightPixels;
        scale = Math.max(1f, dm.density / 1.25f);
        side = "left".equals(fromSide) ? "left" : "right";
        // the laptop is to the left (side right): the pointer comes in at the tablet's left edge
        x = "right".equals(side) ? 2 : width - 3;
        y = Math.max(0, Math.min(height - 1, fy * height));
        meta = 0;
        active = true;
        showArrow();
        reply("entered");
        main.removeCallbacks(alive);
        main.post(alive);
    }

    private void stop(boolean tellLaptop) {
        if (!active) return;
        active = false;
        if (held != null) release();
        main.removeCallbacks(alive);
        main.removeCallbacks(flushScroll);
        hideArrow();
        if (tellLaptop) reply("exit");
    }

    private final Runnable alive = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            reply("alive");  // without this the laptop takes its mouse and keyboard back
            main.postDelayed(this, 1000);
        }
    };

    private void reply(String a) {
        try {
            link.send(new JSONObject().put("t", "uc").put("a", a));
        } catch (JSONException ignored) {
        }
    }

    // ---- the pointer

    private void move(float dx, float dy) {
        x += dx * scale;
        y = Math.max(0, Math.min(height - 1, y + dy * scale));
        boolean back = "right".equals(side) ? x < 0 : x > width - 1;
        if (back && held == null) {  // off the edge it came in from: back to the laptop
            stop(true);
            return;
        }
        x = Math.max(0, Math.min(width - 1, x));
        moveArrow();
        if (held != null && SystemClock.uptimeMillis() - lastDragAt > 16) drag();
    }

    private void button(String b, boolean down) {
        if ("right".equals(b)) {  // a right click: a long press, as on a touch screen
            if (down) tap(650);
            return;
        }
        if ("middle".equals(b)) return;
        if (down) press();
        else release();
    }

    private Path point(float px, float py) {
        Path p = new Path();
        p.moveTo(px, py);
        p.lineTo(px, py);
        return p;
    }

    private void tap(long ms) {
        dispatch(new GestureDescription.StrokeDescription(point(x, y), 0, ms));
    }

    /** Button down: the finger touches and stays (Android 8+), so moving drags. */
    private void press() {
        heldX = x;
        heldY = y;
        lastDragAt = SystemClock.uptimeMillis();
        held = new GestureDescription.StrokeDescription(point(x, y), 0, 1, true);
        dispatch(held);
    }

    private void drag() {
        Path p = new Path();
        p.moveTo(heldX, heldY);
        p.lineTo(x, y);
        long now = SystemClock.uptimeMillis();
        long ms = Math.max(1, Math.min(100, now - lastDragAt));
        lastDragAt = now;
        heldX = x;
        heldY = y;
        try {
            held = held.continueStroke(p, 0, ms, true);
            dispatch(held);
        } catch (IllegalStateException e) {
            held = null;
        }
    }

    private void release() {
        if (held == null) return;
        try {
            dispatch(held.continueStroke(point(x, y), 0, 1, false));
        } catch (IllegalStateException ignored) {
        }
        held = null;
    }

    /** Scrolling is a short swipe from the pointer; small amounts are collected first. */
    private void scroll(float dx, float dy) {
        scrollX += dx * scale;
        scrollY += dy * scale;
        main.removeCallbacks(flushScroll);
        main.postDelayed(flushScroll, 40);
    }

    private final Runnable flushScroll = () -> {
        float sx = Math.max(-height / 2f, Math.min(height / 2f, scrollX));
        float sy = Math.max(-height / 2f, Math.min(height / 2f, scrollY));
        scrollX = scrollY = 0;
        if (Math.abs(sx) < 2 && Math.abs(sy) < 2) return;
        float ex = Math.max(1, Math.min(width - 2, x + sx)), ey = Math.max(1, Math.min(height - 2, y + sy));
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(ex, ey);
        dispatch(new GestureDescription.StrokeDescription(p, 0, 120));
    };

    private void dispatch(GestureDescription.StrokeDescription s) {
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    // ---- the keyboard

    private void key(String name, int v) {
        int code = KeyEvent.keyCodeFromString(name);
        if (code == KeyEvent.KEYCODE_UNKNOWN) return;
        int bit = metaBit(code);
        if (bit != 0) {
            if (v == 1) meta |= bit;
            else if (v == 0) meta &= ~bit;
        }
        if (v == 1 && bit == 0 && global(code)) return;
        if (Build.VERSION.SDK_INT >= 33 && getInputMethod() != null
                && getInputMethod().getCurrentInputConnection() != null) {
            long now = SystemClock.uptimeMillis();
            getInputMethod().getCurrentInputConnection().sendKeyEvent(new KeyEvent(now, now,
                    v == 0 ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN, code, v == 2 ? 1 : 0, meta));
        }
    }

    /** Keys that work everywhere, even with no text box: Esc = Back, ⌘ alone = Home, Alt+Tab = recent apps. */
    private boolean global(int code) {
        boolean noMods = (meta & ~(KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON)) == 0;
        boolean typing = Build.VERSION.SDK_INT >= 33 && getInputMethod() != null
                && getInputMethod().getCurrentInputConnection() != null;
        if (code == KeyEvent.KEYCODE_ESCAPE && noMods && !typing) return performGlobalAction(GLOBAL_ACTION_BACK);
        if (code == KeyEvent.KEYCODE_TAB && (meta & KeyEvent.META_ALT_ON) != 0) {
            return performGlobalAction(GLOBAL_ACTION_RECENTS);
        }
        if ((meta & KeyEvent.META_META_ON) != 0) {
            if (code == KeyEvent.KEYCODE_H) return performGlobalAction(GLOBAL_ACTION_HOME);
            if (code == KeyEvent.KEYCODE_N) return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        }
        return false;
    }

    private static int metaBit(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return KeyEvent.META_META_ON;
            default:
                return 0;
        }
    }

    // ---- drawing the pointer (an accessibility overlay: no extra permission)

    private void showArrow() {
        if (arrow == null) {
            arrow = new Arrow(this);
            arrowLp = new WindowManager.LayoutParams(arrow.size, arrow.size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            arrowLp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                arrowLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        arrowLp.x = Math.round(x);
        arrowLp.y = Math.round(y);
        try {
            if (arrow.getParent() == null) wm.addView(arrow, arrowLp);
            else wm.updateViewLayout(arrow, arrowLp);
        } catch (RuntimeException ignored) {
        }
    }

    private void moveArrow() {
        if (arrow == null || arrow.getParent() == null) return;
        arrowLp.x = Math.round(x);
        arrowLp.y = Math.round(y);
        try {
            wm.updateViewLayout(arrow, arrowLp);
        } catch (RuntimeException ignored) {
        }
    }

    private void hideArrow() {
        if (arrow != null && arrow.getParent() != null) {
            try {
                wm.removeView(arrow);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** The classic arrow, black with a white edge: clear on any screen, in colour or black & white. */
    private static final class Arrow extends View {
        final int size;
        private final Path path = new Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);

        Arrow(Context c) {
            super(c);
            float s = c.getResources().getDisplayMetrics().density * 1.2f;
            size = Math.round(24 * s);
            path.moveTo(1, 1);
            path.lineTo(1, 17 * s);
            path.lineTo(4.2f * s, 13 * s);
            path.lineTo(7.2f * s, 19.5f * s);
            path.lineTo(9.6f * s, 18.4f * s);
            path.lineTo(6.6f * s, 12.2f * s);
            path.lineTo(11.5f * s, 12.2f * s);
            path.close();
            fill.setColor(0xFF000000);
            edge.setColor(0xFFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.6f * s);
            edge.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawPath(path, edge);
            c.drawPath(path, fill);
        }
    }
}

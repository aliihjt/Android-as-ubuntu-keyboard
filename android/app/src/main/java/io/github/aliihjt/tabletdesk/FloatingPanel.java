package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A movable card floating over the Fedora screen with either the keyboard or a
 * trackpad. Drag it by its top bar; it remembers where you left it.
 */
final class FloatingPanel {
    static final String KEYBOARD = "keyboard";
    static final String TRACKPAD = "trackpad";

    private final Context ctx;
    private final WindowManager wm;
    private final Theme th;
    private final SharedPreferences prefs;
    private final String mode;
    private final FrameLayout root;
    private final WindowManager.LayoutParams params;

    FloatingPanel(Context ctx, WindowManager wm, String mode, Runnable close) {
        this.ctx = ctx;
        this.wm = wm;
        this.mode = mode;
        th = new Theme(ctx);
        prefs = ctx.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);

        LinearLayout card = th.card();
        card.setElevation(th.dp(12));
        int p = th.dp(10);
        card.setPadding(p, th.dp(4), p, p);
        card.addView(header(mode.equals(KEYBOARD) ? "Keyboard" : "Trackpad", close));

        if (mode.equals(KEYBOARD)) {
            card.addView(new KeyboardView(th, 46));
        } else {
            TrackpadView pad = new TrackpadView(ctx);
            pad.sensitivity = prefs.getInt("speed", 100) / 100f;
            pad.naturalScroll = prefs.getBoolean("natural", true);
            pad.setBackground(Theme.round(th.fill, th.dp(14)));
            card.addView(pad, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, th.dp(220)));
            LinearLayout clicks = new LinearLayout(ctx);
            clicks.addView(clickButton("left"), th.weighted(0));
            clicks.addView(clickButton("right"), th.weighted(8));
            card.addView(clicks, th.fullWidth(8));
        }

        // a little margin so the card's shadow is visible
        root = new FrameLayout(ctx);
        int m = th.dp(10);
        root.setPadding(m, m, m, m);
        root.addView(card);

        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int width = mode.equals(KEYBOARD)
                ? Math.min(screenW, th.dp(900)) : Math.min(screenW, th.dp(380));
        params = new WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt(mode + "_x", (screenW - width) / 2);
        params.y = prefs.getInt(mode + "_y", screenH / 2);
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View header(String title, Runnable close) {
        LinearLayout h = new LinearLayout(ctx);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(th.dp(40));

        // grab handle like an iPad sheet
        View grip = new View(ctx);
        grip.setBackground(Theme.round(th.separator, th.dp(3)));
        LinearLayout gripArea = new LinearLayout(ctx);
        gripArea.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = th.text(title, 14, th.secondary, true);
        gripArea.addView(name);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(th.dp(36), th.dp(5));
        glp.leftMargin = th.dp(12);
        gripArea.addView(grip, glp);
        float[] start = new float[4];
        gripArea.setOnTouchListener((v, ev) -> {
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
                    prefs.edit().putInt(mode + "_x", params.x).putInt(mode + "_y", params.y).apply();
                    return true;
                default:
                    return true;
            }
        });
        h.addView(gripArea, new LinearLayout.LayoutParams(0, th.dp(40), 1f));

        TextView done = th.text("Done", 16, th.accent, true);
        done.setGravity(Gravity.CENTER);
        done.setPadding(th.dp(12), 0, th.dp(6), 0);
        th.click(done, v -> close.run());
        h.addView(done, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, th.dp(40)));
        return h;
    }

    private View clickButton(String which) {
        TextView b = th.key("", 14);
        b.setMinHeight(th.dp(46));
        th.click(b, v -> HostLink.get().button(which, "click"));
        return b;
    }
}

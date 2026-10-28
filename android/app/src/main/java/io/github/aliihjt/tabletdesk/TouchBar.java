package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * MacBook-style Touch Bar floating above the bottom edge: Esc, buttons that change
 * with the focused desktop app, an Fn layer with F1-F12, and media / brightness keys.
 * Shrinks to a small handle when unused so it does not cover the desktop.
 */
final class TouchBar implements HostLink.Listener {
    private static final int KEY_BG = 0xFF2C2C2E;
    private static final int ACCENT = 0xFF0A84FF;
    private static final long AUTO_HIDE_MS = 8000;

    private static final String[] BROWSER = {
            "◀|alt+left", "▶|alt+right", "⟳|ctrl+r", "🔍 Address|ctrl+l",
            "＋ Tab|ctrl+t", "✕ Tab|ctrl+w", "☆|ctrl+d", "⇥ Next Tab|ctrl+tab"};
    private static final String[] TERMINAL = {
            "^C|ctrl+c", "Copy|ctrl+shift+c", "Paste|ctrl+shift+v", "Tab|tab", "↑|up",
            "↓|down", "clear|text:clear\n", "＋ Tab|ctrl+shift+t"};
    private static final String[] CODE = {
            "⌘ Palette|ctrl+shift+p", "Go to File|ctrl+p", "🔍|ctrl+f",
            "// Comment|ctrl+slash", "Terminal|ctrl+grave", "💾 Save|ctrl+s", "▶ Run|f5"};
    private static final String[] FILES = {
            "◀|alt+left", "▶|alt+right", "↑ Up|alt+up", "📁 New Folder|ctrl+shift+n",
            "Rename|f2", "Hidden|ctrl+h", "🔍|ctrl+f"};
    private static final String[] DESKTOP = {
            "Activities|super", "Apps|super+a", "📁 Files|cmd:files", "⌨ Terminal|cmd:terminal",
            "📷 Screenshot|print", "⚙ Settings|cmd:settings", "Undo|ctrl+z",
            "Copy|ctrl+c", "Paste|ctrl+v"};
    private static final String[] CONTROLS = {
            "🔅|brightdown", "🔆|brightup", "🔇|mute",
            "🔉|voldown", "🔊|volup", "⏮|prev", "⏯|playpause", "⏭|next"};

    private final Context ctx;
    private final WindowManager wm;
    private final FrameLayout root;
    private final LinearLayout bar;
    private final LinearLayout contextRow;
    private final View handle;
    private final TextView fnKey;
    private final WindowManager.LayoutParams params;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoCollapse = () -> setCollapsed(true);
    private final boolean autoHide;
    private boolean fnMode;
    private boolean collapsed;
    private String app = "";

    @SuppressLint("ClickableViewAccessibility")
    TouchBar(Context ctx, WindowManager wm, boolean autoHide) {
        this.ctx = ctx;
        this.wm = wm;
        this.autoHide = autoHide;
        root = new FrameLayout(ctx);

        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rounded(0xF0111113, Ui.dp(ctx, 16)));
        bar.setElevation(Ui.dp(ctx, 6));
        int pad = Ui.dp(ctx, 6);
        bar.setPadding(pad, pad, pad, pad);

        bar.addView(key("esc", "esc", false, 58));
        contextRow = new LinearLayout(ctx);
        contextRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroller = new HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(contextRow);
        bar.addView(scroller, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        fnKey = key("fn", null, false, 44);
        fnKey.setOnClickListener(v -> {
            poke();
            fnMode = !fnMode;
            fnKey.setBackground(Ui.pressable(fnMode ? ACCENT : KEY_BG, Ui.dp(ctx, 8), 0x55FFFFFF));
            rebuildContext();
        });
        bar.addView(fnKey);
        View divider = new View(ctx);
        divider.setBackgroundColor(0xFF48484A);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(ctx, 1), Ui.dp(ctx, 22));
        dlp.setMargins(Ui.dp(ctx, 5), 0, Ui.dp(ctx, 5), 0);
        bar.addView(divider, dlp);
        for (String c : CONTROLS) {
            String[] p = c.split("\\|");
            bar.addView(key(p[0], p[1], true, 40));
        }
        TextView hide = key("\u2304", null, false, 36);
        hide.setOnClickListener(v -> setCollapsed(true));
        bar.addView(hide);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // small pill shown while collapsed; tap or swipe it to bring the bar back
        handle = new View(ctx);
        handle.setBackground(Ui.rounded(0xCC1C1C1E, Ui.dp(ctx, 4)));
        handle.setElevation(Ui.dp(ctx, 3));
        handle.setVisibility(View.GONE);
        root.addView(handle, new FrameLayout.LayoutParams(Ui.dp(ctx, 110), Ui.dp(ctx, 8), Gravity.CENTER));
        root.setOnTouchListener((v, ev) -> {
            if (collapsed && ev.getActionMasked() == MotionEvent.ACTION_DOWN) setCollapsed(false);
            return collapsed;
        });

        params = new WindowManager.LayoutParams(
                expandedWidth(), Ui.dp(ctx, 46),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = Ui.dp(ctx, 8);

        rebuildContext();
        HostLink.get().addListener(this);
        poke();
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    void destroy() {
        handler.removeCallbacksAndMessages(null);
        HostLink.get().removeListener(this);
    }

    void toggleCollapsed() {
        setCollapsed(!collapsed);
    }

    /** Touch Bar is as wide as a MacBook's: never wider than 860dp. */
    private int expandedWidth() {
        int screen = ctx.getResources().getDisplayMetrics().widthPixels;
        return Math.min(screen - Ui.dp(ctx, 24), Ui.dp(ctx, 860));
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        bar.setVisibility(c ? View.GONE : View.VISIBLE);
        handle.setVisibility(c ? View.VISIBLE : View.GONE);
        params.width = c ? Ui.dp(ctx, 160) : expandedWidth();
        params.height = c ? Ui.dp(ctx, 22) : Ui.dp(ctx, 46);
        params.y = c ? Ui.dp(ctx, 2) : Ui.dp(ctx, 8);
        if (root.isAttachedToWindow()) wm.updateViewLayout(root, params);
        if (c) handler.removeCallbacks(autoCollapse);
        else poke();
    }

    /** Restart the idle timer after any interaction. */
    private void poke() {
        handler.removeCallbacks(autoCollapse);
        if (autoHide) handler.postDelayed(autoCollapse, AUTO_HIDE_MS);
    }

    @Override
    public void onStatus(boolean connected, String host) {
        bar.setAlpha(connected ? 1f : 0.55f);
    }

    @Override
    public void onApp(String name) {
        app = name == null ? "" : name;
        if (!fnMode) rebuildContext();
    }

    private void rebuildContext() {
        contextRow.removeAllViews();
        if (fnMode) {
            for (int i = 1; i <= 12; i++) contextRow.addView(key("F" + i, "f" + i, false, 46));
            return;
        }
        for (String c : contextFor(app)) {
            String[] p = c.split("\\|", 2);
            contextRow.addView(key(p[0], p[1], p[1].equals("up") || p[1].equals("down"), 0));
        }
    }

    private static String[] contextFor(String app) {
        String a = app.toLowerCase();
        if (a.contains("firefox") || a.contains("chrom") || a.contains("brave")
                || a.contains("edge") || a.contains("browser") || a.contains("opera")) return BROWSER;
        if (a.contains("terminal") || a.contains("console") || a.contains("tilix")
                || a.contains("ptyxis") || a.contains("konsole")) return TERMINAL;
        if (a.contains("code") || a.contains("studio") || a.contains("idea")) return CODE;
        if (a.equals("files") || a.contains("nautilus") || a.contains("dolphin")) return FILES;
        return DESKTOP;
    }

    /** A Touch Bar key. repeat=true keeps firing while held (volume, arrows). */
    @SuppressLint("ClickableViewAccessibility")
    private TextView key(String label, String action, boolean repeat, int minWidthDp) {
        TextView t = Ui.label(ctx, label, 13, 0xFFF2F2F7, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), 0);
        if (minWidthDp > 0) t.setMinWidth(Ui.dp(ctx, minWidthDp));
        t.setBackground(Ui.pressable(KEY_BG, Ui.dp(ctx, 8), 0x55FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3), 0);
        t.setLayoutParams(lp);
        if (action == null) return t;
        if (!repeat) {
            t.setOnClickListener(v -> {
                Ui.haptic(v);
                poke();
                HostLink.get().action(action);
            });
            return t;
        }
        Runnable again = new Runnable() {
            @Override
            public void run() {
                HostLink.get().action(action);
                handler.postDelayed(this, 110);
            }
        };
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    Ui.haptic(v);
                    poke();
                    HostLink.get().action(action);
                    handler.postDelayed(again, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    handler.removeCallbacks(again);
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }
}

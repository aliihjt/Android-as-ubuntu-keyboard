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
 * MacBook-style Touch Bar along the bottom edge: Esc, buttons that change with
 * the focused desktop app, an Fn layer with F1-F12, and media / brightness keys.
 */
final class TouchBar implements HostLink.Listener {
    private static final int KEY_BG = 0xFF3A3A3C;
    private static final int ACCENT = 0xFF0A84FF;

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
    private final TextView pill;
    private final TextView fnKey;
    private final WindowManager.LayoutParams params;
    private final Handler repeater = new Handler(Looper.getMainLooper());
    private boolean fnMode;
    private boolean collapsed;
    private String app = "";

    TouchBar(Context ctx, WindowManager wm) {
        this.ctx = ctx;
        this.wm = wm;
        root = new FrameLayout(ctx);

        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rounded(0xF2000000, Ui.dp(ctx, 12)));
        int pad = Ui.dp(ctx, 6);
        bar.setPadding(pad, pad, pad, pad);

        bar.addView(key("esc", "esc", false, 64));
        contextRow = new LinearLayout(ctx);
        contextRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView scroller = new HorizontalScrollView(ctx);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(contextRow);
        bar.addView(scroller, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        fnKey = key("fn", null, false, 52);
        fnKey.setOnClickListener(v -> {
            fnMode = !fnMode;
            fnKey.setBackground(Ui.pressable(fnMode ? ACCENT : KEY_BG, Ui.dp(ctx, 6), 0x55FFFFFF));
            rebuildContext();
        });
        bar.addView(fnKey);
        View divider = new View(ctx);
        divider.setBackgroundColor(0xFF555555);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(ctx, 1), Ui.dp(ctx, 26));
        dlp.setMargins(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0);
        bar.addView(divider, dlp);
        for (String c : CONTROLS) {
            String[] p = c.split("\\|");
            bar.addView(key(p[0], p[1], true, 48));
        }
        TextView hide = key("⌄", null, false, 40);
        hide.setOnClickListener(v -> toggleCollapsed());
        bar.addView(hide);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        pill = Ui.label(ctx, "⌃ Touch Bar", 13, 0xFFFFFFFF, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 14), 0);
        pill.setBackground(Ui.pressable(0xE6000000, Ui.dp(ctx, 14), 0x55FFFFFF));
        pill.setOnClickListener(v -> toggleCollapsed());
        pill.setVisibility(View.GONE);
        root.addView(pill, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT));

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 52),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = Ui.dp(ctx, 4);

        rebuildContext();
        HostLink.get().addListener(this);
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    void destroy() {
        repeater.removeCallbacksAndMessages(null);
        HostLink.get().removeListener(this);
    }

    void toggleCollapsed() {
        collapsed = !collapsed;
        bar.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        pill.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        params.width = collapsed ? Ui.dp(ctx, 130) : WindowManager.LayoutParams.MATCH_PARENT;
        params.height = collapsed ? Ui.dp(ctx, 30) : Ui.dp(ctx, 52);
        params.gravity = Gravity.BOTTOM | (collapsed ? Gravity.END : Gravity.CENTER_HORIZONTAL);
        if (root.isAttachedToWindow()) wm.updateViewLayout(root, params);
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
            for (int i = 1; i <= 12; i++) contextRow.addView(key("F" + i, "f" + i, false, 52));
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
        TextView t = Ui.label(ctx, label, 14, 0xFFFFFFFF, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0);
        if (minWidthDp > 0) t.setMinWidth(Ui.dp(ctx, minWidthDp));
        t.setBackground(Ui.pressable(KEY_BG, Ui.dp(ctx, 6), 0x55FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3), 0);
        t.setLayoutParams(lp);
        if (action == null) return t;
        if (!repeat) {
            t.setOnClickListener(v -> {
                Ui.haptic(v);
                HostLink.get().action(action);
            });
            return t;
        }
        Runnable again = new Runnable() {
            @Override
            public void run() {
                HostLink.get().action(action);
                repeater.postDelayed(this, 110);
            }
        };
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    Ui.haptic(v);
                    HostLink.get().action(action);
                    repeater.postDelayed(again, 400);
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
}

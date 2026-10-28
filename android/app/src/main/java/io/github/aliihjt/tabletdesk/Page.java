package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Base for every Fedora Pad screen: "‹ Fedora Pad" back button, large title,
 * and a centred column (max ~820dp wide) that scrolls. Subclasses fill
 * {@link #content} in {@link #build()}.
 */
abstract class Page extends Activity implements HostLink.Listener {
    protected Theme th;
    protected LinearLayout content;
    protected HostLink link = HostLink.get();
    private TextView statusDot;

    /** Page title, or null for no title bar (the home screen draws its own). */
    protected abstract String title();

    protected abstract void build();

    /** true = content fills the screen instead of scrolling (keyboard, trackpad). */
    protected boolean fullScreen() {
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.loadScale(this);
        link.attach(this);
        link.start();
        th = new Theme(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int side = th.dp(20);
        column.setPadding(side, th.dp(14), side, th.dp(24));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        column.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                fullScreen() ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT, fullScreen() ? 1f : 0f));
        build();

        // centred, not stretched across a wide tablet
        FrameLayout center = new FrameLayout(this);
        int max = Math.round(820 * getResources().getDisplayMetrics().density);
        int width = Math.min(max, getResources().getDisplayMetrics().widthPixels);
        center.addView(column, new FrameLayout.LayoutParams(fullScreen() ? FrameLayout.LayoutParams.MATCH_PARENT : width,
                fullScreen() ? FrameLayout.LayoutParams.MATCH_PARENT : FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        View body = center;
        if (!fullScreen()) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(center);
            body = scroll;
        }

        // the desktop: wallpaper, and every page is a macOS window on it
        FrameLayout desktop = new FrameLayout(this);
        desktop.setBackground(th.wallpaper());
        View top;
        if (title() == null) {  // the home screen draws its own desktop
            desktop.addView(body);
            top = body;
        } else {
            LinearLayout window = new LinearLayout(this);
            window.setOrientation(LinearLayout.VERTICAL);
            window.setBackground(Theme.round(th.background, th.dp(14)));
            window.setClipToOutline(true);
            window.setElevation(th.dp(16));
            window.addView(titleBar());
            View hairline = new View(this);
            hairline.setBackgroundColor(th.separator);
            window.addView(hairline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.max(1, th.dp(0.5f))));
            window.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            boolean upright = getResources().getConfiguration().screenHeightDp
                    > getResources().getConfiguration().screenWidthDp;
            int m = th.dp(upright ? 6 : 12);  // more room for content when the tablet is upright
            wlp.setMargins(m, m, m, m);
            desktop.addView(window, wlp);
            top = window;
        }
        setContentView(desktop);

        // draw under transparent system bars, but keep everything clear of them
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        View target = top;
        desktop.setOnApplyWindowInsetsListener((v, insets) -> {
            int t, b;
            if (Build.VERSION.SDK_INT >= 30) {
                t = insets.getInsets(WindowInsets.Type.statusBars()).top;
                b = Math.max(insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                        insets.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                t = insets.getSystemWindowInsetTop();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, t, 0, b);
            return insets;
        });
    }

    /** macOS title bar: traffic lights, centred title, connection status. */
    private View titleBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setMinimumHeight(th.dp(44));
        bar.setBackgroundColor(th.dark ? 0xFF2A2A2C : 0xFFECECEE);
        LinearLayout lights = th.trafficLights(this::finish,
                () -> moveTaskToBack(true), this::toggleFullScreen);
        lights.setPadding(th.dp(8), 0, 0, 0);
        bar.addView(lights, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                th.dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView name = th.text(title(), 15, th.text, true);
        bar.addView(name, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        statusDot = th.caption("");
        statusDot.setPadding(0, 0, th.dp(14), 0);
        bar.addView(statusDot, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        return bar;
    }

    private boolean immersive;

    /** Green button: hide Android's bars for more room, tap again to bring them back. */
    private void toggleFullScreen() {
        immersive = !immersive;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (immersive) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (immersive) flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addListener(this);
        OverlayService.setBubbleHidden(true);  // our own pages don't need the floating button
    }

    @Override
    protected void onPause() {
        link.removeListener(this);
        OverlayService.setBubbleHidden(false);
        super.onPause();
    }

    @Override
    public void onStatus(boolean connected, String host) {
        if (statusDot != null) {
            statusDot.setText(connected ? "\u25CF " + host + " \u00B7 " + link.via() : "\u25CB Not connected");
            statusDot.setTextColor(connected ? Theme.GREEN : th.secondary);
        }
    }

    @Override
    public void onApp(String name) {
    }

    // ---- small helpers for subclasses ----

    protected void add(View v) {
        content.addView(v, th.fullWidth(0));
    }

    protected void add(View v, int topDp) {
        content.addView(v, th.fullWidth(topDp));
    }

    protected void section(String title) {
        content.addView(th.sectionHeader(title));
    }
}

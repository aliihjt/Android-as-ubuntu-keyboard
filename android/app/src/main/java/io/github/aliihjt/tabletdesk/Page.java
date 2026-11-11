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
 * Base for every Fedora Pad screen, in one of two looks that follow the home
 * screen: like an iPad app (full screen, "‹ Home" and a large title; the iPad
 * and Reader homes) or like a Mac window on the wallpaper (the Mac desktop).
 * Content is a centred column (max ~820dp wide) that scrolls. Subclasses fill
 * {@link #content} in {@link #build()}.
 */
abstract class Page extends Activity implements HostLink.Listener {
    protected Theme th;
    protected LinearLayout content;
    protected HostLink link = HostLink.get();
    /** The iPad look (not the Mac desktop's windows). */
    protected boolean ios;
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
        ios = !"desktop".equals(getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).getString("home_style", "ipad"));
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
        if (ios && title() != null && !fullScreen()) {  // iOS: the large title scrolls with the page
            TextView big = th.largeTitle(title());
            big.setPadding(th.dp(4), 0, 0, th.dp(10));
            column.addView(big, 0);
        }

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
        } else if (ios) {  // like an iPad app: full screen, navigation bar, no window
            desktop.setBackgroundColor(th.background);
            LinearLayout screen = new LinearLayout(this);
            screen.setOrientation(LinearLayout.VERTICAL);
            screen.addView(navBar());
            if (fullScreen()) {
                View hairline = new View(this);
                hairline.setBackgroundColor(th.separator);
                screen.addView(hairline, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        Math.max(1, th.dp(0.5f))));
            }
            screen.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            desktop.addView(screen, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            top = screen;
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
        int light = ios && title() != null && !th.dark
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;  // dark icons on a light page
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | light);
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

    /** iOS navigation bar: "‹ Home" on the left, the title (full-screen pages only), connection status. */
    private View navBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setMinimumHeight(th.dp(48));
        TextView back = th.text("‹ " + (this instanceof MainActivity ? "" : "Home"), 17, th.accent, false);
        back.setTextSize(17 * Ui.scale);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(th.dp(14), 0, th.dp(14), 0);
        back.setMinHeight(th.dp(48));
        th.click(back, v -> finish());
        bar.addView(back, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                th.dp(48), Gravity.START | Gravity.CENTER_VERTICAL));
        if (fullScreen()) {
            TextView name = th.text(title(), 17, th.text, true);
            bar.addView(name, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        statusDot = th.caption("");
        statusDot.setPadding(0, 0, th.dp(16), 0);
        bar.addView(statusDot, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        return bar;
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

    /** Coming to the front is the only time Android lets the app read the clipboard: share it with the other devices. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Devices.checkClipboard(this);
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

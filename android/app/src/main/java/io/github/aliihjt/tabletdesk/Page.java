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
        link.start();
        th = new Theme(this);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int side = th.dp(20);
        column.setPadding(side, th.dp(8), side, th.dp(24));

        if (title() != null) {
            LinearLayout nav = new LinearLayout(this);
            nav.setGravity(Gravity.CENTER_VERTICAL);
            nav.setMinimumHeight(th.dp(44));
            TextView back = th.text("‹ Fedora Pad", 17, th.accent, false);
            back.setPadding(0, th.dp(8), th.dp(16), th.dp(8));
            th.click(back, v -> finish());
            nav.addView(back);
            nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            statusDot = th.caption("");
            nav.addView(statusDot);
            column.addView(nav);
            TextView big = th.largeTitle(title());
            big.setPadding(0, th.dp(4), 0, th.dp(8));
            column.addView(big);
        }

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
        View root = center;
        if (!fullScreen()) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.addView(center);
            root = scroll;
        }
        root.setBackgroundColor(th.background);
        setContentView(root);

        // draw under transparent system bars, but keep content clear of them
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(th.background);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | (th.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        int top = column.getPaddingTop();
        column.setOnApplyWindowInsetsListener((v, insets) -> {
            int bar = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.statusBars()).top
                    : insets.getSystemWindowInsetTop();
            v.setPadding(v.getPaddingLeft(), top + bar, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
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
            statusDot.setText(connected ? "● " + host : "○ Not connected");
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

package io.github.aliihjt.tabletdesk;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The bottom half of a laptop: a big trackpad, click buttons, and a keyboard you can show under it. */
public class TrackpadActivity extends Page {
    private TrackpadView pad;
    private SharedPreferences prefs;

    @Override
    protected String title() {
        return "Trackpad";
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);

        FrameLayout padFrame = new FrameLayout(this);
        padFrame.setBackground(Theme.round(th.card, th.dp(22)));
        padFrame.setElevation(th.dark ? 0 : th.dp(1));
        TextView help = th.caption("1 finger: move · tap: click · tap + drag: drag\n"
                + "2 fingers: scroll · tap: right-click · pinch: zoom\n"
                + "3 fingers ↑ overview · ← → workspaces\n"
                + "4 fingers ← → switch app · ↑ all apps · ↓ hide window");
        help.setGravity(Gravity.CENTER);
        padFrame.addView(help, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        pad = new TrackpadView(this);
        pad.setOnTouchListener((v, ev) -> {
            if (help.getAlpha() == 1f) help.animate().alpha(0f).setDuration(300);
            return false;
        });
        padFrame.addView(pad, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(padFrame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout clicks = new LinearLayout(this);
        clicks.addView(clickButton("left"), th.weighted(0));
        clicks.addView(clickButton("right"), th.weighted(10));
        content.addView(clicks, th.fullWidth(10));

        LinearLayout tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.VERTICAL);
        tray.setPadding(th.dp(6), th.dp(8), th.dp(6), th.dp(8));
        tray.setBackground(Theme.round(th.dark ? 0xFF1C1C1E : 0xFFD1D3D9, th.dp(18)));
        tray.addView(new KeyboardView(th, 50));
        tray.setVisibility(prefs.getBoolean("pad_kb", false) ? View.VISIBLE : View.GONE);

        TextView toggle = th.secondaryButton(tray.getVisibility() == View.VISIBLE
                ? "Hide keyboard" : "⌨️  Show keyboard", v -> {
            boolean show = tray.getVisibility() != View.VISIBLE;
            tray.setVisibility(show ? View.VISIBLE : View.GONE);
            prefs.edit().putBoolean("pad_kb", show).apply();
            ((TextView) v).setText(show ? "Hide keyboard" : "⌨️  Show keyboard");
        });
        content.addView(toggle, th.fullWidth(10));
        content.addView(tray, th.fullWidth(10));
    }

    @Override
    protected void onResume() {
        super.onResume();
        pad.sensitivity = prefs.getInt("speed", 100) / 100f;
        pad.naturalScroll = prefs.getBoolean("natural", true);
    }

    private View clickButton(String which) {
        TextView b = th.key("", 14);
        b.setMinHeight(th.dp(58));
        th.click(b, v -> link.button(which, "click"));
        return b;
    }
}

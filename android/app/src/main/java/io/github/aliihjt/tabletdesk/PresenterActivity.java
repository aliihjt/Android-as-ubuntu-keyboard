package io.github.aliihjt.tabletdesk;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Slide remote for LibreOffice Impress, Google Slides and PDF viewers, with a timer. */
public class PresenterActivity extends Page {
    private final Handler clock = new Handler(Looper.getMainLooper());
    private long started;
    private TextView timer;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long s = (SystemClock.elapsedRealtime() - started) / 1000;
            timer.setText(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60));
            clock.postDelayed(this, 500);
        }
    };

    @Override
    protected String title() {
        return "Presenter";
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        LinearLayout top = new LinearLayout(this);
        top.addView(th.primaryButton("▶  Start slideshow", v -> {
            link.combo("f5");
            started = SystemClock.elapsedRealtime();
            clock.removeCallbacks(tick);
            clock.post(tick);
        }), th.weighted(0));
        top.addView(th.secondaryButton("■  End", v -> {
            link.combo("esc");
            clock.removeCallbacks(tick);
        }), th.weighted(10));
        top.addView(th.secondaryButton("⬛  Black screen", v -> link.combo("b")), th.weighted(10));
        content.addView(top);

        timer = th.text("00:00", 44, th.text, true);
        timer.setGravity(Gravity.CENTER);
        content.addView(timer, th.fullWidth(16));

        // two huge buttons that are easy to hit without looking
        LinearLayout arrows = new LinearLayout(this);
        arrows.addView(bigArrow("◀", "Previous", "left"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f);
        next.leftMargin = th.dp(14);
        arrows.addView(bigArrow("▶", "Next", "right"), next);
        content.addView(arrows, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private LinearLayout bigArrow(String glyph, String label, String key) {
        LinearLayout b = th.card();
        b.setGravity(Gravity.CENTER);
        TextView g = th.text(glyph, 64, th.accent, false);
        g.setGravity(Gravity.CENTER);
        b.addView(g);
        TextView l = th.caption(label);
        l.setGravity(Gravity.CENTER);
        b.addView(l, th.fullWidth(10));
        b.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(th.dark ? 0x33FFFFFF : 0x1F000000),
                null, Theme.round(0xFFFFFFFF, th.dp(18))));
        th.click(b, v -> link.combo(key));
        return b;
    }

    @Override
    protected void onDestroy() {
        clock.removeCallbacks(tick);
        super.onDestroy();
    }
}

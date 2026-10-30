package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Focus timer: a ring that empties as time passes; the PC stays quiet meanwhile. */
public class FocusActivity extends Page {
    private static final int[] PRESETS = {15, 25, 45, 60, 90};
    private final Handler tick = new Handler(Looper.getMainLooper());
    private Ring ring;
    private TextView time, button, today;
    private int minutes = 25;

    @Override
    protected String title() {
        return "Focus";
    }

    @Override
    protected void build() {
        minutes = (int) (FocusTimer.total(this) / 60_000L);
        FrameLayout stage = new FrameLayout(this);
        ring = new Ring(this);
        stage.addView(ring, new FrameLayout.LayoutParams(th.dp(260), th.dp(260), Gravity.CENTER));
        time = th.text("25:00", 54, th.text, true);
        time.setGravity(Gravity.CENTER);
        time.setFontFeatureSettings("tnum");
        stage.addView(time, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        add(stage, 8);
        stage.getLayoutParams().height = th.dp(280);

        LinearLayout presets = new LinearLayout(this);
        for (int m : PRESETS) {
            TextView p = th.secondaryButton(m + " min", v -> {
                if (FocusTimer.running(this)) return;
                minutes = m;
                draw();
            });
            presets.addView(p, th.weighted(m == PRESETS[0] ? 0 : 8));
        }
        add(presets, 12);
        button = th.primaryButton("Start", v -> {
            if (FocusTimer.running(this)) FocusTimer.stop(this);
            else FocusTimer.start(this, minutes);
            draw();
        });
        add(button, 14);
        today = th.caption("");
        today.setGravity(Gravity.CENTER);
        add(today, 10);

        section("While focusing");
        Switch tablet = th.toggle(FocusTimer.tabletDnd(this), on -> {
            if (on && !FocusTimer.canQuietTablet(this)) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            }
            FocusTimer.setTabletDnd(this, on);
        });
        add(th.group(
                th.row("💻", Theme.INDIGO, "PC: Do Not Disturb", "Automatic while the timer runs", null),
                th.row("📱", Theme.PURPLE, "Tablet: Do Not Disturb too", "Android asks you to allow Fedora Pad once", tablet)));
        add(th.caption("Tip: StandBy shows the timer in big numbers while the tablet charges."), 8);
    }

    private void draw() {
        boolean running = FocusTimer.running(this);
        long left = running ? FocusTimer.remaining(this) : minutes * 60_000L;
        long total = running ? FocusTimer.total(this) : minutes * 60_000L;
        long s = (left + 999) / 1000;
        time.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
        ring.fraction = total == 0 ? 0 : left / (float) total;
        ring.invalidate();
        button.setText(running ? "Stop" : "Start " + minutes + " minutes");
        button.setBackground(th.pressable(running ? Theme.RED : th.accent, th.dp(14)));
        int n = FocusTimer.doneToday(this);
        today.setText(n == 0 ? "No focus sessions yet today" : "🎯 " + n + (n == 1 ? " session" : " sessions") + " today");
    }

    private final Runnable every = new Runnable() {
        @Override
        public void run() {
            draw();
            tick.postDelayed(this, 500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        FocusTimer.schedule(this);
        tick.post(every);
    }

    @Override
    protected void onPause() {
        tick.removeCallbacks(every);
        super.onPause();
    }

    /** The countdown ring. */
    static final class Ring extends View {
        float fraction = 1f;
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), front = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ring(Context c) {
            super(c);
            float w = 14 * c.getResources().getDisplayMetrics().density;
            for (Paint p : new Paint[]{back, front}) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w);
                p.setStrokeCap(Paint.Cap.ROUND);
            }
            back.setColor(Ui.isDark(c) ? 0xFF2C2C2E : 0xFFE5E5EA);
            front.setColor(Theme.ORANGE);
        }

        @Override
        protected void onDraw(Canvas c) {
            float inset = back.getStrokeWidth();
            RectF r = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
            c.drawArc(r, 0, 360, false, back);
            c.drawArc(r, -90, 360 * fraction, false, front);
        }
    }
}

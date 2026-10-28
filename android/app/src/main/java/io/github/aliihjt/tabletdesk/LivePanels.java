package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Glass panels that stay up to date while open: a media remote for whatever is
 * playing on the PC, and live PC stats. Only one panel is open at a time.
 */
final class LivePanels {
    private static Dialog open;
    private static final Handler poll = new Handler(Looper.getMainLooper());

    private LivePanels() {
    }

    static void dismiss() {
        if (open != null) open.dismiss();
    }

    // ---- media remote ----

    static void media(Context ctx, boolean dark) {
        if (toggleOff("media")) return;
        int fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;
        int muted = dark ? 0x99EBEBF5 : 0x993C3C43;
        LinearLayout col = column(ctx);

        TextView player = Ui.label(ctx, "🎵  NOW PLAYING", 11.5f, muted, true);
        player.setLetterSpacing(0.08f);
        col.addView(player);
        TextView title = Ui.label(ctx, "…", 20, fg, true);
        title.setSingleLine(false);
        title.setMaxLines(2);
        col.addView(title, block(ctx, 6));
        TextView artist = Ui.label(ctx, "", 14, muted, false);
        col.addView(artist);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.CENTER);
        TextView prev = bigButton(ctx, dark, "⏮", () -> HostLink.get().media("previous"));
        TextView play = bigButton(ctx, dark, "⏯", () -> HostLink.get().media("play-pause"));
        TextView next = bigButton(ctx, dark, "⏭", () -> HostLink.get().media("next"));
        buttons.addView(prev, weighted(ctx, false));
        buttons.addView(play, weighted(ctx, true));
        buttons.addView(next, weighted(ctx, true));
        col.addView(buttons, block(ctx, 16));

        TextView volLabel = Ui.label(ctx, "🔊  Volume", 13, fg, true);
        col.addView(volLabel, block(ctx, 16));
        SeekBar volume = new SeekBar(ctx);
        volume.setMax(100);
        volume.setProgress(HostLink.get().lastState().optInt("volume", 50));
        tint(volume, dark);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) HostLink.get().set("volume", v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        col.addView(volume);

        HostLink.get().on("media", m -> {
            title.setText(m.optString("title"));
            String a = m.optString("artist");
            String p = m.optString("player");
            artist.setText(a.isEmpty() ? p : (p.isEmpty() ? a : a + "  ·  " + p));
            play.setText("Playing".equals(m.optString("status")) ? "⏸" : "▶");
        });
        show(ctx, dark, col, "media", () -> HostLink.get().ask("media"), 1000);
    }

    // ---- PC stats ----

    static void stats(Context ctx, boolean dark) {
        if (toggleOff("stats")) return;
        int fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;
        int muted = dark ? 0x99EBEBF5 : 0x993C3C43;
        LinearLayout col = column(ctx);
        TextView head = Ui.label(ctx, "📊  PC STATS", 11.5f, muted, true);
        head.setLetterSpacing(0.08f);
        col.addView(head);

        Meter cpu = new Meter(ctx, dark, "CPU", 0xFF0A84FF);
        Meter ram = new Meter(ctx, dark, "Memory", 0xFFBF5AF2);
        Meter temp = new Meter(ctx, dark, "Temperature", 0xFFFF9F0A);
        Meter disk = new Meter(ctx, dark, "Disk", 0xFF64D2FF);
        Meter bat = new Meter(ctx, dark, "Battery", 0xFF30D158);
        for (Meter m : new Meter[]{cpu, ram, temp, disk, bat}) col.addView(m.row, block(ctx, 10));
        TextView net = Ui.label(ctx, "", 14, fg, true);
        col.addView(net, block(ctx, 12));
        TextView up = Ui.label(ctx, "", 12.5f, muted, false);
        col.addView(up, block(ctx, 4));

        HostLink.get().on("stats", s -> {
            cpu.set(s.optInt("cpu", -1), s.optInt("cpu", 0) + "%");
            ram.set(s.optInt("ram", -1), s.optInt("ram", 0) + "%");
            temp.set(s.has("temp") ? Math.min(100, s.optInt("temp")) : -1, s.optInt("temp") + " °C");
            disk.set(s.optInt("disk", -1), s.optInt("disk", 0) + "%  ·  "
                    + s.optInt("disk_free_gb") + " GB free");
            bat.set(s.optInt("battery", -1), (s.optBoolean("charging") ? "⚡ " : "")
                    + s.optInt("battery") + "%");
            net.setText("⬇ " + speed(s.optInt("down_kbs")) + "    ⬆ " + speed(s.optInt("up_kbs")));
            int min = s.optInt("uptime_min");
            up.setText("Up for " + (min / 60) + " h " + (min % 60) + " min");
        });
        show(ctx, dark, col, "stats", () -> HostLink.get().ask("stats"), 1500);
    }

    private static String speed(int kbs) {
        return kbs >= 1024 ? String.format(java.util.Locale.US, "%.1f MB/s", kbs / 1024f) : kbs + " KB/s";
    }

    /** One labelled bar; hidden when the PC doesn't report that value. */
    private static final class Meter {
        final LinearLayout row;
        final TextView value;
        final ProgressBar bar;

        Meter(Context ctx, boolean dark, String name, int color) {
            int fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;
            row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(ctx);
            top.addView(Ui.label(ctx, name, 13.5f, fg, true),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            value = Ui.label(ctx, "…", 13.5f, fg, false);
            value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            top.addView(value);
            row.addView(top);
            bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressTintList(ColorStateList.valueOf(color));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000));
            row.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Ui.dp(ctx, 8)));
        }

        void set(int percent, String text) {
            row.setVisibility(percent < 0 ? View.GONE : View.VISIBLE);
            bar.setProgress(Math.max(0, percent));
            value.setText(text);
        }
    }

    // ---- shared ----

    private static String openType;

    /** Tapping the same button again closes the panel. */
    private static boolean toggleOff(String type) {
        if (open != null) {
            boolean same = type.equals(openType);
            open.dismiss();
            if (same) return true;
        }
        GlassMenu.dismiss();
        ControlCenter.dismiss();
        return false;
    }

    @SuppressLint("RtlHardcoded")
    private static void show(Context ctx, boolean dark, View content, String type, Runnable ask, long everyMs) {
        Dialog d = Glass.window(ctx, content, dark, Ui.dp(ctx, 26), false);
        d.setCanceledOnTouchOutside(true);
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                ask.run();
                poll.postDelayed(this, everyMs);
            }
        };
        d.setOnDismissListener(x -> {
            poll.removeCallbacks(tick);
            HostLink.get().on(type, null);
            if (open == d) {
                open = null;
                openType = null;
            }
        });
        Glass.place(d, Ui.dp(ctx, 340), WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT, Ui.dp(ctx, 8), Ui.dp(ctx, 60));
        d.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);
        d.show();
        open = d;
        openType = type;
        poll.post(tick);
    }

    private static LinearLayout column(Context ctx) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(ctx, 16);
        col.setPadding(p, p, p, p);
        return col;
    }

    private static TextView bigButton(Context ctx, boolean dark, String glyph, Runnable action) {
        TextView t = Ui.label(ctx, glyph, 26, dark ? 0xFFF2F2F7 : 0xFF1D1D1F, false);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dp(ctx, 60));
        t.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x55FFFFFF),
                Glass.chip(ctx, dark, Ui.dp(ctx, 30), false), null));
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            action.run();
        });
        return t;
    }

    private static void tint(SeekBar s, boolean dark) {
        s.setProgressTintList(ColorStateList.valueOf(dark ? 0xFFFFFFFF : 0xFF1D1D1F));
        s.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0x40FFFFFF : 0x33000000));
    }

    private static LinearLayout.LayoutParams weighted(Context ctx, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (gap) lp.leftMargin = Ui.dp(ctx, 10);
        return lp;
    }

    private static LinearLayout.LayoutParams block(Context ctx, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, topDp);
        return lp;
    }
}

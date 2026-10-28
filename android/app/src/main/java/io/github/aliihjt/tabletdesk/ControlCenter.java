package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/** macOS-style Control Center for the laptop, on a Liquid Glass panel. */
final class ControlCenter implements HostLink.StateListener {
    private static ControlCenter open;

    private final Context ctx;
    private final boolean dark;
    private final int fg;
    private final SharedPreferences prefs;
    private final Runnable toggleTouchBar;
    private final Dialog dialog;
    private final TextView darkTile;
    private final TextView dndTile;
    private final SeekBar brightness;
    private final SeekBar volume;
    private final TextView[] modeCells = new TextView[3];
    private boolean darkOn;
    private boolean dndOn;
    /** true while applying values from the laptop, so they are not sent back */
    private boolean applying;

    private static final String[][] MODES = {
            {"extend", "🖥 Extend"}, {"mirror", "🪞 Mirror"}, {"input", "⌨ Input"}};

    static void toggle(Context ctx, boolean dark, Runnable toggleTouchBar, Runnable onDismiss) {
        if (open != null) {
            open.dialog.dismiss();
            return;
        }
        GlassMenu.dismiss();
        open = new ControlCenter(ctx, dark, toggleTouchBar, onDismiss);
    }

    static void dismiss() {
        if (open != null) open.dialog.dismiss();
    }

    @SuppressLint("RtlHardcoded")
    private ControlCenter(Context ctx, boolean dark, Runnable toggleTouchBar, Runnable onDismiss) {
        this.ctx = ctx;
        this.dark = dark;
        this.toggleTouchBar = toggleTouchBar;
        fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;
        prefs = ctx.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 12);
        col.setPadding(pad, pad, pad, pad);

        // screen mode (remembered on the laptop for every plug-in)
        LinearLayout modes = new LinearLayout(ctx);
        for (int i = 0; i < MODES.length; i++) {
            String m = MODES[i][0];
            TextView t = tile(MODES[i][1], 13);
            t.setOnClickListener(v -> {
                Ui.haptic(v);
                prefs.edit().putString("mode", m).apply();
                HostLink.get().mode(m);
                paintModes();
            });
            modes.addView(t, weighted(i > 0));
            modeCells[i] = t;
        }
        col.addView(modes, block(0));
        paintModes();

        // toggles and actions, two per row like iOS / macOS tiles
        LinearLayout r1 = new LinearLayout(ctx);
        darkTile = tile("", 13);
        darkTile.setOnClickListener(v -> {
            Ui.haptic(v);
            darkOn = !darkOn;
            HostLink.get().set("dark", darkOn ? 1 : 0);
            paintToggles();
        });
        dndTile = tile("", 13);
        dndTile.setOnClickListener(v -> {
            Ui.haptic(v);
            dndOn = !dndOn;
            HostLink.get().set("dnd", dndOn ? 1 : 0);
            paintToggles();
        });
        r1.addView(darkTile, weighted(false));
        r1.addView(dndTile, weighted(true));
        col.addView(r1, block(10));

        LinearLayout r2 = new LinearLayout(ctx);
        TextView pad2 = tile("⌨  Trackpad", 13);
        pad2.setOnClickListener(v -> {
            Intent i = new Intent(ctx, TrackpadActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            dialog().dismiss();
        });
        TextView shot = tile("📷  Screenshot", 13);
        shot.setOnClickListener(v -> {
            dialog().dismiss();
            HostLink.get().combo("print");
        });
        r2.addView(pad2, weighted(false));
        r2.addView(shot, weighted(true));
        col.addView(r2, block(10));

        brightness = slider("☀️  Display", col, "brightness");
        volume = slider("🔊  Sound", col, "volume");

        // quick actions
        LinearLayout r3 = new LinearLayout(ctx);
        String[][] quick = {{"🔒", "super+l"}, {"⚙️", "cmd:settings"},
                {"📁", "cmd:files"}, {"💻", "cmd:terminal"}, {"▭", null}};
        for (int i = 0; i < quick.length; i++) {
            String action = quick[i][1];
            TextView t = tile(quick[i][0], 17);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                Ui.haptic(v);
                if (action == null) toggleTouchBar.run();
                else HostLink.get().action(action);
                dialog().dismiss();
            });
            r3.addView(t, weighted(i > 0));
        }
        col.addView(r3, block(10));

        dialog = Glass.window(ctx, col, dark, Ui.dp(ctx, 26), false);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(x -> {
            HostLink.get().setStateListener(null);
            open = null;
            if (onDismiss != null) onDismiss.run();
        });
        Glass.place(dialog, Ui.dp(ctx, 340), WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT, Ui.dp(ctx, 8), Ui.dp(ctx, 32));
        dialog.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);

        paintToggles();
        HostLink.get().setStateListener(this);
        HostLink.get().requestState();
        dialog.show();
    }

    private Dialog dialog() {
        return dialog;
    }

    @Override
    public void onState(JSONObject s) {
        applying = true;
        if (s.has("volume")) volume.setProgress(s.optInt("volume"));
        if (s.has("brightness")) brightness.setProgress(s.optInt("brightness"));
        else brightness.setEnabled(false);  // desktop PC without a backlight
        darkOn = s.optBoolean("dark", darkOn);
        dndOn = s.optBoolean("dnd", dndOn);
        applying = false;
        paintToggles();
    }

    private void paintToggles() {
        darkTile.setText("🌙  Dark Mode\n" + (darkOn ? "On" : "Off"));
        darkTile.setBackground(Glass.chip(ctx, dark, Ui.dp(ctx, 16), darkOn));
        darkTile.setTextColor(darkOn ? 0xFFFFFFFF : fg);
        dndTile.setText("🔕  Focus\n" + (dndOn ? "Do Not Disturb" : "Off"));
        dndTile.setBackground(Glass.chip(ctx, dark, Ui.dp(ctx, 16), dndOn));
        dndTile.setTextColor(dndOn ? 0xFFFFFFFF : fg);
    }

    private void paintModes() {
        String current = prefs.getString("mode", "extend");
        for (int i = 0; i < modeCells.length; i++) {
            boolean on = MODES[i][0].equals(current);
            modeCells[i].setBackground(Glass.chip(ctx, dark, Ui.dp(ctx, 14), on));
            modeCells[i].setTextColor(on ? 0xFFFFFFFF : fg);
            modeCells[i].setGravity(Gravity.CENTER);
        }
    }

    /** Glass card with a label and a slider; sends at most ~8 updates a second. */
    private SeekBar slider(String label, LinearLayout parent, String what) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Glass.chip(ctx, dark, Ui.dp(ctx, 16), false));
        int p = Ui.dp(ctx, 12);
        card.setPadding(p, Ui.dp(ctx, 10), p, Ui.dp(ctx, 6));
        card.addView(Ui.label(ctx, label, 13, fg, true));
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(dark ? 0xFFFFFFFF : 0xFF1D1D1F));
        bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0x40FFFFFF : 0x33000000));
        long[] last = {0};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int value, boolean user) {
                long now = SystemClock.uptimeMillis();
                if (!user || applying || now - last[0] < 120) return;
                last[0] = now;
                HostLink.get().set(what, value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                HostLink.get().set(what, s.getProgress());
            }
        });
        card.addView(bar);
        parent.addView(card, block(10));
        return bar;
    }

    private TextView tile(String text, float sp) {
        TextView t = Ui.label(ctx, text, sp, fg, false);
        t.setSingleLine(false);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        int p = Ui.dp(ctx, 12);
        t.setPadding(p, Ui.dp(ctx, 10), p, Ui.dp(ctx, 10));
        t.setMinHeight(Ui.dp(ctx, 46));
        t.setBackground(Glass.chip(ctx, dark, Ui.dp(ctx, 16), false));
        return t;
    }

    private LinearLayout.LayoutParams weighted(boolean gapBefore) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (gapBefore) lp.leftMargin = Ui.dp(ctx, 8);
        return lp;
    }

    private LinearLayout.LayoutParams block(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, topDp);
        return lp;
    }
}

package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Fedora Pad's look, modelled on iPadOS: grouped grey background, white (or
 * dark) rounded cards, coloured icon squares, blue accent, green switches.
 * Every screen builds its views through these helpers so they all match.
 */
final class Theme {
    // iOS system colours
    static final int BLUE = 0xFF007AFF;
    static final int GREEN = 0xFF34C759;
    static final int ORANGE = 0xFFFF9500;
    static final int RED = 0xFFFF3B30;
    static final int PINK = 0xFFFF2D55;
    static final int PURPLE = 0xFFAF52DE;
    static final int INDIGO = 0xFF5856D6;
    static final int TEAL = 0xFF30B0C7;
    static final int GRAY = 0xFF8E8E93;
    static final int YELLOW = 0xFFFFCC00;

    final Context ctx;
    final boolean dark;
    final int background;   // page background
    final int card;         // cards and grouped lists
    final int text;         // primary text
    final int secondary;    // secondary text
    final int separator;
    final int fill;         // subtle fill for keys and chips
    final int accent;

    Theme(Context ctx) {
        this.ctx = ctx;
        dark = Ui.isDark(ctx);
        background = dark ? 0xFF000000 : 0xFFF2F2F7;
        card = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        text = dark ? 0xFFFFFFFF : 0xFF000000;
        secondary = dark ? 0x99EBEBF5 : 0x993C3C43;
        separator = dark ? 0xFF38383A : 0xFFC6C6C8;
        fill = dark ? 0xFF2C2C2E : 0xFFE5E5EA;
        accent = dark ? 0xFF0A84FF : BLUE;
    }

    int dp(float v) {
        return Ui.dp(ctx, v);
    }

    // ---- text ----

    TextView text(String s, float sp, int color, boolean semibold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp * Ui.scale);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(semibold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView largeTitle(String s) {
        TextView t = text(s, 32, text, false);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    TextView body(String s) {
        return text(s, 16, text, false);
    }

    TextView caption(String s) {
        TextView t = text(s, 13, secondary, false);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    /** Small grey heading above a group, like iOS Settings. */
    TextView sectionHeader(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.ROOT), 12.5f, secondary, false);
        t.setPadding(dp(16), dp(22), dp(16), dp(7));
        return t;
    }

    // ---- surfaces ----

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Pressable background: colour + ripple clipped to the same rounded shape. */
    Drawable pressable(int color, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000),
                round(color, radius), round(0xFFFFFFFF, radius));
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(card, dp(18)));
        if (!dark) c.setElevation(dp(1));
        return c;
    }

    /** Coloured rounded square with a white symbol, as in iPad Settings. */
    TextView iconChip(String symbol, int color, int sizeDp) {
        TextView t = text(symbol, sizeDp * 0.45f, 0xFFFFFFFF, false);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighten(color), color});
        g.setCornerRadius(dp(sizeDp * 0.24f));
        t.setBackground(g);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return t;
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 28);
        int b = Math.min(255, (c & 0xFF) + 28);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---- buttons ----

    /** Big filled button (the main action of a screen). */
    TextView primaryButton(String label, View.OnClickListener l) {
        TextView b = text(label, 17, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(52));
        b.setBackground(pressable(accent, dp(14)));
        click(b, l);
        return b;
    }

    /** Tinted button: light accent background, accent text. */
    TextView secondaryButton(String label, View.OnClickListener l) {
        TextView b = text(label, 16, accent, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(pressable((accent & 0x00FFFFFF) | 0x1F000000, dp(14)));
        click(b, l);
        return b;
    }

    /** Keyboard-style key. */
    TextView key(String label, float sp) {
        TextView k = text(label, sp, text, false);
        k.setGravity(Gravity.CENTER);
        k.setBackground(pressable(dark ? 0xFF3A3A3C : 0xFFFFFFFF, dp(9)));
        k.setElevation(dark ? 0 : dp(1));
        return k;
    }

    void click(View v, View.OnClickListener l) {
        v.setOnClickListener(x -> {
            Ui.haptic(x);
            l.onClick(x);
        });
    }

    // ---- grouped list rows (iOS Settings style) ----

    /** A row with an icon chip, a title (+ optional subtitle) and a trailing view. */
    LinearLayout row(String symbol, int color, String title, String subtitle, View trailing) {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(52));
        r.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (symbol != null) {
            TextView chip = iconChip(symbol, color, 30);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
            lp.rightMargin = dp(14);
            r.addView(chip);
        }
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(body(title));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = caption(subtitle);
            s.setPadding(0, dp(3), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) r.addView(trailing);
        return r;
    }

    /** A row with an icon and title, and a full-width slider under it. */
    LinearLayout sliderRow(String symbol, int color, String title, SeekBar bar) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = row(symbol, color, title, null, null);
        head.setPadding(head.getPaddingLeft(), head.getPaddingTop(), head.getPaddingRight(), 0);
        box.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        lp.setMargins(dp(52), 0, dp(14), dp(10));
        box.addView(bar, lp);
        return box;
    }

    /** Puts rows in one rounded card with thin separators between them. */
    LinearLayout group(View... rows) {
        LinearLayout c = card();
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                View line = new View(ctx);
                line.setBackgroundColor(separator);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
                lp.leftMargin = dp(58);
                c.addView(line, lp);
            }
            c.addView(rows[i]);
        }
        return c;
    }

    Switch toggle(boolean on, java.util.function.Consumer<Boolean> change) {
        Switch s = new Switch(ctx);
        s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        s.setTrackTintList(new ColorStateList(states, new int[]{GREEN, dark ? 0xFF39393D : 0xFFE9E9EA}));
        s.setOnCheckedChangeListener((v, checked) -> {
            Ui.haptic(v);
            change.accept(checked);
        });
        return s;
    }

    TextView chevron() {
        return text("›", 22, secondary, false);
    }

    SeekBar slider(int max, int value, java.util.function.IntConsumer change) {
        SeekBar b = new SeekBar(ctx);
        b.setMax(max);
        b.setProgress(value);
        b.setProgressTintList(ColorStateList.valueOf(accent));
        b.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(fill));
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) change.accept(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                change.accept(s.getProgress());
            }
        });
        return b;
    }

    /** iOS segmented control. */
    LinearLayout segmented(String[][] options, String current, java.util.function.Consumer<String> pick) {
        LinearLayout row = new LinearLayout(ctx);
        int p = dp(2);
        row.setPadding(p, p, p, p);
        row.setBackground(round(fill, dp(10)));
        TextView[] cells = new TextView[options.length];
        String[] selected = {current};
        Runnable paint = () -> {
            for (int i = 0; i < cells.length; i++) {
                boolean on = options[i][0].equals(selected[0]);
                cells[i].setBackground(on ? round(dark ? 0xFF636366 : 0xFFFFFFFF, dp(8)) : null);
                cells[i].setElevation(on && !dark ? dp(1) : 0);
            }
        };
        for (int i = 0; i < options.length; i++) {
            String value = options[i][0];
            TextView c = text(options[i][1], 14, text, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(6), dp(9), dp(6), dp(9));
            c.setOnClickListener(v -> {
                Ui.haptic(v);
                selected[0] = value;
                paint.run();
                pick.accept(value);
            });
            cells[i] = c;
            row.addView(c, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        paint.run();
        return row;
    }

    // ---- layout params ----

    LinearLayout.LayoutParams fullWidth(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    LinearLayout.LayoutParams weighted(int leftGapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(leftGapDp);
        return lp;
    }
}

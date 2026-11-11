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
 *
 * Black & white screen (Settings › Black & white screen, or Android's own
 * grayscale): colours turn into greys that look alike there, so this mode uses
 * contrast instead of colour: plain white (or black) pages, black outlines,
 * black text and icons, filled-black for "on" and the main button.
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
    final int onAccent;     // text on the accent colour
    final boolean mono;     // black & white screen
    final int onWall;       // text straight on the home screen's wallpaper
    final int wallShadow;   // its shadow (none in black & white)
    final int outline;      // border of cards and icons in black & white (0 = none)

    Theme(Context ctx) {
        this.ctx = ctx;
        dark = Ui.isDark(ctx);
        mono = blackAndWhite(ctx);
        if (mono) {
            background = dark ? 0xFF000000 : 0xFFFFFFFF;
            card = dark ? 0xFF000000 : 0xFFFFFFFF;
            text = dark ? 0xFFFFFFFF : 0xFF000000;
            secondary = dark ? 0xFFD0D0D0 : 0xFF303030;  // dark enough to read on a grey screen
            separator = dark ? 0xFF808080 : 0xFF7A7A7A;
            fill = dark ? 0xFF262626 : 0xFFE8E8E8;
            accent = text;
            onAccent = background;
            onWall = text;
            wallShadow = 0;
            outline = dark ? 0xFFBDBDBD : 0xFF000000;
        } else {
            background = dark ? 0xFF000000 : 0xFFF2F2F7;
            card = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
            text = dark ? 0xFFFFFFFF : 0xFF000000;
            secondary = dark ? 0x99EBEBF5 : 0x993C3C43;
            separator = dark ? 0xFF38383A : 0xFFC6C6C8;
            fill = dark ? 0xFF2C2C2E : 0xFFE5E5EA;
            accent = dark ? 0xFF0A84FF : BLUE;
            onAccent = 0xFFFFFFFF;
            onWall = 0xFFFFFFFF;
            wallShadow = 0x99000000;
            outline = 0;
        }
    }

    static final String BW = "bw_screen";  // "auto" (follow Android's grayscale), "on" or "off"

    /** Black & white look: switched on in Settings, or "auto" and Android's grayscale is on. */
    static boolean blackAndWhite(Context c) {
        String mode = c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE).getString(BW, "auto");
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        return systemGrayscale(c);
    }

    /** Android's (and Samsung's) "Greyscale" colour adjustment: the daltonizer set to monochromacy (0). */
    static boolean systemGrayscale(Context c) {
        try {
            android.content.ContentResolver r = c.getContentResolver();
            return android.provider.Settings.Secure.getInt(r, "accessibility_display_daltonizer_enabled", 0) == 1
                    && android.provider.Settings.Secure.getInt(r, "accessibility_display_daltonizer", -1) == 0;
        } catch (RuntimeException e) {  // some Android versions don't let apps read it: use the setting
            return false;
        }
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

    /** A rounded surface; outlined instead of shadowed on a black & white screen. */
    GradientDrawable surface(int color, float radius) {
        GradientDrawable d = round(color, radius);
        if (mono) d.setStroke(Math.max(1, dp(1.25f)), outline);
        return d;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(surface(card, dp(18)));
        if (!dark && !mono) c.setElevation(dp(1));
        return c;
    }

    /** Coloured rounded square with a white symbol, as in iPad Settings. */
    TextView iconChip(String symbol, int color, int sizeDp) {
        if (mono) {  // white square, black outline and symbol: colours would all be the same grey
            TextView t = text(symbol, sizeDp * 0.45f, text, true);
            t.setGravity(Gravity.CENTER);
            GradientDrawable g = round(card, dp(sizeDp * 0.24f));
            g.setStroke(Math.max(1, dp(sizeDp >= 40 ? 1.75f : 1f)), outline);
            t.setBackground(g);
            t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
            return t;
        }
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

    // ---- macOS desktop ----

    /** macOS-style wallpaper (Sonoma-like colours), light or dark. */
    android.graphics.drawable.Drawable wallpaper() {
        if (mono) return new android.graphics.drawable.ColorDrawable(background);  // plain: easy to read
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, dark
                ? new int[]{0xFF0F1B3D, 0xFF3A1F6B, 0xFF8A2E6E, 0xFFD0565C}
                : new int[]{0xFF7FB3F5, 0xFFB9A6F2, 0xFFF1A7C8, 0xFFFBD3A6});
    }

    /** Frosted panel for the menu bar, widgets and the Dock (on the wallpaper). */
    GradientDrawable frosted(float radius) {
        if (mono) return surface(card, radius);
        GradientDrawable d = round(dark ? 0x8C1E1E1E : 0xA6FFFFFF, radius);
        d.setStroke(Math.max(1, dp(0.75f)), dark ? 0x33FFFFFF : 0x80FFFFFF);
        return d;
    }

    /** The three window buttons: close, minimise, full screen. */
    LinearLayout trafficLights(Runnable close, Runnable minimise, Runnable zoom) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int[] colors = {0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840};
        Runnable[] actions = {close, minimise, zoom};
        for (int i = 0; i < 3; i++) {
            View dot = new View(ctx);
            GradientDrawable g = round(colors[i], dp(7));
            g.setStroke(Math.max(1, dp(0.5f)), 0x33000000);
            dot.setBackground(g);
            Runnable a = actions[i];
            // a bigger invisible touch area around each small dot
            android.widget.FrameLayout hit = new android.widget.FrameLayout(ctx);
            hit.addView(dot, new android.widget.FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
            click(hit, v -> a.run());
            row.addView(hit, new LinearLayout.LayoutParams(dp(26), dp(40)));
        }
        return row;
    }

    // ---- buttons ----

    /** Big filled button (the main action of a screen). */
    TextView primaryButton(String label, View.OnClickListener l) {
        TextView b = text(label, 17, onAccent, true);
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
        if (mono) {
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000),
                    surface(card, dp(14)), round(0xFFFFFFFF, dp(14))));
        } else {
            b.setBackground(pressable((accent & 0x00FFFFFF) | 0x1F000000, dp(14)));
        }
        click(b, l);
        return b;
    }

    /** Keyboard-style key. */
    TextView key(String label, float sp) {
        TextView k = text(label, sp, text, false);
        k.setGravity(Gravity.CENTER);
        if (mono) {
            k.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x55FFFFFF : 0x40000000),
                    surface(card, dp(9)), round(0xFFFFFFFF, dp(9))));
        } else {
            k.setBackground(pressable(dark ? 0xFF3A3A3C : 0xFFFFFFFF, dp(9)));
            k.setElevation(dark ? 0 : dp(1));
        }
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
        if (mono) {  // on = dark knob on a dark track, off = pale knob on a pale track (Android dims the track)
            s.setThumbTintList(new ColorStateList(states, new int[]{text, dark ? 0xFF5A5A5A : 0xFFC4C4C4}));
            s.setTrackTintList(new ColorStateList(states, new int[]{text, dark ? 0xFF3A3A3A : 0xFFB0B0B0}));
        } else {
            s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
            s.setTrackTintList(new ColorStateList(states, new int[]{GREEN, dark ? 0xFF39393D : 0xFFE9E9EA}));
        }
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
        b.setThumbTintList(ColorStateList.valueOf(mono ? text : 0xFFFFFFFF));
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
                if (mono) {  // the chosen one inverted: black with white text
                    cells[i].setBackground(on ? round(text, dp(8)) : null);
                    cells[i].setTextColor(on ? background : text);
                    continue;
                }
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

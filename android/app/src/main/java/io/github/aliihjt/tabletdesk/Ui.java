package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

/** Small helpers so the UI can be built in code without XML layouts. */
final class Ui {
    private Ui() {
    }

    /** User-chosen interface size (Settings: Normal / Large / Extra large). */
    static float scale = 1.35f;

    /** Vibrate on key taps (Settings). */
    static boolean haptics = true;

    static void loadScale(Context c) {
        android.content.SharedPreferences p =
                c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
        scale = p.getInt("uiscale", 135) / 100f;
        haptics = p.getBoolean("haptics", true);
    }

    static int dp(Context c, float v) {
        return Math.round(v * scale * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static RippleDrawable pressable(int color, float radiusPx, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                rounded(color, radiusPx), rounded(0xFFFFFFFF, radiusPx));
    }

    static TextView label(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp * scale);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Theme setting: auto (follow the tablet), light or dark. */
    static boolean isDark(Context c) {
        String theme = c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
                .getString("theme", "auto");
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static void haptic(View v) {
        if (haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
}

package io.github.aliihjt.tabletdesk;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * "Liquid Glass" look: a frosted, blurred, translucent surface with a bright rim
 * and a soft top sheen. Real blur needs Android 12+ with cross-window blur on;
 * otherwise the surface just becomes more opaque so text stays readable.
 */
final class Glass {
    private Glass() {
    }

    static boolean blurAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        WindowManager wm = c.getSystemService(WindowManager.class);
        return wm != null && wm.isCrossWindowBlurEnabled();
    }

    /** Glass surface drawable. tint is the base color without alpha. */
    static Drawable surface(Context c, boolean dark, float radiusPx, boolean blur) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(radiusPx);
        int alpha = blur ? (dark ? 0x73 : 0x9E) : (dark ? 0xEB : 0xF2);
        fill.setColor((alpha << 24) | (dark ? 0x1C1C20 : 0xF7F7FA));
        fill.setStroke(Ui.dp(c, 1), dark ? 0x40FFFFFF : 0xB3FFFFFF);

        // soft light from above, like the specular highlight on glass
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{dark ? 0x1FFFFFFF : 0x59FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radiusPx);
        return new LayerDrawable(new Drawable[]{fill, sheen});
    }

    /** A small glass "chip" used for keys and tiles placed on a glass surface. */
    static GradientDrawable chip(Context c, boolean dark, float radiusPx, boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radiusPx);
        if (active) {
            d.setColor(0xFF0A84FF);
            d.setStroke(Ui.dp(c, 1), 0x66FFFFFF);
        } else {
            d.setColor(dark ? 0x26FFFFFF : 0x0F000000);
            d.setStroke(Ui.dp(c, 1), dark ? 0x26FFFFFF : 0x80FFFFFF);
        }
        return d;
    }

    /**
     * A floating glass window above every app (used for menus, Control Center and
     * the Touch Bar). Touches outside it go to the app below.
     */
    static Dialog window(Context c, View content, boolean dark, float radiusPx, boolean focusable) {
        Dialog d = new Dialog(c, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.setContentView(content);
        Window w = d.getWindow();
        boolean blur = blurAvailable(c);
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        // the blur follows this drawable's rounded outline
        w.setBackgroundDrawable(surface(c, dark, radiusPx, blur));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE));
        if (blur && Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(Ui.dp(c, 36));
        w.setElevation(Ui.dp(c, 12));
        return d;
    }

    static void place(Dialog d, int width, int height, int gravity, int x, int y) {
        Window w = d.getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = width;
        lp.height = height;
        lp.gravity = gravity;
        lp.x = x;
        lp.y = y;
        w.setAttributes(lp);
    }
}

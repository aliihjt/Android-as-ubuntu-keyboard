package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** macOS-style drop-down menu on a Liquid Glass surface. */
final class GlassMenu {
    private static Dialog open;

    private GlassMenu() {
    }

    /**
     * items[0] is the title (shown greyed as a header), then "Label|action|shortcut"
     * entries and "-" separators — the same format MenuBar uses.
     */
    @SuppressLint("RtlHardcoded")
    static void show(Context ctx, View anchor, String[] items, boolean dark, Runnable onDismiss) {
        show(ctx, anchor, items, dark, onDismiss, null);
    }

    /** local handles "local:<name>" actions inside the app instead of on the laptop. */
    @SuppressLint("RtlHardcoded")
    static void show(Context ctx, View anchor, String[] items, boolean dark, Runnable onDismiss,
                     java.util.function.Consumer<String> local) {
        dismiss();
        int fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;
        int muted = dark ? 0x99EBEBF5 : 0x993C3C43;

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 6);
        list.setPadding(pad, pad, pad, pad);

        Dialog[] self = new Dialog[1];
        for (int i = 1; i < items.length; i++) {
            if ("-".equals(items[i])) {
                View line = new View(ctx);
                line.setBackgroundColor(dark ? 0x26FFFFFF : 0x1F000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1));
                lp.setMargins(Ui.dp(ctx, 10), Ui.dp(ctx, 5), Ui.dp(ctx, 10), Ui.dp(ctx, 5));
                list.addView(line, lp);
                continue;
            }
            String[] p = items[i].split("\\|");
            list.addView(row(ctx, p[0], p.length > 2 ? p[2] : "", fg, muted, v -> {
                Ui.haptic(v);
                if (p[1].startsWith("local:") && local != null) local.accept(p[1].substring(6));
                else HostLink.get().action(p[1]);
                if (self[0] != null) self[0].dismiss();
            }));
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);

        Dialog d = Glass.window(ctx, scroll, dark, Ui.dp(ctx, 16), false);
        self[0] = d;
        d.setCanceledOnTouchOutside(true);
        d.setOnDismissListener(x -> {
            if (open == d) open = null;
            if (onDismiss != null) onDismiss.run();
        });

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int width = Ui.dp(ctx, 290);
        int x = Math.max(Ui.dp(ctx, 6), Math.min(loc[0], screenW - width - Ui.dp(ctx, 6)));
        Glass.place(d, width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT,
                x, loc[1] + anchor.getHeight() + Ui.dp(ctx, 4));
        d.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);
        d.show();
        open = d;
    }

    static void dismiss() {
        if (open != null) open.dismiss();
        open = null;
    }

    private static View row(Context ctx, String label, String shortcut, int fg, int muted,
                            View.OnClickListener l) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), 0);
        row.setMinimumHeight(Ui.dp(ctx, 36));

        // blue capsule while pressed, exactly like a selected macOS menu item
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, Ui.rounded(0xFF0A84FF, Ui.dp(ctx, 9)));
        bg.addState(new int[]{}, new ColorDrawable(0));
        row.setBackground(bg);

        TextView name = Ui.label(ctx, label, 14.5f, fg, false);
        TextView keys = Ui.label(ctx, shortcut, 13, muted, false);
        keys.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(keys);
        row.setOnTouchListener((v, ev) -> {  // white text on the blue highlight
            boolean down = ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN;
            boolean end = ev.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || ev.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL;
            if (down) {
                name.setTextColor(0xFFFFFFFF);
                keys.setTextColor(0xCCFFFFFF);
            } else if (end) {
                name.setTextColor(fg);
                keys.setTextColor(muted);
            }
            return false;
        });
        row.setOnClickListener(l);
        return row;
    }
}

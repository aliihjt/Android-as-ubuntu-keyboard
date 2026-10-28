package io.github.aliihjt.tabletdesk;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Every open Fedora window as a card: tap to jump to it, ✕ to close it. */
public class WindowsActivity extends Page {
    private static final int[] COLORS = {Theme.BLUE, Theme.PURPLE, Theme.PINK, Theme.ORANGE,
            Theme.GREEN, Theme.TEAL, Theme.INDIGO, Theme.RED};
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            link.ask("windows");
            poll.postDelayed(this, 2000);
        }
    };
    private LinearLayout grid;
    private String last = "";

    @Override
    protected String title() {
        return "Windows";
    }

    @Override
    protected void build() {
        LinearLayout quick = new LinearLayout(this);
        quick.addView(th.secondaryButton("🖥  Mission Control", v -> link.combo("super")), th.weighted(0));
        quick.addView(th.secondaryButton("⇄  Previous app", v -> link.combo("alt+tab")), th.weighted(10));
        add(quick, 6);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 18);
        TextView wait = th.caption(link.isConnected() ? "Loading windows…" : "Connect the tablet to see Fedora's windows.");
        grid.addView(wait);
    }

    private void show(JSONObject msg) {
        JSONArray list = msg.optJSONArray("list");
        String key = String.valueOf(list);
        if (key.equals(last)) return;  // nothing changed: don't redraw
        last = key;
        grid.removeAllViews();
        if (msg.has("error")) {
            grid.addView(th.caption(msg.optString("error")));
            return;
        }
        if (list == null || list.length() == 0) {
            grid.addView(th.caption("No open windows."));
            return;
        }
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 3 : 2;
        LinearLayout row = null;
        for (int i = 0; i < list.length(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 12));
            }
            row.addView(card(list.optJSONObject(i)), th.weighted(i % columns == 0 ? 0 : 12));
        }
        int rest = list.length() % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new android.view.View(this), th.weighted(12));
    }

    /** A window card: app icon, app name, title, and a close button. */
    private LinearLayout card(JSONObject w) {
        String id = w.optString("id"), app = w.optString("app"), title = w.optString("title");
        LinearLayout c = th.card();
        int p = th.dp(14);
        c.setPadding(p, p, p, p);
        if (w.optBoolean("focused")) {
            android.graphics.drawable.GradientDrawable bg = Theme.round(th.card, th.dp(18));
            bg.setStroke(th.dp(2), th.accent);
            c.setBackground(bg);
        }
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String letter = app.isEmpty() ? "?" : app.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        top.addView(th.iconChip(letter, COLORS[Math.abs(app.hashCode()) % COLORS.length], 40));
        TextView name = th.text(app + (w.optBoolean("minimized") ? "  · hidden" : ""), 15, th.text, true);
        name.setSingleLine(true);
        name.setPadding(th.dp(10), 0, th.dp(6), 0);
        top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = th.text("✕", 16, th.secondary, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(th.pressable(th.fill, th.dp(14)));
        th.click(close, v -> {
            link.window(id, true);
            last = "";
        });
        top.addView(close, new LinearLayout.LayoutParams(th.dp(30), th.dp(30)));
        c.addView(top);
        TextView t = th.caption(title);
        t.setMaxLines(2);
        c.addView(t, th.fullWidth(10));
        c.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(th.dark ? 0x33FFFFFF : 0x1F000000),
                null, Theme.round(0xFFFFFFFF, th.dp(18))));
        th.click(c, v -> {
            link.window(id, false);
            last = "";
        });
        return c;
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("windows", this::show);
        poll.post(tick);
    }

    @Override
    protected void onPause() {
        poll.removeCallbacks(tick);
        link.on("windows", null);
        super.onPause();
    }
}

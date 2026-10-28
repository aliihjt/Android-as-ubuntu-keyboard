package io.github.aliihjt.tabletdesk;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Opens Fedora apps: the ones pinned in Fedora's dash, plus search and AI. */
public class AppsActivity extends Page {
    private static final int[] COLORS = {Theme.BLUE, Theme.PURPLE, Theme.PINK, Theme.ORANGE,
            Theme.GREEN, Theme.TEAL, Theme.INDIGO, Theme.RED};
    private LinearLayout favourites;

    @Override
    protected String title() {
        return "Apps";
    }

    @Override
    protected void build() {
        LinearLayout quick = new LinearLayout(this);
        quick.addView(th.secondaryButton("🔍  Search", v -> link.combo("super")), th.weighted(0));
        quick.addView(th.secondaryButton("▦  All apps", v -> link.combo("super+a")), th.weighted(10));
        quick.addView(th.secondaryButton("✨  AI", v -> link.command("ai")), th.weighted(10));
        add(quick, 6);
        section("Pinned in Fedora's dash");
        favourites = new LinearLayout(this);
        favourites.setOrientation(LinearLayout.VERTICAL);
        add(favourites);
        fill(link.apps());
    }

    private void fill(List<String[]> apps) {
        favourites.removeAllViews();
        if (apps.isEmpty()) {
            TextView none = th.caption(link.isConnected()
                    ? "No pinned apps yet. On Fedora, right-click an app in Activities › Pin to Dash."
                    : "Connect the tablet to see your Fedora apps.");
            none.setPadding(th.dp(16), 0, th.dp(16), 0);
            favourites.addView(none);
            return;
        }
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 5 : 3;
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                favourites.addView(row, th.fullWidth(i == 0 ? 0 : 10));
            }
            String id = apps.get(i)[0], name = apps.get(i)[1];
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, th.dp(10), 0, th.dp(10));
            String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            cell.addView(th.iconChip(letter, COLORS[Math.abs(name.hashCode()) % COLORS.length], 64));
            TextView label = th.text(name, 13, th.text, false);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            cell.addView(label, th.fullWidth(8));
            cell.setBackground(th.pressable(0x00000000, th.dp(16)));
            th.click(cell, v -> link.action("launch:" + id));
            row.addView(cell, th.weighted(i % columns == 0 ? 0 : 8));
        }
        int rest = apps.size() % columns;
        if (rest != 0 && row != null) {
            for (int i = rest; i < columns; i++) row.addView(new android.view.View(this), th.weighted(8));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("apps", m -> favourites.postDelayed(() -> fill(link.apps()), 50));
        link.requestApps();
    }

    @Override
    protected void onPause() {
        link.on("apps", null);
        super.onPause();
    }
}

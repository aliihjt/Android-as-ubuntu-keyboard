package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;
import java.util.Locale;

/** All the tablet's apps with a search box. Long-press an app to pin it to the home screen. */
public class TabletAppsActivity extends Page {
    private List<TabletApps.App> apps;
    private LinearLayout grid;

    @Override
    protected String title() {
        return "Tablet Apps";
    }

    @Override
    protected void build() {
        apps = TabletApps.all(this);
        EditText search = new EditText(this);
        search.setHint("🔍  Search apps");
        search.setHintTextColor(th.secondary);
        search.setTextColor(th.text);
        search.setSingleLine(true);
        search.setBackground(Theme.round(th.fill, th.dp(12)));
        search.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                show(s.toString());
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        });
        add(search, 4);
        add(th.caption("Long-press an app to pin it to the home screen."), 6);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 16);
        show("");
    }

    private void show(String query) {
        grid.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 6 : 4;
        LinearLayout row = null;
        int n = 0;
        for (TabletApps.App a : apps) {
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (n % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(n == 0 ? 0 : 18));
            }
            row.addView(TabletApps.icon(this, th, a, 56, th.text, () -> options(a)), th.weighted(0));
            n++;
        }
        if (n == 0) grid.addView(th.caption("No apps match."));
        for (int i = n % columns; row != null && i != 0 && i < columns; i++) row.addView(new View(this), th.weighted(0));
    }

    private void options(TabletApps.App a) {
        boolean pinned = TabletApps.pinned(this).contains(a.key());
        new AlertDialog.Builder(this).setTitle(a.label)
                .setItems(new String[]{pinned ? "Unpin from home screen" : "📌 Pin to home screen", "App info"},
                        (d, which) -> {
                            if (which == 0) TabletApps.setPinned(this, a, !pinned);
                            else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + a.pkg)));
                        }).show();
    }
}

package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Your own one-tap buttons: a key combination or a text to type. Long-press to delete. */
public class ButtonsActivity extends Page {
    private static final int[] COLORS = {Theme.BLUE, Theme.PURPLE, Theme.PINK, Theme.ORANGE,
            Theme.GREEN, Theme.TEAL, Theme.INDIGO, Theme.RED};
    private SharedPreferences prefs;
    private LinearLayout grid;

    @Override
    protected String title() {
        return "My Buttons";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        add(th.primaryButton("＋  New button", v -> edit()), 6);
        add(th.caption("Tap a button to run it. Long-press to delete it."), 8);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 14);
        refresh();
    }

    private JSONArray buttons() {
        try {
            return new JSONArray(prefs.getString("my_buttons", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void refresh() {
        grid.removeAllViews();
        JSONArray all = buttons();
        if (all.length() == 0) {
            TextView none = th.caption("No buttons yet. Examples: “New terminal tab” = ctrl+shift+t, "
                    + "“My email” = text:me@example.com");
            grid.addView(none);
            return;
        }
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 4 : 2;
        LinearLayout row = null;
        for (int i = 0; i < all.length(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 10));
            }
            JSONObject b = all.optJSONObject(i);
            String label = b.optString("label"), action = b.optString("action");
            int index = i;
            LinearLayout cell = th.card();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = th.dp(14);
            cell.setPadding(p, p, p, p);
            String first = label.isEmpty() ? "⭐" : label.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            cell.addView(th.iconChip(first, COLORS[i % COLORS.length], 44));
            TextView name = th.text(label, 14, th.text, true);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            cell.addView(name, th.fullWidth(10));
            TextView what = th.caption(action.startsWith("text:") ? "types text" : action);
            what.setGravity(Gravity.CENTER);
            what.setSingleLine(true);
            cell.addView(what, th.fullWidth(2));
            th.click(cell, v -> link.action(action));
            cell.setOnLongClickListener(v -> {
                confirmDelete(index, label);
                return true;
            });
            row.addView(cell, th.weighted(i % columns == 0 ? 0 : 10));
        }
        int rest = all.length() % columns;
        if (rest != 0 && row != null) {
            for (int i = rest; i < columns; i++) row.addView(new android.view.View(this), th.weighted(10));
        }
    }

    private void edit() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = th.dp(20);
        form.setPadding(p, th.dp(8), p, 0);
        EditText label = new EditText(this);
        label.setHint("Name, e.g. New terminal tab");
        EditText keys = new EditText(this);
        keys.setHint("Keys, e.g. ctrl+shift+t  — or text:hello");
        keys.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        form.addView(label);
        form.addView(keys);
        new AlertDialog.Builder(this)
                .setTitle("New button")
                .setView(form)
                .setPositiveButton("Add", (d, w) -> {
                    String l = label.getText().toString().trim();
                    String k = keys.getText().toString().trim();
                    if (k.isEmpty()) return;
                    if (l.isEmpty()) l = k;
                    // anything that isn't a key combination is typed as text
                    if (!k.startsWith("text:") && !k.startsWith("cmd:") && k.contains(" ")) k = "text:" + k;
                    JSONArray all = buttons();
                    try {
                        all.put(new JSONObject().put("label", l).put("action", k.toLowerCase(java.util.Locale.ROOT).startsWith("text:") ? k : k.toLowerCase(java.util.Locale.ROOT)));
                    } catch (JSONException ignored) {
                    }
                    prefs.edit().putString("my_buttons", all.toString()).apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(int index, String label) {
        new AlertDialog.Builder(this)
                .setTitle("Delete “" + label + "”?")
                .setPositiveButton("Delete", (d, w) -> {
                    JSONArray all = buttons();
                    all.remove(index);
                    prefs.edit().putString("my_buttons", all.toString()).apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}

package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sticky notes: write on the tablet, and they show on the Fedora desktop too.
 * Stored as [{id, text, color}] on the tablet and in ~/.config/tabletdesk/notes.json.
 */
public class NotesActivity extends Page {
    private static final String[] NAMES = {"yellow", "pink", "blue", "green", "purple", "orange"};
    private static final int[] PAPER = {0xFFFFE58A, 0xFFFFB3C7, 0xFFA8D4FF, 0xFFB5EBA8, 0xFFD9B8F5, 0xFFFFCB8A};
    private SharedPreferences prefs;
    private LinearLayout grid;

    @Override
    protected String title() {
        return "Notes";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        LinearLayout top = new LinearLayout(this);
        top.addView(th.primaryButton("＋  New note", v -> edit(-1)), th.weighted(0));
        top.addView(th.secondaryButton("🖥  Show on Fedora", v -> link.command("notes")), th.weighted(10));
        add(top, 6);
        add(th.caption("Tap a note to edit it. Your notes are also saved on Fedora in Documents › Fedora Pad Notes.md."), 8);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 16);
        refresh();
    }

    // ---- storage: the tablet keeps a copy, Fedora gets every change ----

    private JSONArray notes() {
        try {
            return new JSONArray(prefs.getString("notes", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void save(JSONArray all) {
        prefs.edit().putString("notes", all.toString()).apply();
        try {
            link.send(new JSONObject().put("t", "setnotes").put("list", all));
        } catch (JSONException ignored) {
        }
        refresh();
    }

    /** Fedora's copy wins, unless it has none yet and the tablet does. */
    private void fromFedora(JSONObject msg) {
        JSONArray list = msg.optJSONArray("list");
        if (list == null) return;
        if (list.length() == 0 && notes().length() > 0) {
            save(notes());
            return;
        }
        prefs.edit().putString("notes", list.toString()).apply();
        refresh();
    }

    // ---- grid ----

    private void refresh() {
        grid.removeAllViews();
        JSONArray all = notes();
        if (all.length() == 0) {
            grid.addView(th.caption("No notes yet. Tap “New note” to write one."));
            return;
        }
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 3 : 2;
        LinearLayout row = null;
        for (int i = 0; i < all.length(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 12));
            }
            row.addView(note(all.optJSONObject(i), i), th.weighted(i % columns == 0 ? 0 : 12));
        }
        int rest = all.length() % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new View(this), th.weighted(12));
    }

    private View note(JSONObject n, int index) {
        TextView t = th.text(n.optString("text"), 16, 0xFF1D1D1F, false);
        t.setMaxLines(8);
        t.setMinHeight(th.dp(110));
        t.setGravity(Gravity.TOP | Gravity.START);
        int p = th.dp(14);
        t.setPadding(p, p, p, p);
        t.setBackground(th.pressable(paper(n.optString("color")), th.dp(14)));
        t.setElevation(th.dp(3));
        th.click(t, v -> edit(index));
        return t;
    }

    private static int paper(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return PAPER[i];
        return PAPER[0];
    }

    // ---- editor ----

    private void edit(int index) {
        JSONObject existing = index >= 0 ? notes().optJSONObject(index) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = th.dp(20);
        form.setPadding(p, th.dp(8), p, 0);

        EditText text = new EditText(this);
        text.setHint("Write anything, in any language");
        text.setText(existing == null ? "" : existing.optString("text"));
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setMinLines(5);
        text.setGravity(Gravity.TOP | Gravity.START);

        String[] colour = {existing == null ? NAMES[notes().length() % NAMES.length]
                : existing.optString("color", NAMES[0])};
        LinearLayout palette = new LinearLayout(this);
        View[] swatches = new View[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            String c = NAMES[i];
            View s = new View(this);
            swatches[i] = s;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(34), th.dp(34));
            lp.rightMargin = th.dp(10);
            palette.addView(s, lp);
            th.click(s, v -> {
                colour[0] = c;
                paint(swatches, c);
            });
        }
        paint(swatches, colour[0]);
        form.addView(palette, th.fullWidth(4));
        form.addView(text, th.fullWidth(10));

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "New note" : "Edit note")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String body = text.getText().toString().trim();
                    if (body.isEmpty()) return;
                    try {
                        JSONObject n = new JSONObject()
                                .put("id", existing == null ? Long.toString(System.currentTimeMillis(), 36)
                                        : existing.optString("id"))
                                .put("text", body)
                                .put("color", colour[0]);
                        JSONArray all = notes();
                        if (index >= 0) all.put(index, n);
                        else all.put(n);
                        save(all);
                    } catch (JSONException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) {
            dialog.setNeutralButton("Delete", (d, w) -> {
                JSONArray all = notes();
                all.remove(index);
                save(all);
            });
        }
        dialog.show();
        text.requestFocus();
    }

    private void paint(View[] swatches, String selected) {
        for (int i = 0; i < swatches.length; i++) {
            android.graphics.drawable.GradientDrawable g = Theme.round(PAPER[i], th.dp(17));
            if (NAMES[i].equals(selected)) g.setStroke(th.dp(3), th.text);
            swatches[i].setBackground(g);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("notes", this::fromFedora);
        link.ask("notes");
    }

    @Override
    protected void onPause() {
        link.on("notes", null);
        super.onPause();
    }
}

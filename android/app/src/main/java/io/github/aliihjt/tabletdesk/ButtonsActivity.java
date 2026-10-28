package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Stream Deck: big buttons you make yourself. Each runs one or more steps in
 * order — open an app, open a website, press keys, type text, wait.
 * Stored as {label, emoji, color, action} where action is "step ; step ; ...".
 */
public class ButtonsActivity extends Page {
    private static final int[] COLORS = {Theme.BLUE, Theme.PURPLE, Theme.PINK, Theme.ORANGE,
            Theme.GREEN, Theme.TEAL, Theme.INDIGO, Theme.RED};
    private static final Handler runner = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout grid;

    @Override
    protected String title() {
        return "Stream Deck";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        LinearLayout top = new LinearLayout(this);
        top.addView(th.primaryButton("＋  New button", v -> edit(-1)), th.weighted(0));
        top.addView(th.secondaryButton("Add examples", v -> addExamples()), th.weighted(10));
        add(top, 6);
        add(th.caption("Tap a button to run it. Long-press to edit or delete."), 8);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 16);
        refresh();
    }

    // ---- storage ----

    private JSONArray buttons() {
        try {
            return new JSONArray(prefs.getString("my_buttons", "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void save(JSONArray all) {
        prefs.edit().putString("my_buttons", all.toString()).apply();
        refresh();
    }

    // ---- running ----

    /** Runs "step ; step ; wait:500 ; step" in order. */
    static void run(String action) {
        List<String> steps = new ArrayList<>();
        for (String s : action.split(";")) if (!s.trim().isEmpty()) steps.add(s.trim());
        long at = 0;
        for (String step : steps) {
            if (step.startsWith("wait:")) {
                try {
                    at += Math.min(10_000, Long.parseLong(step.substring(5).trim()));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            runner.postDelayed(() -> HostLink.get().action(step), at);
            at += 250;  // a short pause so apps have time to react
        }
    }

    // ---- grid ----

    private void refresh() {
        grid.removeAllViews();
        JSONArray all = buttons();
        if (all.length() == 0) {
            grid.addView(th.caption("No buttons yet. Tap “New button”, or “Add examples” to start."));
            return;
        }
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 4 : 3;
        LinearLayout row = null;
        for (int i = 0; i < all.length(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 12));
            }
            row.addView(deckButton(all.optJSONObject(i), i), th.weighted(i % columns == 0 ? 0 : 12));
        }
        int rest = all.length() % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new View(this), th.weighted(12));
    }

    private View deckButton(JSONObject b, int index) {
        String label = b.optString("label"), action = b.optString("action");
        String emoji = b.optString("emoji", "");
        int color = b.optInt("color", COLORS[index % COLORS.length]);
        LinearLayout cell = th.card();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = th.dp(14);
        cell.setPadding(p, th.dp(18), p, th.dp(14));
        String icon = emoji.isEmpty() ? (label.isEmpty() ? "⭐" : label.substring(0, 1).toUpperCase(java.util.Locale.ROOT)) : emoji;
        TextView chip = th.iconChip(icon, color, 72);
        cell.addView(chip);
        TextView name = th.text(label, 15, th.text, true);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        cell.addView(name, th.fullWidth(12));
        int steps = action.split(";").length;
        TextView what = th.caption(steps > 1 ? steps + " steps" : describe(action));
        what.setGravity(Gravity.CENTER);
        what.setSingleLine(true);
        cell.addView(what, th.fullWidth(3));
        th.click(cell, v -> {
            chip.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90)
                    .withEndAction(() -> chip.animate().scaleX(1f).scaleY(1f).setDuration(120)).start();
            run(action);
        });
        cell.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle(label)
                    .setItems(new String[]{"Edit", "Delete"}, (d, which) -> {
                        if (which == 0) edit(index);
                        else {
                            JSONArray all = buttons();
                            all.remove(index);
                            save(all);
                        }
                    }).show();
            return true;
        });
        return cell;
    }

    private static String describe(String step) {
        if (step.startsWith("app:")) return "opens an app";
        if (step.startsWith("url:")) return "opens a website";
        if (step.startsWith("text:")) return "types text";
        if (step.startsWith("cmd:")) return step.substring(4);
        return step;
    }

    // ---- editor ----

    private void edit(int index) {
        JSONObject existing = index >= 0 ? buttons().optJSONObject(index) : null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = th.dp(20);
        form.setPadding(p, th.dp(8), p, 0);

        EditText label = field("Name, e.g. Start work", existing == null ? "" : existing.optString("label"));
        EditText emoji = field("Emoji (optional), e.g. 💼", existing == null ? "" : existing.optString("emoji"));
        EditText steps = field("", existing == null ? ""
                : existing.optString("action").replace(" ; ", "\n").replace(";", "\n"));
        steps.setHint("One step per line:\napp:…   url:https://…   ctrl+t   text:hello   wait:500");
        steps.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        steps.setMinLines(4);
        steps.setGravity(Gravity.TOP | Gravity.START);

        int[] color = {existing == null ? COLORS[buttons().length() % COLORS.length]
                : existing.optInt("color", Theme.BLUE)};
        LinearLayout palette = new LinearLayout(this);
        View[] swatches = new View[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) {
            int c = COLORS[i];
            View s = new View(this);
            swatches[i] = s;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(30), th.dp(30));
            lp.rightMargin = th.dp(8);
            palette.addView(s, lp);
            th.click(s, v -> {
                color[0] = c;
                paintSwatches(swatches, color[0]);
            });
        }
        paintSwatches(swatches, color[0]);

        // quick helpers that add a step line
        LinearLayout helpers = new LinearLayout(this);
        helpers.addView(helper("+ App", () -> pickApp(steps)));
        helpers.addView(helper("+ Website", () -> append(steps, "url:https://")));
        helpers.addView(helper("+ Keys", () -> append(steps, "ctrl+")));
        helpers.addView(helper("+ Text", () -> append(steps, "text:")));
        helpers.addView(helper("+ Wait", () -> append(steps, "wait:500")));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(helpers);

        form.addView(label);
        form.addView(emoji);
        form.addView(palette, th.fullWidth(10));
        form.addView(hs, th.fullWidth(12));
        form.addView(steps, th.fullWidth(6));

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "New button" : "Edit button")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    List<String> parts = new ArrayList<>();
                    for (String line : steps.getText().toString().split("\n")) {
                        String s = line.trim();
                        if (s.isEmpty()) continue;
                        boolean literal = s.startsWith("text:") || s.startsWith("url:") || s.startsWith("app:");
                        parts.add(literal ? s : s.toLowerCase(java.util.Locale.ROOT));
                    }
                    if (parts.isEmpty()) return;
                    String name = label.getText().toString().trim();
                    try {
                        JSONObject b = new JSONObject()
                                .put("label", name.isEmpty() ? describe(parts.get(0)) : name)
                                .put("emoji", emoji.getText().toString().trim())
                                .put("color", color[0])
                                .put("action", String.join(" ; ", parts));
                        JSONArray all = buttons();
                        if (index >= 0) all.put(index, b);
                        else all.put(b);
                        save(all);
                    } catch (JSONException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        return e;
    }

    private void paintSwatches(View[] swatches, int selected) {
        for (int i = 0; i < swatches.length; i++) {
            android.graphics.drawable.GradientDrawable g = Theme.round(COLORS[i], th.dp(15));
            if (COLORS[i] == selected) g.setStroke(th.dp(3), th.text);
            swatches[i].setBackground(g);
        }
    }

    private TextView helper(String label, Runnable r) {
        TextView t = th.secondaryButton(label, v -> r.run());
        t.setMinHeight(th.dp(38));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, th.dp(38));
        lp.rightMargin = th.dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private static void append(EditText steps, String line) {
        String cur = steps.getText().toString();
        steps.setText(cur.isEmpty() || cur.endsWith("\n") ? cur + line : cur + "\n" + line);
        steps.setSelection(steps.getText().length());
        steps.requestFocus();
    }

    /** Lists Fedora's installed apps to pick one. */
    private void pickApp(EditText steps) {
        link.on("allapps", m -> {
            link.on("allapps", null);
            JSONArray list = m.optJSONArray("list");
            if (list == null || list.length() == 0) return;
            String[] names = new String[list.length()];
            for (int i = 0; i < names.length; i++) names[i] = list.optJSONObject(i).optString("name");
            new AlertDialog.Builder(this).setTitle("Open which app?")
                    .setItems(names, (d, which) -> append(steps, "app:" + list.optJSONObject(which).optString("id")))
                    .show();
        });
        link.ask("allapps");
    }

    private void addExamples() {
        JSONArray all = buttons();
        try {
            all.put(new JSONObject().put("label", "Start work").put("emoji", "💼").put("color", Theme.BLUE)
                    .put("action", "app:org.mozilla.firefox.desktop ; wait:800 ; cmd:files ; cmd:terminal"));
            all.put(new JSONObject().put("label", "YouTube").put("emoji", "▶️").put("color", Theme.RED)
                    .put("action", "url:https://www.youtube.com"));
            all.put(new JSONObject().put("label", "Screenshot").put("emoji", "📷").put("color", Theme.ORANGE)
                    .put("action", "print"));
            all.put(new JSONObject().put("label", "Lock & go").put("emoji", "\uD83D\uDD12").put("color", Theme.PURPLE)
                    .put("action", "playpause ; wait:300 ; super+l"));
        } catch (JSONException ignored) {
        }
        save(all);
    }
}

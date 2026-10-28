package io.github.aliihjt.tabletdesk;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Terminal Pad: the commands saved on Fedora as big buttons. Tap one and its
 * output shows here. The tablet can only run commands by name; the commands
 * themselves live in ~/.config/tabletdesk/commands.json on Fedora.
 */
public class TerminalActivity extends Page {
    private static final int[] COLORS = {Theme.BLUE, Theme.PURPLE, Theme.ORANGE, Theme.GREEN,
            Theme.TEAL, Theme.INDIGO, Theme.PINK, Theme.RED};
    private static final int MAX_CHARS = 60_000;
    private LinearLayout grid;
    private TextView output, state;
    private ScrollView outScroll;
    private boolean running;

    @Override
    protected String title() {
        return "Terminal Pad";
    }

    @Override
    protected void build() {
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 6);
        grid.addView(th.caption(link.isConnected() ? "Loading commands…" : "Connect the tablet to see your commands."));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        state = th.text("Output", 15, th.text, true);
        bar.addView(state, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(small("■ Stop", v -> link.stopCommand()));
        bar.addView(small("Clear", v -> output.setText("")));
        add(bar, 20);

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(13 * Ui.scale);
        output.setTextColor(0xFFE5E5EA);
        output.setTextIsSelectable(true);
        output.setPadding(th.dp(14), th.dp(12), th.dp(14), th.dp(12));
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.addView(output);
        outScroll = new ScrollView(this);
        outScroll.addView(wide);
        outScroll.setBackground(Theme.round(0xFF1C1C1E, th.dp(14)));
        outScroll.setClipToOutline(true);
        add(outScroll, 8);
        outScroll.getLayoutParams().height = th.dp(320);

        add(th.secondaryButton("✏️  Edit commands on Fedora", v -> link.command("editcommands")), 16);
        add(th.caption("Opens the list on Fedora. Each command has a name, an emoji and the shell command. "
                + "Come back here after saving."), 6);
    }

    private TextView small(String label, View.OnClickListener l) {
        TextView b = th.secondaryButton(label, l);
        b.setTextSize(13 * Ui.scale);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, th.dp(36));
        lp.leftMargin = th.dp(8);
        b.setLayoutParams(lp);
        b.setPadding(th.dp(14), 0, th.dp(14), 0);
        return b;
    }

    private void showList(JSONObject msg) {
        JSONArray list = msg.optJSONArray("list");
        grid.removeAllViews();
        if (list == null || list.length() == 0) {
            grid.addView(th.caption("No commands saved yet. Tap “Edit commands on Fedora”."));
            return;
        }
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 4 : 3;
        LinearLayout row = null;
        for (int i = 0; i < list.length(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 12));
            }
            JSONObject c = list.optJSONObject(i);
            row.addView(tile(c == null ? new JSONObject() : c, i), th.weighted(i % columns == 0 ? 0 : 12));
        }
        int rest = list.length() % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new View(this), th.weighted(12));
    }

    private View tile(JSONObject c, int i) {
        String name = c.optString("name"), icon = c.optString("icon", "▶");
        LinearLayout cell = th.card();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(th.dp(10), th.dp(16), th.dp(10), th.dp(14));
        cell.addView(th.iconChip(icon.isEmpty() ? "▶" : icon, COLORS[i % COLORS.length], 56));
        TextView label = th.text(name, 14, th.text, true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        cell.addView(label, th.fullWidth(10));
        th.click(cell, v -> run(name));
        return cell;
    }

    private void run(String name) {
        if (running) {
            append("(Wait for the running command to finish, or tap Stop.)");
            return;
        }
        if (!link.isConnected()) {
            append("Not connected.");
            return;
        }
        running = true;
        state.setText("⏳  " + name);
        if (output.length() > 0) append("");
        append("$ " + name);
        link.runCommand(name);
    }

    private void append(String line) {
        CharSequence cur = output.getText();
        String next = cur.length() == 0 ? line : cur + "\n" + line;
        if (next.length() > MAX_CHARS) next = next.substring(next.length() - MAX_CHARS);
        output.setText(next);
        outScroll.post(() -> outScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void done(JSONObject msg) {
        running = false;
        int code = msg.optInt("code", -1);
        state.setText(code == 0 ? "✅  Done" : "⚠️  Finished with code " + code);
        state.setTextColor(code == 0 ? Theme.GREEN : Theme.ORANGE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("termlist", this::showList);
        link.on("termout", m -> append(m.optString("line")));
        link.on("termdone", this::done);
        link.ask("termlist");
    }

    @Override
    protected void onPause() {
        link.on("termlist", null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        link.on("termout", null);
        link.on("termdone", null);
        super.onDestroy();
    }
}

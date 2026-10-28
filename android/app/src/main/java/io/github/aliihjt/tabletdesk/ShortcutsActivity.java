package io.github.aliihjt.tabletdesk;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One-tap actions, grouped like iPad Settings, plus ones for the app in front on Fedora. */
public class ShortcutsActivity extends Page {
    private static final String[][] EDIT = {
            {"📋", "Copy", "ctrl+c"}, {"📥", "Paste", "ctrl+v"}, {"✂️", "Cut", "ctrl+x"},
            {"↩️", "Undo", "ctrl+z"}, {"↪️", "Redo", "ctrl+shift+z"}, {"☑️", "Select all", "ctrl+a"},
            {"💾", "Save", "ctrl+s"}, {"🔍", "Find", "ctrl+f"}};
    private static final String[][] WINDOWS = {
            {"⇄", "Switch app", "alt+tab"}, {"▦", "Overview", "super"}, {"🚀", "All apps", "super+a"},
            {"⬆️", "Maximize", "super+up"}, {"⬅️", "Left half", "super+left"},
            {"➡️", "Right half", "super+right"}, {"➖", "Hide", "super+h"}, {"✕", "Close", "ctrl+w"}};
    private static final String[][] SYSTEM = {
            {"📷", "Screenshot", "print"}, {"🖼", "Whole screen", "shift+print"},
            {"🪟", "Window shot", "alt+print"}, {"🎥", "Record", "ctrl+shift+alt+r"},
            {"😀", "Emoji", "ctrl+dot"}, {"🔒", "Lock", "super+l"},
            {"📁", "Files", "cmd:files"}, {"💻", "Terminal", "cmd:terminal"}};
    private static final String[][] BROWSER = {
            {"◀️", "Back", "alt+left"}, {"▶️", "Forward", "alt+right"}, {"🔄", "Reload", "ctrl+r"},
            {"➕", "New tab", "ctrl+t"}, {"✕", "Close tab", "ctrl+w"}, {"🔗", "Address bar", "ctrl+l"},
            {"⭐", "Bookmark", "ctrl+d"}, {"⇥", "Next tab", "ctrl+tab"}};
    private static final String[][] TERMINAL = {
            {"⛔", "Stop (Ctrl+C)", "ctrl+c"}, {"📋", "Copy", "ctrl+shift+c"},
            {"📥", "Paste", "ctrl+shift+v"}, {"➕", "New tab", "ctrl+shift+t"},
            {"⬆️", "Previous", "up"}, {"⇥", "Complete", "tab"}};
    private static final String[][] CODE = {
            {"🎛", "Commands", "ctrl+shift+p"}, {"📄", "Open file", "ctrl+p"},
            {"💬", "Comment", "ctrl+slash"}, {"💻", "Terminal", "ctrl+grave"},
            {"💾", "Save", "ctrl+s"}, {"▶️", "Run", "f5"}};

    @Override
    protected String title() {
        return "Shortcuts";
    }

    @Override
    protected void build() {
        String app = link.app();
        String[][] forApp = forApp(app);
        if (forApp != null) {
            section("For " + app);
            add(grid(forApp, Theme.TEAL));
        }
        section("Edit");
        add(grid(EDIT, Theme.BLUE));
        section("Windows");
        add(grid(WINDOWS, Theme.INDIGO));
        section("Screen & system");
        add(grid(SYSTEM, Theme.ORANGE));
    }

    private static String[][] forApp(String app) {
        String a = app == null ? "" : app.toLowerCase(java.util.Locale.ROOT);
        if (a.contains("firefox") || a.contains("chrom") || a.contains("brave") || a.contains("browser")) return BROWSER;
        if (a.contains("terminal") || a.contains("console") || a.contains("ptyxis") || a.contains("konsole")) return TERMINAL;
        if (a.contains("code") || a.contains("studio")) return CODE;
        return null;
    }

    private LinearLayout grid(String[][] items, int color) {
        float widthDp = Math.min(820, getResources().getConfiguration().screenWidthDp);
        int columns = widthDp >= 640 ? 4 : 3;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 10));
            }
            String[] it = items[i];
            LinearLayout cell = th.card();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = th.dp(12);
            cell.setPadding(p, th.dp(14), p, th.dp(12));
            cell.addView(th.iconChip(it[0], color, 40));
            TextView name = th.text(it[1], 13.5f, th.text, true);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            cell.addView(name, th.fullWidth(10));
            cell.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(th.dark ? 0x33FFFFFF : 0x1F000000),
                    null, Theme.round(0xFFFFFFFF, th.dp(18))));
            th.click(cell, v -> link.action(it[2]));
            row.addView(cell, th.weighted(i % columns == 0 ? 0 : 10));
        }
        // keep the last row's cells the same width as the others
        int rest = items.length % columns;
        if (rest != 0 && row != null) {
            for (int i = rest; i < columns; i++) {
                row.addView(new android.view.View(this), th.weighted(10));
            }
        }
        return grid;
    }
}

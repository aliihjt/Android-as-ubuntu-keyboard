package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Spotlight: one search box for everything — Fedora Pad pages, shortcuts,
 * your buttons, Fedora apps — or search Fedora itself.
 */
public class SpotlightActivity extends Page {
    private final List<Object[]> index = new ArrayList<>();  // {symbol, colour, title, kind, Runnable}
    private EditText field;
    private LinearLayout results;

    @Override
    protected String title() {
        return null;  // floats on the wallpaper, like the real Spotlight
    }

    @Override
    protected void build() {
        buildIndex();
        content.setPadding(0, th.dp(60), 0, 0);

        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(th.frosted(th.dp(16)));
        box.setPadding(th.dp(16), th.dp(6), th.dp(16), th.dp(6));
        box.setElevation(th.dp(12));
        box.addView(th.text("🔍", 22, th.secondary, false));
        field = new EditText(this);
        field.setHint("Spotlight Search");
        field.setHintTextColor(th.secondary);
        field.setTextColor(th.text);
        field.setTextSize(24 * Ui.scale);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.addTextChangedListener(new TextWatcher() {
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
        field.setOnEditorActionListener((v, action, ev) -> {
            boolean enter = action == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (enter && results.getChildCount() > 0) results.getChildAt(0).performClick();
            return enter;
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        flp.leftMargin = th.dp(10);
        box.addView(field, flp);
        TextView close = th.text("✕", 18, th.secondary, false);
        close.setPadding(th.dp(10), th.dp(10), 0, th.dp(10));
        th.click(close, v -> finish());
        box.addView(close);
        add(box);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        add(results, 12);
        show("");

        field.requestFocus();
        field.postDelayed(() -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(field, 0), 250);
    }

    private void buildIndex() {
        Object[][] pages = {
                {"⌨️", Theme.BLUE, "Keyboard", KeyboardActivity.class},
                {"🖱", Theme.PURPLE, "Trackpad", TrackpadActivity.class},
                {"🎵", Theme.PINK, "Media", MediaActivity.class},
                {"🎛", Theme.ORANGE, "Controls", ControlsActivity.class},
                {"⚡", Theme.INDIGO, "Shortcuts", ShortcutsActivity.class},
                {"🚀", Theme.TEAL, "Apps", AppsActivity.class},
                {"✍️", Theme.YELLOW, "Text & Clipboard", ClipboardActivity.class},
                {"📁", Theme.BLUE, "Send Files", FilesActivity.class},
                {"🎤", Theme.RED, "Presenter", PresenterActivity.class},
                {"\uD83C\uDF9A", Theme.ORANGE, "Stream Deck", ButtonsActivity.class},
                {"\uD83D\uDDC2", Theme.INDIGO, "Windows", WindowsActivity.class},
                {"📊", Theme.GREEN, "Stats", StatsActivity.class},
                {"🎙", Theme.RED, "Push to Talk", PushToTalkActivity.class},
                {"\uD83D\uDDD2", Theme.YELLOW, "Notes", NotesActivity.class},
                {">_", 0xFF3A3A3C, "Terminal Pad", TerminalActivity.class},
                {"🌈", Theme.PINK, "Ambient Light", AmbientActivity.class},
                {"⚙️", Theme.GRAY, "Settings", SettingsActivity.class},
        };
        for (Object[] p : pages) {
            Class<?> c = (Class<?>) p[3];
            index.add(new Object[]{p[0], p[1], p[2], "Fedora Pad", (Runnable) () -> startActivity(new Intent(this, c))});
        }
        index.add(new Object[]{"🖥", Theme.INDIGO, "Mission Control", "Fedora", (Runnable) () -> link.combo("super")});
        index.add(new Object[]{"▦", Theme.TEAL, "Launchpad", "Fedora", (Runnable) () -> link.combo("super+a")});
        index.add(new Object[]{"🔒", Theme.GRAY, "Lock Screen", "Fedora", (Runnable) () -> link.combo("super+l")});
        index.add(new Object[]{"✨", Theme.PURPLE, "Ask AI", "Fedora", (Runnable) () -> link.command("ai")});
        index.add(new Object[]{"🎙", Theme.RED, "Voice typing (start / stop)", "Fedora", (Runnable) () -> link.command("voice")});
        for (String[][] group : new String[][][]{ShortcutsActivity.EDIT, ShortcutsActivity.WINDOWS, ShortcutsActivity.SYSTEM}) {
            for (String[] s : group) {
                index.add(new Object[]{s[0], Theme.INDIGO, s[1], "Shortcut", (Runnable) () -> link.action(s[2])});
            }
        }
        try {
            JSONArray mine = new JSONArray(getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
                    .getString("my_buttons", "[]"));
            for (int i = 0; i < mine.length(); i++) {
                JSONObject b = mine.getJSONObject(i);
                String action = b.optString("action");
                index.add(new Object[]{"⭐", Theme.ORANGE, b.optString("label"), "Stream Deck",
                        (Runnable) () -> ButtonsActivity.run(action)});
            }
        } catch (Exception ignored) {
        }
        for (String[] app : link.apps()) {
            index.add(new Object[]{app[1].isEmpty() ? "?" : app[1].substring(0, 1).toUpperCase(Locale.ROOT),
                    Theme.TEAL, app[1], "Fedora app", (Runnable) () -> link.action("launch:" + app[0])});
        }
    }

    private void show(String query) {
        results.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<LinearLayout> rows = new ArrayList<>();
        for (Object[] it : index) {
            String title = (String) it[2];
            if (!q.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (q.isEmpty() && !"Fedora Pad".equals(it[3])) continue;  // empty search: just the pages
            rows.add(row((String) it[0], (Integer) it[1], title, (String) it[3], (Runnable) it[4]));
            if (rows.size() >= 12) break;
        }
        if (!q.isEmpty()) {
            rows.add(row("🔎", Theme.BLUE, "Search Fedora for “" + query.trim() + "”",
                    "Opens Fedora's search", () -> searchFedora(query.trim())));
        }
        LinearLayout list = th.group(rows.toArray(new LinearLayout[0]));
        list.setBackground(th.frosted(th.dp(16)));
        results.addView(list);
        results.setVisibility(rows.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private LinearLayout row(String sym, int color, String title, String kind, Runnable run) {
        LinearLayout r = th.row(sym, color, title, kind, null);
        th.click(r, v -> {
            run.run();
            if (!"Fedora Pad".equals(kind)) finish();
        });
        return r;
    }

    /** Opens GNOME's search on Fedora and pastes the words (any language). */
    private void searchFedora(String words) {
        link.combo("super");
        link.setClip(words);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> HostLink.get().combo("ctrl+v"), 450);  // runs after we close
        finish();
    }
}

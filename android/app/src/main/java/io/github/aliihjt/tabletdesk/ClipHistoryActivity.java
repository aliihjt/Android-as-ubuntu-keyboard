package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The last 50 things copied on the PC. Tap one to paste it where the PC's
 * cursor is; long-press to copy it to the tablet or keep it as a note.
 * Password managers' copies are never kept.
 */
public class ClipHistoryActivity extends Page {
    private final Handler poll = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private EditText search;
    private JSONArray items = new JSONArray();
    private String shown = "";

    @Override
    protected String title() {
        return "Clipboard History";
    }

    @Override
    protected void build() {
        search = new EditText(this);
        search.setHint("🔍  Search what you copied");
        search.setSingleLine(true);
        search.setBackground(Theme.round(th.fill, th.dp(12)));
        search.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                shown = "";
                draw();
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        });
        add(search, 4);
        add(th.caption("Tap: paste on the PC. Long-press: copy to the tablet or save as a note."), 6);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        add(list, 12);
        add(th.secondaryButton("🧹  Clear the history", v -> new AlertDialog.Builder(this)
                .setMessage("Forget everything copied on the PC?")
                .setPositiveButton("Clear", (d, w) -> {
                    try {
                        link.send(new JSONObject().put("t", "clipclear"));
                    } catch (org.json.JSONException ignored) {
                    }
                    items = new JSONArray();
                    shown = "";
                    draw();
                }).setNegativeButton("Cancel", null).show()), 16);
    }

    private void draw() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String key = q + "|" + items;
        if (key.equals(shown)) return;
        shown = key;
        list.removeAllViews();
        int n = 0;
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(Theme.round(th.card, th.dp(14)));
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String text = it.optString("text");
            if (!q.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(q)) continue;
            group.addView(row(text, it.optLong("time")));
            n++;
        }
        if (n == 0) {
            list.addView(th.caption(link.isConnected() ? (q.isEmpty() ? "Nothing copied on the PC yet." : "No match.")
                    : "Connect to the PC to see what you copied."));
        } else {
            list.addView(group);
        }
    }

    private View row(String text, long time) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(th.dp(16), th.dp(12), th.dp(16), th.dp(12));
        TextView t = th.text(text.trim(), 15, th.text, false);
        t.setMaxLines(3);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        r.addView(t);
        r.addView(th.caption(ago(time)), th.fullWidth(3));
        r.setBackground(th.pressable(th.card, th.dp(14)));
        th.click(r, v -> {
            link.setClip(text);
            poll.postDelayed(() -> link.combo(terminal() ? "ctrl+shift+v" : "ctrl+v"), 250);
            Toast.makeText(this, "Pasted on the PC", Toast.LENGTH_SHORT).show();
        });
        r.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setItems(new String[]{"Copy to the tablet", "Save as a note"}, (d, w) -> {
                if (w == 0) {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("From the PC", text));
                    Toast.makeText(this, "Copied on the tablet", Toast.LENGTH_SHORT).show();
                } else {
                    NotesActivity.addNote(this, text);
                    Toast.makeText(this, "Saved in Notes", Toast.LENGTH_SHORT).show();
                }
            }).show();
            return true;
        });
        return r;
    }

    private boolean terminal() {
        String a = link.app() == null ? "" : link.app().toLowerCase(Locale.ROOT);
        for (String k : new String[]{"terminal", "ptyxis", "console", "konsole", "kitty", "alacritty", "wezterm"}) {
            if (a.contains(k)) return true;
        }
        return false;
    }

    private static String ago(long when) {
        long s = Math.max(0, (System.currentTimeMillis() - when) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        return (s / 86400) + " days ago";
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            link.ask("cliphist");
            poll.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        link.on("cliphist", m -> {
            items = m.optJSONArray("list") == null ? new JSONArray() : m.optJSONArray("list");
            draw();
        });
        poll.post(tick);
    }

    @Override
    protected void onPause() {
        poll.removeCallbacks(tick);
        link.on("cliphist", null);
        super.onPause();
    }
}

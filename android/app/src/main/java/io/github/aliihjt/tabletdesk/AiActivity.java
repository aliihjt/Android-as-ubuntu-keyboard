package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * AI chat with Claude, right on the tablet (no laptop needed). Your API key is
 * kept only on this tablet. Other pages can open it with a question:
 * intent extra "ask".
 */
public class AiActivity extends Page {
    static final String EXTRA_ASK = "ask", EXTRA_IMAGE = "image";
    private static final String KEY = "claude_key", HISTORY = "claude_history";
    private static final int VOICE = 41;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private JSONArray history;
    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private TextView sendButton;
    private boolean busy;
    private String pendingImage;  // a JPEG file (a book page) sent with the next message
    private TextView attachment;

    @Override
    protected String title() {
        return "AI Chat";
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        try {
            history = new JSONArray(prefs.getString(HISTORY, "[]"));
        } catch (JSONException e) {
            history = new JSONArray();
        }

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = th.caption("✨ Claude · answers in your language");
        top.addView(who, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(small("New chat", v -> newChat()));
        top.addView(small("🔑 Key", v -> askKey()));
        content.addView(top);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, th.dp(8), 0, th.dp(8));
        scroll = new ScrollView(this);
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(th.dp(8), th.dp(6), th.dp(6), th.dp(6));
        bar.setBackground(Theme.round(th.card, th.dp(22)));
        TextView mic = th.text("🎙", 22, th.text, false);
        mic.setGravity(Gravity.CENTER);
        th.click(mic, v -> voice());
        bar.addView(mic, new LinearLayout.LayoutParams(th.dp(44), th.dp(44)));
        input = new EditText(this);
        input.setHint("Message Claude…");
        input.setHintTextColor(th.secondary);
        input.setTextColor(th.text);
        input.setTextSize(17 * Ui.scale);
        input.setBackground(null);
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bar.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sendButton = th.text("↑", 22, 0xFFFFFFFF, true);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(Theme.round(th.accent, th.dp(20)));
        th.click(sendButton, v -> sendTyped());
        bar.addView(sendButton, new LinearLayout.LayoutParams(th.dp(40), th.dp(40)));
        attachment = th.text("", 13, th.accent, true);
        attachment.setPadding(th.dp(12), th.dp(4), th.dp(12), 0);
        th.click(attachment, v -> attach(null));
        content.addView(attachment);
        content.addView(bar, th.fullWidth(4));
        attach(getIntent().getStringExtra(EXTRA_IMAGE));

        redraw();
        String ask = getIntent().getStringExtra(EXTRA_ASK);
        if (ask != null && !ask.isEmpty()) {
            input.setText(ask);
            input.setSelection(ask.length());
        }
        if (key().isEmpty()) askKey();
    }

    private TextView small(String label, View.OnClickListener l) {
        TextView b = th.secondaryButton(label, l);
        b.setTextSize(13 * Ui.scale);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, th.dp(34));
        lp.leftMargin = th.dp(8);
        b.setLayoutParams(lp);
        b.setPadding(th.dp(12), 0, th.dp(12), 0);
        return b;
    }

    // ---- key ----

    private String key() {
        return prefs.getString(KEY, "").trim();
    }

    private void askKey() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = th.dp(20);
        form.setPadding(p, th.dp(8), p, 0);
        TextView how = th.caption("1. Open console.anthropic.com on any device and sign in.\n"
                + "2. Settings › API keys › Create key, and add a little credit under Billing.\n"
                + "3. Paste the key here. It stays only on this tablet.\n"
                + "Each message costs a small amount from your credit.");
        form.addView(how);
        EditText field = new EditText(this);
        field.setHint("sk-ant-…");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setText(key());
        form.addView(field, th.fullWidth(10));
        new AlertDialog.Builder(this).setTitle("Claude API key").setView(form)
                .setPositiveButton("Save", (d, w) -> prefs.edit()
                        .putString(KEY, field.getText().toString().trim()).apply())
                .setNeutralButton("Open the console", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://console.anthropic.com/settings/keys"))))
                .setNegativeButton("Cancel", null).show();
    }

    // ---- conversation ----

    private void newChat() {
        if (busy) return;
        history = new JSONArray();
        save();
        redraw();
    }

    private void save() {
        prefs.edit().putString(HISTORY, history.toString()).apply();
    }

    private void redraw() {
        list.removeAllViews();
        if (history.length() == 0) {
            TextView hello = th.caption("Ask anything: explain a page of a book, summarise your notes, "
                    + "translate, write an email, help with Linux commands. فارسی هم می‌فهمد.");
            hello.setGravity(Gravity.CENTER);
            hello.setPadding(th.dp(24), th.dp(40), th.dp(24), 0);
            list.addView(hello);
        }
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i);
            if (m == null) continue;
            boolean user = "user".equals(m.optString("role"));
            String text = m.opt("content") instanceof String ? m.optString("content")
                    : ClaudeChat.textOf(m.optJSONArray("content"));
            if (user && m.optJSONArray("content") != null) text = "🖼  " + text;
            if (!text.isEmpty()) bubble(text, user);
        }
        scrollDown();
    }

    private TextView bubble(String text, boolean user) {
        TextView t = th.text(text, 16, user ? 0xFFFFFFFF : th.text, false);
        t.setTextIsSelectable(true);
        int p = th.dp(14);
        t.setPadding(p, th.dp(10), p, th.dp(10));
        t.setBackground(Theme.round(user ? th.accent : th.card, th.dp(18)));
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);  // Persian lines read right to left
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = user ? Gravity.END : Gravity.START;
        lp.topMargin = th.dp(8);
        if (user) lp.leftMargin = th.dp(60);
        else lp.rightMargin = th.dp(40);
        if (!user) {
            t.setOnLongClickListener(v -> {
                replyActions(t.getText().toString());
                return true;
            });
        }
        list.addView(t, lp);
        return t;
    }

    private void replyActions(String text) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Copy", "Type it on Fedora", "Save as a note"}, (d, which) -> {
                    if (which == 0) {
                        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Claude", text));
                        toast("Copied");
                    } else if (which == 1) {
                        link.setClip(text);
                        main.postDelayed(() -> link.combo("ctrl+v"), 250);
                        toast(link.isConnected() ? "Typed on Fedora" : "Not connected to Fedora");
                    } else {
                        NotesActivity.addNote(this, text);
                        toast("Saved in Notes");
                    }
                }).show();
    }

    private void attach(String path) {
        pendingImage = path != null && new java.io.File(path).exists() ? path : null;
        attachment.setText(pendingImage == null ? "" : "🖼  Page picture attached · tap to remove");
        attachment.setVisibility(pendingImage == null ? View.GONE : View.VISIBLE);
    }

    /** The user's turn: text, or a picture of a page plus the question. */
    private Object userContent(String text) throws JSONException {
        if (pendingImage == null) return text;
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(new java.io.File(pendingImage).toPath());
        } catch (java.io.IOException e) {
            return text;
        }
        String data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        return new JSONArray()
                .put(new JSONObject().put("type", "image").put("source", new JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg").put("data", data)))
                .put(new JSONObject().put("type", "text").put("text", text));
    }

    private void sendTyped() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || busy) return;
        if (key().isEmpty()) {
            askKey();
            return;
        }
        input.setText("");
        try {
            history.put(new JSONObject().put("role", "user").put("content", userContent(text)));
        } catch (JSONException ignored) {
        }
        boolean withPicture = pendingImage != null;
        attach(null);
        save();
        if (history.length() == 1) list.removeAllViews();
        bubble(withPicture ? "🖼  " + text : text, true);
        TextView reply = bubble("…", false);
        scrollDown();
        busy = true;
        sendButton.setAlpha(0.4f);
        JSONArray snapshot;
        try {
            snapshot = new JSONArray(history.toString());
        } catch (JSONException e) {
            return;
        }
        String apiKey = key();
        new Thread(() -> ClaudeChat.send(apiKey, snapshot, new ClaudeChat.Callback() {
            @Override
            public void onText(String soFar) {
                main.post(() -> {
                    reply.setText(soFar);
                    scrollDown();
                });
            }

            @Override
            public void onHistoryReplaced(JSONArray h) {
                main.post(() -> {
                    try {  // keep this turn's question on the end
                        JSONArray copy = new JSONArray(h.toString());
                        history = copy;
                        save();
                    } catch (JSONException ignored) {
                    }
                });
            }

            @Override
            public void onDone(JSONArray contentBlocks, String stop, String error) {
                main.post(() -> finishTurn(reply, contentBlocks, stop, error));
            }
        }), "claude").start();
    }

    private void finishTurn(TextView reply, JSONArray contentBlocks, String stop, String error) {
        busy = false;
        sendButton.setAlpha(1f);
        if (error != null || "refusal".equals(stop) || contentBlocks == null) {
            // leave the unanswered question out, so the history stays a clean back-and-forth
            history.remove(history.length() - 1);
            save();
            reply.setText(error != null ? "⚠️ " + error : "⚠️ Claude can't help with that request.");
            reply.setTextColor(Theme.ORANGE);
            reply.setOnLongClickListener(null);
            return;
        }
        try {
            history.put(new JSONObject().put("role", "assistant").put("content", contentBlocks));
        } catch (JSONException ignored) {
        }
        save();
        String text = ClaudeChat.textOf(contentBlocks);
        if ("max_tokens".equals(stop)) text += "\n\n(The answer was cut off. Ask “continue”.)";
        reply.setText(text.isEmpty() ? "(no answer)" : text);
        scrollDown();
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---- voice ----

    private void voice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String lang = prefs.getString(PushToTalk.LANG, "auto");
        if (!"auto".equals(lang)) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        try {
            startActivityForResult(i, VOICE);
        } catch (android.content.ActivityNotFoundException e) {
            toast("No speech recognition on this tablet");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        String cur = input.getText().toString();
        input.setText(cur.isEmpty() ? r.get(0) : cur + " " + r.get(0));
        input.setSelection(input.getText().length());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

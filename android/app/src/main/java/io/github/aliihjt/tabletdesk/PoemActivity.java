package io.github.aliihjt.tabletdesk;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Persian poetry from Ganjoor: a poet's books and poems (mode "cat"), one poem
 * as couplets (mode "poem"), or Fal-e Hafez (mode "faal").
 */
public class PoemActivity extends Page {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout pane;
    private String mode;
    private JSONObject poem;
    private boolean meanings;
    private TextToSpeech tts;

    @Override
    protected String title() {
        String m = getIntent().getStringExtra("mode");
        if ("faal".equals(m)) return "فال حافظ";
        String t = getIntent().getStringExtra("title");
        return t == null ? "گنجور" : t;
    }

    @Override
    protected void build() {
        mode = getIntent().getStringExtra("mode");
        pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        add(pane, 4);
        String id = getIntent().getStringExtra("id");
        if ("cat".equals(mode)) loadCategory(id);
        else if ("faal".equals(mode)) loadPoem(GetBooksActivity.GANJOOR + "hafez/faal");
        else loadPoem(poemUrl(id));
    }

    private static String poemUrl(String id) {
        return GetBooksActivity.GANJOOR + "poem/" + id + "?catInfo=false&catPoems=false&rhymes=false&recitations=false"
                + "&images=false&songs=false&comments=false&verseDetails=true&navigation=true";
    }

    private void loading() {
        pane.removeAllViews();
        pane.addView(th.caption("در حال بارگذاری…  Loading…"));
    }

    private void failed(Exception e) {
        main.post(() -> {
            pane.removeAllViews();
            pane.addView(th.caption("Couldn't reach Ganjoor (" + e.getMessage() + "). Check the internet."));
            pane.addView(th.secondaryButton("Open ganjoor.net", v ->
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://ganjoor.net")))), th.fullWidth(10));
        });
    }

    // ---- a poet's books, and the poems in one ----

    private void loadCategory(String id) {
        loading();
        new Thread(() -> {
            try {
                JSONObject cat = new JSONObject(GetBooksActivity.fetch(GetBooksActivity.GANJOOR + "cat/" + id + "?poems=true"))
                        .getJSONObject("cat");
                main.post(() -> {
                    if (!isDestroyed()) showCategory(cat);
                });
            } catch (Exception e) {
                failed(e);
            }
        }, "ganjoor").start();
    }

    private void showCategory(JSONObject cat) {
        pane.removeAllViews();
        JSONArray children = cat.optJSONArray("children");
        JSONArray poems = cat.optJSONArray("poems");
        int n = (children == null ? 0 : children.length()) + (poems == null ? 0 : poems.length());
        if (n == 0) pane.addView(th.caption("خالی است · Nothing here"));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; children != null && i < children.length(); i++) {
            JSONObject c = children.optJSONObject(i);
            LinearLayout r = th.row("📚", Theme.PURPLE, c.optString("title"), null, th.text("‹", 22, th.secondary, false));
            th.click(r, v -> startActivity(new Intent(this, PoemActivity.class).putExtra("mode", "cat")
                    .putExtra("id", String.valueOf(c.optInt("id"))).putExtra("title", c.optString("title"))));
            list.addView(th.group(r), th.fullWidth(list.getChildCount() == 0 ? 0 : 8));
        }
        for (int i = 0; poems != null && i < poems.length(); i++) {
            JSONObject p = poems.optJSONObject(i);
            LinearLayout r = th.row(null, 0, p.optString("title"), p.optString("excerpt"), null);
            th.click(r, v -> startActivity(new Intent(this, PoemActivity.class).putExtra("mode", "poem")
                    .putExtra("id", String.valueOf(p.optInt("id"))).putExtra("title", p.optString("title"))));
            list.addView(th.group(r), th.fullWidth(list.getChildCount() == 0 ? 0 : 8));
        }
        pane.addView(list);
    }

    // ---- one poem ----

    private void loadPoem(String url) {
        loading();
        new Thread(() -> {
            try {
                JSONObject p = new JSONObject(GetBooksActivity.fetch(url));
                main.post(() -> {
                    if (isDestroyed()) return;
                    poem = p;
                    showPoem();
                });
            } catch (Exception e) {
                failed(e);
            }
        }, "ganjoor").start();
    }

    private void showPoem() {
        pane.removeAllViews();
        TextView head = th.text(poem.optString("title"), 22, th.text, true);
        head.setGravity(Gravity.CENTER);
        pane.addView(head);
        TextView where = th.caption(poem.optString("fullTitle"));
        where.setGravity(Gravity.CENTER);
        pane.addView(where, th.fullWidth(4));

        LinearLayout card = th.card();
        card.setPadding(th.dp(18), th.dp(18), th.dp(18), th.dp(18));
        JSONArray verses = poem.optJSONArray("verses");
        LinearLayout couplet = null;
        for (int i = 0; verses != null && i < verses.length(); i++) {
            JSONObject v = verses.optJSONObject(i);
            int pos = v.optInt("versePosition");
            TextView t = th.text(v.optString("text"), 19, th.text, false);
            t.setLineSpacing(0, 1.3f);
            if (pos == 0 || pos == 1) {  // the two halves of a couplet, side by side
                if (pos == 0 || couplet == null) {
                    couplet = new LinearLayout(this);
                    couplet.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                    card.addView(couplet, th.fullWidth(card.getChildCount() == 0 ? 0 : 14));
                }
                t.setGravity(pos == 0 ? Gravity.START : Gravity.END);
                couplet.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                t.setGravity(Gravity.CENTER);
                card.addView(t, th.fullWidth(card.getChildCount() == 0 ? 0 : 14));
                couplet = null;
            }
            String meaning = v.optString("coupletSummary", "");
            if (meanings && !meaning.isEmpty() && !meaning.equals("null")) {
                TextView m = th.text(meaning, 14, th.accent, false);
                card.addView(m, th.fullWidth(6));
                couplet = null;
            }
        }
        pane.addView(card, th.fullWidth(14));

        LinearLayout tools = new LinearLayout(this);
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        tools.addView(th.secondaryButton("🔊 خواندن", v -> readAloud()), th.weighted(0));
        tools.addView(th.secondaryButton(meanings ? "📝 بدون معنی" : "📝 معنی", v -> {
            meanings = !meanings;
            showPoem();
        }), th.weighted(8));
        tools.addView(th.secondaryButton("🗒 Notes", v -> {
            NotesActivity.addNote(this, poem.optString("plainText").trim() + "\n— " + poem.optString("fullTitle"));
            Toast.makeText(this, "Saved in Notes", Toast.LENGTH_SHORT).show();
        }), th.weighted(8));
        tools.addView(th.secondaryButton("✨ Claude", v -> ClaudeApp.ask(this, ("faal".equals(mode)
                ? "This is my Fal-e Hafez (فال حافظ). Interpret it for me in simple Persian and English:\n\n"
                : "Explain this Persian poem line by line in simple Persian, then summarise it in English:\n\n")
                + poem.optString("plainText").trim() + "\n— " + poem.optString("fullTitle"), null)), th.weighted(8));
        pane.addView(tools, th.fullWidth(14));

        LinearLayout nav = new LinearLayout(this);
        nav.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if ("faal".equals(mode)) {
            nav.addView(th.primaryButton("🎲 فال دوباره", v -> loadPoem(GetBooksActivity.GANJOOR + "hafez/faal")),
                    th.weighted(0));
        } else {
            JSONObject prev = poem.optJSONObject("previous"), next = poem.optJSONObject("next");
            if (prev != null) nav.addView(th.secondaryButton("→ " + prev.optString("title"), v ->
                    loadPoem(poemUrl(String.valueOf(prev.optInt("id"))))), th.weighted(0));
            if (next != null) nav.addView(th.secondaryButton(next.optString("title") + " ←", v ->
                    loadPoem(poemUrl(String.valueOf(next.optInt("id"))))), th.weighted(prev == null ? 0 : 8));
        }
        pane.addView(nav, th.fullWidth(10));
    }

    private void readAloud() {
        String text = poem.optString("plainText").trim();
        if (tts != null) {
            if (tts.isSpeaking()) {
                tts.stop();
                return;
            }
            speak(text);
            return;
        }
        tts = new TextToSpeech(this, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS) {
                Toast.makeText(this, "No text-to-speech engine installed.", Toast.LENGTH_LONG).show();
                return;
            }
            speak(text);
        }));
    }

    private void speak(String text) {
        Locale fa = new Locale("fa", "IR");
        if (tts.isLanguageAvailable(fa) < TextToSpeech.LANG_AVAILABLE) {
            Toast.makeText(this, "No Persian voice yet: install one (e.g. eSpeak NG or RHVoice) and pick it in "
                    + "Android Settings › Text-to-speech.", Toast.LENGTH_LONG).show();
        }
        tts.setLanguage(fa);
        tts.setSpeechRate(0.85f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "poem");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}

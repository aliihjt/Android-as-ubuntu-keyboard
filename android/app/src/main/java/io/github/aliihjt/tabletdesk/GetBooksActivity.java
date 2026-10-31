package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Get Books: free classics from Project Gutenberg (downloaded into Books),
 * Persian poetry from Ganjoor (with Fal-e Hafez), and Iranian book stores.
 */
public class GetBooksActivity extends Page {
    private static final String GUTENDEX = "https://gutendex.com/books/";
    static final String GANJOOR = "https://api.ganjoor.net/api/ganjoor/";
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout pane;
    private String tab = "en";

    /** Famous poets on Ganjoor: name, root category. */
    private static final String[][] POETS = {
            {"حافظ", "9"}, {"سعدی", "118"}, {"مولوی", "97"}, {"فردوسی", "32"}, {"خیام", "30"},
            {"عطار", "151"}, {"نظامی", "110"}, {"پروین اعتصامی", "146"}, {"سنایی", "188"}, {"جامی", "285"}};

    /** Iranian stores and free Persian libraries: name, address, what it is. */
    private static final String[][] STORES = {
            {"طاقچه · Taaghche", "https://taaghche.com", "E-books and audiobooks, many free; has its own app"},
            {"فیدیبو · Fidibo", "https://fidibo.com", "E-books, audiobooks and podcasts"},
            {"کتابراه · Ketabrah", "https://www.ketabrah.ir", "E-books and audiobooks, many free"},
            {"نوار · Navaar", "https://www.navaar.ir", "Persian audiobooks"},
            {"کتابناک · Ketabnak", "https://ketabnak.com", "Free Persian e-books (PDF / EPUB)"},
            {"ویکی‌نبشته · Wikisource", "https://fa.wikisource.org", "Free Persian classic texts"},
            {"گنجور · Ganjoor", "https://ganjoor.net", "Every Persian poet, online"}};

    @Override
    protected String title() {
        return "Get Books";
    }

    @Override
    protected void build() {
        add(th.segmented(new String[][]{{"en", "📚 Free classics"}, {"fa", "🌹 شعر فارسی"}, {"ir", "🇮🇷 Iranian stores"}},
                tab, t -> {
                    tab = t;
                    show();
                }), 6);
        pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        add(pane, 16);
        show();
    }

    private void show() {
        pane.removeAllViews();
        if (tab.equals("en")) classics();
        else if (tab.equals("fa")) poetry();
        else stores();
    }

    // ---- Project Gutenberg ----

    private void classics() {
        LinearLayout top = new LinearLayout(this);
        EditText search = new EditText(this);
        search.setHint("Search 75,000 free books (title or author)");
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        Runnable go = () -> gutenberg(GUTENDEX + "?search=" + Uri.encode(search.getText().toString().trim()), results);
        search.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                go.run();
                return true;
            }
            return false;
        });
        top.addView(search, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(th.primaryButton("Search", v -> go.run()));
        pane.addView(top);
        pane.addView(th.caption("From Project Gutenberg: books old enough to be free. They download into Books as EPUB."),
                th.fullWidth(6));
        pane.addView(results, th.fullWidth(14));
        gutenberg(GUTENDEX, results);  // most downloaded
    }

    private void gutenberg(String url, LinearLayout into) {
        into.removeAllViews();
        into.addView(th.caption("Loading…"));
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(fetch(url));
                JSONArray list = o.getJSONArray("results");
                String next = o.optString("next", "");
                main.post(() -> {
                    if (isDestroyed()) return;
                    into.removeAllViews();
                    if (list.length() == 0) into.addView(th.caption("Nothing found. Try an author's surname."));
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject b = list.optJSONObject(i);
                        if (b != null) into.addView(bookRow(b), th.fullWidth(i == 0 ? 0 : 8));
                    }
                    if (!next.isEmpty() && !next.equals("null")) {
                        TextView more = th.secondaryButton("More…", v -> gutenberg(next, into));
                        into.addView(more, th.fullWidth(14));
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    into.removeAllViews();
                    into.addView(th.caption("Couldn't reach Project Gutenberg (" + e.getMessage()
                            + "). Check the internet, or try the Iranian stores tab."));
                });
            }
        }, "gutenberg").start();
    }

    private View bookRow(JSONObject b) {
        String title = b.optString("title");
        JSONArray authors = b.optJSONArray("authors");
        StringBuilder who = new StringBuilder();
        for (int i = 0; authors != null && i < authors.length(); i++) {
            String n = authors.optJSONObject(i).optString("name");
            // "Austen, Jane" -> "Jane Austen"
            if (n.contains(", ")) n = n.substring(n.indexOf(", ") + 2) + " " + n.substring(0, n.indexOf(", "));
            if (who.length() > 0) who.append(", ");
            who.append(n);
        }
        JSONArray langs = b.optJSONArray("languages");
        String lang = langs != null && langs.length() > 0 ? langs.optString(0).toUpperCase(java.util.Locale.ROOT) : "";
        String epub = b.optJSONObject("formats") == null ? "" : b.optJSONObject("formats").optString("application/epub+zip");
        LinearLayout r = th.row("📖", Theme.INDIGO, title, who + (lang.isEmpty() ? "" : "  ·  " + lang),
                th.text(epub.isEmpty() ? "" : "⬇️", 20, th.accent, false));
        th.click(r, v -> {
            if (epub.isEmpty()) {
                Toast.makeText(this, "No EPUB of this one.", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONArray sum = b.optJSONArray("summaries");
            String about = sum != null && sum.length() > 0 ? sum.optString(0) : "";
            new AlertDialog.Builder(this).setTitle(title)
                    .setMessage((who.length() > 0 ? who + "\n\n" : "") + (about.length() > 600 ? about.substring(0, 600) + "…" : about))
                    .setPositiveButton("Download", (d, w) -> download(b.optInt("id"), title, epub))
                    .setNegativeButton("Cancel", null).show();
        });
        return th.group(r);
    }

    private void download(int id, String title, String url) {
        AlertDialog wait = new AlertDialog.Builder(this).setTitle("Downloading").setMessage(title + "…")
                .setCancelable(false).show();
        new Thread(() -> {
            File dir = new File(getFilesDir(), "books");
            dir.mkdirs();
            File out = new File(dir, "gutenberg-" + id + ".epub");
            try {
                HttpURLConnection c = open(url);
                long size = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n, shown = -1;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                        got += n;
                        int pct = size > 0 ? (int) (got * 100 / size) : -1;
                        if (pct != shown && pct >= 0) {
                            shown = pct;
                            main.post(() -> wait.setMessage(title + "…  " + pct + " %"));
                        }
                    }
                } finally {
                    c.disconnect();
                }
                String uri = Uri.fromFile(out).toString();
                BooksActivity.addBook(this, uri, title, "epub");
                main.post(() -> {
                    wait.dismiss();
                    new AlertDialog.Builder(this).setTitle("Added to Books").setMessage(title)
                            .setPositiveButton("Read now", (d, w) -> startActivity(new Intent(this, ReaderActivity.class)
                                    .putExtra("uri", uri).putExtra("title", title).putExtra("type", "epub")))
                            .setNegativeButton("OK", null).show();
                });
            } catch (IOException e) {
                out.delete();
                main.post(() -> {
                    wait.dismiss();
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "gutenberg-download").start();
    }

    // ---- Persian poetry ----

    private void poetry() {
        TextView faal = th.primaryButton("🎲 فال حافظ  ·  Fal-e Hafez", v ->
                startActivity(new Intent(this, PoemActivity.class).putExtra("mode", "faal")));
        faal.setTextSize(18 * Ui.scale);
        pane.addView(faal, th.fullWidth(0));
        TextView tip = th.caption("نیت کنید و بزنید — make a wish, then tap. From Ganjoor (ganjoor.net).");
        tip.setGravity(Gravity.CENTER);
        pane.addView(tip, th.fullWidth(6));
        pane.addView(th.sectionHeader("شاعران · Poets"), th.fullWidth(10));
        View[] rows = new View[POETS.length];
        for (int i = 0; i < POETS.length; i++) {
            String[] p = POETS[i];
            LinearLayout r = th.row("🌹", i % 2 == 0 ? Theme.PINK : Theme.PURPLE, p[0], null, th.chevron());
            th.click(r, v -> startActivity(new Intent(this, PoemActivity.class)
                    .putExtra("mode", "cat").putExtra("id", p[1]).putExtra("title", p[0])));
            rows[i] = r;
        }
        pane.addView(th.group(rows));
    }

    // ---- Iranian stores ----

    private void stores() {
        pane.addView(th.caption("Buy or borrow Persian books. Their apps work best; these open in the browser."));
        View[] rows = new View[STORES.length];
        for (int i = 0; i < STORES.length; i++) {
            String[] s = STORES[i];
            LinearLayout r = th.row("📗", i < 4 ? Theme.ORANGE : Theme.GREEN, s[0], s[2], th.chevron());
            th.click(r, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s[1]))));
            rows[i] = r;
        }
        pane.addView(th.group(rows), th.fullWidth(10));
        pane.addView(th.caption("Downloaded a PDF or EPUB? Add it in Books › Add books."), th.fullWidth(10));
    }

    // ---- network ----

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "FedoraPad");
        return c;
    }

    /** A small text answer (JSON). Call off the main thread. */
    static String fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            return new String(all.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}

package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Html;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads one book: PDF pages (swipe or ◀ ▶, zoom, night mode), or EPUB / text
 * chapters in a web view (text size, night mode). Select text to ask AI about
 * it or keep it as a note. Remembers where you stopped.
 */
public class ReaderActivity extends Page {
    private static final String HOST = "https://book.local/";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String uri, type, bookTitle;
    private int index, total;
    private TextView where;
    private FrameLayout body;
    private boolean night;
    private int zoom;  // PDF: 100..300 %, text: font size %

    // PDF
    private ParcelFileDescriptor fd;
    private PdfRenderer pdf;
    private ImageView pageView;
    private ScrollView pageScroll;
    private Bitmap pageBitmap;

    // EPUB / text
    private WebView web;
    private ZipFile zip;
    private final List<String> chapters = new ArrayList<>();
    private String plainHtml;
    private int restoreScroll;

    @Override
    protected String title() {
        String t = getIntent().getStringExtra("title");
        return t == null ? "Book" : t.length() > 40 ? t.substring(0, 39) + "…" : t;
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        uri = getIntent().getStringExtra("uri");
        type = getIntent().getStringExtra("type");
        bookTitle = getIntent().getStringExtra("title");
        index = getIntent().getIntExtra("pos", 0);
        restoreScroll = BooksActivity.savedScroll(this, uri);
        night = prefs.getBoolean("reader_night", th.dark);
        zoom = "pdf".equals(type) ? 100 : prefs.getInt("reader_font", 110);

        content.addView(toolbar());
        body = new FrameLayout(this);
        body.setBackground(Theme.round(night ? 0xFF1C1C1E : Color.WHITE, th.dp(12)));
        body.setClipToOutline(true);
        content.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        try {
            if ("pdf".equals(type)) openPdf();
            else if ("epub".equals(type)) openEpub();
            else openText();
        } catch (Exception e) {
            TextView err = th.body("Can't open this book: " + e.getMessage()
                    + "\nIt may have been moved or deleted. Remove it from Books and add it again.");
            err.setPadding(th.dp(20), th.dp(20), th.dp(20), th.dp(20));
            body.addView(err);
        }
    }

    // ---- toolbar ----

    private View toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(tool("◀", v -> go(index - 1)));
        where = th.text("", 14, th.secondary, true);
        where.setGravity(Gravity.CENTER);
        bar.addView(where, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(tool("▶", v -> go(index + 1)));
        bar.addView(tool("A−", v -> scale(-1)));
        bar.addView(tool("A+", v -> scale(1)));
        bar.addView(tool("🌙", v -> toggleNight()));
        bar.addView(tool("✨ Ask AI", v -> askAi()));
        bar.addView(tool("🗒 Note", v -> note()));
        bar.setPadding(0, 0, 0, th.dp(8));
        return bar;
    }

    private TextView tool(String label, View.OnClickListener l) {
        TextView b = th.secondaryButton(label, l);
        b.setTextSize(14 * Ui.scale);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, th.dp(38));
        lp.leftMargin = th.dp(6);
        b.setLayoutParams(lp);
        b.setPadding(th.dp(12), 0, th.dp(12), 0);
        return b;
    }

    private void showWhere() {
        where.setText(total <= 0 ? "" : ("pdf".equals(type) ? "Page " : "Part ") + (index + 1) + " / " + total);
    }

    private void go(int i) {
        if (total <= 0 || i < 0 || i >= total) return;
        savePosition(0);
        index = i;
        restoreScroll = 0;
        if (pdf != null) renderPage();
        else loadChapter();
    }

    private void scale(int dir) {
        if (pdf != null) {
            zoom = Math.max(100, Math.min(300, zoom + dir * 50));
            renderPage();
        } else {
            zoom = Math.max(70, Math.min(220, zoom + dir * 10));
            prefs.edit().putInt("reader_font", zoom).apply();
            applyStyle();
        }
    }

    private void toggleNight() {
        night = !night;
        prefs.edit().putBoolean("reader_night", night).apply();
        body.setBackground(Theme.round(night ? 0xFF1C1C1E : Color.WHITE, th.dp(12)));
        if (pdf != null) paintNight();
        else applyStyle();
    }

    // ---- PDF ----

    @SuppressLint("ClickableViewAccessibility")
    private void openPdf() throws IOException {
        fd = getContentResolver().openFileDescriptor(Uri.parse(uri), "r");
        pdf = new PdfRenderer(fd);
        total = pdf.getPageCount();
        index = Math.min(index, total - 1);
        pageView = new ImageView(this);
        pageView.setAdjustViewBounds(true);
        HorizontalScrollView wide = new HorizontalScrollView(this);
        wide.addView(pageView);
        pageScroll = new ScrollView(this);
        pageScroll.addView(wide);
        body.addView(pageScroll);
        // swipe sideways to turn the page
        GestureDetector swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || zoom > 100 || Math.abs(vx) < 1.5f * Math.abs(vy) || Math.abs(vx) < 800) return false;
                go(vx < 0 ? index + 1 : index - 1);
                return true;
            }
        });
        pageScroll.setOnTouchListener((v, ev) -> {
            swipe.onTouchEvent(ev);
            return false;
        });
        body.post(this::renderPage);
    }

    private void renderPage() {
        int width = Math.max(body.getWidth(), th.dp(300)) * zoom / 100;
        int page = index;
        showWhere();
        worker.execute(() -> {
            try {
                PdfRenderer.Page p = pdf.openPage(page);
                int height = Math.round(width * (float) p.getHeight() / p.getWidth());
                Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
                main.post(() -> {
                    if (isDestroyed()) return;
                    pageBitmap = bmp;
                    pageView.setImageBitmap(bmp);
                    pageView.setLayoutParams(new FrameLayout.LayoutParams(width, height));
                    paintNight();
                    pageScroll.scrollTo(0, restoreScroll * height / 1000);
                    restoreScroll = 0;
                    savePosition(0);
                });
            } catch (RuntimeException ignored) {  // closed while drawing
            }
        });
    }

    private void paintNight() {
        if (!night) {
            pageView.setColorFilter(null);
            return;
        }
        // white paper becomes dark grey, black text light grey
        float k = -0.85f;
        pageView.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                k, 0, 0, 0, 240, 0, k, 0, 0, 240, 0, 0, k, 0, 240, 0, 0, 0, 1, 0})));
    }

    // ---- EPUB ----

    private void openEpub() throws Exception {
        File copy = new File(getCacheDir(), "books/" + Integer.toHexString(uri.hashCode()) + ".epub");
        if (!copy.exists()) {
            copy.getParentFile().mkdirs();
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri));
                 OutputStream out = new FileOutputStream(copy)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        zip = new ZipFile(copy);
        String opf = rootFile();
        String base = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";
        Map<String, String> manifest = new HashMap<>();
        List<String> spine = new ArrayList<>();
        XmlPullParser x = android.util.Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        try (InputStream in = zip.getInputStream(zip.getEntry(opf))) {
            x.setInput(in, null);
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e != XmlPullParser.START_TAG) continue;
                String tag = x.getName().replaceAll(".*:", "");
                if (tag.equals("item")) manifest.put(x.getAttributeValue(null, "id"), x.getAttributeValue(null, "href"));
                else if (tag.equals("itemref")) spine.add(x.getAttributeValue(null, "idref"));
            }
        }
        for (String id : spine) {
            String href = manifest.get(id);
            if (href != null) chapters.add(normalise(base + URLDecoder.decode(href, "UTF-8")));
        }
        total = chapters.size();
        if (total == 0) throw new IOException("no chapters found");
        index = Math.min(index, total - 1);
        setUpWeb();
        loadChapter();
    }

    private String rootFile() throws Exception {
        XmlPullParser x = android.util.Xml.newPullParser();
        try (InputStream in = zip.getInputStream(zip.getEntry("META-INF/container.xml"))) {
            x.setInput(in, null);
            for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                if (e == XmlPullParser.START_TAG && x.getName().endsWith("rootfile")) {
                    return x.getAttributeValue(null, "full-path");
                }
            }
        }
        throw new IOException("not an EPUB");
    }

    /** "OEBPS/text/../images/a.png" -> "OEBPS/images/a.png" */
    private static String normalise(String path) {
        List<String> parts = new ArrayList<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(p);
            }
        }
        return String.join("/", parts);
    }

    // ---- text ----

    private void openText() throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(Uri.parse(uri))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0 && all.size() < 8_000_000) all.write(buf, 0, n);
        }
        String text = new String(all.toByteArray(), StandardCharsets.UTF_8);
        plainHtml = "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'></head>"
                + "<body dir='auto'><div style='white-space:pre-wrap'>" + Html.escapeHtml(text) + "</div></body></html>";
        total = 1;
        index = 0;
        setUpWeb();
        loadChapter();
    }

    // ---- web view for EPUB and text ----

    @SuppressLint("SetJavaScriptEnabled")
    private void setUpWeb() {
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);  // only for text size, night mode and selection
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (zip == null || !url.startsWith(HOST)) {
                    // a book may not load anything from the internet
                    return new WebResourceResponse("text/plain", "utf-8", null);
                }
                try {
                    String path = normalise(URLDecoder.decode(r.getUrl().getPath().substring(1), "UTF-8"));
                    ZipEntry e = zip.getEntry(path);
                    if (e == null) return new WebResourceResponse("text/plain", "utf-8", null);
                    return new WebResourceResponse(mime(path), "utf-8", zip.getInputStream(e));
                } catch (IOException ex) {
                    return new WebResourceResponse("text/plain", "utf-8", null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if (url.startsWith(HOST)) {  // a link inside the book: follow it, keep the part number right
                    String path = normalise(r.getUrl().getPath().substring(1));
                    int i = chapters.indexOf(path);
                    if (i >= 0) index = i;
                    showWhere();
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));  // web links open in the browser
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                applyStyle();
                int at = restoreScroll;
                restoreScroll = 0;
                if (at > 0) {
                    v.evaluateJavascript("window.scrollTo(0," + at + "*document.body.scrollHeight/1000)", null);
                }
            }
        });
        body.addView(web);
    }

    private void loadChapter() {
        showWhere();
        if (plainHtml != null) web.loadDataWithBaseURL(HOST, plainHtml, "text/html", "utf-8", null);
        else web.loadUrl(HOST + Uri.encode(chapters.get(index), "/"));
        savePosition(0);
    }

    private void applyStyle() {
        if (web == null) return;
        String css = "html{font-size:" + zoom + "% !important}"
                + "body{max-width:44em;margin:0 auto !important;padding:1.2em 1.4em 4em !important;line-height:1.75;"
                + "font-family:sans-serif;" + (night ? "background:#1c1c1e !important;color:#e5e5ea !important;" : "")
                + "}img,svg{max-width:100% !important;height:auto !important}"
                + (night ? "a{color:#0a84ff}*{background-color:transparent !important;color:inherit !important}" : "");
        web.evaluateJavascript("(function(){var s=document.getElementById('fp-style');"
                + "if(!s){s=document.createElement('style');s.id='fp-style';(document.head||document.body).appendChild(s);}"
                + "s.textContent=" + JSONObject.quote(css) + ";})()", null);
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".xhtml") || p.endsWith(".xht")) return "application/xhtml+xml";
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ttf") || p.endsWith(".otf")) return "font/" + p.substring(p.length() - 3);
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // ---- AI and notes ----

    /** Selected text (EPUB / text), or "" */
    private void selection(java.util.function.Consumer<String> then) {
        if (web == null) {
            then.accept("");
            return;
        }
        web.evaluateJavascript("window.getSelection().toString()", v -> {
            String s = "";
            try {
                s = new JSONArray("[" + v + "]").getString(0).trim();
            } catch (Exception ignored) {
            }
            then.accept(s);
        });
    }

    private void askAi() {
        if (pdf != null) {  // a PDF page goes to Claude as a picture
            if (pageBitmap == null) return;
            File f = new File(getCacheDir(), "ask-page.jpg");
            try (OutputStream out = new FileOutputStream(f)) {
                Bitmap small = scaleDown(pageBitmap, 1568);
                small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (IOException e) {
                return;
            }
            startActivity(new Intent(this, AiActivity.class)
                    .putExtra(AiActivity.EXTRA_IMAGE, f.getAbsolutePath())
                    .putExtra(AiActivity.EXTRA_ASK, "Explain this page of “" + bookTitle + "” simply."));
            return;
        }
        selection(sel -> {
            if (!sel.isEmpty()) {
                open("Explain this passage from “" + bookTitle + "”:\n\n" + sel);
                return;
            }
            web.evaluateJavascript("document.body.innerText.substring(0,20000)", v -> {
                String text = "";
                try {
                    text = new JSONArray("[" + v + "]").getString(0).trim();
                } catch (Exception ignored) {
                }
                open("Summarise this part of “" + bookTitle + "” in a few lines:\n\n" + text);
            });
        });
    }

    private void open(String question) {
        startActivity(new Intent(this, AiActivity.class).putExtra(AiActivity.EXTRA_ASK, question));
    }

    private static Bitmap scaleDown(Bitmap b, int maxSide) {
        int side = Math.max(b.getWidth(), b.getHeight());
        if (side <= maxSide) return b;
        float k = maxSide / (float) side;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
    }

    private void note() {
        selection(sel -> {
            String place = bookTitle + ("pdf".equals(type) ? ", page " + (index + 1) : "");
            if (!sel.isEmpty()) {
                NotesActivity.addNote(this, "“" + sel + "”\n— " + place);
                Toast.makeText(this, "Saved in Notes", Toast.LENGTH_SHORT).show();
                return;
            }
            EditText field = new EditText(this);
            field.setHint("Your note about " + place);
            field.setMinLines(3);
            new AlertDialog.Builder(this).setTitle("Note").setView(field)
                    .setPositiveButton("Save", (d, w) -> {
                        String t = field.getText().toString().trim();
                        if (t.isEmpty()) return;
                        NotesActivity.addNote(this, t + "\n— " + place);
                        Toast.makeText(this, "Saved in Notes", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null).show();
        });
    }

    // ---- remember the place ----

    private void savePosition(int scroll) {
        BooksActivity.savePosition(this, uri, index, total, scroll);
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.evaluateJavascript("Math.round(1000*scrollY/Math.max(1,document.body.scrollHeight))", v -> {
                try {
                    savePosition(Integer.parseInt(v.trim()));
                } catch (NumberFormatException e) {
                    savePosition(0);
                }
            });
        } else if (pageScroll != null && pageBitmap != null) {
            savePosition(1000 * pageScroll.getScrollY() / Math.max(1, pageBitmap.getHeight()));
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.execute(() -> {  // after any page still being drawn
            try {
                if (pdf != null) pdf.close();
                if (fd != null) fd.close();
            } catch (IOException ignored) {
            }
        });
        worker.shutdown();
        try {
            if (zip != null) zip.close();
        } catch (IOException ignored) {
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}

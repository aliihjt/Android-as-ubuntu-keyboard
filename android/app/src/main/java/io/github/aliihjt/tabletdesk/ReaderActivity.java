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
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Html;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
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
 * chapters in a web view (text size, night mode, read aloud, highlights,
 * double-tap a word to translate it). Select text to ask Claude about it or
 * keep it as a note. Remembers where you stopped and how long you read.
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

    // read aloud
    private TextToSpeech tts;
    private boolean ttsReady, listening, stopAtPartEnd;
    private List<String> paras = new ArrayList<>();
    private int sayAt;
    private int rate = 1;  // index in RATES
    private static final float[] RATES = {0.8f, 1f, 1.25f, 1.5f, 2f};
    private LinearLayout listenBar;
    private TextView playButton, rateButton, sleepButton;
    private final Runnable sleepStop = this::stopListening;
    private long readSince;

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
        content.addView(listenBar());
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
        where.setPadding(th.dp(8), 0, th.dp(2), 0);
        bar.addView(where);
        bar.addView(tool("▶", v -> go(index + 1)));
        // the rest scrolls sideways when the tablet is upright
        LinearLayout tools = new LinearLayout(this);
        tools.addView(tool("A−", v -> scale(-1)));
        tools.addView(tool("A+", v -> scale(1)));
        tools.addView(tool("🌙", v -> toggleNight()));
        tools.addView(tool("🔊 Listen", v -> startListening()));
        tools.addView(tool("🖍 Highlight", v -> highlight()));
        tools.addView(tool("🗒 Note", v -> note()));
        tools.addView(tool("✨ Ask Claude", v -> askAi()));
        tools.addView(tool("📑", v -> startActivity(new Intent(this, HighlightsActivity.class)
                .putExtra("uri", uri).putExtra("title", bookTitle))));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(tools);
        bar.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.setPadding(0, 0, 0, th.dp(8));
        return bar;
    }

    /** Shown while reading aloud: play / pause, paragraph back and forward, speed, sleep timer. */
    private View listenBar() {
        listenBar = new LinearLayout(this);
        listenBar.setGravity(Gravity.CENTER_VERTICAL);
        listenBar.setVisibility(View.GONE);
        TextView label = th.text("🔊 Reading aloud", 14, th.secondary, true);
        listenBar.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        listenBar.addView(tool("⏮", v -> speakFrom(Math.max(0, sayAt - 1))));
        playButton = tool("⏸", v -> {
            if (listening) pauseListening();
            else resumeListening();
        });
        listenBar.addView(playButton);
        listenBar.addView(tool("⏭", v -> speakFrom(Math.min(paras.size() - 1, sayAt + 1))));
        rateButton = tool("1×", v -> {
            rate = (rate + 1) % RATES.length;
            rateButton.setText(rateLabel());
            prefs.edit().putInt("tts_rate", rate).apply();
            if (tts != null) tts.setSpeechRate(RATES[rate]);
            if (listening) speakFrom(sayAt);
        });
        listenBar.addView(rateButton);
        sleepButton = tool("⏲", v -> sleepTimer());
        listenBar.addView(sleepButton);
        listenBar.addView(tool("✕", v -> stopListening()));
        listenBar.setPadding(0, 0, 0, th.dp(8));
        return listenBar;
    }

    private String rateLabel() {
        float r = RATES[rate];
        return (r == (int) r ? String.valueOf((int) r) : String.valueOf(r)) + "×";
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
        // paragraphs, so read aloud can follow along
        StringBuilder html = new StringBuilder("<html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width'></head><body dir='auto'>");
        for (String para : text.split("\\r?\\n\\s*\\n")) {
            if (para.trim().isEmpty()) continue;
            html.append("<p dir='auto' style='white-space:pre-wrap;margin:0 0 1em'>")
                    .append(Html.escapeHtml(para.replaceAll("^\\s*\\n|\\n\\s*$", ""))).append("</p>");
        }
        plainHtml = html.append("</body></html>").toString();
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
        web.addJavascriptInterface(new Bridge(), "FP");
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
                v.evaluateJavascript(TAP_WORD, null);
                applyHighlights();
                int at = restoreScroll;
                restoreScroll = 0;
                if (at > 0) {
                    v.evaluateJavascript("window.scrollTo(0," + at + "*document.body.scrollHeight/1000)", null);
                }
                if (listening) collectAndSpeak(false);  // turned the page while listening
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
                + (night ? "a{color:#0a84ff}*{background-color:transparent !important;color:inherit !important}" : "")
                + ".fp-say{background-color:rgba(10,132,255,.18) !important;border-radius:6px}"
                + ".fp-yellow{background-color:#ffe066 !important;color:#000 !important}"
                + ".fp-green{background-color:#9be58f !important;color:#000 !important}"
                + ".fp-blue{background-color:#8fc8ff !important;color:#000 !important}"
                + ".fp-pink{background-color:#ffa3c2 !important;color:#000 !important}"
                + "::highlight(fp-yellow){background-color:#ffe066;color:#000}"
                + "::highlight(fp-green){background-color:#9be58f;color:#000}"
                + "::highlight(fp-blue){background-color:#8fc8ff;color:#000}"
                + "::highlight(fp-pink){background-color:#ffa3c2;color:#000}";
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
            ClaudeApp.ask(this, "Explain this page of “" + bookTitle + "” simply.", pageBitmap);
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
        ClaudeApp.ask(this, question, null);
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

    // ---- double-tap a word ----

    /** Double-tap a word: FP.word(word, its sentence). */
    private static final String TAP_WORD = "(function(){if(window.fpTap)return;window.fpTap=1;var last=0,lx=0,ly=0;"
            + "document.addEventListener('click',function(e){if(e.target.closest&&e.target.closest('a'))return;"
            + "var now=Date.now(),dbl=now-last<400&&Math.abs(e.clientX-lx)<30&&Math.abs(e.clientY-ly)<30;"
            + "last=dbl?0:now;lx=e.clientX;ly=e.clientY;if(!dbl)return;"
            + "var r=document.caretRangeFromPoint(e.clientX,e.clientY);if(!r||r.startContainer.nodeType!=3)return;"
            + "var s=r.startContainer.nodeValue,o=r.startOffset,re=/[\\p{L}\\p{M}\\p{N}\\u200c'’-]/u,a=o,b=o;"
            + "while(a>0&&re.test(s[a-1]))a--;while(b<s.length&&re.test(s[b]))b++;var w=s.substring(a,b);if(!w)return;"
            + "var p=r.startContainer.parentNode.innerText||s,i=p.indexOf(w),j=i+w.length;"
            + "var st=Math.max(p.lastIndexOf('.',i),p.lastIndexOf('!',i),p.lastIndexOf('?',i),p.lastIndexOf('؟',i))+1;"
            + "var en=['.','!','?','؟'].map(function(c){return p.indexOf(c,j)}).filter(function(x){return x>=0});"
            + "en=en.length?Math.min.apply(null,en)+1:p.length;"
            + "FP.word(w,i<0?'':p.substring(st,en).trim().substring(0,400));},true);})()";

    public final class Bridge {  // public: the web view calls it by reflection
        @JavascriptInterface
        public void word(String word, String sentence) {
            main.post(() -> {
                if (!isDestroyed()) WordLookup.show(ReaderActivity.this, word, sentence == null ? "" : sentence, bookTitle);
            });
        }
    }

    // ---- highlights ----

    /** Marks text in the page, ignoring spaces and line breaks; CSS highlights when the web view has them. */
    private static final String MARK = "(function(list){var api=window.CSS&&CSS.highlights&&window.Highlight,sets={};"
            + "function map(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,nodes=[],all='',at=[];"
            + "while(n=w.nextNode()){var pn=n.parentNode;if(pn&&/^(SCRIPT|STYLE)$/.test(pn.nodeName))continue;var s=n.nodeValue;"
            + "for(var i=0;i<s.length;i++){if(/\\s/.test(s[i]))continue;all+=s[i];at.push([nodes.length,i]);}nodes.push(n);}"
            + "return{all:all,at:at,nodes:nodes};}"
            + "function find(q){q=q.replace(/\\s+/g,'');if(!q)return null;var m=map(),k=m.all.indexOf(q);if(k<0)return null;"
            + "var a=m.at[k],b=m.at[k+q.length-1],r=document.createRange();"
            + "r.setStart(m.nodes[a[0]],a[1]);r.setEnd(m.nodes[b[0]],b[1]+1);return r;}"
            + "function wrap(r,c){var root=r.commonAncestorContainer;if(root.nodeType==3)root=root.parentNode;"
            + "var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null),n,ts=[];while(n=w.nextNode())if(r.intersectsNode(n))ts.push(n);"
            + "var sc=r.startContainer,so=r.startOffset,ec=r.endContainer,eo=r.endOffset;"
            + "ts.forEach(function(t){var s=t===sc?so:0,e=t===ec?eo:t.nodeValue.length;if(e<=s)return;var mid=t;"
            + "if(s>0){mid=t.splitText(s);e-=s;}if(e<mid.nodeValue.length)mid.splitText(e);"
            + "var m=document.createElement('mark');m.className='fp-'+c;mid.parentNode.insertBefore(m,mid);m.appendChild(mid);});}"
            + "if(api)['yellow','green','blue','pink'].forEach(function(c){CSS.highlights.delete('fp-'+c)});"
            + "list.forEach(function(h){var r=find(h.t);if(!r)return;if(api)(sets[h.c]=sets[h.c]||[]).push(r);else wrap(r,h.c);});"
            + "if(api)for(var c in sets)CSS.highlights.set('fp-'+c,new Highlight(...sets[c]));})";

    private void applyHighlights() {
        if (web == null) return;
        JSONArray list = new JSONArray();
        for (ReadingData.Highlight h : ReadingData.highlights(this, uri)) {
            if (h.part != index) continue;
            try {
                list.put(new JSONObject().put("t", h.text).put("c", h.color));
            } catch (org.json.JSONException ignored) {
            }
        }
        if (list.length() > 0) web.evaluateJavascript(MARK + "(" + list + ")", null);
    }

    private void highlight() {
        if (web == null) {
            Toast.makeText(this, "Highlights work in EPUB and text books. In a PDF, use 🗒 Note.", Toast.LENGTH_LONG).show();
            return;
        }
        selection(sel -> {
            if (sel.isEmpty()) {
                Toast.makeText(this, "Select text first: long-press a word, then drag.", Toast.LENGTH_LONG).show();
                return;
            }
            String text = sel.length() > 5000 ? sel.substring(0, 5000) : sel;
            String[] color = {prefs.getString("hl_color", "yellow")};
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(th.dp(20), th.dp(8), th.dp(20), 0);
            box.addView(th.segmented(new String[][]{{"yellow", "🟡 Yellow"}, {"green", "🟢 Green"},
                    {"blue", "🔵 Blue"}, {"pink", "🩷 Pink"}}, color[0], c -> color[0] = c));
            EditText note = new EditText(this);
            note.setHint("Add a note (optional)");
            note.setMinLines(2);
            box.addView(note, th.fullWidth(10));
            new AlertDialog.Builder(this).setTitle("Highlight")
                    .setMessage(text.length() > 200 ? text.substring(0, 200) + "…" : text)
                    .setView(box)
                    .setPositiveButton("Save", (d, w) -> {
                        prefs.edit().putString("hl_color", color[0]).apply();
                        ReadingData.addHighlight(this, uri, bookTitle, index, text, color[0],
                                note.getText().toString().trim());
                        web.evaluateJavascript("window.getSelection().removeAllRanges()", null);
                        applyHighlights();
                    })
                    .setNegativeButton("Cancel", null).show();
        });
    }

    // ---- read aloud ----

    /** Numbers the paragraphs and returns their text, and the first one on screen. */
    private static final String PARAS = "(function(){var q='p,h1,h2,h3,h4,h5,h6,li,blockquote,pre,dd,dt,td,figcaption,div';"
            + "var els=document.body.querySelectorAll(q),out=[],start=-1;"
            + "for(var i=0;i<els.length;i++){var e=els[i];if(e.querySelector(q))continue;var t=(e.innerText||'').trim();if(!t)continue;"
            + "e.setAttribute('data-fp',out.length);if(start<0&&e.getBoundingClientRect().bottom>4)start=out.length;out.push(t);}"
            + "if(!out.length){var b=(document.body.innerText||'').trim();if(b){document.body.setAttribute('data-fp',0);out.push(b);}}"
            + "return JSON.stringify({p:out,s:Math.max(0,start)});})()";

    private void startListening() {
        if (web == null) {
            Toast.makeText(this, "Read aloud works in EPUB and text books.", Toast.LENGTH_LONG).show();
            return;
        }
        listening = true;
        listenBar.setVisibility(View.VISIBLE);
        playButton.setText("⏸");
        if (tts == null) {
            rate = Math.max(0, Math.min(RATES.length - 1, prefs.getInt("tts_rate", 1)));
            rateButton.setText(rateLabel());
            tts = new TextToSpeech(this, status -> main.post(() -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (!ttsReady) {
                    Toast.makeText(this, "No text-to-speech engine. Install one from the app store "
                            + "(Google Speech Services, or eSpeak NG / RHVoice for Persian).", Toast.LENGTH_LONG).show();
                    stopListening();
                    return;
                }
                tts.setSpeechRate(RATES[rate]);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        int k = Integer.parseInt(id.split(":")[0]);
                        main.post(() -> saying(k));
                    }

                    @Override
                    public void onDone(String id) {
                        if (id.endsWith(":end")) main.post(() -> partFinished());
                    }

                    @Override
                    public void onError(String id) {
                        onDone(id);
                    }
                });
                if (listening) collectAndSpeak(true);
            }));
            return;
        }
        if (ttsReady) collectAndSpeak(true);
    }

    private void collectAndSpeak(boolean fromScreen) {
        if (!ttsReady) return;
        web.evaluateJavascript(PARAS, v -> {
            try {
                JSONObject o = new JSONObject(new JSONArray("[" + v + "]").getString(0));
                JSONArray p = o.getJSONArray("p");
                paras = new ArrayList<>();
                for (int i = 0; i < p.length(); i++) paras.add(p.getString(i));
                speakFrom(fromScreen ? o.optInt("s") : 0);
            } catch (Exception e) {
                paras = new ArrayList<>();
                partFinished();
            }
        });
    }

    private void speakFrom(int from) {
        if (tts == null || !ttsReady || paras.isEmpty()) return;
        listening = true;
        playButton.setText("⏸");
        sayAt = Math.max(0, Math.min(from, paras.size() - 1));
        StringBuilder sample = new StringBuilder();
        for (int i = sayAt; i < paras.size() && sample.length() < 1500; i++) sample.append(paras.get(i));
        Locale lang = ReadingData.persian(sample.toString()) ? new Locale("fa", "IR")
                : "fa".equals(Locale.getDefault().getLanguage()) ? Locale.US : Locale.getDefault();
        if (tts.isLanguageAvailable(lang) < TextToSpeech.LANG_AVAILABLE && "fa".equals(lang.getLanguage())) {
            Toast.makeText(this, "No Persian voice yet: install one (e.g. eSpeak NG or RHVoice) and pick it in "
                    + "Android Settings › Text-to-speech.", Toast.LENGTH_LONG).show();
        }
        tts.setLanguage(lang);
        int max = Math.max(500, TextToSpeech.getMaxSpeechInputLength() - 100);
        boolean first = true;
        for (int k = sayAt; k < paras.size(); k++) {
            String t = paras.get(k);
            List<String> chunks = new ArrayList<>();
            while (t.length() > max) {  // split long paragraphs at a sentence or a space
                int cut = Math.max(t.lastIndexOf(". ", max), t.lastIndexOf(' ', max));
                if (cut < max / 2) cut = max;
                chunks.add(t.substring(0, cut + 1));
                t = t.substring(cut + 1);
            }
            chunks.add(t);
            for (int j = 0; j < chunks.size(); j++) {
                boolean last = k == paras.size() - 1 && j == chunks.size() - 1;
                tts.speak(chunks.get(j), first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null,
                        k + ":" + j + (last ? ":end" : ""));
                first = false;
            }
        }
    }

    private void saying(int k) {
        sayAt = k;
        if (web != null) web.evaluateJavascript("(function(){var o=document.querySelector('.fp-say');"
                + "if(o)o.classList.remove('fp-say');var e=document.querySelector('[data-fp=\"" + k + "\"]');"
                + "if(e){e.classList.add('fp-say');e.scrollIntoView({block:'center',behavior:'smooth'});}})()", null);
    }

    /** The end of this part: go on to the next one, unless the sleep timer says stop. */
    private void partFinished() {
        if (!listening) return;
        if (stopAtPartEnd || index + 1 >= total) {
            stopListening();
            return;
        }
        go(index + 1);  // onPageFinished carries on reading
    }

    private void pauseListening() {
        listening = false;
        if (tts != null) tts.stop();
        playButton.setText("▶");
    }

    private void resumeListening() {
        if (paras.isEmpty()) collectAndSpeak(true);
        else speakFrom(sayAt);
    }

    private void stopListening() {
        listening = false;
        stopAtPartEnd = false;
        main.removeCallbacks(sleepStop);
        if (tts != null) tts.stop();
        if (listenBar != null) {
            listenBar.setVisibility(View.GONE);
            sleepButton.setText("⏲");
        }
        if (web != null) web.evaluateJavascript("(function(){var o=document.querySelector('.fp-say');"
                + "if(o)o.classList.remove('fp-say');})()", null);
    }

    private void sleepTimer() {
        String[] names = {"Off", "15 minutes", "30 minutes", "60 minutes", "End of this part"};
        int[] minutes = {0, 15, 30, 60, -1};
        new AlertDialog.Builder(this).setTitle("Stop reading after")
                .setItems(names, (d, w) -> {
                    main.removeCallbacks(sleepStop);
                    stopAtPartEnd = minutes[w] < 0;
                    if (minutes[w] > 0) main.postDelayed(sleepStop, minutes[w] * 60_000L);
                    sleepButton.setText(minutes[w] == 0 ? "⏲" : minutes[w] < 0 ? "⏲ part" : "⏲ " + minutes[w] + "m");
                }).show();
    }

    // ---- remember the place ----

    private void savePosition(int scroll) {
        BooksActivity.savePosition(this, uri, index, total, scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        readSince = SystemClock.elapsedRealtime();
    }

    @Override
    protected void onPause() {
        ReadingData.addSeconds(this, (SystemClock.elapsedRealtime() - readSince) / 1000);
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
        main.removeCallbacks(sleepStop);
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}

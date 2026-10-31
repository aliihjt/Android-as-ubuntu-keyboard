package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Books: your PDF, EPUB and text files as a library with covers and reading
 * progress. The files stay where they are; Fedora Pad only remembers them.
 */
public class BooksActivity extends Page {
    private static final String LIBRARY = "books";
    private static final int PICK = 51;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout grid;

    static final class Book {
        final String uri, title, type;
        final int position, total;

        Book(JSONObject o) {
            uri = o.optString("uri");
            title = o.optString("title");
            type = o.optString("type");
            position = o.optInt("pos", 0);
            total = o.optInt("total", 0);
        }
    }

    @Override
    protected String title() {
        return "Books";
    }

    @Override
    protected void build() {
        LinearLayout top = new LinearLayout(this);
        top.addView(th.primaryButton("＋  Add books", v -> pick()), th.weighted(0));
        top.addView(th.secondaryButton("📚 Get free books", v -> startActivity(new Intent(this, GetBooksActivity.class))),
                th.weighted(10));
        add(top, 6);
        add(th.caption("PDF, EPUB and text files from the tablet, a USB stick or the cloud. "
                + "In a book: select text to ask Claude about it or save it as a note."), 8);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        add(grid, 18);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();  // progress changes after reading
    }

    // ---- library ----

    static List<Book> library(Context c) {
        List<Book> out = new ArrayList<>();
        JSONArray all = raw(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null) out.add(new Book(o));
        }
        return out;
    }

    private static JSONArray raw(Context c) {
        try {
            return new JSONArray(prefs(c).getString(LIBRARY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    /** Where the reader is: page (PDF) or chapter (EPUB), scroll 0..1000 inside it. */
    static void savePosition(Context c, String uri, int pos, int total, int scroll) {
        JSONArray all = raw(c);
        try {
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                if (!o.optString("uri").equals(uri)) continue;
                o.put("pos", pos).put("total", total).put("scroll", scroll).put("opened", System.currentTimeMillis());
                // most recently read first
                all.remove(i);
                JSONArray sorted = new JSONArray().put(o);
                for (int j = 0; j < all.length(); j++) sorted.put(all.get(j));
                prefs(c).edit().putString(LIBRARY, sorted.toString()).apply();
                return;
            }
        } catch (JSONException ignored) {
        }
    }

    static int savedScroll(Context c, String uri) {
        JSONArray all = raw(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optString("uri").equals(uri)) return o.optInt("scroll", 0);
        }
        return 0;
    }

    /** Adds a book (e.g. one downloaded from Get Books) unless it is already there. */
    static void addBook(Context c, String uri, String title, String type) {
        JSONArray all = raw(c);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && uri.equals(o.optString("uri"))) return;
        }
        try {
            all.put(new JSONObject().put("uri", uri).put("title", title).put("type", type));
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(LIBRARY, all.toString()).apply();
    }

    static void open(Context c, Book b) {
        c.startActivity(new Intent(c, ReaderActivity.class)
                .putExtra("uri", b.uri).putExtra("title", b.title).putExtra("type", b.type)
                .putExtra("pos", b.position));
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/epub+zip", "text/plain"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        JSONArray all = raw(this);
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            String name = displayName(u);
            String lower = name.toLowerCase(Locale.ROOT);
            String mime = String.valueOf(getContentResolver().getType(u));
            String type = lower.endsWith(".pdf") || mime.contains("pdf") ? "pdf"
                    : lower.endsWith(".epub") || mime.contains("epub") ? "epub" : "txt";
            String title = name.replaceAll("\\.(pdf|epub|txt)$", "").replace('_', ' ');
            boolean known = false;
            for (int i = 0; i < all.length(); i++) known |= u.toString().equals(all.optJSONObject(i).optString("uri"));
            if (known) continue;
            try {
                all.put(new JSONObject().put("uri", u.toString()).put("title", title).put("type", type));
            } catch (JSONException ignored) {
            }
        }
        prefs(this).edit().putString(LIBRARY, all.toString()).apply();
        refresh();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String s = u.getLastPathSegment();
        return s == null ? "Book" : s;
    }

    // ---- shelf ----

    private void refresh() {
        grid.removeAllViews();
        List<Book> books = library(this);
        if (books.isEmpty()) {
            grid.addView(th.caption("No books yet. Tap “Add books”."));
            return;
        }
        int columns = Math.min(820, getResources().getConfiguration().screenWidthDp) >= 640 ? 4 : 3;
        LinearLayout row = null;
        for (int i = 0; i < books.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 18));
            }
            row.addView(cover(books.get(i)), th.weighted(i % columns == 0 ? 0 : 14));
        }
        int rest = books.size() % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new View(this), th.weighted(14));
    }

    private View cover(Book b) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        FrameLayout art = new FrameLayout(this);
        int color = b.type.equals("pdf") ? Theme.RED : b.type.equals("epub") ? Theme.INDIGO : Theme.GRAY;
        art.setBackground(Theme.round(color, th.dp(10)));
        art.setClipToOutline(true);
        art.setElevation(th.dp(4));
        TextView name = th.text(b.title, 15, Color.WHITE, true);
        name.setPadding(th.dp(10), th.dp(12), th.dp(10), th.dp(12));
        name.setMaxLines(5);
        art.addView(name);
        TextView kind = th.text(b.type.toUpperCase(Locale.ROOT), 11, 0xCCFFFFFF, true);
        kind.setPadding(th.dp(10), 0, 0, th.dp(8));
        art.addView(kind, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        ImageView page = new ImageView(this);
        page.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        cell.addView(art, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, th.dp(190)));
        if (b.type.equals("pdf")) pdfCover(b, page);

        String progress = b.total > 0 ? (b.type.equals("pdf") ? "Page " : "Chapter ") + (b.position + 1) + " of " + b.total
                : "Not started";
        TextView under = th.caption(progress);
        under.setSingleLine(true);
        cell.addView(under, th.fullWidth(6));
        th.click(cell, v -> open(this, b));
        cell.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle(b.title)
                    .setItems(new String[]{"Remove from Books"}, (d, w) -> remove(b)).show();
            return true;
        });
        return cell;
    }

    /** First page of the PDF as the cover, drawn in the background. */
    private void pdfCover(Book b, ImageView into) {
        new Thread(() -> {
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(Uri.parse(b.uri), "r");
                 PdfRenderer r = new PdfRenderer(fd)) {
                PdfRenderer.Page p = r.openPage(0);
                int w = th.dp(150), h = Math.round(w * (float) p.getHeight() / p.getWidth());
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
                main.post(() -> into.setImageBitmap(bmp));
            } catch (Exception ignored) {  // moved or deleted: the coloured cover stays
            }
        }, "cover").start();
    }

    private void remove(Book b) {
        JSONArray all = raw(this), kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && !o.optString("uri").equals(b.uri)) kept.put(o);
        }
        prefs(this).edit().putString(LIBRARY, kept.toString()).apply();
        Uri u = Uri.parse(b.uri);
        if ("file".equals(u.getScheme()) && u.getPath() != null && u.getPath().startsWith(getFilesDir().getPath())) {
            new java.io.File(u.getPath()).delete();  // a book downloaded in Get Books
        } else {
            try {
                getContentResolver().releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        refresh();
    }
}

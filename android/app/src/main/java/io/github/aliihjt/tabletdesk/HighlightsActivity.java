package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Highlights and notes, per book. Export a book's as Markdown: to Downloads,
 * to the PC, to Notes, or share it to any app (Obsidian, Keep…).
 */
public class HighlightsActivity extends Page {
    private String only;  // one book's uri, or null for every book
    private LinearLayout list;

    @Override
    protected String title() {
        return "Highlights";
    }

    @Override
    protected void build() {
        only = getIntent().getStringExtra("uri");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        add(list, 6);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        Map<String, List<ReadingData.Highlight>> books = new LinkedHashMap<>();
        for (ReadingData.Highlight h : ReadingData.highlights(this, only)) {
            List<ReadingData.Highlight> l = books.get(h.book);
            if (l == null) books.put(h.book, l = new java.util.ArrayList<>());
            l.add(h);
        }
        if (books.isEmpty()) {
            list.addView(th.caption("No highlights yet. In a book, select text (long-press and drag), "
                    + "then tap 🖍 Highlight. Add a note to it if you like."));
            return;
        }
        for (Map.Entry<String, List<ReadingData.Highlight>> e : books.entrySet()) {
            String book = e.getKey(), name = e.getValue().get(0).title;
            LinearLayout head = new LinearLayout(this);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView t = th.text(name, 20, th.text, true);
            head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(th.secondaryButton("⇪ Export", v -> export(book, name)));
            list.addView(head, th.fullWidth(list.getChildCount() == 0 ? 0 : 26));
            list.addView(th.caption(e.getValue().size() + " highlight" + (e.getValue().size() == 1 ? "" : "s")), th.fullWidth(2));
            for (ReadingData.Highlight h : e.getValue()) list.addView(card(h), th.fullWidth(10));
        }
    }

    private View card(ReadingData.Highlight h) {
        LinearLayout c = th.card();
        LinearLayout row = new LinearLayout(this);
        View bar = new View(this);
        bar.setBackground(Theme.round(ReadingData.paint(h.color), th.dp(3)));
        row.addView(bar, new LinearLayout.LayoutParams(th.dp(5), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(th.dp(12), 0, 0, 0);
        TextView quote = th.body(h.text);
        quote.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        texts.addView(quote);
        if (!h.note.isEmpty()) {
            TextView note = th.text("🗒 " + h.note, 15, th.accent, false);
            note.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            texts.addView(note, th.fullWidth(8));
        }
        texts.addView(th.caption("Part " + (h.part + 1)), th.fullWidth(6));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(row);
        c.setPadding(th.dp(14), th.dp(12), th.dp(14), th.dp(12));
        th.click(c, v -> actions(h));
        return c;
    }

    private void actions(ReadingData.Highlight h) {
        new AlertDialog.Builder(this).setTitle(h.title)
                .setItems(new String[]{"📖 Open in the book", "✨ Ask Claude about it", "🗒 Copy to Notes",
                        "📋 Copy", "🗑 Delete"}, (d, w) -> {
                    switch (w) {
                        case 0:
                            openBook(h);
                            break;
                        case 1:
                            ClaudeApp.ask(this, "From “" + h.title + "”:\n\n" + h.text
                                    + (h.note.isEmpty() ? "" : "\n\nMy note: " + h.note)
                                    + "\n\nExplain this passage and why it matters.", null);
                            break;
                        case 2:
                            NotesActivity.addNote(this, "“" + h.text + "”\n— " + h.title
                                    + (h.note.isEmpty() ? "" : "\n\n" + h.note));
                            toast("Saved in Notes");
                            break;
                        case 3:
                            getSystemService(android.content.ClipboardManager.class)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("highlight", h.text));
                            toast("Copied");
                            break;
                        default:
                            ReadingData.removeHighlight(this, h.time);
                            refresh();
                    }
                }).show();
    }

    private void openBook(ReadingData.Highlight h) {
        for (BooksActivity.Book b : BooksActivity.library(this)) {
            if (b.uri.equals(h.book)) {
                startActivity(new Intent(this, ReaderActivity.class).putExtra("uri", b.uri)
                        .putExtra("title", b.title).putExtra("type", b.type).putExtra("pos", h.part));
                return;
            }
        }
        toast("That book is no longer in Books.");
    }

    // ---- export ----

    private void export(String book, String name) {
        String md = ReadingData.markdown(this, book, name);
        String file = name.replaceAll("[\\\\/:*?\"<>|]", " ").trim() + " - highlights.md";
        new AlertDialog.Builder(this).setTitle("Export “" + name + "”")
                .setItems(new String[]{"⬇️ Save to Downloads", "💻 Send to the PC", "🗒 Copy to Notes",
                        "📤 Share (Obsidian, Keep, email…)"}, (d, w) -> {
                    if (w == 0) saveToDownloads(file, md);
                    else if (w == 1) sendToPc(file, md);
                    else if (w == 2) {
                        NotesActivity.addNote(this, md);
                        toast("Saved in Notes");
                    } else {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, name + " – highlights")
                                .putExtra(Intent.EXTRA_TEXT, md), "Share highlights"));
                    }
                }).show();
    }

    private void saveToDownloads(String file, String md) {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Saving needs Android 10. Use Share or Send to the PC.");
            return;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, file);
        v.put(MediaStore.Downloads.MIME_TYPE, "text/markdown");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fedora Pad");
        try {
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (OutputStream out = getContentResolver().openOutputStream(u)) {
                out.write(md.getBytes(StandardCharsets.UTF_8));
            }
            toast("Saved in Downloads › Fedora Pad");
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
        }
    }

    private void sendToPc(String file, String md) {
        if (!link.isConnected()) {
            toast("Not connected to the PC");
            return;
        }
        new Thread(() -> {
            try {
                byte[] all = md.getBytes(StandardCharsets.UTF_8);
                String id = UUID.randomUUID().toString();
                int chunk = 48 * 1024;
                for (int at = 0; at == 0 || at < all.length; at += chunk) {
                    int len = Math.min(chunk, all.length - at);
                    link.sendFileChunk(id, file, Base64.encodeToString(all, at, len, Base64.NO_WRAP), at + len >= all.length);
                }
                runOnUiThread(() -> toast("Sent to the PC › Downloads › Fedora Pad"));
            } catch (InterruptedException e) {
                runOnUiThread(() -> toast("Couldn't send"));
            }
        }, "send-highlights").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

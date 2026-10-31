package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The calm home screen for reading (Settings › Home screen › Reader): a big
 * clock, “Continue reading”, today's goal and streak, reading tools, your
 * pinned apps and Claude. One tap switches back to the desktop.
 */
final class ReaderHome {
    private final Activity a;
    private final Theme th;
    private final Runnable switchToDesktop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout column;
    private TextView clock, date;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
            date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now));
            handler.postDelayed(this, 15_000);
        }
    };

    ReaderHome(Activity a, Theme th, Runnable switchToDesktop) {
        this.a = a;
        this.th = th;
        this.switchToDesktop = switchToDesktop;
        column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        int side = th.dp(22);
        column.setPadding(side, th.dp(18), side, th.dp(28));
    }

    View view() {
        ScrollView scroll = new ScrollView(a);
        FrameLayout center = new FrameLayout(a);
        int max = th.dp(720);
        int width = Math.min(max, a.getResources().getDisplayMetrics().widthPixels);
        center.addView(column, new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));
        scroll.addView(center);
        return scroll;
    }

    void resume() {
        build();
        handler.post(tick);
    }

    void pause() {
        handler.removeCallbacks(tick);
    }

    private void build() {
        column.removeAllViews();
        column.addView(top());
        column.addView(continueCard(), th.fullWidth(22));
        column.addView(todayCard(), th.fullWidth(14));
        column.addView(tiles(), th.fullWidth(22));
        View apps = pinned();
        if (apps != null) column.addView(apps, th.fullWidth(22));
        TextView ai = th.primaryButton("✨  Ask Claude", v -> askClaude());
        ai.setBackground(th.pressable(0xFFD97757, th.dp(14)));
        ai.setTextSize(17 * Ui.scale);
        column.addView(ai, th.fullWidth(24));
    }

    private TextView shadowed(String s, float sp, boolean bold) {
        TextView t = th.text(s, sp, Color.WHITE, bold);
        t.setShadowLayer(th.dp(4), 0, th.dp(1), 0x99000000);
        return t;
    }

    // ---- clock and the few buttons ----

    private View top() {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = shadowed("", 64, false);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        left.addView(clock);
        date = shadowed("", 17, true);
        left.addView(date);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(roundButton("🔍", () -> a.startActivity(new Intent(a, SpotlightActivity.class))));
        row.addView(roundButton("⚙️", () -> a.startActivity(new Intent(a, SettingsActivity.class))));
        row.addView(roundButton("🖥", switchToDesktop));
        return row;
    }

    private View roundButton(String symbol, Runnable r) {
        TextView b = th.text(symbol, 20, Color.WHITE, false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(th.frosted(th.dp(22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(44), th.dp(44));
        lp.leftMargin = th.dp(10);
        b.setLayoutParams(lp);
        th.click(b, v -> r.run());
        return b;
    }

    // ---- continue reading ----

    private View continueCard() {
        LinearLayout card = th.card();
        card.setPadding(th.dp(16), th.dp(16), th.dp(16), th.dp(16));
        List<BooksActivity.Book> books = BooksActivity.library(a);
        if (books.isEmpty()) {
            card.addView(th.text("Start a book", 20, th.text, true));
            card.addView(th.caption("Add your PDFs and EPUBs, or download free classics and Persian poetry."), th.fullWidth(6));
            LinearLayout b = new LinearLayout(a);
            b.addView(th.primaryButton("＋ Add books", v -> a.startActivity(new Intent(a, BooksActivity.class))), th.weighted(0));
            b.addView(th.secondaryButton("📚 Get free books", v -> a.startActivity(new Intent(a, GetBooksActivity.class))),
                    th.weighted(10));
            card.addView(b, th.fullWidth(14));
            return card;
        }
        BooksActivity.Book book = books.get(0);  // the most recently read
        LinearLayout row = new LinearLayout(a);
        FrameLayout cover = new FrameLayout(a);
        int color = book.type.equals("pdf") ? Theme.RED : book.type.equals("epub") ? Theme.INDIGO : Theme.GRAY;
        cover.setBackground(Theme.round(color, th.dp(8)));
        cover.setClipToOutline(true);
        cover.setElevation(th.dp(3));
        TextView name = th.text(book.title, 12, Color.WHITE, true);
        name.setPadding(th.dp(8), th.dp(8), th.dp(8), th.dp(8));
        name.setMaxLines(5);
        cover.addView(name);
        ImageView page = new ImageView(a);
        page.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(page, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (book.type.equals("pdf")) pdfCover(book, page);
        row.addView(cover, new LinearLayout.LayoutParams(th.dp(92), th.dp(130)));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(th.dp(16), 0, 0, 0);
        texts.addView(th.caption("CONTINUE READING"));
        TextView title = th.text(book.title, 20, th.text, true);
        title.setMaxLines(2);
        texts.addView(title, th.fullWidth(4));
        String where = book.total > 0 ? (book.type.equals("pdf") ? "Page " : "Part ") + (book.position + 1)
                + " of " + book.total : "Not started";
        texts.addView(th.caption(where), th.fullWidth(4));
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(book.total > 0 ? (book.position + 1) * 1000 / book.total : 0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(th.accent));
        texts.addView(bar, th.fullWidth(8));
        texts.addView(th.primaryButton("📖  Continue", v -> BooksActivity.open(a, book)), th.fullWidth(10));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        th.click(cover, v -> BooksActivity.open(a, book));
        return card;
    }

    private void pdfCover(BooksActivity.Book b, ImageView into) {
        new Thread(() -> {
            try (ParcelFileDescriptor fd = a.getContentResolver().openFileDescriptor(Uri.parse(b.uri), "r");
                 PdfRenderer r = new PdfRenderer(fd)) {
                PdfRenderer.Page p = r.openPage(0);
                int w = th.dp(92), h = Math.round(w * (float) p.getHeight() / p.getWidth());
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p.close();
                handler.post(() -> into.setImageBitmap(bmp));
            } catch (Exception ignored) {  // moved or deleted: the coloured cover stays
            }
        }, "cover").start();
    }

    // ---- today's goal, streak, week ----

    private View todayCard() {
        LinearLayout card = th.card();
        card.setPadding(th.dp(16), th.dp(16), th.dp(16), th.dp(16));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int goal = ReadingData.goal(a), today = ReadingData.minutes(a, 0), streak = ReadingData.streak(a);
        Ring ring = new Ring(a, Math.min(1f, today / (float) goal), th.accent, th.fill);
        row.addView(ring, new LinearLayout.LayoutParams(th.dp(84), th.dp(84)));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(th.dp(16), 0, 0, 0);
        texts.addView(th.caption("TODAY"));
        texts.addView(th.text(today + " of " + goal + " min", 20, th.text, true), th.fullWidth(2));
        texts.addView(th.caption(today >= goal ? "🎉 Goal reached!" : (goal - today) + " min to go"), th.fullWidth(2));
        texts.addView(th.text(streak > 0 ? "🔥 " + streak + "-day streak" : "Read today to start a streak",
                15, streak > 0 ? Theme.ORANGE : th.secondary, true), th.fullWidth(6));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(week(goal));
        card.addView(row);
        th.click(card, v -> chooseGoal());
        return card;
    }

    /** Seven small bars, today on the right; full colour when the goal was met. */
    private View week(int goal) {
        LinearLayout bars = new LinearLayout(a);
        bars.setGravity(Gravity.BOTTOM);
        int[] w = ReadingData.week(a);
        for (int i = 0; i < 7; i++) {
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            View bar = new View(a);
            float f = Math.min(1f, w[i] / (float) goal);
            bar.setBackground(Theme.round(w[i] >= goal ? th.accent : w[i] > 0 ? 0x8C0A84FF : th.fill, th.dp(3)));
            col.addView(bar, new LinearLayout.LayoutParams(th.dp(10), Math.max(th.dp(4), Math.round(th.dp(56) * f))));
            TextView day = th.caption(ReadingData.weekdayLetter(6 - i));
            day.setTextSize(11);
            col.addView(day);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(16), th.dp(76));
            lp.leftMargin = th.dp(2);
            bars.addView(col, lp);
        }
        return bars;
    }

    private void chooseGoal() {
        int[] options = {10, 15, 20, 30, 45, 60, 90};
        String[] names = new String[options.length];
        for (int i = 0; i < options.length; i++) names[i] = options[i] + " minutes a day";
        new AlertDialog.Builder(a).setTitle("Daily reading goal")
                .setItems(names, (d, w) -> {
                    ReadingData.setGoal(a, options[w]);
                    build();
                }).show();
    }

    static final class Ring extends View {
        private final float done;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        Ring(Context c, float done, int color, int trackColor) {
            super(c);
            this.done = done;
            float w = c.getResources().getDisplayMetrics().density * 10;
            for (Paint p : new Paint[]{track, arc}) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w);
                p.setStrokeCap(Paint.Cap.ROUND);
            }
            track.setColor(trackColor);
            arc.setColor(color);
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = arc.getStrokeWidth() / 2 + 1;
            box.set(pad, pad, getWidth() - pad, getHeight() - pad);
            c.drawArc(box, 0, 360, false, track);
            if (done > 0) c.drawArc(box, -90, Math.max(2, 360 * done), false, arc);
        }
    }

    // ---- tools and apps ----

    private View tiles() {
        Object[][] items = {
                {"📚", Theme.ORANGE, "Library", (Runnable) () -> a.startActivity(new Intent(a, BooksActivity.class))},
                {"⬇️", Theme.GREEN, "Get Books", (Runnable) () -> a.startActivity(new Intent(a, GetBooksActivity.class))},
                {"🖍", Theme.YELLOW, "Highlights", (Runnable) () -> a.startActivity(new Intent(a, HighlightsActivity.class))},
                {"⭐", Theme.INDIGO, "Words", (Runnable) () -> a.startActivity(new Intent(a, VocabActivity.class))},
                {"🎲", Theme.PINK, "فال حافظ", (Runnable) () -> a.startActivity(new Intent(a, PoemActivity.class)
                        .putExtra("mode", "faal"))},
                {"🗒", Theme.YELLOW, "Notes", (Runnable) () -> a.startActivity(new Intent(a, NotesActivity.class))},
                {"✍️", Theme.BLUE, "Handwriting", (Runnable) () -> a.startActivity(new Intent(a, SketchActivity.class))},
                {"▦", Theme.TEAL, "All Apps", (Runnable) () -> a.startActivity(new Intent(a, TabletAppsActivity.class))},
        };
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        int columns = 4;
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(a);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 18));
            }
            Object[] it = items[i];
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(th.iconChip((String) it[0], (Integer) it[1], 58));
            TextView label = shadowed((String) it[2], 13, true);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            cell.addView(label, th.fullWidth(6));
            Runnable open = (Runnable) it[3];
            th.click(cell, v -> open.run());
            row.addView(cell, th.weighted(0));
        }
        return grid;
    }

    private View pinned() {
        List<TabletApps.App> apps = TabletApps.pinnedApps(a);
        if (apps.isEmpty()) return null;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView head = shadowed("YOUR APPS", 13, true);
        head.setLetterSpacing(0.08f);
        box.addView(head);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(a);
                box.addView(row, th.fullWidth(12));
            }
            TabletApps.App app = apps.get(i);
            row.addView(TabletApps.icon(a, th, app, 58, Color.WHITE, () -> new AlertDialog.Builder(a)
                    .setTitle(app.label).setItems(new String[]{"Unpin from home screen"}, (d, w) -> {
                        TabletApps.setPinned(a, app, false);
                        build();
                    }).show()), th.weighted(0));
        }
        int rest = apps.size() % 4;
        for (int i = rest; rest != 0 && i < 4; i++) row.addView(new View(a), th.weighted(0));
        return box;
    }

    private void askClaude() {
        List<BooksActivity.Book> books = BooksActivity.library(a);
        if (books.isEmpty()) {
            ClaudeApp.open(a);
            return;
        }
        BooksActivity.Book b = books.get(0);
        new AlertDialog.Builder(a).setTitle("✨ Claude")
                .setItems(new String[]{"Talk about “" + b.title + "”", "Suggest my next book", "Just open Claude"},
                        (d, w) -> {
                            if (w == 0) {
                                ClaudeApp.ask(a, "I'm reading “" + b.title + "”"
                                        + (b.total > 0 ? " (part " + (b.position + 1) + " of " + b.total + ")" : "")
                                        + ". Without spoilers past where I am, remind me what has happened so far "
                                        + "and ask me what I think.", null);
                            } else if (w == 1) {
                                StringBuilder q = new StringBuilder("Suggest 5 books I'd enjoy next (some free on Project "
                                        + "Gutenberg, some Persian). My recent books:\n");
                                for (int i = 0; i < books.size() && i < 10; i++) q.append("- ").append(books.get(i).title).append('\n');
                                ClaudeApp.ask(a, q.toString(), null);
                            } else {
                                ClaudeApp.open(a);
                            }
                        }).show();
    }
}

package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Handwriting: write or draw with a finger or a stylus (with a stylus, the
 * palm is ignored). Pens, highlighter, eraser, undo, lined or grid paper.
 * Save to the tablet's Gallery, send to Fedora, or ask Claude to read it.
 */
public class SketchActivity extends Page {
    private static final int[] INKS = {0xFF1D1D1F, 0xFF007AFF, 0xFFFF3B30, 0xFF34C759, 0xFFAF52DE};
    private Ink ink;
    private final List<TextView> toolButtons = new ArrayList<>();

    @Override
    protected String title() {
        return "Handwriting";
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        ink = new Ink(this, th);
        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        for (int c : INKS) bar.addView(swatch(c, false));
        bar.addView(swatch(0x66FFCC00, true));  // highlighter
        bar.addView(tool("Eraser", v -> select(v, () -> ink.eraser = true)));
        bar.addView(tool("Thin", v -> ink.width = 3));
        bar.addView(tool("Thick", v -> ink.width = 8));
        bar.addView(tool("↶", v -> ink.undo()));
        bar.addView(tool("↷", v -> ink.redo()));
        bar.addView(tool("Paper", v -> ink.nextPaper()));
        bar.addView(tool("Clear", v -> new AlertDialog.Builder(this).setMessage("Clear the page?")
                .setPositiveButton("Clear", (d, w) -> ink.clear()).setNegativeButton("Cancel", null).show()));
        bar.addView(tool("📂 Open", v -> openSaved()));
        bar.addView(tool("💾 Save", v -> save(true)));
        bar.addView(tool("💻 To Fedora", v -> sendToFedora()));
        bar.addView(tool("✨ Ask Claude", v -> askAi()));
        barScroll.addView(bar);
        content.addView(barScroll);
        ink.setBackground(Theme.round(Color.WHITE, th.dp(12)));
        ink.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = th.dp(8);
        content.addView(ink, lp);
        String file = getIntent().getStringExtra("file");
        if (file != null) ink.load(new File(file));
    }

    private TextView tool(String label, View.OnClickListener l) {
        TextView b = th.secondaryButton(label, l);
        b.setTextSize(13 * Ui.scale);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, th.dp(38));
        lp.leftMargin = th.dp(6);
        b.setLayoutParams(lp);
        b.setPadding(th.dp(12), 0, th.dp(12), 0);
        return b;
    }

    private View swatch(int color, boolean highlighter) {
        TextView s = new TextView(this);
        s.setText(highlighter ? "H" : "");
        s.setGravity(Gravity.CENTER);
        s.setTextColor(0xFF1D1D1F);
        s.setBackground(Theme.round(color | (highlighter ? 0xFF000000 : 0), th.dp(16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(32), th.dp(32));
        lp.leftMargin = th.dp(6);
        s.setLayoutParams(lp);
        toolButtons.add(s);
        th.click(s, v -> select(v, () -> {
            ink.eraser = false;
            ink.color = color;
            ink.highlighter = highlighter;
        }));
        if (color == INKS[0]) mark(s);
        return s;
    }

    private void select(View v, Runnable apply) {
        apply.run();
        for (TextView t : toolButtons) t.setScaleX(1f);
        for (TextView t : toolButtons) t.setScaleY(1f);
        if (v instanceof TextView && toolButtons.contains(v)) mark((TextView) v);
    }

    private static void mark(TextView t) {
        t.setScaleX(1.25f);
        t.setScaleY(1.25f);
    }

    // ---- files ----

    private File folder() {
        File f = new File(getFilesDir(), "handwriting");
        f.mkdirs();
        return f;
    }

    /** Saves in Fedora Pad (to open again) and, if asked, in the Gallery. */
    private File save(boolean toGallery) {
        Bitmap page = ink.picture();
        String name = "Handwriting " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date()) + ".png";
        File f = ink.file != null ? ink.file : new File(folder(), name);
        try (OutputStream out = new FileOutputStream(f)) {
            page.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            toast("Couldn't save: " + e.getMessage());
            return null;
        }
        ink.file = f;
        if (toGallery) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Fedora Pad");
            }
            try {
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(u)) {
                        page.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
                toast("Saved (also in Gallery › Pictures › Fedora Pad)");
            } catch (Exception e) {
                toast("Saved in Fedora Pad");
            }
        }
        return f;
    }

    private void openSaved() {
        File[] files = folder().listFiles();
        if (files == null || files.length == 0) {
            toast("Nothing saved yet");
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName().replace(".png", "");
        new AlertDialog.Builder(this).setTitle("Open")
                .setItems(names, (d, which) -> ink.load(files[which]))
                .setPositiveButton("New page", (d, w) -> ink.newPage())
                .show();
    }

    private void sendToFedora() {
        if (!link.isConnected()) {
            toast("Not connected to Fedora");
            return;
        }
        File f = save(false);
        if (f == null) return;
        new Thread(() -> {
            try {
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                String id = UUID.randomUUID().toString();
                int chunk = 48 * 1024;
                for (int at = 0; at < all.length; at += chunk) {
                    int len = Math.min(chunk, all.length - at);
                    link.sendFileChunk(id, f.getName(), Base64.encodeToString(all, at, len, Base64.NO_WRAP),
                            at + len >= all.length);
                }
                runOnUiThread(() -> toast("Sent to Fedora › Downloads › Fedora Pad"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Couldn't send: " + e.getMessage()));
            }
        }, "send-sketch").start();
    }

    private void askAi() {
        ClaudeApp.ask(this, "Read my handwriting and type it out. If it is a question or "
                + "a maths problem, answer it too.", ink.picture());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- the drawing surface ----

    private static final class Stroke {
        final Path path = new Path();
        final Paint paint;

        Stroke(Paint p) {
            paint = p;
        }
    }

    static final class Ink extends View {
        private final Theme th;
        private final List<Stroke> strokes = new ArrayList<>(), undone = new ArrayList<>();
        private final Paint lines = new Paint();
        private Stroke current;
        private Bitmap background;  // an opened page
        private boolean stylusSeen;
        private float lastX, lastY;
        int color = INKS[0], width = 4, paper;  // paper: 0 lines, 1 grid, 2 blank
        boolean eraser, highlighter;
        File file;

        Ink(Context c, Theme th) {
            super(c);
            this.th = th;
            lines.setColor(0x1F007AFF);
            lines.setStrokeWidth(Math.max(1, th.dp(1)));
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        void nextPaper() {
            paper = (paper + 1) % 3;
            invalidate();
        }

        void undo() {
            if (strokes.isEmpty()) return;
            undone.add(strokes.remove(strokes.size() - 1));
            invalidate();
        }

        void redo() {
            if (undone.isEmpty()) return;
            strokes.add(undone.remove(undone.size() - 1));
            invalidate();
        }

        void clear() {
            strokes.clear();
            undone.clear();
            background = null;
            invalidate();
        }

        void newPage() {
            clear();
            file = null;
        }

        void load(File f) {
            clear();
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) return;
            background = b;
            file = f;
            paper = 2;  // the saved picture already has its paper
            invalidate();
        }

        /** The page as a picture: white paper, lines, ink. */
        Bitmap picture() {
            Bitmap b = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.WHITE);
            draw(c);
            return b;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (background != null) {
                c.drawBitmap(background, null, new android.graphics.Rect(0, 0, getWidth(),
                        getWidth() * background.getHeight() / background.getWidth()), null);
            }
            int step = th.dp(32);
            if (paper == 0) {
                for (int y = step * 2; y < getHeight(); y += step) c.drawLine(0, y, getWidth(), y, lines);
            } else if (paper == 1) {
                for (int y = step; y < getHeight(); y += step) c.drawLine(0, y, getWidth(), y, lines);
                for (int x = step; x < getWidth(); x += step) c.drawLine(x, 0, x, getHeight(), lines);
            }
            int layer = c.saveLayer(null, null);  // the eraser only removes ink, not the paper
            for (Stroke s : strokes) c.drawPath(s.path, s.paint);
            c.restoreToCount(layer);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int tool = e.getToolType(0);
            boolean pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
            if (pen) stylusSeen = true;
            else if (stylusSeen && tool == MotionEvent.TOOL_TYPE_FINGER) return true;  // palm rejection
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    boolean erase = eraser || tool == MotionEvent.TOOL_TYPE_ERASER;  // the pen's eraser end
                    if (erase) {
                        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                        p.setStrokeWidth(th.dp(width * 5));
                    } else {
                        p.setColor(color);
                        float pressure = pen ? 0.6f + e.getPressure() : 1f;
                        p.setStrokeWidth(th.dp(highlighter ? 22 : width * pressure / 2f));
                    }
                    current = new Stroke(p);
                    current.path.moveTo(x, y);
                    current.path.lineTo(x + 0.1f, y);
                    strokes.add(current);
                    undone.clear();
                    lastX = x;
                    lastY = y;
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    if (current == null) break;
                    for (int h = 0; h < e.getHistorySize(); h++) smooth(e.getHistoricalX(h), e.getHistoricalY(h));
                    smooth(x, y);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    current = null;
                    break;
                default:
                    break;
            }
            invalidate();
            return true;
        }

        private void smooth(float x, float y) {
            current.path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2);
            lastX = x;
            lastY = y;
        }
    }

    // a drawing is never lost when leaving the page
    @Override
    protected void onPause() {
        if (ink != null && !ink.strokes.isEmpty()) save(false);
        super.onPause();
    }
}

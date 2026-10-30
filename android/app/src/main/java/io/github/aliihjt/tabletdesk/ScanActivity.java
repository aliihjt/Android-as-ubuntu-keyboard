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
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Document scanner: photograph pages, drag the four corners onto the paper's
 * edges (they start on the page found automatically), and each page is
 * straightened and cleaned up. All pages become one PDF that goes to the PC's
 * Downloads › Fedora Pad, to the tablet's Downloads, or to any app.
 */
public class ScanActivity extends Page {
    private static final int CAMERA = 61, PHOTOS = 62;
    private static final String[][] LOOKS = {{"scan", "Scan"}, {"gray", "Grey"}, {"color", "Colour"}};
    private final List<File> pages = new ArrayList<>();
    private LinearLayout strip, crop;
    private TextView count;
    private Uri shot;
    private String look = "scan";

    @Override
    protected String title() {
        return "Scanner";
    }

    @Override
    protected void build() {
        look = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).getString("scan_look", "scan");
        LinearLayout top = new LinearLayout(this);
        top.addView(th.primaryButton("📷  Photograph a page", v -> camera()), th.weighted(0));
        top.addView(th.secondaryButton("🖼  From photos", v -> startActivityForResult(
                new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PHOTOS)), th.weighted(10));
        add(top, 4);
        add(th.segmented(LOOKS, look, v -> {
            look = v;
            getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).edit().putString("scan_look", v).apply();
        }), 12);
        crop = new LinearLayout(this);
        crop.setOrientation(LinearLayout.VERTICAL);
        add(crop, 12);
        count = th.sectionHeader("No pages yet");
        add(count, 10);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        strip = new LinearLayout(this);
        hs.addView(strip);
        add(hs, 4);

        LinearLayout out = new LinearLayout(this);
        out.addView(th.primaryButton("💻  PDF to PC", v -> sendToPc()), th.weighted(0));
        out.addView(th.secondaryButton("⬇️  Save", v -> saveToDownloads(true)), th.weighted(10));
        out.addView(th.secondaryButton("↗️  Share", v -> share()), th.weighted(10));
        add(out, 16);
        LinearLayout more = new LinearLayout(this);
        more.addView(th.secondaryButton("✨  Ask Claude about it", v -> askClaude()), th.weighted(0));
        more.addView(th.secondaryButton("🗑  Start over", v -> clearAll()), th.weighted(10));
        add(more, 10);
    }

    // ---- getting a photo ----

    private void camera() {
        if (Build.VERSION.SDK_INT >= 29) {  // full-size photo into a file we own (deleted right after)
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "Fedora Pad scan.jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Fedora Pad");
            shot = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        }
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (shot != null) {
            i.putExtra(MediaStore.EXTRA_OUTPUT, shot).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        try {
            startActivityForResult(i, CAMERA);
        } catch (android.content.ActivityNotFoundException e) {
            toast("No camera app on this tablet");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri from = null;
        Bitmap small = null;
        if (request == CAMERA) {
            if (result == RESULT_OK) {
                if (shot != null) from = shot;
                else if (data != null && data.getExtras() != null) small = (Bitmap) data.getExtras().get("data");
            } else if (shot != null) {
                getContentResolver().delete(shot, null, null);
                shot = null;
            }
        } else if (request == PHOTOS && result == RESULT_OK && data != null) {
            from = data.getData();
        }
        Bitmap b = small != null ? small : from != null ? load(from) : null;
        if (from != null && from.equals(shot)) {
            getContentResolver().delete(shot, null, null);
            shot = null;
        }
        if (b != null) showCrop(b);
    }

    /** At most ~2400 px on the long side, turned the right way up. */
    private Bitmap load(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 2400) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            int turn = 0;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                turn = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            } catch (IOException ignored) {
            }
            if (b != null && turn != 0) {
                Matrix m = new Matrix();
                m.postRotate(turn);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (IOException | SecurityException e) {
            toast("Couldn't open the photo");
            return null;
        }
    }

    // ---- corners ----

    private void showCrop(Bitmap photo) {
        crop.removeAllViews();
        CropView view = new CropView(this, photo, th);
        crop.addView(th.caption("Drag the four dots onto the corners of the paper."));
        int h = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
        crop.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h));
        LinearLayout row = new LinearLayout(this);
        row.addView(th.primaryButton("✓  Use this page", v -> {
            addPage(straighten(photo, view.corners()));
            crop.removeAllViews();
        }), th.weighted(0));
        row.addView(th.secondaryButton("Whole photo", v -> {
            addPage(straighten(photo, new PointF[]{new PointF(0, 0), new PointF(photo.getWidth(), 0),
                    new PointF(photo.getWidth(), photo.getHeight()), new PointF(0, photo.getHeight())}));
            crop.removeAllViews();
        }), th.weighted(10));
        row.addView(th.secondaryButton("Cancel", v -> crop.removeAllViews()), th.weighted(10));
        crop.addView(row, th.fullWidth(10));
    }

    /** Maps the four corners (TL, TR, BR, BL in photo pixels) onto a flat page and applies the look. */
    private Bitmap straighten(Bitmap photo, PointF[] c) {
        float w = Math.max(dist(c[0], c[1]), dist(c[3], c[2]));
        float h = Math.max(dist(c[0], c[3]), dist(c[1], c[2]));
        float k = Math.min(1f, 2400f / Math.max(w, h));
        int W = Math.max(1, Math.round(w * k)), H = Math.max(1, Math.round(h * k));
        Matrix m = new Matrix();
        m.setPolyToPoly(new float[]{c[0].x, c[0].y, c[1].x, c[1].y, c[2].x, c[2].y, c[3].x, c[3].y}, 0,
                new float[]{0, 0, W, 0, W, H, 0, H}, 0, 4);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (!look.equals("color")) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            if (look.equals("scan")) {  // whiter paper, darker ink
                float con = 1.7f, shift = -0.7f * 128 + 40;
                cm.postConcat(new ColorMatrix(new float[]{con, 0, 0, 0, shift, 0, con, 0, 0, shift,
                        0, 0, con, 0, shift, 0, 0, 0, 1, 0}));
            }
            p.setColorFilter(new ColorMatrixColorFilter(cm));
        }
        cv.drawBitmap(photo, m, p);
        return out;
    }

    private static float dist(PointF a, PointF b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    // ---- pages ----

    private File folder() {
        File f = new File(getCacheDir(), "scan");
        f.mkdirs();
        return f;
    }

    private void addPage(Bitmap page) {
        File f = new File(folder(), "page-" + System.currentTimeMillis() + ".jpg");
        try (OutputStream out = new FileOutputStream(f)) {
            page.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            toast("Couldn't keep the page");
            return;
        }
        pages.add(f);
        refreshStrip();
    }

    private void refreshStrip() {
        strip.removeAllViews();
        count.setText(pages.isEmpty() ? "NO PAGES YET" : pages.size() + (pages.size() == 1 ? " PAGE" : " PAGES"));
        for (File f : pages) {
            ImageView img = new ImageView(this);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            img.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath(), o));
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackground(Theme.round(Color.WHITE, th.dp(6)));
            img.setClipToOutline(true);
            img.setElevation(th.dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(96), th.dp(130));
            lp.rightMargin = th.dp(10);
            strip.addView(img, lp);
            img.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setItems(new String[]{"Remove this page"}, (d, w) -> {
                    pages.remove(f);
                    f.delete();
                    refreshStrip();
                }).show();
                return true;
            });
        }
    }

    private void clearAll() {
        for (File f : pages) f.delete();
        pages.clear();
        refreshStrip();
        crop.removeAllViews();
    }

    /** All pages as one PDF, each page in its own size (A4 width). */
    private File makePdf() {
        if (pages.isEmpty()) {
            toast("Photograph a page first");
            return null;
        }
        PdfDocument doc = new PdfDocument();
        for (int i = 0; i < pages.size(); i++) {
            Bitmap b = BitmapFactory.decodeFile(pages.get(i).getAbsolutePath());
            if (b == null) continue;
            int pw = 595, ph = Math.round(595f * b.getHeight() / b.getWidth());
            PdfDocument.Page p = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create());
            Matrix m = new Matrix();
            m.setScale(pw / (float) b.getWidth(), ph / (float) b.getHeight());
            p.getCanvas().drawBitmap(b, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            doc.finishPage(p);
        }
        String name = "Scan " + new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(new Date()) + ".pdf";
        File f = new File(folder(), name);
        try (OutputStream out = new FileOutputStream(f)) {
            doc.writeTo(out);
        } catch (IOException e) {
            toast("Couldn't make the PDF");
            return null;
        } finally {
            doc.close();
        }
        return f;
    }

    private void sendToPc() {
        if (!link.isConnected()) {
            toast("Connect to the PC first, or use Save / Share");
            return;
        }
        File pdf = makePdf();
        if (pdf == null) return;
        toast("Sending…");
        new Thread(() -> {
            try {
                byte[] all = java.nio.file.Files.readAllBytes(pdf.toPath());
                String id = UUID.randomUUID().toString();
                int chunk = 48 * 1024;
                for (int at = 0; at < all.length; at += chunk) {
                    int len = Math.min(chunk, all.length - at);
                    link.sendFileChunk(id, pdf.getName(), Base64.encodeToString(all, at, len, Base64.NO_WRAP),
                            at + len >= all.length);
                }
                runOnUiThread(() -> toast("📄 " + pdf.getName() + " is in the PC's Downloads › Fedora Pad"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Couldn't send: " + e.getMessage()));
            }
        }, "send-scan").start();
    }

    /** Into the tablet's Downloads › Fedora Pad; returns its link for sharing. */
    private Uri saveToDownloads(boolean tell) {
        File pdf = makePdf();
        if (pdf == null) return null;
        if (Build.VERSION.SDK_INT < 29) {
            toast("Saving needs Android 10. Use “PDF to PC”.");
            return null;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, pdf.getName());
        v.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fedora Pad");
        try {
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (OutputStream out = getContentResolver().openOutputStream(u)) {
                out.write(java.nio.file.Files.readAllBytes(pdf.toPath()));
            }
            if (tell) toast("Saved in Downloads › Fedora Pad");
            return u;
        } catch (Exception e) {
            toast("Couldn't save: " + e.getMessage());
            return null;
        }
    }

    private void share() {
        Uri u = saveToDownloads(false);
        if (u == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share the scan"));
    }

    private void askClaude() {
        if (pages.isEmpty()) {
            toast("Photograph a page first");
            return;
        }
        Bitmap first = BitmapFactory.decodeFile(pages.get(pages.size() - 1).getAbsolutePath());
        ClaudeApp.ask(this, "Read this scanned page and summarise it. If it's a form, tell me what to fill in.", first);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) clearAll();  // scans are only kept in the PDF you made
        super.onDestroy();
    }

    // ---- the corner editor ----

    static final class CropView extends View {
        private final Bitmap photo;
        private final Theme th;
        private final PointF[] pts = new PointF[4];  // in photo pixels: TL, TR, BR, BL
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                shade = new Paint();
        private final Matrix toView = new Matrix(), toPhoto = new Matrix();
        private int dragging = -1;

        CropView(Context c, Bitmap photo, Theme th) {
            super(c);
            this.photo = photo;
            this.th = th;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(th.dp(2.5f));
            line.setColor(Theme.BLUE);
            dot.setColor(Theme.BLUE);
            shade.setColor(0x66000000);
            PointF[] found = findPaper(photo);
            System.arraycopy(found, 0, pts, 0, 4);
        }

        PointF[] corners() {
            return pts;
        }

        /**
         * A first guess: the bright paper against a darker table. Shrinks the
         * picture, then takes the outermost bright pixels toward each corner.
         */
        static PointF[] findPaper(Bitmap b) {
            int sw = 120, sh = Math.max(1, 120 * b.getHeight() / b.getWidth());
            Bitmap s = Bitmap.createScaledBitmap(b, sw, sh, true);
            int[] px = new int[sw * sh];
            s.getPixels(px, 0, sw, 0, 0, sw, sh);
            long sum = 0;
            int[] lum = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                lum[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                sum += lum[i];
            }
            int threshold = (int) (sum / px.length) + 15;
            // corner i is the bright pixel with the largest score toward that corner
            int[][] dir = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            PointF[] out = new PointF[4];
            int bright = 0;
            for (int k = 0; k < 4; k++) {
                float best = -Float.MAX_VALUE;
                int bx = 0, by = 0;
                for (int y = 0; y < sh; y++) {
                    for (int x = 0; x < sw; x++) {
                        if (lum[y * sw + x] < threshold) continue;
                        if (k == 0) bright++;
                        float score = dir[k][0] * x + dir[k][1] * y;
                        if (score > best) {
                            best = score;
                            bx = x;
                            by = y;
                        }
                    }
                }
                out[k] = new PointF(bx * b.getWidth() / (float) sw, by * b.getHeight() / (float) sh);
            }
            if (bright < px.length / 10) {  // nothing clear: start a little inside the photo
                float ix = b.getWidth() * 0.08f, iy = b.getHeight() * 0.08f;
                out[0] = new PointF(ix, iy);
                out[1] = new PointF(b.getWidth() - ix, iy);
                out[2] = new PointF(b.getWidth() - ix, b.getHeight() - iy);
                out[3] = new PointF(ix, b.getHeight() - iy);
            }
            return out;
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = Math.min(getWidth() / (float) photo.getWidth(), getHeight() / (float) photo.getHeight());
            float dx = (getWidth() - photo.getWidth() * k) / 2, dy = (getHeight() - photo.getHeight() * k) / 2;
            toView.setScale(k, k);
            toView.postTranslate(dx, dy);
            toView.invert(toPhoto);
            c.drawBitmap(photo, toView, null);
            float[] v = new float[8];
            for (int i = 0; i < 4; i++) {
                v[i * 2] = pts[i].x;
                v[i * 2 + 1] = pts[i].y;
            }
            toView.mapPoints(v);
            Path p = new Path();
            p.moveTo(v[0], v[1]);
            for (int i = 1; i < 4; i++) p.lineTo(v[i * 2], v[i * 2 + 1]);
            p.close();
            Path outside = new Path();
            outside.addRect(dx, dy, dx + photo.getWidth() * k, dy + photo.getHeight() * k, Path.Direction.CW);
            outside.op(p, Path.Op.DIFFERENCE);
            c.drawPath(outside, shade);
            c.drawPath(p, line);
            for (int i = 0; i < 4; i++) c.drawCircle(v[i * 2], v[i * 2 + 1], th.dp(12), dot);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float[] at = {e.getX(), e.getY()};
            toPhoto.mapPoints(at);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float best = Float.MAX_VALUE;
                    float[] scale = {1, 0};
                    toPhoto.mapVectors(scale);
                    float reach = th.dp(40) * Math.abs(scale[0]);
                    dragging = -1;
                    for (int i = 0; i < 4; i++) {
                        float d = (float) Math.hypot(pts[i].x - at[0], pts[i].y - at[1]);
                        if (d < best && d < reach) {
                            best = d;
                            dragging = i;
                        }
                    }
                    getParent().requestDisallowInterceptTouchEvent(dragging >= 0);
                    return dragging >= 0;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragging < 0) return false;
                    pts[dragging].set(Math.max(0, Math.min(photo.getWidth(), at[0])),
                            Math.max(0, Math.min(photo.getHeight(), at[1])));
                    invalidate();
                    return true;
                default:
                    dragging = -1;
                    return true;
            }
        }
    }
}

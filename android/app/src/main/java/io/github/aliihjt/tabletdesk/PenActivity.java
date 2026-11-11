package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pen Tablet: the tablet as a drawing tablet for the laptop (host/pen.py makes a
 * virtual pen on Fedora, so GIMP, Krita and Xournal++ see a real pen).
 * The pad has the laptop screen's shape: its corners are the screen's corners.
 * One finger draws; a two-finger tap is a right-click; a stylus also hovers and
 * gives real pressure. A faint copy of the lines stays on the pad to see where you are.
 */
public class PenActivity extends Page {
    private Pad pad;
    private boolean eraser;
    private float laptopRatio = 16f / 9f;   // width / height of the laptop screen

    @Override
    protected String title() {
        return "Pen Tablet";
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView pen = th.secondaryButton("✏️  Pen", null);
        TextView rubber = th.secondaryButton("🧽  Eraser", null);
        Runnable paint = () -> {
            pen.setAlpha(eraser ? 0.45f : 1f);
            rubber.setAlpha(eraser ? 1f : 0.45f);
        };
        th.click(pen, v -> {
            eraser = false;
            paint.run();
        });
        th.click(rubber, v -> {
            eraser = true;
            paint.run();
        });
        paint.run();
        bar.addView(pen, th.weighted(0));
        bar.addView(rubber, th.weighted(8));
        bar.addView(th.secondaryButton("↶  Undo", v -> link.combo("ctrl+z")), th.weighted(8));
        bar.addView(th.secondaryButton("Clear pad", v -> pad.clear()), th.weighted(8));
        content.addView(bar);
        content.addView(th.caption("Draw with a finger or a stylus. Two-finger tap: right-click. "
                + "Works in Krita, GIMP, Xournal++ and Inkscape on the laptop."), th.fullWidth(8));

        FrameLayout holder = new FrameLayout(this);
        pad = new Pad(this);
        holder.addView(pad);
        holder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit(holder));
        content.addView(holder, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** The biggest pad with the laptop screen's shape that fits. */
    private void fit(FrameLayout holder) {
        int w = holder.getWidth(), h = holder.getHeight();
        if (w == 0 || h == 0) return;
        int pw = w, ph = Math.round(w / laptopRatio);
        if (ph > h) {
            ph = h;
            pw = Math.round(h * laptopRatio);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pad.getLayoutParams();
        if (lp.width == pw && lp.height == ph) return;
        lp.width = pw;
        lp.height = ph;
        lp.gravity = Gravity.CENTER;
        pad.post(() -> pad.setLayoutParams(lp));
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("penarea", m -> {
            int w = m.optInt("w"), h = m.optInt("h");
            if (w > 0 && h > 0) {
                laptopRatio = w / (float) h;
                View holder = (View) pad.getParent();
                if (holder != null) fit((FrameLayout) holder);
            }
        });
        link.ask("pen");
    }

    @Override
    protected void onPause() {
        super.onPause();
        link.on("penarea", null);
        send("out", 0, 0, 0);  // lift the pen on the laptop
    }

    private void send(String state, float x, float y, float pressure) {
        try {
            JSONObject o = new JSONObject().put("t", "pen").put("s", state)
                    .put("x", Math.round(x * 10000) / 10000.0).put("y", Math.round(y * 10000) / 10000.0)
                    .put("p", Math.round(pressure * 1000) / 1000.0);
            if (eraser) o.put("tool", "eraser");
            link.send(o);
        } catch (JSONException ignored) {
        }
    }

    /** The drawing surface: sends every point, and keeps a faint copy of the lines. */
    private final class Pad extends View {
        private final Path ink = new Path();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long twoDownAt;
        private boolean twoMoved;

        Pad(Context c) {
            super(c);
            setBackground(th.surface(th.mono ? th.card : (th.dark ? 0xFF1C1C1E : 0xFFFFFFFF), th.dp(12)));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(th.dp(2.5f));
            line.setColor((th.text & 0x00FFFFFF) | 0x8C000000);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(th.dp(1));
            frame.setColor(th.separator);
            setLayoutParams(new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        }

        void clear() {
            ink.reset();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            // a thin cross in the middle, like the laptop screen's centre
            c.drawLine(getWidth() / 2f - th.dp(8), getHeight() / 2f, getWidth() / 2f + th.dp(8), getHeight() / 2f, frame);
            c.drawLine(getWidth() / 2f, getHeight() / 2f - th.dp(8), getWidth() / 2f, getHeight() / 2f + th.dp(8), frame);
            c.drawPath(ink, line);
        }

        private float fx(MotionEvent e, int i) {
            return Math.max(0f, Math.min(1f, e.getX(i) / Math.max(1, getWidth())));
        }

        private float fy(MotionEvent e, int i) {
            return Math.max(0f, Math.min(1f, e.getY(i) / Math.max(1, getHeight())));
        }

        /** A stylus gives real pressure; a finger draws with a steady medium line. */
        private float pressure(MotionEvent e, int i) {
            int tool = e.getToolType(i);
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                return Math.max(0.05f, Math.min(1f, e.getPressure(i)));
            }
            return 0.6f;
        }

        @Override
        public boolean onHoverEvent(MotionEvent e) {  // a stylus above the screen: the pen hovers on the laptop
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_HOVER_EXIT) send("out", fx(e, 0), fy(e, 0), 0);
            else send("hover", fx(e, 0), fy(e, 0), 0);
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    twoDownAt = 0;
                    ink.moveTo(e.getX(), e.getY());
                    send("down", fx(e, 0), fy(e, 0), pressure(e, 0));
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2) {  // maybe a two-finger tap: stop drawing
                        send("up", fx(e, 0), fy(e, 0), 0);
                        twoDownAt = e.getEventTime();
                        twoMoved = false;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (twoDownAt != 0) {
                        twoMoved = true;
                        break;
                    }
                    for (int h = 0; h < e.getHistorySize(); h++) {  // every point, for smooth lines
                        ink.lineTo(e.getHistoricalX(0, h), e.getHistoricalY(0, h));
                    }
                    ink.lineTo(e.getX(), e.getY());
                    send("move", fx(e, 0), fy(e, 0), pressure(e, 0));
                    invalidate();
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (twoDownAt != 0 && e.getEventTime() - twoDownAt < 300 && !twoMoved) {
                        send("right", fx(e, 0), fy(e, 0), 0);
                        twoDownAt = -1;  // handled: no more right-clicks until all fingers are up
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (twoDownAt == 0) send("up", fx(e, 0), fy(e, 0), 0);
                    twoDownAt = 0;
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}

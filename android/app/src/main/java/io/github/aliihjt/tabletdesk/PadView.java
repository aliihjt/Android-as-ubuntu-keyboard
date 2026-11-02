package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Live picture of a game controller on the Controller page: lights up the
 * buttons that are held and moves the sticks, so you can see it works.
 */
class PadView extends View {
    private final Theme th;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    int buttons;                    // bits in ControllerActivity.KEYS order
    float lx, ly, rx, ry, lt, rt;   // sticks -1..1, triggers 0..1
    int hx, hy;                     // d-pad -1/0/1
    boolean ps;                     // ✕ ○ □ △ instead of A B X Y

    PadView(Context c, Theme th) {
        super(c);
        this.th = th;
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, Math.min(Math.round(w * 0.5f), th.dp(250)));
    }

    private boolean on(int bit) {
        return (buttons >> bit & 1) != 0;
    }

    private int idle() {
        return th.dark ? 0xFF48484A : 0xFFFFFFFF;
    }

    private int lit() {
        return th.accent;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float u = Math.min(w / 10f, h / 5.5f);
        float cx = w / 2f;

        // shoulders: L2 / R2 as bars that fill, L1 / R1 as buttons
        for (int side = -1; side <= 1; side += 2) {
            float x0 = cx + side * 2.2f * u, x1 = cx + side * 4.2f * u;
            float left = Math.min(x0, x1), right = Math.max(x0, x1);
            float t = side < 0 ? lt : rt;
            boolean held = on(side < 0 ? 6 : 7);
            float amount = Math.max(t, held ? 1f : 0f);
            paint.setColor(idle());
            r.set(left, 0, right, 0.38f * u);
            c.drawRoundRect(r, 0.19f * u, 0.19f * u, paint);
            if (amount > 0.01f) {
                paint.setColor(lit());
                r.set(left, 0, left + (right - left) * amount, 0.38f * u);
                c.drawRoundRect(r, 0.19f * u, 0.19f * u, paint);
            }
            paint.setColor(on(side < 0 ? 4 : 5) ? lit() : idle());
            r.set(left, 0.5f * u, right, 0.9f * u);
            c.drawRoundRect(r, 0.2f * u, 0.2f * u, paint);
            label(c, side < 0 ? (ps ? "L1" : "LB") : (ps ? "R1" : "RB"), (left + right) / 2, 0.7f * u, 0.3f * u,
                    on(side < 0 ? 4 : 5));
        }

        // body
        paint.setColor(th.dark ? 0xFF2C2C2E : 0xFFE5E5EA);
        r.set(cx - 4.7f * u, 1.0f * u, cx + 4.7f * u, h - 0.1f * u);
        c.drawRoundRect(r, 1.6f * u, 1.6f * u, paint);

        // d-pad
        float dx = cx - 3f * u, dy = 2.4f * u, a = 0.42f * u;
        int[][] arms = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int[] arm : arms) {
            boolean held = (arm[0] != 0 && arm[0] == hx) || (arm[1] != 0 && arm[1] == hy);
            paint.setColor(held ? lit() : idle());
            float ax = dx + arm[0] * 2 * a, ay = dy + arm[1] * 2 * a;
            r.set(ax - a, ay - a, ax + a, ay + a);
            c.drawRoundRect(r, 0.12f * u, 0.12f * u, paint);
        }

        // face buttons: a bottom, b right, x left, y top
        float fx = cx + 3f * u, fy = 2.4f * u, off = 0.8f * u, rad = 0.38f * u;
        String[] names = ps ? new String[]{"✕", "○", "□", "△"} : new String[]{"A", "B", "X", "Y"};
        float[][] pos = {{0, 1}, {1, 0}, {-1, 0}, {0, -1}};
        for (int i = 0; i < 4; i++) {
            boolean held = on(i);
            paint.setColor(held ? lit() : idle());
            float bx = fx + pos[i][0] * off, by = fy + pos[i][1] * off;
            c.drawCircle(bx, by, rad, paint);
            label(c, names[i], bx, by, 0.42f * u, held);
        }

        // select / start / mode
        pill(c, cx - 1.3f * u, 1.7f * u, u, on(8), ps ? "SHARE" : "VIEW");
        pill(c, cx + 1.3f * u, 1.7f * u, u, on(9), ps ? "OPTIONS" : "MENU");
        paint.setColor(on(10) ? lit() : idle());
        c.drawCircle(cx, 2.65f * u, 0.32f * u, paint);
        label(c, ps ? "PS" : "⊗", cx, 2.65f * u, 0.26f * u, on(10));

        // sticks
        stick(c, cx - 1.7f * u, 4.0f * u, u, lx, ly, on(11));
        stick(c, cx + 1.7f * u, 4.0f * u, u, rx, ry, on(12));
    }

    private void pill(Canvas c, float x, float y, float u, boolean held, String text) {
        paint.setColor(held ? lit() : idle());
        r.set(x - 0.42f * u, y - 0.16f * u, x + 0.42f * u, y + 0.16f * u);
        c.drawRoundRect(r, 0.16f * u, 0.16f * u, paint);
        paint.setColor(th.secondary);
        paint.setTextSize(0.2f * u);
        c.drawText(text, x, y + 0.5f * u, paint);
    }

    private void stick(Canvas c, float x, float y, float u, float sx, float sy, boolean pressed) {
        paint.setColor(th.dark ? 0xFF1C1C1E : 0xFFD1D1D6);
        c.drawCircle(x, y, 0.85f * u, paint);
        paint.setColor(pressed ? lit() : idle());
        c.drawCircle(x + sx * 0.45f * u, y + sy * 0.45f * u, 0.45f * u, paint);
    }

    private void label(Canvas c, String s, float x, float y, float size, boolean held) {
        paint.setColor(held ? 0xFFFFFFFF : th.text);
        paint.setTextSize(size);
        Paint.FontMetrics fm = paint.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2, paint);
    }
}

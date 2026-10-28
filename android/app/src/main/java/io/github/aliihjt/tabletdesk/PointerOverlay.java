package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Draws the laptop's mouse pointer on the tablet when the touchpad moves it onto
 * the tablet's screen: the RDP app does not draw the PC's pointer itself, so
 * without this it seems to vanish. Touches pass straight through.
 */
final class PointerOverlay extends View implements HostLink.PointerListener {
    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float px = -1, py = -1;  // 0..1 of the screen, -1 = hidden

    PointerOverlay(Context ctx) {
        super(ctx);
        float s = ctx.getResources().getDisplayMetrics().density * 1.1f;
        // classic arrow pointer, tip at (0, 0)
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * s);
        arrow.lineTo(4.2f * s, 13 * s);
        arrow.lineTo(7.2f * s, 19.5f * s);
        arrow.lineTo(9.6f * s, 18.4f * s);
        arrow.lineTo(6.6f * s, 12.2f * s);
        arrow.lineTo(12 * s, 12.2f * s);
        arrow.close();
        fill.setColor(0xFF000000);
        outline.setColor(0xFFFFFFFF);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.6f * s);
        outline.setStrokeJoin(Paint.Join.ROUND);
        HostLink.get().setPointerListener(this);
    }

    WindowManager.LayoutParams params() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        // Android only lets touches pass through another app's full-screen
        // overlay when it is at most 80% opaque
        p.alpha = 0.8f;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    void destroy() {
        HostLink.get().setPointerListener(null);
    }

    @Override
    public void onPointer(float x, float y) {
        px = x;
        py = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (px < 0 || py < 0) return;
        c.save();
        c.translate(px * getWidth(), py * getHeight());
        c.drawPath(arrow, outline);
        c.drawPath(arrow, fill);
        c.restore();
    }
}

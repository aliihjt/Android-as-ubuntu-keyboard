package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ambient light: the whole tablet glows in the colours of the Fedora screen,
 * like a bias light. Fedora samples its screen at 3 x 3 points a few times a
 * second; each point becomes a soft glow that fades smoothly to the next colour.
 */
public class AmbientActivity extends Activity {
    private final HostLink link = HostLink.get();
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            link.ask("ambient");
            poll.postDelayed(this, 300);
        }
    };
    private Glow glow;
    private TextView hint;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        link.attach(this);
        link.start();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        glow = new Glow(this);
        root.addView(glow);
        hint = new TextView(this);
        hint.setText(link.isConnected() ? "Ambient light · tap to show this, ◁ Back to close"
                : "Connect the tablet to Fedora to start the ambient light");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(15);
        hint.setShadowLayer(8, 0, 0, 0xAA000000);
        int p = Math.round(24 * getResources().getDisplayMetrics().density);
        hint.setPadding(p, p, p, p);
        root.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        root.setOnClickListener(v -> showHint());
        setContentView(root);
        hideBars();
        showHint();
    }

    private void showHint() {
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(3000).setDuration(800).start();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void colours(JSONObject msg) {
        JSONArray list = msg.optJSONArray("colors");
        if (list == null || list.length() == 0) return;
        int[] target = new int[9];
        for (int i = 0; i < 9; i++) {
            JSONArray c = list.optJSONArray(Math.min(i, list.length() - 1));
            target[i] = c == null ? Color.BLACK : Color.rgb(c.optInt(0), c.optInt(1), c.optInt(2));
        }
        glow.setTarget(target);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlayService.setBubbleHidden(true);
        link.on("ambient", this::colours);
        poll.post(tick);
    }

    @Override
    protected void onPause() {
        poll.removeCallbacks(tick);
        link.on("ambient", null);
        OverlayService.setBubbleHidden(false);
        super.onPause();
    }

    /** Nine soft glows on black, fading towards the newest colours every frame. */
    private static class Glow extends View {
        private final float[][] now = new float[9][3];
        private final int[] target = new int[9];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Glow(Context c) {
            super(c);
        }

        void setTarget(int[] t) {
            System.arraycopy(t, 0, target, 0, 9);
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            boolean moving = false;
            float r = 0, g = 0, b = 0;
            for (int i = 0; i < 9; i++) {
                float[] c = now[i];
                float[] to = {Color.red(target[i]), Color.green(target[i]), Color.blue(target[i])};
                for (int k = 0; k < 3; k++) {
                    float d = to[k] - c[k];
                    if (Math.abs(d) > 0.5f) moving = true;
                    c[k] += d * 0.12f;  // smooth fade, about half a second
                }
                r += c[0];
                g += c[1];
                b += c[2];
            }
            // the average colour fills the screen, each point glows on top
            canvas.drawColor(Color.rgb(Math.round(r / 9 * 0.55f), Math.round(g / 9 * 0.55f), Math.round(b / 9 * 0.55f)));
            float radius = Math.max(w, h) * 0.45f;
            for (int i = 0; i < 9; i++) {
                float x = w * (i % 3 + 0.5f) / 3f, y = h * (i / 3 + 0.5f) / 3f;
                int c = Color.rgb(Math.round(now[i][0]), Math.round(now[i][1]), Math.round(now[i][2]));
                paint.setShader(new RadialGradient(x, y, radius, new int[]{c & 0xDDFFFFFF, c & 0x00FFFFFF},
                        null, Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, radius, paint);
            }
            if (moving) postInvalidateOnAnimation();
        }
    }
}

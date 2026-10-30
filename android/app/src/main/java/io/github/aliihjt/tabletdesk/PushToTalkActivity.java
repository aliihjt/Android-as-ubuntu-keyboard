package io.github.aliihjt.tabletdesk;

import android.Manifest;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Push to Talk: hold the big mic (or the volume-down key), speak, let go:
 * the words are typed where the cursor is on Fedora.
 */
public class PushToTalkActivity extends Page {
    private PushToTalk ptt;
    private TextView status, words;
    private SharedPreferences prefs;

    @Override
    protected String title() {
        return "Push to Talk";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        ptt = new PushToTalk(this, (s, t) -> {
            status.setText(s);
            if (!t.isEmpty() || s.startsWith("🎙")) words.setText(t);
        });

        add(th.caption("Click where you want to write on Fedora, then hold the mic and speak. "
                + "Let go and the words are typed there. The volume-down key works too."), 4);

        FrameLayout stage = new FrameLayout(this);
        stage.addView(ptt.button(th, 150), new FrameLayout.LayoutParams(th.dp(150), th.dp(150), Gravity.CENTER));
        add(stage, 24);
        stage.getLayoutParams().height = th.dp(200);

        status = th.text("Hold to talk", 17, th.text, true);
        status.setGravity(Gravity.CENTER);
        add(status, 8);
        words = th.text("", 20, th.text, false);
        words.setGravity(Gravity.CENTER);
        words.setMinHeight(th.dp(90));
        int p = th.dp(16);
        words.setPadding(p, p, p, p);
        words.setBackground(Theme.round(th.card, th.dp(16)));
        add(words, 12);

        section("Microphone");
        add(th.segmented(new String[][]{{"tablet", "📱 Tablet mic"}, {"laptop", "💻 Laptop mic (offline)"}},
                prefs.getString(PushToTalk.SOURCE, "tablet"),
                v -> prefs.edit().putString(PushToTalk.SOURCE, v).apply()));
        add(th.caption("Tablet mic shows the words as you speak (uses Google speech). "
                + "Laptop mic works offline after ./host/voice-setup.sh on Fedora."), 6);

        section("Language (tablet mic)");
        add(th.segmented(new String[][]{{"auto", "Automatic"}, {"en-US", "English"}, {"fa-IR", "فارسی"}},
                prefs.getString(PushToTalk.LANG, "auto"),
                v -> prefs.edit().putString(PushToTalk.LANG, v).apply()));

        LinearLayout tip = new LinearLayout(this);
        tip.addView(th.secondaryButton("🫧  Floating mic over the Fedora screen",
                v -> OverlayService.showPanel(this, FloatingPanel.TALK)), th.weighted(0));
        add(tip, 20);

        if (!PushToTalk.micAllowed(this)) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
    }

    // hold the volume-down key to talk without touching the screen
    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (ev.getRepeatCount() == 0) ptt.down();
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            ptt.up();
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    @Override
    protected void onPause() {
        ptt.up();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ptt.release();
        super.onDestroy();
    }
}

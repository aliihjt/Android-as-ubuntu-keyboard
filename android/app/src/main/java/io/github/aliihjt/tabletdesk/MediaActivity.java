package io.github.aliihjt.tabletdesk;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Remote for whatever plays on the PC: Spotify, YouTube in Firefox, VLC... */
public class MediaActivity extends Page {
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            link.ask("media");
            poll.postDelayed(this, 1000);
        }
    };
    private TextView title, artist, play;

    @Override
    protected String title() {
        return "Media";
    }

    @Override
    protected void build() {
        LinearLayout card = th.card();
        int p = th.dp(24);
        card.setPadding(p, p, p, p);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(th.iconChip("🎵", Theme.PINK, 120));
        title = th.text("Nothing playing", 24, th.text, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        card.addView(title, th.fullWidth(20));
        artist = th.caption("");
        artist.setGravity(Gravity.CENTER);
        card.addView(artist, th.fullWidth(6));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(round("⏮", 30, () -> link.media("previous")));
        play = round("▶", 40, () -> link.media("play-pause"));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(th.dp(96), th.dp(96));
        plp.leftMargin = plp.rightMargin = th.dp(28);
        buttons.addView(play, plp);
        buttons.addView(round("⏭", 30, () -> link.media("next")));
        card.addView(buttons, th.fullWidth(26));
        add(card, 6);

        section("Volume");
        add(th.group(th.sliderRow("🔊", Theme.BLUE, "Volume",
                th.slider(100, link.lastState().optInt("volume", 50), v -> link.set("volume", v)))));
    }

    private TextView round(String glyph, float sp, Runnable action) {
        TextView t = th.text(glyph, sp, th.text, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(th.pressable(th.fill, th.dp(48)));
        t.setLayoutParams(new LinearLayout.LayoutParams(th.dp(72), th.dp(72)));
        th.click(t, v -> action.run());
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("media", m -> {
            title.setText(m.optString("title"));
            String a = m.optString("artist"), pl = m.optString("player");
            artist.setText(a.isEmpty() ? pl : (pl.isEmpty() ? a : a + "  ·  " + pl));
            boolean playing = "Playing".equals(m.optString("status"));
            play.setText(playing ? "⏸" : "▶");
            play.setTextColor(0xFFFFFFFF);
            play.setBackground(th.pressable(Theme.PINK, th.dp(48)));
        });
        poll.post(tick);
    }

    @Override
    protected void onPause() {
        poll.removeCallbacks(tick);
        link.on("media", null);
        super.onPause();
    }
}

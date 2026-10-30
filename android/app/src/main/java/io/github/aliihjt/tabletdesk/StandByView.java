package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * StandBy: a big clock for a charging tablet, with the date, weather, what's
 * playing on the PC (with buttons), the PC's load, the battery and the focus
 * timer. Red and dim at night; moves a little every minute so nothing burns in.
 * Used by StandByActivity and by the screen saver (StandByDream).
 */
final class StandByView extends FrameLayout {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HostLink link = HostLink.get();
    private final SharedPreferences prefs;
    private final LinearLayout body, side;
    private final TextView clock, date, weather, song, songBy, pc, battery, focus;
    private final LinearLayout mediaCard, focusCard;
    private final Random random = new Random();
    private long weatherAt;
    private boolean night = false, attached;
    private int tickCount;

    StandByView(Context c) {
        super(c);
        Ui.loadScale(c);
        prefs = c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
        setBackgroundColor(Color.BLACK);
        float d = getResources().getDisplayMetrics().density;
        boolean upright = getResources().getConfiguration().screenHeightDp > getResources().getConfiguration().screenWidthDp;

        body = new LinearLayout(c);
        body.setOrientation(upright ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER);
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        clock = label(c, upright ? 110 : 150, true);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setFontFeatureSettings("tnum");
        clock.setIncludeFontPadding(false);
        date = label(c, 24, false);
        date.setAlpha(0.75f);
        left.addView(clock);
        left.addView(date);
        body.addView(left, new LinearLayout.LayoutParams(upright ? LayoutParams.MATCH_PARENT : 0,
                LayoutParams.WRAP_CONTENT, upright ? 0 : 1.4f));

        side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        int gap = Math.round(12 * d);
        weather = label(c, 22, false);
        battery = label(c, 18, false);
        side.addView(card(c, weather, battery));
        song = label(c, 20, true);
        song.setSingleLine(true);
        songBy = label(c, 15, false);
        songBy.setSingleLine(true);
        songBy.setAlpha(0.7f);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.CENTER);
        for (String[] b : new String[][]{{"⏮", "previous"}, {"⏯", "play-pause"}, {"⏭", "next"}}) {
            TextView t = label(c, 30, false);
            t.setText(b[0]);
            t.setPadding(Math.round(18 * d), Math.round(6 * d), Math.round(18 * d), 0);
            t.setOnClickListener(v -> {
                Ui.haptic(v);
                link.media(b[1]);
                ui.postDelayed(() -> link.ask("media"), 400);
            });
            buttons.addView(t);
        }
        mediaCard = card(c, song, songBy, buttons);
        side.addView(mediaCard, margins(gap));
        pc = label(c, 17, false);
        side.addView(card(c, pc), margins(gap));
        focus = label(c, 30, true);
        focus.setFontFeatureSettings("tnum");
        focusCard = card(c, focus);
        side.addView(focusCard, margins(gap));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(upright ? LayoutParams.MATCH_PARENT : 0,
                LayoutParams.WRAP_CONTENT, upright ? 0 : 1f);
        if (upright) slp.topMargin = Math.round(28 * d);
        body.addView(side, slp);
        int p = Math.round(36 * d);
        body.setPadding(p, p, p, p);
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        weather.setOnClickListener(v -> askCity());
    }

    private static TextView label(Context c, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(Context c, android.view.View... kids) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        l.setBackground(Theme.round(0xFF1C1C1E, 22 * d));
        int p = Math.round(16 * d);
        l.setPadding(p, p, p, p);
        for (android.view.View k : kids) l.addView(k);
        return l;
    }

    private static LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    // ---- life ----

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        link.attach(getContext());
        link.start();
        link.on("media", this::media);
        link.on("stats", this::stats);
        FocusTimer.schedule(getContext());
        ui.post(second);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        ui.removeCallbacksAndMessages(null);
        link.on("media", null);
        link.on("stats", null);
        super.onDetachedFromWindow();
    }

    private final Runnable second = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;
            Calendar now = Calendar.getInstance();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.getTime()));
            date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now.getTime()));
            int h = now.get(Calendar.HOUR_OF_DAY);
            setNight(h >= 22 || h < 6);
            showFocus();
            if (tickCount % 3 == 0 && link.isConnected()) link.ask("media");
            if (tickCount % 5 == 0) {
                if (link.isConnected()) link.ask("stats");
                else pc.setText("💻 PC not connected");
                showBattery();
            }
            if (tickCount % 60 == 0) shift();
            if (System.currentTimeMillis() - weatherAt > 30 * 60_000L) loadWeather();
            tickCount++;
            ui.postDelayed(this, 1000);
        }
    };

    /** Night: everything red and dimmer, easy on the eyes in a dark room. */
    private void setNight(boolean on) {
        if (on == night) return;
        night = on;
        int color = on ? 0xFFFF3B30 : Color.WHITE;
        for (TextView t : new TextView[]{clock, date, weather, song, songBy, pc, battery, focus}) t.setTextColor(color);
        body.setAlpha(on ? 0.55f : 1f);
    }

    /** Moves everything a few pixels each minute against screen burn-in. */
    private void shift() {
        float d = getResources().getDisplayMetrics().density;
        body.animate().translationX((random.nextFloat() - 0.5f) * 16 * d)
                .translationY((random.nextFloat() - 0.5f) * 16 * d).setDuration(2000).start();
    }

    private void showFocus() {
        Context c = getContext();
        boolean on = FocusTimer.running(c);
        focusCard.setVisibility(on ? VISIBLE : GONE);
        if (on) {
            long s = (FocusTimer.remaining(c) + 999) / 1000;
            focus.setText(String.format(Locale.US, "🎯 %d:%02d", s / 60, s % 60));
        }
    }

    private void showBattery() {
        Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        battery.setText((charging ? "⚡ " : "🔋 ") + Math.round(level * 100f / scale) + " %");
    }

    private void media(JSONObject m) {
        String title = m.optString("title", "");
        boolean nothing = title.isEmpty() || "Nothing playing".equals(title) || "Media keys".equals(title);
        mediaCard.setVisibility(link.isConnected() ? VISIBLE : GONE);
        song.setText(nothing ? "🎵 Music on the PC" : "🎵 " + title);
        songBy.setText(nothing ? "" : m.optString("artist", ""));
    }

    private void stats(JSONObject s) {
        StringBuilder b = new StringBuilder("💻 ").append(link.host());
        if (s.has("cpu")) b.append("   CPU ").append(s.optInt("cpu")).append(" %");
        if (s.has("ram")) b.append("   RAM ").append(s.optInt("ram")).append(" %");
        if (s.has("temp")) b.append("   ").append(s.optInt("temp")).append(" °C");
        pc.setText(b);
    }

    // ---- weather (Open-Meteo: free, no account, no location permission) ----

    private void askCity() {
        android.widget.EditText field = new android.widget.EditText(getContext());
        field.setHint("e.g. Tehran");
        field.setText(prefs.getString("weather_city", ""));
        try {
            new android.app.AlertDialog.Builder(getContext()).setTitle("Weather for which city?").setView(field)
                    .setPositiveButton("OK", (d, w) -> {
                        prefs.edit().putString("weather_city", field.getText().toString().trim())
                                .remove("weather_lat").apply();
                        loadWeather();
                    }).setNegativeButton("Cancel", null).show();
        } catch (RuntimeException ignored) {  // the screen saver can't show dialogs
        }
    }

    private void loadWeather() {
        weatherAt = System.currentTimeMillis();
        String city = prefs.getString("weather_city", "");
        if (city.isEmpty()) {
            weather.setText("☁️ Tap to set your city");
            return;
        }
        new Thread(() -> {
            try {
                String lat = prefs.getString("weather_lat", ""), lon = prefs.getString("weather_lon", "");
                if (lat.isEmpty()) {
                    JSONArray r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + URLEncoder.encode(city, "UTF-8"))).optJSONArray("results");
                    if (r == null || r.length() == 0) {
                        ui.post(() -> weather.setText("☁️ City not found: tap to change"));
                        return;
                    }
                    lat = String.valueOf(r.getJSONObject(0).getDouble("latitude"));
                    lon = String.valueOf(r.getJSONObject(0).getDouble("longitude"));
                    prefs.edit().putString("weather_lat", lat).putString("weather_lon", lon).apply();
                }
                JSONObject now = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code&timezone=auto"))
                        .getJSONObject("current");
                String text = icon(now.optInt("weather_code")) + " " + Math.round(now.optDouble("temperature_2m"))
                        + "°  " + city;
                ui.post(() -> weather.setText(text));
            } catch (Exception e) {
                ui.post(() -> weather.setText("☁️ No weather (offline?)"));
                weatherAt = System.currentTimeMillis() - 25 * 60_000L;  // try again in 5 minutes
            }
        }, "weather").start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather codes as one emoji. */
    private static String icon(int code) {
        if (code == 0) return "☀️";
        if (code <= 2) return "🌤";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫";
        if (code <= 67 || (code >= 80 && code <= 82)) return "🌧";
        if (code <= 77 || code == 85 || code == 86) return "❄️";
        return "⛈";
    }
}

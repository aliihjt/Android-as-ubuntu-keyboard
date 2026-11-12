package io.github.aliihjt.tabletdesk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small widgets for the home screens: weather, the next calendar event, the
 * laptop's battery, and (on the Reader home) what the laptop is playing.
 */
final class HomeWidgets {
    private final Activity a;
    private final Theme th;
    private final boolean music;
    private final HostLink link = HostLink.get();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private TextView weather, event, laptop, song;
    private long weatherAt;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (laptop == null) return;  // not drawn yet: view() starts it
            if (link.isConnected()) {
                link.ask("stats");
                if (music) link.ask("media");
            } else {
                laptop.setText("💻 Not connected");
            }
            if (System.currentTimeMillis() - weatherAt > 30 * 60_000L) loadWeather();
            ui.postDelayed(this, 30_000);
        }
    };

    private boolean active;  // between resume() and pause()

    HomeWidgets(Activity a, Theme th, boolean music) {
        this.a = a;
        this.th = th;
        this.music = music;
        prefs = a.getSharedPreferences(OverlayService.PREFS, Activity.MODE_PRIVATE);
    }

    HorizontalScrollView view() {
        LinearLayout row = new LinearLayout(a);
        row.setBaselineAligned(false);  // else cards with 2-3 lines sit lower and get cut off
        weather = card(row, "☁️ Weather", v -> askCity());
        event = card(row, "📅 Calendar", v -> calendarTap());
        laptop = card(row, "💻 Laptop", v -> a.startActivity(new Intent(a, StatsActivity.class)));
        power = card(row, "🔋 Batteries", v -> a.startActivity(new Intent(a, DevicesActivity.class)));
        showBatteries();
        if (music) song = card(row, "🎵 Nothing playing", v -> link.media("play-pause"));
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        if (active) {  // resume() came first (the iPad home draws its pages a moment later)
            showEvent();
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
        return scroll;
    }

    private TextView card(LinearLayout row, String text, android.view.View.OnClickListener tap) {
        TextView t = th.text(text, 14, th.text, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMaxLines(3);
        t.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(10));
        t.setBackground(th.surface(th.card, th.dp(16)));
        t.setMinHeight(th.dp(64));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(170), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = th.dp(10);
        th.click(t, tap);
        row.addView(t, lp);
        return t;
    }

    private TextView power;

    /** Every device's battery (from the PC), like the iPhone's Batteries widget. */
    private void showBatteries() {
        if (power == null) return;
        String text = Devices.batteryText();
        power.setText(text.isEmpty() ? "🔋 Batteries\nConnect to the laptop" : text);
    }

    void resume() {
        active = true;
        Devices.batteryListener = this::showBatteries;
        try {
            link.send(new org.json.JSONObject().put("t", "batteries?"));
        } catch (org.json.JSONException ignored) {
        }
        link.on("stats", s -> {
            if (laptop == null) return;
            StringBuilder b = new StringBuilder("💻 ").append(link.host());
            if (s.has("battery")) b.append("\n").append(s.optBoolean("charging") ? "⚡ " : "🔋 ").append(s.optInt("battery")).append(" %");
            if (s.has("cpu")) b.append("  ·  CPU ").append(s.optInt("cpu")).append(" %");
            laptop.setText(b);
        });
        if (music) link.on("media", m -> {
            if (song == null) return;
            String title = m.optString("title");
            boolean none = title.isEmpty() || "Missing".equals(m.optString("status")) || "Stopped".equals(m.optString("status"));
            song.setText(none ? "🎵 Nothing playing" : ("Playing".equals(m.optString("status")) ? "⏸ " : "▶ ") + title
                    + (m.optString("artist").isEmpty() ? "" : "\n" + m.optString("artist")));
        });
        showEvent();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    void pause() {
        active = false;
        Devices.batteryListener = null;
        ui.removeCallbacks(tick);
        link.on("stats", null);
        if (music) link.on("media", null);
    }

    // ---- weather (Open-Meteo, the same city as StandBy)

    private void askCity() {
        EditText field = new EditText(a);
        field.setHint("e.g. Tehran");
        field.setText(prefs.getString("weather_city", ""));
        new AlertDialog.Builder(a).setTitle("Weather for which city?").setView(field)
                .setPositiveButton("OK", (d, w) -> {
                    prefs.edit().putString("weather_city", field.getText().toString().trim()).remove("weather_lat").apply();
                    loadWeather();
                }).setNegativeButton("Cancel", null).show();
    }

    private void loadWeather() {
        weatherAt = System.currentTimeMillis();
        String city = prefs.getString("weather_city", "");
        if (city.isEmpty()) {
            weather.setText("☁️ Weather\nTap to set your city");
            return;
        }
        new Thread(() -> {
            try {
                String lat = prefs.getString("weather_lat", ""), lon = prefs.getString("weather_lon", "");
                if (lat.isEmpty()) {
                    JSONArray r = new JSONObject(StandByView.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + URLEncoder.encode(city, "UTF-8"))).optJSONArray("results");
                    if (r == null || r.length() == 0) {
                        ui.post(() -> weather.setText("☁️ City not found\nTap to change"));
                        return;
                    }
                    lat = String.valueOf(r.getJSONObject(0).getDouble("latitude"));
                    lon = String.valueOf(r.getJSONObject(0).getDouble("longitude"));
                    prefs.edit().putString("weather_lat", lat).putString("weather_lon", lon).apply();
                }
                JSONObject j = new JSONObject(StandByView.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"));
                JSONObject now = j.getJSONObject("current");
                JSONObject day = j.optJSONObject("daily");
                String range = day == null ? "" : "\nH " + Math.round(day.getJSONArray("temperature_2m_max").getDouble(0))
                        + "°  L " + Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)) + "°";
                String text = StandByView.icon(now.optInt("weather_code")) + " " + Math.round(now.optDouble("temperature_2m"))
                        + "°  " + city + range;
                ui.post(() -> weather.setText(text));
            } catch (Exception e) {
                ui.post(() -> weather.setText("☁️ No weather\n(offline?)"));
                weatherAt = System.currentTimeMillis() - 25 * 60_000L;
            }
        }, "weather").start();
    }

    // ---- the next event in the tablet's calendar

    private boolean calendarAllowed() {
        return a.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private void calendarTap() {
        if (!calendarAllowed()) {
            a.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, 41);
            return;
        }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                    .appendPath(String.valueOf(System.currentTimeMillis())).build()));
        } catch (RuntimeException ignored) {
        }
    }

    void showEvent() {
        if (event == null) return;  // not drawn yet
        if (!calendarAllowed()) {
            event.setText("📅 Calendar\nTap to show your events");
            return;
        }
        long now = System.currentTimeMillis();
        String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY};
        try (Cursor c = CalendarContract.Instances.query(a.getContentResolver(), cols, now, now + 3 * 86_400_000L)) {
            String text = "📅 Nothing in the next 3 days";
            if (c != null) {
                java.util.TreeMap<Long, String> next = new java.util.TreeMap<>();
                while (c.moveToNext()) {
                    long begin = c.getLong(1);
                    if (begin >= now - 30 * 60_000L || c.getInt(2) == 1) next.put(begin, c.getString(0));
                }
                if (!next.isEmpty()) {
                    long at = next.firstKey();
                    String when = new SimpleDateFormat(sameDay(at, now) ? "HH:mm" : "EEE HH:mm", Locale.getDefault()).format(new Date(at));
                    text = "📅 " + when + "\n" + next.firstEntry().getValue();
                }
            }
            event.setText(text);
        } catch (RuntimeException e) {
            event.setText("📅 Calendar");
        }
    }

    private static boolean sameDay(long a, long b) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        return f.format(new Date(a)).equals(f.format(new Date(b)));
    }
}

package io.github.aliihjt.tabletdesk;

import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

/** Live numbers from the PC, refreshed every 1.5 seconds. */
public class StatsActivity extends Page {
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            link.ask("stats");
            poll.postDelayed(this, 1500);
        }
    };
    private Metric cpu, ram, temp, disk, battery;
    private TextView network, uptime;

    @Override
    protected String title() {
        return "Stats";
    }

    @Override
    protected void build() {
        cpu = new Metric("⚙️", Theme.BLUE, "Processor");
        ram = new Metric("🧠", Theme.PURPLE, "Memory");
        temp = new Metric("🌡", Theme.ORANGE, "Temperature");
        disk = new Metric("💾", Theme.TEAL, "Disk");
        battery = new Metric("🔋", Theme.GREEN, "Battery");
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(cpu.card, th.weighted(0));
        r1.addView(ram.card, th.weighted(12));
        add(r1, 6);
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(temp.card, th.weighted(0));
        r2.addView(disk.card, th.weighted(12));
        add(r2, 12);
        LinearLayout r3 = new LinearLayout(this);
        r3.addView(battery.card, th.weighted(0));
        LinearLayout net = th.card();
        int p = th.dp(16);
        net.setPadding(p, p, p, p);
        net.addView(th.iconChip("📶", Theme.INDIGO, 36));
        net.addView(th.caption("Network"), th.fullWidth(12));
        network = th.text("…", 18, th.text, true);
        net.addView(network, th.fullWidth(4));
        uptime = th.caption("");
        net.addView(uptime, th.fullWidth(6));
        r3.addView(net, th.weighted(12));
        add(r3, 12);
    }

    /** A card with a big number and a bar. */
    private final class Metric {
        final LinearLayout card;
        final TextView value;
        final ProgressBar bar;

        Metric(String symbol, int color, String name) {
            card = th.card();
            int p = th.dp(16);
            card.setPadding(p, p, p, p);
            card.addView(th.iconChip(symbol, color, 36));
            card.addView(th.caption(name), th.fullWidth(12));
            value = th.text("…", 30, th.text, true);
            card.addView(value, th.fullWidth(4));
            bar = new ProgressBar(StatsActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressTintList(ColorStateList.valueOf(color));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(th.fill));
            card.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, th.dp(8)));
        }

        void set(JSONObject s, String key, String text, int percent) {
            card.setVisibility(s.has(key) ? View.VISIBLE : View.INVISIBLE);
            value.setText(text);
            bar.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private static String speed(int kbs) {
        return kbs >= 1024 ? String.format(java.util.Locale.US, "%.1f MB/s", kbs / 1024f) : kbs + " KB/s";
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("stats", s -> {
            cpu.set(s, "cpu", s.optInt("cpu") + "%", s.optInt("cpu"));
            ram.set(s, "ram", s.optInt("ram") + "%", s.optInt("ram"));
            temp.set(s, "temp", s.optInt("temp") + "°C", s.optInt("temp"));
            disk.set(s, "disk", s.optInt("disk_free_gb") + " GB free", s.optInt("disk"));
            battery.set(s, "battery", (s.optBoolean("charging") ? "⚡" : "") + s.optInt("battery") + "%",
                    s.optInt("battery"));
            network.setText("↓ " + speed(s.optInt("down_kbs")) + "   ↑ " + speed(s.optInt("up_kbs")));
            int min = s.optInt("uptime_min");
            uptime.setText("On for " + (min / 60) + " h " + (min % 60) + " min");
        });
        poll.post(tick);
    }

    @Override
    protected void onPause() {
        poll.removeCallbacks(tick);
        link.on("stats", null);
        super.onPause();
    }
}

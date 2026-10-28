package io.github.aliihjt.tabletdesk;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Look and options, grouped like iPad Settings. */
public class SettingsActivity extends Page {
    private SharedPreferences prefs;

    @Override
    protected String title() {
        return "Settings";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);

        section("Screen");
        add(th.segmented(new String[][]{{"extend", "Second screen"}, {"mirror", "Same picture"},
                {"input", "Keyboard only"}}, prefs.getString("mode", "extend"), m -> {
            prefs.edit().putString("mode", m).apply();
            link.mode(m);
        }));

        section("Floating button");
        add(th.group(
                th.row("⌨️", Theme.BLUE, "Show floating button", "Over the Fedora screen, for keyboard and trackpad",
                        th.toggle(prefs.getBoolean("bubble", true), on -> {
                            prefs.edit().putBoolean("bubble", on).apply();
                            OverlayService.stop(this);
                            if (on) getWindow().getDecorView().postDelayed(() -> OverlayService.start(this), 400);
                        }))));

        section("Text size");
        add(th.segmented(new String[][]{{"100", "Small"}, {"115", "Medium"}, {"135", "Large"}},
                String.valueOf(prefs.getInt("uiscale", 115)), v -> {
                    prefs.edit().putInt("uiscale", Integer.parseInt(v)).apply();
                    recreate();
                }));

        section("Appearance");
        add(th.segmented(new String[][]{{"auto", "Automatic"}, {"light", "Light"}, {"dark", "Dark"}},
                prefs.getString("theme", "auto"), v -> {
                    prefs.edit().putString("theme", v).apply();
                    recreate();
                }));

        section("Trackpad");
        add(th.group(
                th.sliderRow("🖱", Theme.PURPLE, "Speed",
                        th.slider(250, prefs.getInt("speed", 100) - 50,
                                v -> prefs.edit().putInt("speed", v + 50).apply())),
                pref("↕️", Theme.INDIGO, "Natural scrolling", "Content follows your fingers", "natural"),
                pref("📳", Theme.GRAY, "Vibrate on tap", null, "haptics")));

        section("Wi-Fi mode");
        String wifiState = link.wifiPaired()
                ? "Paired. Unplug and Fedora Pad keeps working on the same Wi-Fi."
                : "Not paired. On Fedora run host/wifi-setup.sh, then plug the tablet in once.";
        add(th.group(
                th.row("\uD83D\uDCF6", Theme.BLUE, "Use Wi-Fi without the cable", wifiState,
                        th.toggle(prefs.getBoolean("wifi_use", true),
                                on -> prefs.edit().putBoolean("wifi_use", on).apply())),
                link(th.row("\uD83D\uDDD1", Theme.RED, "Forget Wi-Fi pairing", null, th.chevron()), () -> {
                    link.forgetWifi();
                    recreate();
                })));

        section("Connection");
        add(th.group(
                link(th.row("🔄", Theme.GREEN, "Reconnect the Fedora screen", null, th.chevron()),
                        () -> MainActivity.openRdp(this)),
                link(th.row("⏹", Theme.RED, "Stop Fedora Pad on this tablet", "Until the next plug-in", th.chevron()),
                        () -> OverlayService.stop(this))));

        section("First-time setup");
        TextView steps = th.caption(
                "1  Tablet: Settings › About › tap Build number 7 times, then Developer options › USB debugging on.\n"
                        + "2  Fedora: run host/setup.sh, then log out and back in.\n"
                        + "3  Plug in the USB-C cable and allow USB debugging (tick “Always allow”).\n"
                        + "4  Install “Windows App” and add the PC 127.0.0.1 with the password setup.sh printed.");
        LinearLayout box = th.card();
        int p = th.dp(16);
        box.setPadding(p, p, p, p);
        box.addView(steps);
        add(box);

        section("About");
        add(th.group(
                th.row("📱", Theme.BLUE, "Fedora Pad", "Build " + buildNumber(), null),
                link(th.row("🐙", Theme.GRAY, "Project on GitHub", null, th.chevron()),
                        () -> startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/aliihjt/Android-as-ubuntu-keyboard"))))));
    }

    private LinearLayout pref(String sym, int color, String title, String sub, String key) {
        return th.row(sym, color, title, sub, th.toggle(prefs.getBoolean(key, true), on -> {
            prefs.edit().putBoolean(key, on).apply();
            Ui.loadScale(this);
        }));
    }

    private LinearLayout link(LinearLayout row, Runnable run) {
        th.click(row, v -> run.run());
        return row;
    }

    private String buildNumber() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return String.valueOf(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}

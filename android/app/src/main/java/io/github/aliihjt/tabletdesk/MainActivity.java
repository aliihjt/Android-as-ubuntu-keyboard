package io.github.aliihjt.tabletdesk;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Home screen: start the Mac bars, open the trackpad, open the desktop screen. */
public class MainActivity extends Activity implements HostLink.Listener {
    private static final int FG = 0xFF1D1D1F;
    private static final int MUTED = 0xFF6E6E73;
    private static final String AVNC = "com.gaurav.avnc";
    /** RDP clients, best first: Microsoft "Windows App", then aFreeRDP. */
    private static final String[] RDP = {"com.microsoft.rdc.androidx", "com.freerdp.afreerdp"};

    private TextView status;
    private TextView barsButton;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        Ui.loadScale(this);
        HostLink.get().start();

        // Launched by host/connect.sh when the cable is plugged in.
        if (getIntent().getBooleanExtra("autostart", false) && Settings.canDrawOverlays(this)) {
            OverlayService.start(this);
            finish();
            return;
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int m = Ui.dp(this, 24);
        col.setPadding(m, Ui.dp(this, 48), m, m);

        col.addView(Ui.label(this, "TabletDesk", 30, FG, true));
        col.addView(Ui.label(this, "Build " + buildNumber(), 13, MUTED, false));
        TextView sub = Ui.label(this, "Your tablet as Fedora's screen, keyboard, trackpad & Touch Bar — over one USB-C cable",
                15, MUTED, false);
        sub.setSingleLine(false);
        col.addView(sub);
        status = Ui.label(this, "", 15, FG, true);
        status.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        col.addView(status);

        col.addView(section("Mode (used every time you plug in)"));
        col.addView(modePicker());

        barsButton = button("", v -> toggleBars());
        col.addView(barsButton);
        col.addView(button("⌨  Keyboard & Trackpad",
                v -> startActivity(new Intent(this, TrackpadActivity.class))));
        col.addView(button("🖥  Open desktop screen (RDP — Fedora / Wayland)", v -> openRdp()));
        col.addView(button("🖥  Open desktop screen (VNC — Xorg session)", v -> openVnc()));

        col.addView(section("Options"));
        col.addView(sizePicker());
        col.addView(check("Show macOS menu bar (top)", "menubar"));
        col.addView(check("Show Touch Bar (bottom)", "touchbar"));
        col.addView(check("Auto-hide bars when not used (like macOS full screen)", "autohide"));
        TextView offsetLabel = Ui.label(this, "", 15, FG, false);
        col.addView(offsetLabel);
        SeekBar offset = new SeekBar(this);
        offset.setMax(120);
        offset.setProgress(prefs.getInt("menuoffset", 0));
        offsetLabel.setText("Menu bar position: " + prefs.getInt("menuoffset", 0) + " dp from top");
        offset.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                offsetLabel.setText("Menu bar position: " + p + " dp from top");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("menuoffset", s.getProgress()).apply();
                if (OverlayService.running) OverlayService.start(MainActivity.this);  // move it now
            }
        });
        col.addView(offset);

        TextView speedLabel = Ui.label(this, "", 15, FG, false);
        col.addView(speedLabel);
        SeekBar speed = new SeekBar(this);
        speed.setMax(250);
        speed.setProgress(prefs.getInt("speed", 100) - 50);
        speedLabel.setText("Trackpad speed: " + prefs.getInt("speed", 100) + "%");
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                prefs.edit().putInt("speed", p + 50).apply();
                speedLabel.setText("Trackpad speed: " + (p + 50) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        col.addView(speed);

        col.addView(section("Setup (once)"));
        TextView help = Ui.label(this,
                "1. Tablet: Settings › About › tap Build number 7×, then Developer options › USB debugging ON.\n"
                        + "2. Fedora: run host/setup.sh, then log out and back in.\n"
                        + "3. Connect the USB-C cable (it charges the tablet and carries all data). Accept “Allow USB debugging”.\n"
                        + "4. Fedora: host/connect.sh extend   (or mirror / input).\n"
                        + "5. Install “Windows App” (or aFreeRDP) and add PC 127.0.0.1 with the user/password setup.sh printed.",
                14, MUTED, false);
        help.setSingleLine(false);
        help.setLineSpacing(0, 1.25f);
        col.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF5F5F7);
        scroll.addView(col);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HostLink.get().addListener(this);
        refreshBarsButton();
    }

    @Override
    protected void onPause() {
        HostLink.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onStatus(boolean connected, String host) {
        if (connected && prefs.contains("mode")) HostLink.get().mode(prefs.getString("mode", "extend"));
        status.setText(connected ? "● Connected to " + host + " (USB-C)"
                : "○ Waiting for Fedora — plug in the cable and run host/connect.sh");
        status.setTextColor(connected ? 0xFF1E8E3E : 0xFFC5221F);
    }

    @Override
    public void onApp(String name) {
    }

    /** Same number as the GitHub Actions build that made this APK. */
    private String buildNumber() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return String.valueOf(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void toggleBars() {
        if (OverlayService.running) {
            OverlayService.stop(this);
            barsButton.postDelayed(this::refreshBarsButton, 300);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        OverlayService.start(this);
        barsButton.postDelayed(this::refreshBarsButton, 300);
    }

    private void refreshBarsButton() {
        barsButton.setText(OverlayService.running
                ? "■  Hide menu bar & Touch Bar"
                : "▶  Show macOS menu bar & Touch Bar");
    }

    private void openVnc() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnc://127.0.0.1:5900")));
        } catch (ActivityNotFoundException e) {
            openStore(AVNC);
        }
    }

    private void openRdp() {
        for (String pkg : RDP) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                startActivity(i);
                return;
            }
        }
        openStore(RDP[0]);
    }

    private void openStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView b = Ui.label(this, text, 16, FG, false);
        int p = Ui.dp(this, 16);
        b.setPadding(p, p, p, p);
        b.setBackground(Ui.pressable(0xFFFFFFFF, Ui.dp(this, 12), 0x22000000));
        b.setElevation(Ui.dp(this, 1));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        b.setLayoutParams(lp);
        return b;
    }

    /** Interface size for the bars, menus, Control Center and trackpad. */
    private LinearLayout sizePicker() {
        int[] sizes = {100, 135, 165};
        String[] names = {"A  Normal", "A  Large", "A  Extra large"};
        LinearLayout row = new LinearLayout(this);
        int current = prefs.getInt("uiscale", 135);
        for (int i = 0; i < sizes.length; i++) {
            int size = sizes[i];
            TextView t = Ui.label(this, names[i], 12 + i * 2, size == current ? 0xFFFFFFFF : FG, true);
            t.setGravity(android.view.Gravity.CENTER);
            int p = Ui.dp(this, 10);
            t.setPadding(p, p, p, p);
            t.setBackground(Ui.pressable(size == current ? 0xFF0A84FF : 0xFFFFFFFF, Ui.dp(this, 12), 0x22000000));
            t.setOnClickListener(v -> {
                prefs.edit().putInt("uiscale", size).apply();
                if (OverlayService.running) OverlayService.start(this);  // rebuild the bars
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 8));
            row.addView(t, lp);
        }
        return row;
    }

    private LinearLayout modePicker() {
        String[][] modes = {
                {"extend", "\uD83D\uDDA5\uD83D\uDDA5  Extend", "2nd screen"},
                {"mirror", "\uD83E\uDE9E  Mirror", "same picture"},
                {"input", "\u2328  Input only", "keyboard + trackpad"}};
        LinearLayout row = new LinearLayout(this);
        TextView[] cells = new TextView[modes.length];
        for (int i = 0; i < modes.length; i++) {
            String m = modes[i][0];
            TextView t = Ui.label(this, modes[i][1] + "\n" + modes[i][2], 14, FG, false);
            t.setSingleLine(false);
            t.setGravity(android.view.Gravity.CENTER);
            int p = Ui.dp(this, 12);
            t.setPadding(p, p, p, p);
            t.setOnClickListener(v -> {
                prefs.edit().putString("mode", m).apply();
                HostLink.get().mode(m);
                paintModes(cells, modes);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            row.addView(t, lp);
            cells[i] = t;
        }
        paintModes(cells, modes);
        return row;
    }

    private void paintModes(TextView[] cells, String[][] modes) {
        String current = prefs.getString("mode", "extend");
        for (int i = 0; i < cells.length; i++) {
            boolean on = modes[i][0].equals(current);
            cells[i].setBackground(Ui.pressable(on ? 0xFF0A84FF : 0xFFFFFFFF, Ui.dp(this, 12), 0x22000000));
            cells[i].setTextColor(on ? 0xFFFFFFFF : FG);
        }
    }

    private TextView section(String title) {
        TextView t = Ui.label(this, title, 18, FG, true);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        return t;
    }

    private CheckBox check(String text, String key) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(15 * Ui.scale);
        c.setTextColor(FG);
        c.setChecked(prefs.getBoolean(key, true));
        c.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (OverlayService.running) OverlayService.start(this);  // re-apply
        });
        return c;
    }
}

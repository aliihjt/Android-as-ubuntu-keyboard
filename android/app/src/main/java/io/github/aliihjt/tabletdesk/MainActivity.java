package io.github.aliihjt.tabletdesk;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Home screen: a glass dashboard with connection status, quick actions and all
 * settings, in the same Liquid Glass style as the menu bar and Touch Bar.
 */
public class MainActivity extends Activity implements HostLink.Listener {
    private static final int ACCENT = 0xFF0A84FF;
    private static final String AVNC = "com.gaurav.avnc";
    /** RDP clients, best first: Microsoft "Windows App", then aFreeRDP. */
    private static final String[] RDP = {"com.microsoft.rdc.androidx", "com.freerdp.afreerdp"};

    private SharedPreferences prefs;
    private boolean dark;
    private int fg;
    private int muted;
    private TextView statusTitle;
    private TextView statusText;
    private View statusDot;
    private TextView startButton;

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

        dark = Ui.isDark(this);
        fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;
        muted = dark ? 0x99EBEBF5 : 0x993C3C43;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int m = Ui.dp(this, 18);
        col.setPadding(m, Ui.dp(this, 36), m, Ui.dp(this, 40));

        col.addView(header());
        col.addView(statusCard(), block(18));
        col.addView(quickTiles(), block(14));

        col.addView(tipsCard(), block(14));

        // everything else folds away under "Settings" so the screen stays simple
        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        boolean open = prefs.getBoolean("show_settings", false);
        settings.setVisibility(open ? View.VISIBLE : View.GONE);
        TextView settingsButton = pill(open ? "\u2699\uFE0F  Hide settings  \u25B4" : "\u2699\uFE0F  Settings  \u25BE",
                false, v -> {
                    boolean show = settings.getVisibility() != View.VISIBLE;
                    settings.setVisibility(show ? View.VISIBLE : View.GONE);
                    prefs.edit().putBoolean("show_settings", show).apply();
                    ((TextView) v).setText(show ? "\u2699\uFE0F  Hide settings  \u25B4" : "\u2699\uFE0F  Settings  \u25BE");
                });
        LinearLayout.LayoutParams sbl = block(18);
        sbl.height = Ui.dp(this, 48);
        col.addView(settingsButton, sbl);
        col.addView(settings);

        settings.addView(section("Screen"));
        settings.addView(card(segmented(new String[][]{
                {"extend", "🖥🖥  Extend"}, {"mirror", "🪞  Mirror"},
                {"input", "⌨  Input only"}}, prefs.getString("mode", "extend"), mode -> {
            prefs.edit().putString("mode", mode).apply();
            HostLink.get().mode(mode);
        }), hint("Extend = second screen · Mirror = same picture · Input = keyboard & trackpad only")));

        settings.addView(section("Laptop"));
        settings.addView(card(
                toggle("Laptop keyboard off while connected", "The tablet becomes your keyboard",
                        !HostLink.get().lastState().optBoolean("laptopkbd", false),
                        on -> HostLink.get().set("laptopkbd", on ? 0 : 1)),
                toggle("Touch screen", "Touch the Fedora picture directly",
                        HostLink.get().lastState().optBoolean("touch", true),
                        on -> HostLink.get().set("touch", on ? 1 : 0))));

        settings.addView(section("Bars"));
        settings.addView(card(
                prefToggle("Menu bar", "macOS-style menus at the top", "menubar", false),
                prefToggle("Touch Bar", "Context keys at the bottom", "touchbar", false),
                prefToggle("Auto-hide", "Bars tuck away when not used", "autohide", false)));

        settings.addView(section("Look"));
        settings.addView(card(
                label("Size"),
                segmented(new String[][]{{"100", "A  Normal"}, {"135", "A  Large"}, {"165", "A  Extra large"}},
                        String.valueOf(prefs.getInt("uiscale", 135)), v -> {
                            prefs.edit().putInt("uiscale", Integer.parseInt(v)).apply();
                            restyle();
                        }),
                gap(),
                label("Theme"),
                segmented(new String[][]{{"auto", "◐  Auto"}, {"light", "☀️  Light"},
                        {"dark", "🌙  Dark"}}, prefs.getString("theme", "auto"), v -> {
                    prefs.edit().putString("theme", v).apply();
                    restyle();
                })));

        settings.addView(section("Trackpad"));
        settings.addView(card(
                slider("Speed", "speed", 50, 300, "%"),
                prefToggle("Natural scrolling", "Content follows your fingers", "natural", false),
                prefToggle("Vibrate on tap", "Feel each key and click", "haptics", true)));

        settings.addView(section("Desktop bars"));
        settings.addView(card(pill("\u25A0  Stop the menu bar and Touch Bar", false, v -> {
            OverlayService.stop(this);
            startButton.postDelayed(this::refreshStart, 300);
        })));

        settings.addView(section("First-time setup"));
        TextView help = Ui.label(this,
                "1  Tablet: Settings › About › tap Build number 7×, then Developer options › USB debugging on.\n"
                        + "2  Fedora: run host/setup.sh, then log out and back in.\n"
                        + "3  Plug in the USB-C cable and allow USB debugging (tick “Always allow”).\n"
                        + "4  Install “Windows App” and add the PC 127.0.0.1 with the password setup.sh printed.\n"
                        + "From then on everything starts by itself when you plug in.",
                13.5f, muted, false);
        help.setSingleLine(false);
        help.setLineSpacing(0, 1.35f);
        settings.addView(card(help));

        // keep it readable on wide tablets: centered column, at most ~760dp wide
        FrameLayout center = new FrameLayout(this);
        int maxWidth = Math.round(760 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.min(maxWidth, getResources().getDisplayMetrics().widthPixels),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        center.addView(col, clp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, dark
                ? new int[]{0xFF0F172A, 0xFF231A3A, 0xFF0B2B33}
                : new int[]{0xFFE8EEFB, 0xFFF5E9F3, 0xFFE4F3F0}));
        scroll.addView(center);
        setContentView(scroll);
        // let the gradient run under a see-through status bar with matching icons
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HostLink.get().addListener(this);
        refreshStart();
    }

    @Override
    protected void onPause() {
        HostLink.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onStatus(boolean connected, String host) {
        if (connected && prefs.contains("mode")) HostLink.get().mode(prefs.getString("mode", "extend"));
        statusTitle.setText(connected ? "Connected to " + host : "Waiting for Fedora");
        statusText.setText(connected
                ? "USB-C · charging and sharing input"
                : "Plug in the USB-C cable — Fedora starts everything by itself");
        statusDot.setBackground(Ui.rounded(connected ? 0xFF30D158 : 0xFFFF9F0A, Ui.dp(this, 6)));
    }

    @Override
    public void onApp(String name) {
    }

    // ---- sections ----

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView title = Ui.label(this, "TabletDesk", 28, fg, true);
        titles.addView(title);
        titles.addView(Ui.label(this, "Fedora on your tablet · Build " + buildNumber(), 13, muted, false));
        row.addView(titles);
        return row;
    }

    private View statusCard() {
        LinearLayout card = cardBase();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        top.addView(statusDot, new LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 12), 0, 0, 0);
        statusTitle = Ui.label(this, "", 19, fg, true);
        statusText = Ui.label(this, "", 13, muted, false);
        statusText.setSingleLine(false);
        texts.addView(statusTitle);
        texts.addView(statusText);
        top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        LinearLayout startRow = new LinearLayout(this);
        startButton = pill("", true, v -> startEverything());
        startButton.setOnLongClickListener(v -> {  // Xorg sessions use VNC instead
            openVnc();
            return true;
        });
        startRow.addView(startButton, new LinearLayout.LayoutParams(0, Ui.dp(this, 56), 1f));
        card.addView(startRow, block(14));
        LinearLayout buttons = new LinearLayout(this);
        TextView keyboard = pill("\u2328\uFE0F  Keyboard", false, v -> openFloatingKeyboard());
        buttons.addView(keyboard, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        View g2 = new View(this);
        buttons.addView(g2, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
        TextView trackpad = pill("\uD83D\uDDB1  Trackpad", false,
                v -> startActivity(new Intent(this, TrackpadActivity.class)));
        buttons.addView(trackpad, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));
        card.addView(buttons, block(10));
        onStatus(HostLink.get().isConnected(), HostLink.get().host());
        return card;
    }

    private View quickTiles() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(tile("⌨️", "Keyboard & Trackpad", "Type and move the mouse",
                v -> startActivity(new Intent(this, TrackpadActivity.class))), weighted(false));
        r1.addView(tile("🎛️", "Control Center", "Volume, brightness, switches",
                v -> openControlCenter()), weighted(true));
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(tile("📋", "Send clipboard", "Tablet → PC, paste with Ctrl+V",
                v -> sendClipboard()), weighted(false));
        r2.addView(tile("📥", "Get clipboard", "PC → tablet",
                v -> getClipboard()), weighted(true));
        grid.addView(r1);
        grid.addView(r2, block(10));
        return grid;
    }

    // ---- actions ----

    private void toggleBars() {
        if (OverlayService.running) {
            OverlayService.stop(this);
            startButton.postDelayed(this::refreshStart, 300);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” for TabletDesk", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        OverlayService.start(this);
        startButton.postDelayed(this::refreshStart, 300);
    }

    /** One tap: menu bar + Touch Bar, then the Fedora screen. */
    private void startEverything() {
        if (!Settings.canDrawOverlays(this)) {
            toggleBars();  // asks for the permission first
            return;
        }
        if (!OverlayService.running) OverlayService.start(this);
        startButton.postDelayed(this::refreshStart, 300);
        openRdp();
    }

    private void openFloatingKeyboard() {
        if (!Settings.canDrawOverlays(this)) {
            toggleBars();
            return;
        }
        OverlayService.toggleKeyboard(this);
        openRdp();  // the keyboard floats over the Fedora screen
    }

    private View tipsCard() {
        TextView t = Ui.label(this,
                "\u2460  Plug in the USB-C cable\n"
                        + "\u2461  Tap \u25B6 Start \u2014 the Fedora screen opens\n"
                        + "\u2462  Tap \u2328 Keyboard for a keyboard on top of it, or \uD83D\uDDB1 Trackpad for the full page\n"
                        + "Every option is also in the TabletDesk menu at the top of the screen.",
                13.5f, muted, false);
        t.setSingleLine(false);
        t.setLineSpacing(0, 1.4f);
        return card(t);
    }

    private void refreshStart() {
        if (startButton != null) {
            startButton.setText(OverlayService.running ? "\uD83D\uDDA5  Show Fedora screen" : "\u25B6  Start");
        }
    }

    /** Rebuild the bars and this screen after a look change. */
    private void restyle() {
        if (OverlayService.running) OverlayService.start(this);
        recreate();
    }

    private void openControlCenter() {
        if (!Settings.canDrawOverlays(this)) {
            toggleBars();  // asks for the permission
            return;
        }
        ControlCenter.toggle(this, dark, () -> { }, null);
    }

    private void sendClipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "Tablet clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        HostLink.get().setClip(clip.getItemAt(0).coerceToText(this).toString());
        Toast.makeText(this, "Copied to the PC — paste with Ctrl+V", Toast.LENGTH_SHORT).show();
    }

    private void getClipboard() {
        HostLink.get().requestClip(text -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("PC", text));
            Toast.makeText(this, "PC clipboard copied to the tablet", Toast.LENGTH_SHORT).show();
        });
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

    /** Same number as the GitHub Actions build that made this APK. */
    private String buildNumber() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return String.valueOf(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ---- building blocks ----

    private LinearLayout cardBase() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        c.setPadding(p, p, p, p);
        c.setBackground(Glass.surface(this, dark, Ui.dp(this, 22), true));
        c.setElevation(Ui.dp(this, 2));
        return c;
    }

    private View card(View... children) {
        LinearLayout c = cardBase();
        for (int i = 0; i < children.length; i++) {
            c.addView(children[i], block(i == 0 ? 0 : 6));
        }
        return c;
    }

    private TextView section(String title) {
        TextView t = Ui.label(this, title.toUpperCase(java.util.Locale.ROOT), 12.5f, muted, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 22), 0, Ui.dp(this, 8));
        return t;
    }

    private TextView label(String text) {
        return Ui.label(this, text, 14, fg, true);
    }

    private TextView hint(String text) {
        TextView t = Ui.label(this, text, 12.5f, muted, false);
        t.setSingleLine(false);
        return t;
    }

    private View gap() {
        View v = new View(this);
        v.setMinimumHeight(Ui.dp(this, 6));
        return v;
    }

    private TextView pill(String text, boolean primary, View.OnClickListener l) {
        TextView t = Ui.label(this, text, 15, primary ? 0xFFFFFFFF : fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x40FFFFFF),
                primary ? Ui.rounded(ACCENT, Ui.dp(this, 24)) : Glass.chip(this, dark, Ui.dp(this, 24), false),
                null));
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            l.onClick(v);
        });
        return t;
    }

    private View tile(String icon, String title, String subtitle, View.OnClickListener l) {
        LinearLayout t = cardBase();
        t.addView(Ui.label(this, icon, 24, fg, false));
        TextView name = Ui.label(this, title, 15, fg, true);
        name.setPadding(0, Ui.dp(this, 8), 0, 0);
        t.addView(name);
        TextView sub = Ui.label(this, subtitle, 12, muted, false);
        sub.setSingleLine(false);
        t.addView(sub);
        t.setForeground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33808080),
                null, Ui.rounded(0xFFFFFFFF, Ui.dp(this, 22))));
        t.setOnClickListener(v -> {
            Ui.haptic(v);
            l.onClick(v);
        });
        return t;
    }

    /** Glass segmented control; the selected chip is blue. */
    private View segmented(String[][] options, String current, java.util.function.Consumer<String> pick) {
        LinearLayout row = new LinearLayout(this);
        int p = Ui.dp(this, 4);
        row.setPadding(p, p, p, p);
        row.setBackground(Glass.chip(this, dark, Ui.dp(this, 16), false));
        TextView[] chips = new TextView[options.length];
        String[] selected = {current};
        Runnable paint = () -> {
            for (int i = 0; i < chips.length; i++) {
                boolean on = options[i][0].equals(selected[0]);
                chips[i].setBackground(on ? Ui.rounded(ACCENT, Ui.dp(this, 12)) : null);
                chips[i].setTextColor(on ? 0xFFFFFFFF : fg);
            }
        };
        for (int i = 0; i < options.length; i++) {
            String value = options[i][0];
            TextView c = Ui.label(this, options[i][1], 13.5f, fg, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
            c.setOnClickListener(v -> {
                Ui.haptic(v);
                selected[0] = value;
                paint.run();
                pick.accept(value);
            });
            chips[i] = c;
            row.addView(c, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        paint.run();
        return row;
    }

    private View toggle(String title, String subtitle, boolean on,
                        java.util.function.Consumer<Boolean> change) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.label(this, title, 15, fg, true));
        TextView sub = Ui.label(this, subtitle, 12.5f, muted, false);
        sub.setSingleLine(false);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch s = new Switch(this);
        s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFF2F2F7}));
        s.setTrackTintList(new ColorStateList(states, new int[]{0xFF30D158, dark ? 0x66787880 : 0x33787880}));
        s.setScaleX(Ui.scale);
        s.setScaleY(Ui.scale);
        s.setOnCheckedChangeListener((v, checked) -> {
            Ui.haptic(v);
            change.accept(checked);
        });
        row.addView(s);
        row.setOnClickListener(v -> s.toggle());
        return row;
    }

    /** A switch stored in the app settings; restyle=true redraws everything. */
    private View prefToggle(String title, String subtitle, String key, boolean restyle) {
        return toggle(title, subtitle, prefs.getBoolean(key, true), on -> {
            prefs.edit().putBoolean(key, on).apply();
            Ui.loadScale(this);
            if (restyle) restyle();
            else if (OverlayService.running) OverlayService.start(this);
        });
    }

    private View slider(String title, String key, int min, int max, String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.addView(Ui.label(this, title, 15, fg, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int value = prefs.getInt(key, 100);
        TextView shown = Ui.label(this, value + unit, 14, muted, false);
        head.addView(shown);
        box.addView(head);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0x40FFFFFF : 0x26000000));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                prefs.edit().putInt(key, p + min).apply();
                shown.setText((p + min) + unit);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(bar);
        return box;
    }

    private LinearLayout.LayoutParams weighted(boolean gapBefore) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (gapBefore) lp.leftMargin = Ui.dp(this, 10);
        return lp;
    }

    private LinearLayout.LayoutParams block(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }
}

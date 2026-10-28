package io.github.aliihjt.tabletdesk;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fedora Pad home, laid out like a macOS desktop: menu bar on top, a widget
 * for the connection, Launchpad-style icons, and the Dock at the bottom.
 */
public class MainActivity extends Page {
    /** RDP apps that show the Fedora screen, best first. */
    static final String[] RDP = {"com.microsoft.rdc.androidx", "com.freerdp.afreerdp"};

    // menu bar menus: title, then "Label|action|shortcut", "-" = separator
    private static final String[][] MENUS = {
            {"File", "New Window|ctrl+n|⌘N", "New Tab|ctrl+t|⌘T", "Open…|ctrl+o|⌘O", "-",
                    "Save|ctrl+s|⌘S", "Print…|ctrl+p|⌘P", "-", "Close Window|ctrl+w|⌘W"},
            {"Edit", "Undo|ctrl+z|⌘Z", "Redo|ctrl+shift+z|⇧⌘Z", "-", "Cut|ctrl+x|⌘X",
                    "Copy|ctrl+c|⌘C", "Paste|ctrl+v|⌘V", "Select All|ctrl+a|⌘A", "-",
                    "Find…|ctrl+f|⌘F", "Emoji & Symbols|ctrl+dot|⌘."},
            {"View", "Zoom In|ctrl+equal|⌘+", "Zoom Out|ctrl+minus|⌘−", "Actual Size|ctrl+0|⌘0", "-",
                    "Reload|f5|F5", "Enter Full Screen|f11|F11"},
            {"Go", "Back|alt+left|⌘[", "Forward|alt+right|⌘]", "Home|alt+home|⇧⌘H", "-",
                    "Launchpad|super+a|", "Mission Control|super|"},
            {"Window", "Minimize|super+h|⌘M", "Zoom|super+up|", "Tile Left|super+left|", "Tile Right|super+right|",
                    "-", "Cycle Through Windows|alt+tab|⌘⇥", "Move to Other Display|super+shift+right|"},
            {"Help", "Fedora Help|f1|F1"},
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView hostName, hostInfo, clock, battery, dot;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        super.onCreate(state);
        // started by the PC when the cable is plugged in: just bring up the floating button
        if (getIntent().getBooleanExtra("autostart", false) && Settings.canDrawOverlays(this)) {
            OverlayService.start(this);
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected String title() {
        return null;
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        ((View) content.getParent()).setPadding(0, 0, 0, 0);  // edge to edge, like a desktop

        content.addView(menuBar());

        // widget + Launchpad icons scroll; menu bar and Dock stay put
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        int side = th.dp(24);
        middle.setPadding(side, th.dp(20), side, th.dp(20));
        middle.addView(widget(), new LinearLayout.LayoutParams(
                Math.min(th.dp(460), getResources().getDisplayMetrics().widthPixels - 2 * side),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        middle.addView(launchpad(), th.fullWidth(28));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(middle);
        content.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(th.dp(8), 0, th.dp(8), th.dp(10));
        dockRow.addView(dock());
        content.addView(dockRow, th.fullWidth(0));

        onStatus(link.isConnected(), link.host());
    }

    // ---- menu bar ----

    private View menuBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(th.frosted(0));
        bar.setPadding(th.dp(10), 0, th.dp(12), 0);
        bar.setMinimumHeight(th.dp(32));

        LinearLayout menus = new LinearLayout(this);
        menus.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = menuTitle("◆", true);
        th.click(logo, v -> showMenu(v, new String[]{"◆", "About This PC|cmd:about|",
                "System Settings…|cmd:settings|", "-", "Lock Screen|super+l|⌃⌘Q",
                "Log Out…|cmd:logout|", "Restart…|cmd:restart|", "Shut Down…|cmd:poweroff|"}));
        menus.addView(logo);
        TextView app = menuTitle("Fedora Pad", true);
        th.click(app, v -> startActivity(new Intent(this, SettingsActivity.class)));
        menus.addView(app);
        for (String[] m : MENUS) {
            TextView t = menuTitle(m[0], false);
            th.click(t, v -> showMenu(v, m));
            menus.addView(t);
        }
        HorizontalScrollView menuScroll = new HorizontalScrollView(this);
        menuScroll.setHorizontalScrollBarEnabled(false);
        menuScroll.addView(menus);
        bar.addView(menuScroll, new LinearLayout.LayoutParams(0, th.dp(32), 1f));

        TextView search = menuTitle("🔍", false);
        th.click(search, v -> startActivity(new Intent(this, SpotlightActivity.class)));
        bar.addView(search);
        dot = menuTitle("", false);
        bar.addView(dot);
        battery = menuTitle("", false);
        bar.addView(battery);
        clock = menuTitle("", true);
        bar.addView(clock);
        return bar;
    }

    private TextView menuTitle(String s, boolean bold) {
        TextView t = th.text(s, 13.5f, th.text, bold);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(th.dp(9), 0, th.dp(9), 0);
        t.setMinHeight(th.dp(32));
        return t;
    }

    /** A real dropdown that sends the shortcut to Fedora. */
    private void showMenu(View anchor, String[] items) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        int group = 0;
        for (int i = 1; i < items.length; i++) {
            if ("-".equals(items[i])) {
                group++;
                continue;
            }
            String[] p = items[i].split("\\|");
            String keys = p.length > 2 && !p[2].isEmpty() ? "      " + p[2] : "";
            menu.add(group, i, i, p[0] + keys);
        }
        if (Build.VERSION.SDK_INT >= 28) menu.setGroupDividerEnabled(true);
        popup.setOnMenuItemClickListener(item -> {
            link.action(items[item.getItemId()].split("\\|")[1]);
            return true;
        });
        popup.show();
    }

    private void updateClock() {
        clock.setText(new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date()));
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100
                    / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            battery.setText((charging ? "⚡" : "") + level + "%");
        }
    }

    // ---- widget ----

    private View widget() {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setBackground(th.frosted(th.dp(22)));
        int p = th.dp(18);
        w.setPadding(p, p, p, p);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(th.iconChip("🖥", Theme.BLUE, 48));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(th.dp(12), 0, 0, 0);
        hostName = th.text("", 18, th.text, true);
        hostInfo = th.caption("");
        texts.addView(hostName);
        texts.addView(hostInfo, th.fullWidth(3));
        top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        w.addView(top);

        TextView start = th.primaryButton("Show Fedora Screen", v -> showScreen());
        start.setOnLongClickListener(v -> {  // Xorg desktops use VNC instead of RDP
            openVnc();
            return true;
        });
        w.addView(start, th.fullWidth(14));
        w.addView(th.segmented(new String[][]{
                {"extend", "Second screen"}, {"mirror", "Same picture"}, {"input", "Keys only"}},
                prefs.getString("mode", "extend"), mode -> {
                    prefs.edit().putString("mode", mode).apply();
                    link.mode(mode);
                }), th.fullWidth(10));
        return w;
    }

    // ---- Launchpad ----

    private View launchpad() {
        Object[][] apps = {
                {"⌨️", Theme.BLUE, "Keyboard", KeyboardActivity.class},
                {"🖱", Theme.PURPLE, "Trackpad", TrackpadActivity.class},
                {"🎵", Theme.PINK, "Media", MediaActivity.class},
                {"🎛", Theme.ORANGE, "Controls", ControlsActivity.class},
                {"⚡", Theme.INDIGO, "Shortcuts", ShortcutsActivity.class},
                {"🚀", Theme.TEAL, "Apps", AppsActivity.class},
                {"✍️", Theme.YELLOW, "Text", ClipboardActivity.class},
                {"📁", Theme.BLUE, "Send Files", FilesActivity.class},
                {"🎤", Theme.RED, "Presenter", PresenterActivity.class},
                {"\uD83C\uDF9A", Theme.ORANGE, "Stream Deck", ButtonsActivity.class},
                {"\uD83D\uDDC2", Theme.INDIGO, "Windows", WindowsActivity.class},
                {"📊", Theme.GREEN, "Stats", StatsActivity.class},
                {"🔍", Theme.GRAY, "Spotlight", SpotlightActivity.class},
                {"⚙️", Theme.GRAY, "Settings", SettingsActivity.class},
        };
        int columns = getResources().getConfiguration().screenWidthDp >= 700 ? 7 : 4;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < apps.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 20));
            }
            Object[] a = apps[i];
            row.addView(appIcon((String) a[0], (Integer) a[1], (String) a[2], 64,
                    () -> startActivity(new Intent(this, (Class<?>) a[3]))), th.weighted(0));
        }
        int rest = apps.length % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new View(this), th.weighted(0));
        return grid;
    }

    /** Icon + white label, like Launchpad. */
    private View appIcon(String symbol, int color, String label, int sizeDp, Runnable open) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView icon = th.iconChip(symbol, color, sizeDp);
        icon.setElevation(th.dp(4));
        cell.addView(icon);
        TextView name = th.text(label, 13, 0xFFFFFFFF, true);
        name.setShadowLayer(th.dp(3), 0, th.dp(1), 0x99000000);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        cell.addView(name, th.fullWidth(8));
        th.click(cell, v -> bounce(icon, open));
        return cell;
    }

    // ---- Dock ----

    private View dock() {
        LinearLayout d = new LinearLayout(this);
        d.setGravity(Gravity.CENTER_VERTICAL);
        d.setBackground(th.frosted(th.dp(24)));
        int p = th.dp(8);
        d.setPadding(p, p, p, p);
        Object[][] items = {
                {"⌨️", Theme.BLUE, (Runnable) () -> startActivity(new Intent(this, KeyboardActivity.class))},
                {"🖱", Theme.PURPLE, (Runnable) () -> startActivity(new Intent(this, TrackpadActivity.class))},
                {"🎵", Theme.PINK, (Runnable) () -> startActivity(new Intent(this, MediaActivity.class))},
                {"🎛", Theme.ORANGE, (Runnable) () -> startActivity(new Intent(this, ControlsActivity.class))},
                null,
                {"🖥", Theme.INDIGO, (Runnable) () -> link.combo("super")},       // Mission Control
                {"▦", Theme.TEAL, (Runnable) () -> link.combo("super+a")},              // Launchpad on Fedora
                {"🔍", Theme.GRAY, (Runnable) () -> startActivity(new Intent(this, SpotlightActivity.class))},
                {"⚙️", Theme.GRAY, (Runnable) () -> startActivity(new Intent(this, SettingsActivity.class))},
        };
        for (Object[] it : items) {
            if (it == null) {  // divider between apps and system items, as in the macOS Dock
                View line = new View(this);
                line.setBackgroundColor(th.dark ? 0x40FFFFFF : 0x33000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, th.dp(1)), th.dp(40));
                lp.setMargins(th.dp(6), 0, th.dp(6), 0);
                d.addView(line, lp);
                continue;
            }
            TextView icon = th.iconChip((String) it[0], (Integer) it[1], 50);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) icon.getLayoutParams();
            lp.setMargins(th.dp(4), 0, th.dp(4), 0);
            Runnable open = (Runnable) it[2];
            th.click(icon, v -> bounce(icon, open));
            d.addView(icon);
        }
        return d;
    }

    /** The Dock's little bounce before an app opens. */
    private void bounce(View v, Runnable then) {
        v.animate().scaleX(1.18f).scaleY(1.18f).translationY(-th.dp(8)).setDuration(120)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).translationY(0).setDuration(140)
                        .withEndAction(then)).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onStatus(boolean connected, String host) {
        if (hostName == null) return;
        hostName.setText(connected ? host : "Not connected");
        hostInfo.setText(connected ? ("Wi-Fi".equals(link.via()) ? "Connected over Wi-Fi" : "Connected with the USB-C cable \u00B7 charging")
                : "Plug the tablet into the laptop with the USB-C cable.");
        dot.setText(connected ? "●" : "○");
        dot.setTextColor(connected ? Theme.GREEN : th.secondary);
        if (connected && prefs.contains("mode")) link.mode(prefs.getString("mode", "extend"));
    }

    // ---- actions ----

    /** The floating button (for keyboard etc.) plus the Fedora screen. */
    private void showScreen() {
        if (!ensureOverlayPermission(this)) return;
        OverlayService.start(this);
        openRdp(this);
    }

    /** Asks once for "display over other apps", which the floating button needs. */
    static boolean ensureOverlayPermission(android.app.Activity a) {
        if (Settings.canDrawOverlays(a)) return true;
        Toast.makeText(a, "Allow Fedora Pad to “display over other apps”, then come back.",
                Toast.LENGTH_LONG).show();
        a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + a.getPackageName())));
        return false;
    }

    static void openRdp(android.content.Context c) {
        for (String pkg : RDP) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            }
        }
        openStore(c, RDP[0]);
    }

    private void openVnc() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnc://127.0.0.1:5900")));
        } catch (ActivityNotFoundException e) {
            openStore(this, "com.gaurav.avnc");
        }
    }

    static void openStore(android.content.Context c, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            c.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}

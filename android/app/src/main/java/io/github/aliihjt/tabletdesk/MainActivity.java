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
 * for the connection, Launchpad-style icons (the tablet's own tools and pinned
 * apps, then the Fedora tools), and the Dock at the bottom. It can also be the
 * tablet's home screen (Settings › Home screen).
 */
public class MainActivity extends Page implements HostLink.StateListener {
    /** RDP apps that show the Fedora screen, best first. */
    static final String[] RDP = {"com.microsoft.rdc.androidx", "com.freerdp.afreerdp"};
    /** spacedesk shows a Windows PC's screen (Fedora Pad for Windows). */
    static final String SPACEDESK = "ph.spacedesk.beta";

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
    private ReaderHome readerHome;  // the calm reading home instead of the desktop
    private String style;
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
        // the old built-in AI chat is gone (Claude app instead): don't keep its key or history
        prefs.edit().remove("claude_key").remove("claude_history").apply();
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
        style = prefs.getString("home_style", "desktop");
        if (style.equals("reader")) {
            readerHome = new ReaderHome(this, th, () -> setStyle("desktop"));
            content.addView(readerHome.view(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            return;
        }

        content.addView(menuBar());

        // widget + Launchpad icons scroll; menu bar and Dock stay put
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        int side = th.dp(24);
        middle.setPadding(side, th.dp(20), side, th.dp(20));
        int screenW = getResources().getDisplayMetrics().widthPixels;
        middle.addView(widget(), new LinearLayout.LayoutParams(
                portrait() ? screenW - 2 * side : Math.min(th.dp(460), screenW - 2 * side),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        middle.addView(launchpad(), th.fullWidth(28));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(middle);
        content.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Control Center at the bottom, above the Dock
        LinearLayout ccRow = new LinearLayout(this);
        ccRow.setPadding(th.dp(12), 0, th.dp(12), th.dp(8));
        ccRow.addView(controlCenter(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(ccRow, th.fullWidth(0));

        // the Dock scrolls sideways if a narrow (portrait) screen can't fit it
        HorizontalScrollView dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setFillViewport(true);
        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(th.dp(8), 0, th.dp(8), th.dp(10));
        dockRow.addView(dock());
        dockScroll.addView(dockRow);
        content.addView(dockScroll, th.fullWidth(0));

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

    private LinearLayout pinnedBox;

    private View launchpad() {
        Object[][] tablet = {
                {"📚", Theme.ORANGE, "Books", BooksActivity.class},
                {"\uD83D\uDDD2", Theme.YELLOW, "Notes", NotesActivity.class},
                {"✍️", Theme.BLUE, "Handwriting", SketchActivity.class},
                {"✨", 0xFFD97757, "Claude", (Runnable) () -> ClaudeApp.open(this)},
                {"🔍", Theme.GRAY, "Search", SpotlightActivity.class},
                {"📖", Theme.GREEN, "Reader Home", (Runnable) () -> setStyle("reader")},
                {"⬇️", Theme.GREEN, "Get Books", GetBooksActivity.class},
                {"▦", Theme.TEAL, "Tablet Apps", TabletAppsActivity.class},
                {"🕰", Theme.ORANGE, "StandBy", StandByActivity.class},
                {"⏱", Theme.RED, "Focus", FocusActivity.class},
                {"📄", Theme.BLUE, "Scanner", ScanActivity.class},
                {"⚙️", Theme.GRAY, "Settings", SettingsActivity.class},
        };
        Object[][] fedora = {
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
                {"🎙", Theme.RED, "Push to Talk", PushToTalkActivity.class},
                {"🖥", Theme.BLUE, "Show on PC", MirrorActivity.class},
                {"📷", Theme.RED, "Webcam", WebcamActivity.class},
                {"📋", Theme.TEAL, "Clipboard History", ClipHistoryActivity.class},
                {"⏰", Theme.INDIGO, "Wake & Sleep", WakeActivity.class},
                {">_", 0xFF3A3A3C, "Terminal Pad", TerminalActivity.class},
                {"🌈", Theme.PINK, "Ambient Light", AmbientActivity.class},
        };
        LinearLayout all = new LinearLayout(this);
        all.setOrientation(LinearLayout.VERTICAL);
        all.addView(header("This tablet"));
        all.addView(iconGrid(tablet), th.fullWidth(12));
        pinnedBox = new LinearLayout(this);
        pinnedBox.setOrientation(LinearLayout.VERTICAL);
        all.addView(pinnedBox, th.fullWidth(0));
        showPinned();
        all.addView(header("Fedora"), th.fullWidth(28));
        all.addView(iconGrid(fedora), th.fullWidth(12));
        return all;
    }

    private int columns() {
        int w = getResources().getConfiguration().screenWidthDp;
        return w >= 900 ? 7 : w >= 600 ? 5 : 4;  // fewer, bigger icons when upright
    }

    private View iconGrid(Object[][] apps) {
        int columns = columns();
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
                    a[3] instanceof Runnable ? (Runnable) a[3]
                            : () -> startActivity(new Intent(this, (Class<?>) a[3]))), th.weighted(0));
        }
        int rest = apps.length % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new View(this), th.weighted(0));
        return grid;
    }

    /** The tablet apps pinned from Tablet Apps (long-press there), with their real icons. */
    private void showPinned() {
        if (pinnedBox == null) return;
        pinnedBox.removeAllViews();
        java.util.List<TabletApps.App> pinned = TabletApps.pinnedApps(this);
        if (pinned.isEmpty()) return;
        int columns = columns();
        LinearLayout row = null;
        for (int i = 0; i < pinned.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                pinnedBox.addView(row, th.fullWidth(20));
            }
            TabletApps.App a = pinned.get(i);
            row.addView(TabletApps.icon(this, th, a, 64, 0xFFFFFFFF, () -> new android.app.AlertDialog.Builder(this)
                    .setTitle(a.label).setItems(new String[]{"Unpin from home screen"}, (d, w) -> {
                        TabletApps.setPinned(this, a, false);
                        showPinned();
                    }).show()), th.weighted(0));
        }
        int rest = pinned.size() % columns;
        for (int i = rest; rest != 0 && i < columns; i++) row.addView(new View(this), th.weighted(0));
    }

    private TextView header(String text) {
        TextView t = th.text(text.toUpperCase(java.util.Locale.ROOT), 13, 0xE6FFFFFF, true);
        t.setLetterSpacing(0.08f);
        t.setShadowLayer(th.dp(3), 0, th.dp(1), 0x99000000);
        return t;
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
                {"📚", Theme.ORANGE, (Runnable) () -> startActivity(new Intent(this, BooksActivity.class))},
                {"\uD83D\uDDD2", Theme.YELLOW, (Runnable) () -> startActivity(new Intent(this, NotesActivity.class))},
                {"✨", 0xFFD97757, (Runnable) () -> ClaudeApp.open(this)},
                {"▦", Theme.TEAL, (Runnable) () -> startActivity(new Intent(this, TabletAppsActivity.class))},
                null,
                {"⌨️", Theme.BLUE, (Runnable) () -> startActivity(new Intent(this, KeyboardActivity.class))},
                {"🖱", Theme.PURPLE, (Runnable) () -> startActivity(new Intent(this, TrackpadActivity.class))},
                {"🖥", Theme.INDIGO, (Runnable) () -> link.combo("super")},       // Mission Control on Fedora
                {"🔍", Theme.GRAY, (Runnable) () -> startActivity(new Intent(this, SpotlightActivity.class))},
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

    /** Desktop or Reader home; Settings › Home screen can change it too. */
    private void setStyle(String s) {
        prefs.edit().putString("home_style", s).apply();
        recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.getString("home_style", "desktop").equals(style)) {
            recreate();
            return;
        }
        if (readerHome != null) {
            readerHome.resume();
            return;
        }
        handler.post(tick);
        link.setStateListener(this);
        link.requestState();
        link.on("media", this::showMedia);
        handler.post(mediaTick);
        showPinned();
    }

    /** As the tablet's home screen there is nothing to go back to. */
    @Override
    public void onBackPressed() {
        if (!isHomeApp()) super.onBackPressed();
    }

    private boolean isHomeApp() {
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    @Override
    protected void onPause() {
        if (readerHome != null) readerHome.pause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(mediaTick);
        link.setStateListener(null);
        link.on("media", null);
        super.onPause();
    }

    private boolean portrait() {
        return getResources().getConfiguration().screenHeightDp > getResources().getConfiguration().screenWidthDp;
    }

    // ---- Control Center (bottom of the home screen) ----

    private static final String[][] TOGGLES = {
            {"wifi", "\uD83D\uDCF6", "Wi-Fi"}, {"bluetooth", "\uD83D\uDD35", "Bluetooth"},
            {"dnd", "\uD83C\uDF19", "Focus"}, {"dark", "\u25D0", "Dark"},
            {"nightlight", "\uD83C\uDF05", "Night Light"}, {"micmute", "\uD83C\uDF99", "Mic off"}};
    private final java.util.Map<String, TextView> toggleViews = new java.util.HashMap<>();
    private android.widget.SeekBar brightnessBar, volumeBar;
    private TextView songTitle, playButton;
    private boolean applying;
    private final Runnable mediaTick = new Runnable() {
        @Override
        public void run() {
            link.ask("media");
            handler.postDelayed(this, 2500);
        }
    };

    private View controlCenter() {
        LinearLayout cc = new LinearLayout(this);
        cc.setOrientation(LinearLayout.VERTICAL);
        cc.setBackground(th.frosted(th.dp(22)));
        int p = th.dp(12);
        cc.setPadding(p, th.dp(4), p, p);

        // handle: tap to fold the panel away, like pulling a sheet down
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        boolean open = prefs.getBoolean("cc_open", true);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        TextView handle = th.text(open ? "\u2304  Control Center" : "\u2303  Control Center", 12.5f, th.secondary, true);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(0, th.dp(6), 0, th.dp(6));
        th.click(handle, v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            prefs.edit().putBoolean("cc_open", show).apply();
            handle.setText(show ? "\u2304  Control Center" : "\u2303  Control Center");
        });
        cc.addView(handle);

        // round toggles; upright screens get two rows of three
        int perRow = portrait() ? 3 : TOGGLES.length;
        LinearLayout row = null;
        for (int i = 0; i < TOGGLES.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                body.addView(row, th.fullWidth(i == 0 ? 2 : 10));
            }
            row.addView(roundToggle(TOGGLES[i][0], TOGGLES[i][1], TOGGLES[i][2]), th.weighted(0));
        }

        // sliders: side by side when wide, stacked when upright
        brightnessBar = th.slider(100, link.lastState().optInt("brightness", 50), v -> {
            if (!applying) link.set("brightness", v);
        });
        volumeBar = th.slider(100, link.lastState().optInt("volume", 50), v -> {
            if (!applying) link.set("volume", v);
        });
        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(portrait() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        sliders.addView(sliderPill("\u2600\uFE0F", brightnessBar), portrait() ? th.fullWidth(0) : th.weighted(0));
        sliders.addView(sliderPill("\uD83D\uDD0A", volumeBar), portrait() ? th.fullWidth(8) : th.weighted(10));
        body.addView(sliders, th.fullWidth(12));

        // now playing
        LinearLayout media = new LinearLayout(this);
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setBackground(Theme.round(th.dark ? 0x33FFFFFF : 0x80FFFFFF, th.dp(16)));
        media.setPadding(th.dp(12), th.dp(6), th.dp(6), th.dp(6));
        media.addView(th.text("\uD83C\uDFB5", 18, th.text, false));
        songTitle = th.text("Nothing playing", 14, th.text, true);
        songTitle.setSingleLine(true);
        songTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        songTitle.setPadding(th.dp(10), 0, th.dp(6), 0);
        th.click(songTitle, v -> startActivity(new Intent(this, MediaActivity.class)));
        media.addView(songTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        media.addView(mediaButton("\u23EE", () -> link.media("previous")));
        playButton = mediaButton("\u25B6", () -> link.media("play-pause"));
        media.addView(playButton);
        media.addView(mediaButton("\u23ED", () -> link.media("next")));
        body.addView(media, th.fullWidth(10));

        cc.addView(body);
        paintToggles(link.lastState());
        return cc;
    }

    private View roundToggle(String key, String symbol, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView circle = th.text(symbol, 20, th.text, false);
        circle.setGravity(Gravity.CENTER);
        box.addView(circle, new LinearLayout.LayoutParams(th.dp(52), th.dp(52)));
        TextView name = th.caption(label);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        box.addView(name, th.fullWidth(5));
        toggleViews.put(key, circle);
        th.click(box, v -> {
            boolean on = !link.lastState().optBoolean(key, false);
            try {
                link.lastState().put(key, on);  // show it at once; the PC confirms shortly
            } catch (org.json.JSONException ignored) {
            }
            paintToggles(link.lastState());
            link.set(key, on ? 1 : 0);
            handler.postDelayed(link::requestState, 800);
        });
        return box;
    }

    private View sliderPill(String symbol, android.widget.SeekBar bar) {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Theme.round(th.dark ? 0x33FFFFFF : 0x80FFFFFF, th.dp(16)));
        pill.setPadding(th.dp(12), th.dp(4), th.dp(4), th.dp(4));
        pill.addView(th.text(symbol, 18, th.text, false));
        pill.addView(bar, new LinearLayout.LayoutParams(0, th.dp(40), 1f));
        return pill;
    }

    private TextView mediaButton(String glyph, Runnable action) {
        TextView t = th.text(glyph, 20, th.text, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(th.pressable(0x00000000, th.dp(20)));
        th.click(t, v -> action.run());
        t.setLayoutParams(new LinearLayout.LayoutParams(th.dp(44), th.dp(40)));
        return t;
    }

    private void paintToggles(org.json.JSONObject st) {
        for (java.util.Map.Entry<String, TextView> e : toggleViews.entrySet()) {
            boolean on = st.optBoolean(e.getKey(), false);
            e.getValue().setBackground(Theme.round(on ? th.accent : (th.dark ? 0x40FFFFFF : 0xB3FFFFFF), th.dp(26)));
            e.getValue().setTextColor(on ? 0xFFFFFFFF : th.text);
        }
    }

    @Override
    public void onState(org.json.JSONObject st) {
        paintToggles(st);
        applying = true;
        if (st.has("brightness") && brightnessBar != null) brightnessBar.setProgress(st.optInt("brightness"));
        if (st.has("volume") && volumeBar != null) volumeBar.setProgress(st.optInt("volume"));
        applying = false;
    }

    private void showMedia(org.json.JSONObject m) {
        if (songTitle == null) return;
        String title = m.optString("title"), artist = m.optString("artist");
        songTitle.setText(artist.isEmpty() ? title : title + "  \u00B7  " + artist);
        playButton.setText("Playing".equals(m.optString("status")) ? "\u23F8" : "\u25B6");
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
        if ("windows".equals(HostLink.get().os())) {
            Intent sd = c.getPackageManager().getLaunchIntentForPackage(SPACEDESK);
            if (sd == null) {
                Toast.makeText(c, "Install spacedesk to see the Windows screen", Toast.LENGTH_LONG).show();
                openStore(c, SPACEDESK);
            } else {
                c.startActivity(sd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return;
        }
        String ip = HostLink.get().wifiIp();
        for (String pkg : RDP) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) continue;
            if (!ip.isEmpty()) {
                // over Wi-Fi the screen is at the laptop's own address, not 127.0.0.1
                android.content.ClipboardManager cm = c.getSystemService(android.content.ClipboardManager.class);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Fedora PC", ip));
                String user = HostLink.get().user();
                Intent direct = new Intent(Intent.ACTION_VIEW, Uri.parse("rdp://full%20address=s:" + ip + ":3389"
                        + (user.isEmpty() ? "" : "&username=s:" + Uri.encode(user))));
                direct.setPackage(pkg);
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Toast.makeText(c, "Over Wi-Fi: connect Windows App to " + ip
                        + " (copied). Same user and password as 127.0.0.1.", Toast.LENGTH_LONG).show();
                try {
                    c.startActivity(direct);
                    return;
                } catch (ActivityNotFoundException ignored) {
                    // this app version has no rdp:// links: just open it
                }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
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

package io.github.aliihjt.tabletdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * macOS-style menu bar pinned to the top of the tablet screen. Like macOS in
 * full screen it can auto-hide to a thin strip; touching the strip brings it back.
 */
final class MenuBar implements HostLink.Listener {
    private static final long AUTO_HIDE_MS = 4000;
    /** two lines: menus on top, icons + status below */
    private static final int BAR_HEIGHT_DP = 54;

    // First entry is the menu title; then "Label|action|shortcut hint", "-" = separator.
    // Actions: plain key combo, "cmd:<name>" (whitelisted on host) or "text:<chars>".
    private static final String[] SYSTEM_MENU = {
            "◆",
            "About This Computer|cmd:about", "System Settings…|cmd:settings", "-",
            "Files|cmd:files", "Terminal|cmd:terminal", "System Monitor|cmd:monitor", "-",
            "Show Mouse Pointer|cursor:", "-",
            "Lock Screen|super+l|Super+L", "Suspend|cmd:suspend", "Log Out…|cmd:logout",
            "Restart…|cmd:restart", "Shut Down…|cmd:poweroff",
    };
    private static final String[] APP_MENU = {
            "",  // replaced by the focused app's name
            "Hide|super+h|Super+H", "-", "Quit|ctrl+q|Ctrl+Q",
    };
    private static final String[][] MENUS = {
            {"File", "New Window|ctrl+n|Ctrl+N", "New Tab|ctrl+t|Ctrl+T", "Open…|ctrl+o|Ctrl+O", "-",
                    "Save|ctrl+s|Ctrl+S", "Save As…|ctrl+shift+s|Shift+Ctrl+S", "Print…|ctrl+p|Ctrl+P", "-",
                    "Close Window|ctrl+w|Ctrl+W"},
            {"Edit", "Undo|ctrl+z|Ctrl+Z", "Redo|ctrl+shift+z|Shift+Ctrl+Z", "-",
                    "Cut|ctrl+x|Ctrl+X", "Copy|ctrl+c|Ctrl+C", "Paste|ctrl+v|Ctrl+V",
                    "Select All|ctrl+a|Ctrl+A", "-", "Find…|ctrl+f|Ctrl+F", "Emoji & Symbols|ctrl+dot|Ctrl+."},
            {"View", "Zoom In|ctrl+equal|Ctrl++", "Zoom Out|ctrl+minus|Ctrl+-", "Actual Size|ctrl+0|Ctrl+0", "-",
                    "Reload|f5|F5", "Full Screen|f11|F11"},
            {"Go", "Back|alt+left|Alt+←", "Forward|alt+right|Alt+→", "Home Folder|alt+home|Alt+Home", "-",
                    "Previous Workspace|ctrl+alt+left|Ctrl+Alt+←", "Next Workspace|ctrl+alt+right|Ctrl+Alt+→"},
            {"Window", "Minimize|super+h|Super+H", "Maximize|super+up|Super+↑",
                    "Tile Left|super+left|Super+←", "Tile Right|super+right|Super+→", "-",
                    "Switch Windows|alt+tab|Alt+Tab", "Show All Windows|super|Super",
                    "Show Applications|super+a|Super+A", "Move to Other Display|super+shift+right|Shift+Super+→"},
            {"Help", "Help|f1|F1", "Keyboard Shortcuts|cmd:settings"},
    };

    private final Context ctx;
    private final WindowManager wm;
    private final boolean dark;
    private final int fg;
    private final FrameLayout root;
    private final LinearLayout bar;
    private final View strip;
    private final TextView appName;
    private final TextView status;
    private final TextView battery;
    private final TextView clock;
    private final WindowManager.LayoutParams params;
    private final boolean autoHide;
    private final boolean blur;
    private final Runnable toggleTouchBar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("EEE HH:mm", Locale.getDefault());
    private final Runnable hide = this::collapse;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClockAndBattery();
            handler.postDelayed(this, 10_000);
        }
    };
    private boolean collapsed;

    @SuppressLint("ClickableViewAccessibility")
    MenuBar(Context ctx, WindowManager wm, boolean autoHide, Runnable toggleTouchBar) {
        this.ctx = ctx;
        this.toggleTouchBar = toggleTouchBar;
        this.wm = wm;
        this.autoHide = autoHide;
        dark = Ui.isDark(ctx);
        fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;

        root = new FrameLayout(ctx);
        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        blur = Glass.blurAvailable(ctx);
        bar.setBackgroundColor(blur ? (dark ? 0x66141418 : 0x8CF7F7FA) : (dark ? 0xE01C1C1E : 0xE6F6F6F8));
        bar.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 10), 0);
        bar.setElevation(Ui.dp(ctx, 2));

        TextView logo = menuTitle(SYSTEM_MENU[0], true, SYSTEM_MENU);
        logo.setTextSize(15 * Ui.scale);
        // menus scroll sideways if the screen is too narrow for them
        LinearLayout menus = new LinearLayout(ctx);
        menus.setGravity(Gravity.CENTER_VERTICAL);
        menus.addView(logo);
        appName = menuTitle("Fedora", true, null);
        appName.setOnClickListener(v -> {
            String[] items = APP_MENU.clone();
            items[0] = HostLink.get().app();
            showMenu(v, items);
        });
        // every TabletDesk option in one menu
        TextView settings = menuTitle("TabletDesk", true, null);
        settings.setOnClickListener(v -> {
            HostLink.get().requestState();  // fresh PC info for next time
            showMenu(v, tabletDeskMenu());
        });
        menus.addView(settings);
        menus.addView(appName);
        for (String[] m : MENUS) menus.addView(menuTitle(m[0], false, m));
        TextView tools = menuTitle("Tools", false, null);
        tools.setOnClickListener(v -> showMenu(v, toolsMenu()));
        menus.addView(tools);
        android.widget.HorizontalScrollView menuScroll = new android.widget.HorizontalScrollView(ctx);
        menuScroll.setHorizontalScrollBarEnabled(false);
        menuScroll.setFillViewport(true);
        menuScroll.addView(menus, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        // line 1: the menus
        bar.addView(menuScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // line 2: icons on the left, status / battery / clock on the right
        LinearLayout line2 = new LinearLayout(ctx);
        line2.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(line2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        // labelled buttons, so it is obvious what each one does
        line2.addView(iconButton("\uD83D\uDE80 Apps", this::showDock));  // favourite apps
        line2.addView(iconButton("\uD83D\uDD0D Search", v -> HostLink.get().combo("super")));
        line2.addView(iconButton("\u2328 Keyboard", v -> OverlayService.toggleKeyboard(ctx)));  // floating
        line2.addView(iconButton("\uD83D\uDDB1 Trackpad", v -> {  // full-screen keyboard & trackpad
            Intent i = new Intent(ctx, TrackpadActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }));
        line2.addView(iconButton("\u2630 Control", v -> {  // Control Center
            handler.removeCallbacks(hide);
            ControlCenter.toggle(ctx, dark, toggleTouchBar, this::scheduleHide);
        }));
        line2.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));
        status = small("");
        line2.addView(status);
        battery = small("");
        line2.addView(battery);
        clock = small("");
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        line2.addView(clock);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // thin handle shown while hidden
        strip = new View(ctx);
        strip.setBackground(Ui.rounded(dark ? 0x99FFFFFF : 0x80000000, Ui.dp(ctx, 3)));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Ui.dp(ctx, 120), Ui.dp(ctx, 4),
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        slp.topMargin = Ui.dp(ctx, 3);
        strip.setVisibility(View.GONE);
        root.addView(strip, slp);
        root.setOnTouchListener((v, ev) -> {
            if (collapsed && ev.getActionMasked() == MotionEvent.ACTION_DOWN) expand();
            return collapsed;
        });

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Ui.dp(ctx, BAR_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // user-chosen distance from the top edge (Settings: "Menu bar position")
        params.y = Math.round(ctx.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
                .getInt("menuoffset", 0) * ctx.getResources().getDisplayMetrics().density);
        if (Build.VERSION.SDK_INT >= 30) {
            // sit just below Android's status bar (or at the very top when it is hidden)
            params.setFitInsetsTypes(android.view.WindowInsets.Type.statusBars()
                    | android.view.WindowInsets.Type.displayCutout());
        }
        if (blur && Build.VERSION.SDK_INT >= 31) {
            params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(Ui.dp(ctx, 30));
        }
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        HostLink.get().addListener(this);
        handler.post(tick);
        scheduleHide();
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        return params;
    }

    void destroy() {
        GlassMenu.dismiss();
        ControlCenter.dismiss();
        handler.removeCallbacksAndMessages(null);
        HostLink.get().removeListener(this);
    }

    @Override
    public void onStatus(boolean connected, String host) {
        status.setText(connected ? "\u25CF" : "\u25CB");  // green = connected
        status.setTextColor(connected ? (dark ? 0xFF30D158 : 0xFF1E8E3E) : (dark ? 0xFFFF6961 : 0xFFC5221F));
    }

    @Override
    public void onApp(String name) {
        appName.setText(name);
    }

    private void scheduleHide() {
        handler.removeCallbacks(hide);
        if (autoHide) handler.postDelayed(hide, AUTO_HIDE_MS);
    }

    private void collapse() {
        if (collapsed) return;
        collapsed = true;
        bar.setVisibility(View.GONE);
        strip.setVisibility(View.VISIBLE);
        params.height = Ui.dp(ctx, 12);
        if (root.isAttachedToWindow()) wm.updateViewLayout(root, params);
    }

    private void expand() {
        collapsed = false;
        bar.setVisibility(View.VISIBLE);
        strip.setVisibility(View.GONE);
        params.height = Ui.dp(ctx, BAR_HEIGHT_DP);
        if (root.isAttachedToWindow()) wm.updateViewLayout(root, params);
        scheduleHide();
    }

    private TextView menuTitle(String title, boolean bold, String[] items) {
        TextView t = Ui.label(ctx, title, 13, fg, false);
        t.setTypeface(Typeface.create(bold ? "sans-serif" : "sans-serif-medium",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        int pad = Ui.dp(ctx, 8);
        t.setPadding(pad, 0, pad, 0);
        t.setBackground(Ui.pressable(0x00000000, Ui.dp(ctx, 5), dark ? 0x40FFFFFF : 0x26000000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2));
        t.setLayoutParams(lp);
        if (items != null) t.setOnClickListener(v -> showMenu(v, items));
        return t;
    }

    private TextView iconButton(String glyph, View.OnClickListener l) {
        TextView t = menuTitle(glyph, false, null);
        t.setTextSize(13 * Ui.scale);
        t.setBackground(Ui.pressable(dark ? 0x1FFFFFFF : 0x0F000000, Ui.dp(ctx, 8),
                dark ? 0x40FFFFFF : 0x26000000));  // a visible button, not just a glyph
        ((LinearLayout.LayoutParams) t.getLayoutParams()).rightMargin = Ui.dp(ctx, 4);
        t.setOnClickListener(v -> {
            scheduleHide();
            l.onClick(v);
        });
        return t;
    }

    private TextView small(String text) {
        TextView t = Ui.label(ctx, text, 12.5f, fg, false);
        t.setPadding(Ui.dp(ctx, 7), 0, Ui.dp(ctx, 7), 0);
        return t;
    }

    private void showMenu(View anchor, String[] items) {
        handler.removeCallbacks(hide);  // stay visible while a menu is open
        ControlCenter.dismiss();
        GlassMenu.show(ctx, anchor, items, dark, this::scheduleHide, this::handleLocal);
    }

    private static String check(boolean on) {
        return on ? "\u2713  " : "     ";
    }

    private android.content.SharedPreferences prefs() {
        return ctx.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    /** All options, as a menu: ✓ marks what is on. */
    private String[] tabletDeskMenu() {
        android.content.SharedPreferences p = prefs();
        String mode = p.getString("mode", "extend");
        org.json.JSONObject st = HostLink.get().lastState();
        boolean laptopKbdOff = !st.optBoolean("laptopkbd", false);
        boolean touch = st.optBoolean("touch", true);
        int size = p.getInt("uiscale", 135);
        int speed = p.getInt("speed", 100);
        String theme = p.getString("theme", "auto");
        return new String[]{
                "TabletDesk",
                "\uD83D\uDCBB  " + pcInfo(st) + "|local:none",
                "-",
                "\u2328  Floating keyboard (with numpad)|local:floatkb",
                "\uD83D\uDDB1  Full-screen keyboard & trackpad|local:trackpad",
                "\u2630  Control Center…|local:cc",
                "-",
                check(mode.equals("extend")) + "Extend (second screen)|local:mode:extend",
                check(mode.equals("mirror")) + "Mirror (same picture)|local:mode:mirror",
                check(mode.equals("input")) + "Input only (no screen)|local:mode:input",
                "-",
                check(laptopKbdOff) + "Laptop keyboard off while connected|local:laptopkbd",
                check(touch) + "Touch screen|local:touch",
                "     Show Mouse Pointer|cursor:",
                "-",
                check(p.getBoolean("touchbar", true)) + "Show Touch Bar|local:touchbar",
                check(p.getBoolean("autohide", true)) + "Auto-hide bars|local:autohide",
                "-",
                check(size == 100) + "Size: Normal|local:size:100",
                check(size == 135) + "Size: Large|local:size:135",
                check(size == 165) + "Size: Extra large|local:size:165",
                "-",
                "-",
                check(theme.equals("auto")) + "Theme: Automatic|local:theme:auto",
                check(theme.equals("light")) + "Theme: Light|local:theme:light",
                check(theme.equals("dark")) + "Theme: Dark|local:theme:dark",
                "-",
                "     Trackpad faster|local:faster|" + speed + "%",
                "     Trackpad slower|local:slower",
                check(p.getBoolean("natural", true)) + "Natural scrolling|local:natural",
                check(p.getBoolean("haptics", true)) + "Vibrate on tap|local:haptics",
                "-",
                "     Reconnect screen|local:reconnect",
                "-",
                "     Open TabletDesk app…|local:app",
                "     Hide bars until next plug-in|local:stop",
        };
    }

    private void handleLocal(String name) {
        android.content.SharedPreferences p = prefs();
        org.json.JSONObject st = HostLink.get().lastState();
        boolean rebuild = false;  // settings that change how the bars look
        if (name.startsWith("t:")) {  // on/off switch on the PC
            String key = name.substring(2);
            HostLink.get().set(key, st.optBoolean(key, false) ? 0 : 1);
            HostLink.get().requestState();
        } else if (name.startsWith("power:")) {
            HostLink.get().set("power", Integer.parseInt(name.substring(6)));
            HostLink.get().requestState();
        } else if (name.startsWith("theme:")) {
            p.edit().putString("theme", name.substring(6)).apply();
            rebuild = true;
        } else if (name.startsWith("mode:")) {
            String m = name.substring(5);
            p.edit().putString("mode", m).apply();
            HostLink.get().mode(m);
        } else if (name.startsWith("size:")) {
            p.edit().putInt("uiscale", Integer.parseInt(name.substring(5))).apply();
            rebuild = true;
        } else {
            switch (name) {
                case "trackpad":
                case "app": {
                    Intent i = new Intent(ctx, name.equals("app") ? MainActivity.class : TrackpadActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    break;
                }
                case "floatkb":
                    OverlayService.toggleKeyboard(ctx);
                    break;
                case "cc":
                    ControlCenter.toggle(ctx, dark, toggleTouchBar, this::scheduleHide);
                    break;
                case "laptopkbd":  // currently off -> turn on (1), and the other way round
                    HostLink.get().set("laptopkbd", st.optBoolean("laptopkbd", false) ? 0 : 1);
                    HostLink.get().requestState();
                    break;
                case "touch":
                    HostLink.get().set("touch", st.optBoolean("touch", true) ? 0 : 1);
                    HostLink.get().requestState();
                    break;
                case "touchbar":
                case "autohide":
                    p.edit().putBoolean(name, !p.getBoolean(name, true)).apply();
                    rebuild = true;
                    break;
                case "lower":
                case "higher": {
                    int o = p.getInt("menuoffset", 0) + (name.equals("lower") ? 12 : -12);
                    p.edit().putInt("menuoffset", Math.max(0, Math.min(160, o))).apply();
                    rebuild = true;
                    break;
                }
                case "faster":
                case "slower": {
                    int s = p.getInt("speed", 100) + (name.equals("faster") ? 25 : -25);
                    p.edit().putInt("speed", Math.max(50, Math.min(300, s))).apply();
                    break;
                }
                case "natural":
                case "haptics":
                    p.edit().putBoolean(name, !p.getBoolean(name, true)).apply();
                    rebuild = true;
                    break;
                case "clip":
                    HostLink.get().requestClip(text -> {
                        android.content.ClipboardManager cm =
                                ctx.getSystemService(android.content.ClipboardManager.class);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("PC", text));
                        android.widget.Toast.makeText(ctx, "PC clipboard copied to the tablet",
                                android.widget.Toast.LENGTH_SHORT).show();
                    });
                    break;
                case "reconnect":
                    openRdpApp();
                    break;
                case "stop":
                    OverlayService.stop(ctx);
                    break;
                default:
                    break;
            }
        }
        if (rebuild) OverlayService.start(ctx);
    }

    private static String pcInfo(org.json.JSONObject st) {
        StringBuilder sb = new StringBuilder("PC");
        if (st.has("cpu")) sb.append("  \u00B7  CPU ").append(st.optInt("cpu")).append('%');
        if (st.has("ram")) sb.append("  \u00B7  RAM ").append(st.optInt("ram")).append('%');
        if (st.has("battery")) {
            sb.append("  \u00B7  ").append(st.optBoolean("charging") ? "\u26A1" : "\uD83D\uDD0B")
                    .append(st.optInt("battery")).append('%');
        }
        return sb.toString();
    }

    /** PC switches and handy tools; ✓ marks what is on. */
    private String[] toolsMenu() {
        HostLink.get().requestState();
        org.json.JSONObject st = HostLink.get().lastState();
        int power = st.optInt("power", -1);
        return new String[]{
                "Tools",
                "\uD83D\uDCF7  Screenshot…|print|Print",
                "     Screenshot of whole screen|shift+print|Shift+Print",
                "     Screenshot of window|alt+print|Alt+Print",
                "\uD83C\uDFA5  Start / stop screen recording|ctrl+shift+alt+r|Ctrl+Shift+Alt+R",
                "-",
                "\uD83D\uDCCB  Copy PC clipboard to tablet|local:clip",
                "\uD83D\uDE00  Emoji & symbols|ctrl+dot|Ctrl+.",
                "-",
                check(st.optBoolean("wifi")) + "Wi-Fi|local:t:wifi",
                check(st.optBoolean("bluetooth")) + "Bluetooth|local:t:bluetooth",
                check(st.optBoolean("micmute")) + "Mute microphone|local:t:micmute",
                check(st.optBoolean("nightlight")) + "Night Light|local:t:nightlight",
                check(st.optBoolean("dark")) + "Dark mode (PC)|local:t:dark",
                check(st.optBoolean("dnd")) + "Do Not Disturb|local:t:dnd",
                check(st.optBoolean("awake")) + "Keep PC awake|local:t:awake",
                "-",
                check(power == 0) + "Power: Battery saver|local:power:0",
                check(power == 1) + "Power: Balanced|local:power:1",
                check(power == 2) + "Power: Performance|local:power:2",
        };
    }

    private void openRdpApp() {
        for (String pkg : new String[]{"com.microsoft.rdc.androidx", "com.freerdp.afreerdp"}) {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            }
        }
    }

    /** Fedora's favourite apps (the ones pinned in the dash) as a glass menu. */
    private void showDock(View anchor) {
        java.util.List<String[]> apps = HostLink.get().apps();
        String[] items = new String[apps.size() + 3];
        items[0] = "Apps";
        for (int i = 0; i < apps.size(); i++) {
            items[i + 1] = apps.get(i)[1] + "|launch:" + apps.get(i)[0];
        }
        items[apps.size() + 1] = "-";
        items[apps.size() + 2] = "All Apps…|super+a|Super+A";
        showMenu(anchor, items);
        HostLink.get().requestApps();  // refresh for next time
    }

    private void updateClockAndBattery() {
        clock.setText(clockFormat.format(new Date()));
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (level >= 0) {
            battery.setText((charging ? "\u26A1" : "") + (level * 100 / scale) + "%");
        }
    }
}

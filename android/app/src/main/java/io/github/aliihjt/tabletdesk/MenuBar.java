package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** macOS-style menu bar pinned to the top of the tablet screen. */
final class MenuBar implements HostLink.Listener {
    private static final int BG = 0xEBF4F4F6;
    private static final int FG = 0xFF1D1D1F;

    // First entry is the menu title; then "Label|action|shortcut hint", "-" = separator.
    // Actions: plain key combo, "cmd:<name>" (whitelisted on host) or "text:<chars>".
    private static final String[] SYSTEM_MENU = {
            "◆",
            "About This Computer|cmd:about", "System Settings…|cmd:settings", "-",
            "Files|cmd:files", "Terminal|cmd:terminal", "System Monitor|cmd:monitor", "-",
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
    private final Context popupTheme;
    private final LinearLayout root;
    private final TextView appName;
    private final TextView status;
    private final TextView battery;
    private final TextView clock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("EEE d MMM  HH:mm", Locale.getDefault());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClockAndBattery();
            handler.postDelayed(this, 10_000);
        }
    };

    MenuBar(Context ctx, Runnable toggleTouchBar) {
        this.ctx = ctx;
        this.popupTheme = new ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_Light);
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), 0);

        root.addView(menuTitle(SYSTEM_MENU[0], true, SYSTEM_MENU));
        appName = menuTitle("Fedora", true, null);
        appName.setOnClickListener(v -> {
            String[] items = APP_MENU.clone();
            items[0] = HostLink.get().app();
            showMenu(v, items);
        });
        root.addView(appName);
        for (String[] m : MENUS) root.addView(menuTitle(m[0], false, m));

        View spacer = new View(ctx);
        root.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        root.addView(iconButton("⌨", v -> {  // keyboard & trackpad
            Intent i = new Intent(ctx, TrackpadActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }));
        root.addView(iconButton("▭", v -> toggleTouchBar.run()));  // Touch Bar on/off
        status = Ui.label(ctx, "", 13, FG, false);
        status.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
        root.addView(status);
        battery = Ui.label(ctx, "", 13, FG, false);
        battery.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
        root.addView(battery);
        clock = Ui.label(ctx, "", 13, FG, false);
        clock.setPadding(Ui.dp(ctx, 8), 0, 0, 0);
        root.addView(clock);

        HostLink.get().addListener(this);
        handler.post(tick);
    }

    View view() {
        return root;
    }

    WindowManager.LayoutParams params() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 28),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    void destroy() {
        handler.removeCallbacks(tick);
        HostLink.get().removeListener(this);
    }

    @Override
    public void onStatus(boolean connected, String host) {
        status.setText(connected ? "● " + host : "○ Not connected");
        status.setTextColor(connected ? 0xFF1E8E3E : 0xFFC5221F);
    }

    @Override
    public void onApp(String name) {
        appName.setText(name);
    }

    private TextView menuTitle(String title, boolean bold, String[] items) {
        TextView t = Ui.label(ctx, title, 13.5f, FG, bold);
        int pad = Ui.dp(ctx, 9);
        t.setPadding(pad, 0, pad, 0);
        t.setBackground(Ui.pressable(0x00000000, Ui.dp(ctx, 5), 0x33000000));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        if (items != null) t.setOnClickListener(v -> showMenu(v, items));
        return t;
    }

    private TextView iconButton(String glyph, View.OnClickListener l) {
        TextView t = menuTitle(glyph, false, null);
        t.setTextSize(15);
        t.setOnClickListener(l);
        return t;
    }

    private void showMenu(View anchor, String[] items) {
        PopupMenu popup = new PopupMenu(popupTheme, anchor);
        Menu menu = popup.getMenu();
        int group = 0;
        for (int i = 1; i < items.length; i++) {
            if ("-".equals(items[i])) {
                group++;
                continue;
            }
            String[] parts = items[i].split("\\|");
            String title = parts.length > 2 ? parts[0] + "      " + parts[2] : parts[0];
            menu.add(group, i, i, title);
        }
        if (Build.VERSION.SDK_INT >= 28) menu.setGroupDividerEnabled(true);
        popup.setOnMenuItemClickListener(item -> {
            String[] parts = items[item.getItemId()].split("\\|");
            HostLink.get().action(parts[1]);
            return true;
        });
        popup.show();
    }

    private void updateClockAndBattery() {
        clock.setText(clockFormat.format(new Date()));
        Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (level >= 0) {
            battery.setText((charging ? "⚡" : "🔋") + " " + (level * 100 / scale) + "%");
        }
    }
}

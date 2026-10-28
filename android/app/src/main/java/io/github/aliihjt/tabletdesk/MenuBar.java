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
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
    private final WindowManager wm;
    private final boolean dark;
    private final int fg;
    private final Context popupTheme;
    private final FrameLayout root;
    private final LinearLayout bar;
    private final View strip;
    private final TextView appName;
    private final TextView status;
    private final TextView battery;
    private final TextView clock;
    private final WindowManager.LayoutParams params;
    private final boolean autoHide;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("EEE d MMM  HH:mm", Locale.getDefault());
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
        this.wm = wm;
        this.autoHide = autoHide;
        dark = Ui.isDark(ctx);
        fg = dark ? 0xFFF2F2F7 : 0xFF1D1D1F;
        popupTheme = new ContextThemeWrapper(ctx, dark
                ? android.R.style.Theme_DeviceDefault : android.R.style.Theme_DeviceDefault_Light);

        root = new FrameLayout(ctx);
        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(dark ? 0xE01C1C1E : 0xE6F6F6F8);
        bar.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 10), 0);
        bar.setElevation(Ui.dp(ctx, 2));

        TextView logo = menuTitle(SYSTEM_MENU[0], true, SYSTEM_MENU);
        logo.setTextSize(14);
        bar.addView(logo);
        appName = menuTitle("Fedora", true, null);
        appName.setOnClickListener(v -> {
            String[] items = APP_MENU.clone();
            items[0] = HostLink.get().app();
            showMenu(v, items);
        });
        bar.addView(appName);
        for (String[] m : MENUS) bar.addView(menuTitle(m[0], false, m));

        bar.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(iconButton("\u2328", v -> {  // keyboard & trackpad
            Intent i = new Intent(ctx, TrackpadActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }));
        bar.addView(iconButton("\u25AD", v -> toggleTouchBar.run()));  // Touch Bar on/off
        status = small("");
        bar.addView(status);
        battery = small("");
        bar.addView(battery);
        clock = small("");
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(clock);
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
                WindowManager.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 26),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
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
        handler.removeCallbacksAndMessages(null);
        HostLink.get().removeListener(this);
    }

    @Override
    public void onStatus(boolean connected, String host) {
        status.setText(connected ? "\u25CF " + host : "\u25CB Offline");
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
        params.height = Ui.dp(ctx, 26);
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
        t.setTextSize(14);
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
        popup.setOnDismissListener(p -> scheduleHide());
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
            battery.setText((charging ? "\u26A1" : "") + (level * 100 / scale) + "%");
        }
    }
}

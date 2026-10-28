package io.github.aliihjt.tabletdesk;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Fedora Pad home: connection card with one big button, then big tiles. */
public class MainActivity extends Page {
    /** RDP apps that show the Fedora screen, best first. */
    static final String[] RDP = {"com.microsoft.rdc.androidx", "com.freerdp.afreerdp"};

    private SharedPreferences prefs;
    private TextView hostName;
    private TextView hostInfo;
    private TextView pill;

    @Override
    protected void onCreate(Bundle state) {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        // started by the PC when the cable is plugged in: just bring up the floating button
        if (getIntent().getBooleanExtra("autostart", false) && Settings.canDrawOverlays(this)) {
            super.onCreate(state);
            OverlayService.start(this);
            finish();
            return;
        }
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected String title() {
        return null;
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);

        // header: big title + connection pill
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, th.dp(16), 0, th.dp(14));
        head.addView(th.largeTitle("Fedora Pad"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pill = th.text("", 13, 0xFFFFFFFF, true);
        pill.setPadding(th.dp(12), th.dp(6), th.dp(12), th.dp(6));
        head.addView(pill);
        add(head);

        add(connectionCard());

        section("Tools");
        add(tiles());

        TextView tip = th.caption("Tip: over the Fedora screen, tap the small round button for the keyboard, "
                + "trackpad and media. Drag it anywhere.");
        tip.setPadding(th.dp(16), th.dp(18), th.dp(16), 0);
        add(tip);

        onStatus(link.isConnected(), link.host());
    }

    private View connectionCard() {
        LinearLayout card = th.card();
        int p = th.dp(18);
        card.setPadding(p, p, p, p);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(th.iconChip("🖥", Theme.BLUE, 56));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(th.dp(14), 0, 0, 0);
        hostName = th.text("", 20, th.text, true);
        hostInfo = th.caption("");
        texts.addView(hostName);
        texts.addView(hostInfo, th.fullWidth(4));
        top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        TextView start = th.primaryButton("Show Fedora Screen", v -> showScreen());
        start.setOnLongClickListener(v -> {  // Xorg desktops use VNC instead of RDP
            openVnc();
            return true;
        });
        card.addView(start, th.fullWidth(18));

        card.addView(th.segmented(new String[][]{
                {"extend", "Second screen"}, {"mirror", "Same picture"}, {"input", "Keyboard only"}},
                prefs.getString("mode", "extend"), mode -> {
                    prefs.edit().putString("mode", mode).apply();
                    link.mode(mode);
                }), th.fullWidth(12));
        return card;
    }

    private View tiles() {
        Object[][] items = {
                {"⌨️", Theme.BLUE, "Keyboard", "Type on Fedora", KeyboardActivity.class},
                {"🖱", Theme.PURPLE, "Trackpad", "Mouse + keyboard", TrackpadActivity.class},
                {"🎵", Theme.PINK, "Media", "Play, pause, volume", MediaActivity.class},
                {"🎛", Theme.ORANGE, "Controls", "Brightness, Wi-Fi…", ControlsActivity.class},
                {"⚡", Theme.INDIGO, "Shortcuts", "Copy, paste, windows", ShortcutsActivity.class},
                {"🚀", Theme.TEAL, "Apps", "Open Fedora apps", AppsActivity.class},
                {"📊", Theme.GREEN, "Stats", "CPU, memory, battery", StatsActivity.class},
{"\u270D\uFE0F", Theme.YELLOW, "Text & Clipboard", "Write here, type there", ClipboardActivity.class},
                {"\uD83D\uDCC1", Theme.BLUE, "Send Files", "Photos and files to PC", FilesActivity.class},
                {"\uD83C\uDFA4", Theme.RED, "Presenter", "Slides remote + timer", PresenterActivity.class},
                {"\u2B50", Theme.ORANGE, "My Buttons", "Your own shortcuts", ButtonsActivity.class},
                {"⚙️", Theme.GRAY, "Settings", "Look and options", SettingsActivity.class},
        };
        float widthDp = Math.min(820, getResources().getConfiguration().screenWidthDp);
        int columns = widthDp >= 640 ? 4 : 2;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                grid.addView(row, th.fullWidth(i == 0 ? 0 : 12));
            }
            Object[] it = items[i];
            row.addView(tile((String) it[0], (Integer) it[1], (String) it[2], (String) it[3], (Class<?>) it[4]),
                    th.weighted(i % columns == 0 ? 0 : 12));
        }
        return grid;
    }

    private View tile(String symbol, int color, String title, String sub, Class<?> page) {
        LinearLayout t = th.card();
        int p = th.dp(16);
        t.setPadding(p, p, p, p);
        t.addView(th.iconChip(symbol, color, 46));
        TextView name = th.text(title, 17, th.text, true);
        t.addView(name, th.fullWidth(14));
        TextView s = th.caption(sub);
        s.setSingleLine(true);
        t.addView(s, th.fullWidth(4));
        t.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(th.dark ? 0x33FFFFFF : 0x1F000000),
                null, Theme.round(0xFFFFFFFF, th.dp(18))));
        th.click(t, v -> startActivity(new Intent(this, page)));
        return t;
    }

    @Override
    public void onStatus(boolean connected, String host) {
        if (hostName == null) return;
        hostName.setText(connected ? host : "Not connected");
        hostInfo.setText(connected ? "Connected with the USB-C cable · charging"
                : "Plug the tablet into the laptop with the USB-C cable.");
        pill.setText(connected ? "●  Connected" : "○  Offline");
        pill.setBackground(Theme.round(connected ? Theme.GREEN : Theme.GRAY, th.dp(14)));
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

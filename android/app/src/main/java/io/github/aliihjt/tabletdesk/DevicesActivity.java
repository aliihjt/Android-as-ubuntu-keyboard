package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Devices: the iPad, iPhone and other tablets connected to the same PC. Send
 * them files, text and the clipboard, ring them, open a page on them or play
 * and pause their music. Also the "Send to device" choice in Android's share
 * sheet, and the screen shown when another device rings this tablet.
 */
public class DevicesActivity extends Page {
    private static final int PICK = 71, CONFIRM = 72;
    private LinearLayout list;
    private android.widget.Switch uclipSwitch;
    private Devices.Device target;  // for the file picker

    @Override
    protected String title() {
        return "Devices";
    }

    @Override
    protected void build() {
        add(th.caption("Everything connected to the same PC — the iPad and iPhone web app, other tablets — "
                + "can send to each other, ring each other and share one clipboard."), 4);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        add(list, 12);

        section("Universal Clipboard");
        uclipSwitch = th.toggle(Devices.uclip, on -> {
            try {
                link.send(new JSONObject().put("t", "uclipset").put("on", on));
            } catch (JSONException ignored) {
            }
        });
        LinearLayout now = th.row("📤", Theme.BLUE, "Send the tablet's clipboard now", null, th.chevron());
        th.click(now, v -> {
            String text = clipboard();
            if (text.isEmpty()) {
                toast("The clipboard is empty");
                return;
            }
            Devices.lastClip = text;
            try {
                link.send(new JSONObject().put("t", "uclip").put("s", text));
                toast("Shared with every device");
            } catch (JSONException ignored) {
            }
        });
        add(th.group(th.row("📋", Theme.TEAL, "Copy on one, paste on all",
                "The PC, this tablet and the phones share what you copy", uclipSwitch), now));
        add(th.caption("Android lets only the app in front read the clipboard: what you copy on the tablet is "
                + "shared the next time a Fedora Pad screen opens, or with the button."), 6);

        section("Notifications");
        android.widget.Switch mirror = th.toggle(NotifyListener.enabled(this), on -> {
            getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).edit().putBoolean(NotifyListener.PREF, on).apply();
            if (on && !NotifyListener.allowed(this)) {
                toast("Turn on Fedora Pad here, then come back");
                startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        android.content.SharedPreferences prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        android.widget.Switch fromPc = th.toggle(prefs.getBoolean(Devices.PC_NOTIFS, true),
                on -> prefs.edit().putBoolean(Devices.PC_NOTIFS, on).apply());
        add(th.group(th.row("🔔", Theme.RED, "Show notifications on other devices",
                "Messages and alerts from this tablet appear on the iPhone and the laptop; reply from the iPhone", mirror),
                th.row("💻", Theme.INDIGO, "Show the laptop's notifications here",
                        "Chrome, Telegram, mail and other alerts on Fedora also appear on this tablet", fromPc)));
        add(th.caption("Only devices paired with your PC get them. Off by default; Android asks you to allow it once."), 6);

        section("This tablet");
        LinearLayout name = th.row("✏️", Theme.GRAY, "Name", Devices.myName(this), th.chevron());
        th.click(name, v -> rename());
        add(th.group(name));

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i.getBooleanExtra("pc_actions", false)) {  // Control Center › Unlock PC
            i.removeExtra("pc_actions");
            list.post(this::pcActions);
        }
        if (i.getBooleanExtra("stop_ring", false)) {
            Devices.stopRing();
            String who = i.getStringExtra("ring_from");
            if (who != null) {
                new AlertDialog.Builder(this).setTitle("📍 Here I am!")
                        .setMessage(who + " is looking for this tablet.")
                        .setPositiveButton("Found it", (d, w) -> Devices.stopRing()).show();
            }
        }
        String action = i.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) shareSheet(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.on("devices", m -> {
            Devices.update(m);
            draw();
        });
        link.ask("devices");
        draw();
    }

    @Override
    protected void onPause() {
        link.on("devices", null);
        super.onPause();
    }

    private void draw() {
        list.removeAllViews();
        List<View> rows = new ArrayList<>();
        LinearLayout pcRow = th.row("💻", Theme.INDIGO, link.isConnected() ? link.host() : "PC",
                link.isConnected() ? link.via() + (link.latency() >= 0 ? " · " + link.latency() + " ms" : "")
                        + " · tap: unlock, lock, sleep" : "Not connected · plug in the cable, or check Wi-Fi", th.chevron());
        th.click(pcRow, v -> pcActions());
        rows.add(pcRow);
        for (Devices.Device d : Devices.others()) {
            LinearLayout r = th.row(d.icon(), d.kind.equals("android") ? Theme.GREEN : Theme.BLUE, d.name,
                    d.kind.equals("android") ? "Android · tap for actions" : "Web app · tap for actions", th.chevron());
            th.click(r, v -> actions(d));
            rows.add(r);
        }
        list.addView(th.group(rows.toArray(new View[0])));
        if (Devices.others().isEmpty()) {
            list.addView(th.caption(link.isConnected()
                    ? "No other device yet. Open Fedora Pad on the iPad or iPhone (web app) or another tablet."
                    : "Connect to the PC first."), th.fullWidth(8));
        }
        if (uclipSwitch != null && uclipSwitch.isChecked() != Devices.uclip) uclipSwitch.setChecked(Devices.uclip);
    }

    private void actions(Devices.Device d) {
        boolean android = d.kind.equals("android");
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("🖼  Photos and files");
        acts.add(() -> pickFiles(d));
        labels.add("✍️  Text or link");
        acts.add(() -> ask("Text or link for " + d.name, t -> {
            Devices.sendText(d.id, t);
            toast("Sent to " + d.name);
        }));
        labels.add("📋  This tablet's clipboard");
        acts.add(() -> {
            String t = clipboard();
            if (t.isEmpty()) toast("The clipboard is empty");
            else {
                Devices.sendText(d.id, t);
                toast("Sent to " + d.name);
            }
        });
        labels.add("📍  Ring it");
        acts.add(() -> {
            remote(d, "ring", null, null);
            toast("Ringing " + d.name);
        });
        labels.add("💬  Message");
        acts.add(() -> ask("Message for " + d.name, t -> {
            try {
                Devices.remoteAction(d.id, new JSONObject().put("a", "notice").put("text", t));
            } catch (JSONException ignored) {
            }
        }));
        String[][] pages = {{"🕰  Open StandBy", "standby"}, {"⏱  Open Focus", "focus"}, {"🎲  Open فال حافظ", "faal"},
                {android ? "📚  Open Books" : "📚  Open Get Books", "books"}, {"🏠  Go Home", "home"}};
        for (String[] p : pages) {
            labels.add(p[0]);
            acts.add(() -> {
                remote(d, "open", p[1], null);
                toast("Opened on " + d.name);
            });
        }
        if (android) {
            String[][] media = {{"⏯  Play / pause its music", "play-pause"}, {"⏭  Next song", "next"},
                    {"🔊  Louder", "volup"}, {"🔉  Quieter", "voldown"}};
            for (String[] m : media) {
                labels.add(m[0]);
                acts.add(() -> remote(d, "media", null, m[1]));
            }
        }
        new AlertDialog.Builder(this).setTitle(d.icon() + "  " + d.name)
                .setItems(labels.toArray(new String[0]), (dlg, w) -> acts.get(w).run()).show();
    }

    private void remote(Devices.Device d, String a, String page, String key) {
        try {
            JSONObject m = new JSONObject().put("a", a);
            if (page != null) m.put("page", page);
            if (key != null) m.put("key", key);
            Devices.remoteAction(d.id, m);
        } catch (JSONException ignored) {
        }
    }

    // ---- the laptop itself

    private void pcActions() {
        new AlertDialog.Builder(this).setTitle("💻  " + link.host())
                .setItems(new String[]{"🔓  Unlock the laptop", "🔒  Lock the laptop", "💤  Sleep timers", "📊  Battery, CPU, memory"},
                        (d, w) -> {
                            if (w == 0) unlock();
                            else if (w == 1) link.send(obj("t", "lock"));
                            else if (w == 2) startActivity(new Intent(this, WakeActivity.class));
                            else startActivity(new Intent(this, StatsActivity.class));
                        }).show();
    }

    /** The tablet's own screen lock first (fingerprint, PIN), then the laptop's unlock PIN. */
    @SuppressWarnings("deprecation")
    private void unlock() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        Intent confirm = km == null || !km.isDeviceSecure() ? null
                : km.createConfirmDeviceCredentialIntent("Unlock " + link.host(), "Confirm it's you");
        if (confirm != null) startActivityForResult(confirm, CONFIRM);
        else sendPin();
    }

    private void sendPin() {
        // an older version kept the PIN as plain text: move it into the encrypted store
        android.content.SharedPreferences p = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        String old = p.getString("unlock_pin", "");
        if (!old.isEmpty()) {
            SecretStore.put(this, Devices.UNLOCK_PIN, old);
            p.edit().remove("unlock_pin").apply();
        }
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        boolean locked = km != null && km.isDeviceSecure();
        // a saved PIN is only used behind the tablet's own screen lock
        String saved = locked ? SecretStore.get(this, Devices.UNLOCK_PIN) : "";
        if (!saved.isEmpty()) {
            link.send(obj("t", "unlock", "pin", saved));
            return;
        }
        EditText field = new EditText(this);
        field.setHint("Unlock PIN (from ./host/unlock-setup.sh)");
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        android.widget.CheckBox keep = new android.widget.CheckBox(this);
        keep.setText(locked ? "Remember it (the tablet's screen lock still asks each time)"
                : "Set a screen lock on the tablet to remember the PIN");
        keep.setEnabled(locked);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(th.dp(20), th.dp(8), th.dp(20), 0);
        box.addView(field);
        box.addView(keep);
        new AlertDialog.Builder(this).setTitle("🔓 Unlock " + link.host()).setView(box)
                .setPositiveButton("Unlock", (d, w) -> {
                    String pin = field.getText().toString().trim();
                    if (pin.isEmpty()) return;
                    if (keep.isChecked()) SecretStore.put(this, Devices.UNLOCK_PIN, pin);
                    link.send(obj("t", "unlock", "pin", pin));
                })
                .setNegativeButton("Cancel", null).show();
    }

    private static JSONObject obj(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    // ---- sending files

    private void pickFiles(Devices.Device d) {
        target = d;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CONFIRM) {
            if (result == RESULT_OK) sendPin();
            return;
        }
        if (request != PICK || result != RESULT_OK || data == null || target == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        send(target, uris);
    }

    private void send(Devices.Device d, List<Uri> uris) {
        for (Uri u : uris) {
            String name = Devices.displayName(this, u);
            Toast t = Toast.makeText(this, "📤 " + name + " → " + d.name, Toast.LENGTH_SHORT);
            t.show();
            Devices.sendFile(this, u, name, d.id, pct -> {
                if (pct == 100) Toast.makeText(this, "✓ " + name + " sent to " + d.name, Toast.LENGTH_SHORT).show();
            });
        }
    }

    // ---- Android's share sheet: "Send to device"

    private void shareSheet(Intent i) {
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> extra = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (extra != null) uris.addAll(extra);
        } else {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        }
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        List<Devices.Device> others = Devices.others();
        // Handoff the other way: a link opens in Chrome on the laptop
        java.util.regex.Matcher web = java.util.regex.Pattern.compile("https?://\\S+").matcher(text == null ? "" : text);
        String url = uris.isEmpty() && web.find() ? web.group() : null;
        int first = url != null ? 1 : 0;
        if (!link.isConnected() || (others.isEmpty() && url == null)) {
            toast(link.isConnected() ? "No other device is connected" : "Not connected to the PC");
            return;
        }
        String[] names = new String[others.size() + first];
        if (url != null) names[0] = "💻  Open on " + link.host();
        for (int k = 0; k < others.size(); k++) names[k + first] = others.get(k).icon() + "  " + others.get(k).name;
        new AlertDialog.Builder(this).setTitle("Send to…").setItems(names, (dlg, w) -> {
            if (url != null && w == 0) {
                link.send(obj("t", "url", "u", url));
                toast("Opening on " + link.host());
                return;
            }
            Devices.Device d = others.get(w - first);
            if (!uris.isEmpty()) send(d, uris);
            else if (text != null && !text.isEmpty()) {
                Devices.sendText(d.id, text);
                toast("Sent to " + d.name);
            }
        }).show();
    }

    // ---- small helpers

    private String clipboard() {
        ClipData c = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (c == null || c.getItemCount() == 0 || c.getItemAt(0).getText() == null) return "";
        return c.getItemAt(0).getText().toString();
    }

    private void ask(String title, java.util.function.Consumer<String> then) {
        EditText field = new EditText(this);
        field.setMinLines(2);
        new AlertDialog.Builder(this).setTitle(title).setView(field)
                .setPositiveButton("Send", (d, w) -> {
                    String t = field.getText().toString().trim();
                    if (!t.isEmpty()) then.accept(t);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void rename() {
        EditText field = new EditText(this);
        field.setText(Devices.myName(this));
        new AlertDialog.Builder(this).setTitle("This tablet's name").setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).edit()
                            .putString("dev_name", field.getText().toString().trim()).apply();
                    Devices.hello(getApplicationContext());
                    recreate();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

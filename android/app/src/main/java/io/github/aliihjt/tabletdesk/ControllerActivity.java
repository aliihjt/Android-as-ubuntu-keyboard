package io.github.aliihjt.tabletdesk;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Game controller pass-through: a PS4 DualShock 4, Xbox or other controller
 * paired with the tablet becomes a real controller on Fedora (host/gamepad.py
 * makes a virtual one with uinput). Rumble from the game shakes the controller.
 * Android only gives controller input to the app on screen, so this page stays
 * open (and the tablet awake) while you play on the laptop.
 */
public class ControllerActivity extends Page implements InputManager.InputDeviceListener, HostLink.StateListener {
    /** Button bits sent to the PC, same order as BUTTONS in host/gamepad.py. */
    static final int[] KEYS = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR};
    private static final int SELECT_BIT = 8, L2_BIT = 6, R2_BIT = 7;
    static final int MAX_PLAYERS = 4;

    /** One controller connected to the tablet. */
    private static final class Pad {
        int deviceId, player;
        String name;
        boolean sony, rightOnZ, analogL, analogR;
        int buttons, keyHx, keyHy, axisHx, axisHy;
        float lx, ly, rx, ry, lt, rt;
        String lastSent = "";
        String onPc;    // name on the PC once it answered
        String error;
    }

    private final SparseArray<Pad> pads = new SparseArray<>();  // by Android device id
    private SharedPreferences prefs;
    private InputManager im;
    private PadView padView;
    private TextView heroTitle, heroText;
    private LinearLayout list;
    private boolean plugged;  // padadd sent for the pads below
    private android.widget.Switch steamSwitch;
    private LinearLayout iisuHolder;
    private boolean applying;

    @Override
    protected String title() {
        return "Controller";
    }

    @Override
    protected void build() {
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        im = (InputManager) getSystemService(INPUT_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);  // input stops if it sleeps

        LinearLayout hero = th.card();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(th.dp(18), th.dp(18), th.dp(18), th.dp(18));
        heroTitle = th.text("", 18, th.text, true);
        heroTitle.setGravity(Gravity.CENTER);
        hero.addView(heroTitle);
        heroText = th.caption("");
        heroText.setGravity(Gravity.CENTER);
        hero.addView(heroText, th.fullWidth(4));
        padView = new PadView(this, th);
        hero.addView(padView, th.fullWidth(16));
        hero.addView(th.secondaryButton("📳 Test rumble", v -> {
            for (int i = 0; i < pads.size(); i++) rumble(pads.valueAt(i), 1f, 0.6f, 350, true);
        }), th.fullWidth(14));
        add(hero);

        section("Controllers");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        add(list);

        section("On Fedora");
        add(th.group(
                th.row("🎮", Theme.GREEN, "Send to Fedora", "Play games on the laptop with it",
                        th.toggle(prefs.getBoolean("pad_send", true), on -> {
                            prefs.edit().putBoolean("pad_send", on).apply();
                            if (on) plugAll();
                            else unplugAll();
                        })),
                th.row("📳", Theme.ORANGE, "Rumble", "Games shake the controller",
                        th.toggle(prefs.getBoolean("pad_rumble", true),
                                on -> prefs.edit().putBoolean("pad_rumble", on).apply())),
                th.row("🌙", Theme.INDIGO, "Dim the tablet while playing", "Saves battery; it stays awake",
                        th.toggle(prefs.getBoolean("pad_dim", false), on -> {
                            prefs.edit().putBoolean("pad_dim", on).apply();
                            dim(on);
                        }))));
        add(th.caption("Fedora sees it as:"), 14);
        add(th.segmented(new String[][]{{"xbox", "Xbox controller"}, {"ps", "PlayStation controller"}},
                kind(), v -> {
                    prefs.edit().putString("pad_kind", v).apply();
                    unplugAll();  // the PC makes the new kind of controller
                    plugAll();
                    padView.ps = "ps".equals(v);
                    padView.invalidate();
                }), 6);
        add(th.caption("Xbox works in every game, Steam and emulators. PlayStation shows ✕ ○ □ △ in the "
                + "games that support it; if a game doesn't see the controller, switch to Xbox."), 6);

        section("When a controller connects");
        add(th.segmented(new String[][]{{"off", "Nothing"}, {"smart", "Smart"}, {"iisu", "iiSU"}, {"fedora", "Fedora"}},
                prefs.getString(ControllerAuto.PREF, "off"),
                v -> prefs.edit().putString(ControllerAuto.PREF, v).apply()));
        add(th.caption("Smart: while the tablet is connected to Fedora, this page opens and you play on the "
                + "laptop; otherwise iiSU opens and you play on the tablet. iiSU / Fedora: always that one."), 6);
        steamSwitch = th.toggle(link.lastState().optBoolean("steambp", false), on -> {
            if (!applying) link.set("steambp", on ? 1 : 0);
        });
        iisuHolder = new LinearLayout(this);
        add(th.group(
                th.row("🎮", Theme.BLUE, "Open Steam Big Picture on Fedora",
                        "When any controller connects to the laptop, even without the tablet", steamSwitch),
                iisuHolder), 10);
        add(th.caption("host/update.sh installs Steam on Fedora and iiSU on the tablet if they are missing."), 6);

        section("Pair a PS4 controller");
        add(th.caption("1. Hold SHARE and the PS button until the light bar flashes quickly.\n"
                + "2. On the tablet: Bluetooth › Pair new device › Wireless Controller.\n"
                + "Or plug the controller into the tablet with a USB cable. Xbox, 8BitDo and other "
                + "Bluetooth controllers work the same way."));
        add(th.secondaryButton("Open Bluetooth settings",
                v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))), 10);
        add(th.caption("Keep this page open while you play: Android only gives the controller to the app "
                + "on screen. Up to 4 controllers, over the USB cable or Wi-Fi. Tip: a PS4 controller also "
                + "works plugged straight into the laptop; Fedora has the driver built in."), 14);

        padView.ps = "ps".equals(kind());
        link.on("padok", this::onPadOk);
        link.on("padrumble", m -> {
            Pad p = byPlayer(m.optInt("p", -1));
            if (p != null) rumble(p, (float) m.optDouble("s", 0), (float) m.optDouble("w", 0), m.optInt("ms", 0), false);
        });
    }

    private String kind() {
        return prefs.getString("pad_kind", "xbox");
    }

    // ---- controllers coming and going ----

    @Override
    protected void onStart() {
        super.onStart();
        ControllerAuto.pageOpen = true;
    }

    /** iiSU row: open it, or get it when it isn't on the tablet. */
    private void showIisu() {
        iisuHolder.removeAllViews();
        boolean has = ControllerAuto.iisuIntent(this) != null;
        LinearLayout r = th.row("🕹", Theme.PURPLE, has ? "Open iiSU" : "Get iiSU",
                has ? "Game launcher for playing on the tablet" : "Not installed: opens the download page",
                th.chevron());
        th.click(r, v -> ControllerAuto.openIisu(this, true));
        iisuHolder.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public void onState(JSONObject st) {
        if (!st.has("steambp")) return;
        applying = true;
        steamSwitch.setChecked(st.optBoolean("steambp"));
        applying = false;
    }

    @Override
    protected void onPause() {
        link.setStateListener(null);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.setStateListener(this);
        link.requestState();
        showIisu();
        im.registerInputDeviceListener(this, null);
        for (int id : im.getInputDeviceIds()) onInputDeviceAdded(id);
        dim(prefs.getBoolean("pad_dim", false));
        if (Build.VERSION.SDK_INT >= 30) {  // each stick movement as it happens, not once per frame
            getWindow().getDecorView().requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK);
        }
        plugAll();
        refresh();
    }

    @Override
    protected void onStop() {
        im.unregisterInputDeviceListener(this);
        unplugAll();  // the page is gone, so is the controller on Fedora
        ControllerAuto.pageOpen = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        link.on("padok", null);
        link.on("padrumble", null);
        super.onDestroy();
    }

    @Override
    public void onStatus(boolean connected, String host) {
        super.onStatus(connected, host);
        if (connected) {  // (re)connected: plug the controllers in again
            plugged = false;
            plugAll();
        } else {
            for (int i = 0; i < pads.size(); i++) pads.valueAt(i).onPc = null;
        }
        refresh();
    }

    static boolean isPad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        if ((s & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return false;
        boolean[] has = d.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START);
        return has[0] || has[1];
    }

    @Override
    public void onInputDeviceAdded(int id) {
        if (pads.get(id) != null || pads.size() >= MAX_PLAYERS) return;
        InputDevice d = InputDevice.getDevice(id);
        if (!isPad(d)) return;
        Pad p = new Pad();
        p.deviceId = id;
        p.name = d.getName();
        p.sony = d.getVendorId() == 0x054C;
        p.rightOnZ = d.getMotionRange(MotionEvent.AXIS_Z) != null || d.getMotionRange(MotionEvent.AXIS_RX) == null;
        p.analogL = d.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || d.getMotionRange(MotionEvent.AXIS_BRAKE) != null;
        p.analogR = d.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null || d.getMotionRange(MotionEvent.AXIS_GAS) != null;
        p.player = freePlayer(d.getControllerNumber() - 1);
        pads.put(id, p);
        if (plugged) plug(p);
        refresh();
    }

    @Override
    public void onInputDeviceRemoved(int id) {
        Pad p = pads.get(id);
        if (p == null) return;
        pads.remove(id);
        if (plugged) unplug(p);
        refresh();
    }

    @Override
    public void onInputDeviceChanged(int id) {
        if (pads.get(id) != null && !isPad(InputDevice.getDevice(id))) onInputDeviceRemoved(id);
        else onInputDeviceAdded(id);
    }

    /** Android's own controller number when it's free, else the lowest free player. */
    private int freePlayer(int wanted) {
        if (wanted >= 0 && wanted < MAX_PLAYERS && byPlayer(wanted) == null) return wanted;
        for (int i = 0; i < MAX_PLAYERS; i++) if (byPlayer(i) == null) return i;
        return 0;
    }

    private Pad byPlayer(int player) {
        for (int i = 0; i < pads.size(); i++) if (pads.valueAt(i).player == player) return pads.valueAt(i);
        return null;
    }

    // ---- to the PC ----

    private boolean sending() {
        return prefs.getBoolean("pad_send", true) && link.isConnected();
    }

    private void plugAll() {
        if (!sending() || plugged) return;
        plugged = true;
        for (int i = 0; i < pads.size(); i++) plug(pads.valueAt(i));
    }

    private void unplugAll() {
        if (!plugged) return;
        for (int i = 0; i < pads.size(); i++) unplug(pads.valueAt(i));
        plugged = false;
        refresh();
    }

    private void plug(Pad p) {
        p.lastSent = "";
        p.onPc = null;
        p.error = null;
        JSONObject o = new JSONObject();
        try {
            o.put("t", "padadd").put("p", p.player).put("kind", kind()).put("name", p.name);
        } catch (JSONException ignored) {
        }
        link.send(o);
        sendState(p);
    }

    private void unplug(Pad p) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", "padrm").put("p", p.player);
        } catch (JSONException ignored) {
        }
        link.send(o);
        p.onPc = null;
    }

    private void onPadOk(JSONObject m) {
        Pad p = byPlayer(m.optInt("p", -1));
        if (p == null) return;
        if (m.has("error")) p.error = m.optString("error");
        else p.onPc = m.optString("name", "controller");
        refresh();
    }

    private void sendState(Pad p) {
        float lt = p.analogL ? p.lt : (bit(p, L2_BIT) ? 1f : 0f);
        float rt = p.analogR ? p.rt : (bit(p, R2_BIT) ? 1f : 0f);
        int hx = p.axisHx != 0 ? p.axisHx : p.keyHx, hy = p.axisHy != 0 ? p.axisHy : p.keyHy;
        String msg = String.format(Locale.US, "{\"t\":\"pad\",\"p\":%d,\"b\":%d,\"lx\":%.3f,\"ly\":%.3f,"
                        + "\"rx\":%.3f,\"ry\":%.3f,\"lt\":%.3f,\"rt\":%.3f,\"hx\":%d,\"hy\":%d}",
                p.player, p.buttons, p.lx, p.ly, p.rx, p.ry, lt, rt, hx, hy);
        if (plugged && !msg.equals(p.lastSent)) {
            p.lastSent = msg;
            link.raw(msg);
        }
        padView.buttons = p.buttons;
        padView.lx = p.lx;
        padView.ly = p.ly;
        padView.rx = p.rx;
        padView.ry = p.ry;
        padView.lt = lt;
        padView.rt = rt;
        padView.hx = hx;
        padView.hy = hy;
        padView.invalidate();
    }

    private static boolean bit(Pad p, int b) {
        return (p.buttons >> b & 1) != 0;
    }

    // ---- input from the controllers ----

    private Pad padFor(InputDevice d, int id) {
        Pad p = pads.get(id);
        if (p == null && isPad(d)) {
            onInputDeviceAdded(id);
            p = pads.get(id);
        }
        return p;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        Pad p = padFor(ev.getDevice(), ev.getDeviceId());
        if (p == null) return super.dispatchKeyEvent(ev);
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (ev.getRepeatCount() > 0) return true;  // held buttons don't repeat on a controller
        int bit = -1;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == code) bit = i;
        if (code == KeyEvent.KEYCODE_BACK) bit = SELECT_BIT;  // some pads send Back for Select / View
        if (bit >= 0) {
            p.buttons = down ? p.buttons | 1 << bit : p.buttons & ~(1 << bit);
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            p.keyHx = down ? (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1) : 0;
        } else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            p.keyHy = down ? (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) : 0;
        } else if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
            return true;
        } else {
            return super.dispatchKeyEvent(ev);  // volume keys etc. keep working
        }
        sendState(p);
        return true;  // never let B become Back or the d-pad move focus on this page
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || ev.getAction() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(ev);
        }
        Pad p = padFor(ev.getDevice(), ev.getDeviceId());
        if (p == null) return super.dispatchGenericMotionEvent(ev);
        p.lx = ev.getAxisValue(MotionEvent.AXIS_X);
        p.ly = ev.getAxisValue(MotionEvent.AXIS_Y);
        p.rx = ev.getAxisValue(p.rightOnZ ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX);
        p.ry = ev.getAxisValue(p.rightOnZ ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY);
        p.lt = Math.max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE));
        p.rt = Math.max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS));
        p.axisHx = Math.round(ev.getAxisValue(MotionEvent.AXIS_HAT_X));
        p.axisHy = Math.round(ev.getAxisValue(MotionEvent.AXIS_HAT_Y));
        sendState(p);
        return true;
    }

    // ---- rumble, dimming, the page ----

    private void rumble(Pad p, float strong, float weak, int ms, boolean test) {
        if (!test && !prefs.getBoolean("pad_rumble", true)) return;
        InputDevice d = InputDevice.getDevice(p.deviceId);
        if (d == null) return;
        boolean stop = ms <= 0 || (strong < 0.02f && weak < 0.02f);
        ms = Math.min(Math.max(ms, 1), 10000);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = d.getVibratorManager();
                int[] ids = vm.getVibratorIds();
                if (ids.length > 0) {
                    if (stop) {
                        vm.cancel();
                        return;
                    }
                    CombinedVibration.ParallelCombination all = CombinedVibration.startParallel();
                    boolean any = false;
                    for (int i = 0; i < ids.length; i++) {
                        // two motors: the first is the big (strong) one
                        float m = ids.length > 1 ? (i == 0 ? strong : weak) : Math.max(strong, weak);
                        int amp = Math.min(255, Math.round(m * 255));
                        if (amp < 1) continue;
                        all.addVibrator(ids[i], VibrationEffect.createOneShot(ms, amp));
                        any = true;
                    }
                    if (any) vm.vibrate(all.combine());
                    return;
                }
            }
            Vibrator v = d.getVibrator();
            if (!v.hasVibrator()) return;
            if (stop) {
                v.cancel();
                return;
            }
            int amp = Math.max(1, Math.min(255, Math.round(Math.max(strong, weak) * 255)));
            v.vibrate(VibrationEffect.createOneShot(ms, v.hasAmplitudeControl() ? amp : VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) {  // controller gone or without motors
        }
    }

    private void dim(boolean on) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        boolean windows = "windows".equals(link.os());
        if (pads.size() == 0) {
            heroTitle.setText("No controller yet");
            heroText.setText("Pair a PS4 or other controller with the tablet (below), then press any button.");
            padView.setAlpha(0.45f);
            LinearLayout r = th.row("🔵", Theme.BLUE, "Pair a controller", "Bluetooth settings", th.chevron());
            th.click(r, v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            list.addView(th.group(r));
            return;
        }
        padView.setAlpha(1f);
        Pad first = null;
        LinearLayout group = th.card();
        for (int i = 0; i < pads.size(); i++) {
            Pad p = pads.valueAt(i);
            if (first == null || p.player < first.player) first = p;
            String state = status(p, windows);
            if (group.getChildCount() > 0) {
                android.view.View line = new android.view.View(this);
                line.setBackgroundColor(th.separator);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, th.dp(0.5f)));
                lp.leftMargin = th.dp(58);
                group.addView(line, lp);
            }
            group.addView(th.row(String.valueOf(p.player + 1), p.onPc != null ? Theme.GREEN : Theme.GRAY,
                    p.name + (p.sony ? " (PlayStation)" : ""), state, null));
        }
        list.addView(group);
        heroTitle.setText(first.name);
        heroText.setText("Player " + (first.player + 1) + " · " + status(first, windows));
    }

    private String status(Pad p, boolean windows) {
        if (!prefs.getBoolean("pad_send", true)) return "Only on the tablet (Send to Fedora is off)";
        if (!link.isConnected()) return "Waiting for the PC…";
        if (windows) return "Needs a Fedora / Linux PC for now";
        if (p.error != null) return p.error;
        if (p.onPc == null) return "Connecting to Fedora…";
        return "On Fedora as " + ("ps".equals(kind()) ? "a PlayStation" : "an Xbox") + " controller";
    }
}

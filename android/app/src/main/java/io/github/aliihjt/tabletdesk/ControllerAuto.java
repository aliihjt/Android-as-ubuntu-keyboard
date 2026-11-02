package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;

import java.util.Locale;

/**
 * "When a controller connects" (Controller page): turning a controller on opens
 * the iiSU game launcher to play on the tablet, or the Controller page to play
 * on Fedora (where Steam Big Picture can open by itself, host/gamepad.py).
 * Smart picks Fedora while the tablet is connected to it, iiSU otherwise.
 * Runs while Fedora Pad's service or home screen is alive; the floating-button
 * permission lets it open an app from the background.
 */
final class ControllerAuto {
    static final String IISU = "com.iisulauncher";
    static final String IISU_DOWNLOAD = "https://github.com/iisu-network/iiSU/releases";
    /** off | smart | iisu | fedora */
    static final String PREF = "pad_auto";

    static volatile boolean pageOpen;  // the Controller page is on screen: it handles controllers itself
    private static boolean registered;
    private static long lastLaunch = -60000;

    private ControllerAuto() {
    }

    static void register(Context c) {
        if (registered) return;
        registered = true;
        Context app = c.getApplicationContext();
        InputManager im = app.getSystemService(InputManager.class);
        im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
                connected(app, id);
            }

            @Override
            public void onInputDeviceRemoved(int id) {
            }

            @Override
            public void onInputDeviceChanged(int id) {
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private static void connected(Context c, int id) {
        SharedPreferences prefs = c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString(PREF, "off");
        if ("off".equals(mode) || pageOpen || !ControllerActivity.isPad(InputDevice.getDevice(id))) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 20000) return;  // a second controller, or the same one reconnecting
        lastLaunch = now;
        HostLink link = HostLink.get();
        boolean fedora = "fedora".equals(mode)
                || ("smart".equals(mode) && link.isConnected() && !"windows".equals(link.os()));
        try {
            if (fedora) {
                c.startActivity(new Intent(c, ControllerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                openIisu(c, false);
            }
        } catch (RuntimeException ignored) {  // Android refused to open it from the background
        }
    }

    /** The intent that opens iiSU, or null when it isn't installed. */
    static Intent iisuIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLaunchIntentForPackage(IISU);
        if (i == null) {  // a home-screen launcher may have no app-drawer icon
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(IISU);
            if (pm.resolveActivity(home, 0) != null) i = home;
        }
        if (i == null) {  // another build with a different package name
            Intent all = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(all, 0)) {
                if (String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT).contains("iisu")) {
                    i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(r.activityInfo.packageName, r.activityInfo.name);
                    break;
                }
            }
        }
        return i == null ? null : i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** Opens iiSU; when it's missing, opens its download page if getIt (a tap, not a controller). */
    static boolean openIisu(Context c, boolean getIt) {
        Intent i = iisuIntent(c);
        if (i != null) {
            c.startActivity(i);
            return true;
        }
        if (getIt) {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(IISU_DOWNLOAD))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return false;
    }
}

package io.github.aliihjt.tabletdesk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates Fedora Pad from inside the app: reads the newest build number from
 * GitHub, downloads the APK and hands it to Android's installer (Android asks
 * to confirm; it only accepts an update signed with the same key).
 */
final class AppUpdate {
    static final String BASE = "https://github.com/aliihjt/Android-as-ubuntu-keyboard/releases/download/latest-apk/";

    interface Progress {
        /** percent -1 = not downloading; text = what to show */
        void on(int percent, String text);
    }

    private AppUpdate() {
    }

    static long installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The newest build on GitHub, or -1 when it can't be reached. Call off the main thread. */
    static long latest() {
        try {
            HttpURLConnection c = open(BASE + "version.txt");
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[32];
                int n = in.read(buf);
                return Long.parseLong(new String(buf, 0, Math.max(0, n)).trim());
            } finally {
                c.disconnect();
            }
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "FedoraPad");
        c.setInstanceFollowRedirects(true);
        return c;
    }

    /** Checks, downloads and installs, reporting along the way on the main thread. */
    static void run(Context ctx, Progress p) {
        Context c = ctx.getApplicationContext();
        Handler ui = new Handler(Looper.getMainLooper());
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            p.on(-1, "Allow Fedora Pad to install updates, then tap Update again.");
            ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName())));
            return;
        }
        p.on(0, "Checking…");
        new Thread(() -> {
            long have = installed(c), newest = latest();
            if (newest < 0) {
                ui.post(() -> p.on(-1, "Couldn't reach GitHub. Check the internet (or plug into the PC: it updates the app too)."));
                return;
            }
            if (newest <= have) {
                ui.post(() -> p.on(-1, "✅ Up to date (build " + have + ")"));
                return;
            }
            File apk = new File(c.getCacheDir(), "update.apk");
            try {
                HttpURLConnection conn = open(BASE + "TabletDesk.apk");
                long size = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int n, shown = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int pct = size > 0 ? (int) (got * 100 / size) : 0;
                        if (pct != shown) {
                            shown = pct;
                            String mb = String.format(java.util.Locale.US, "%.1f / %.1f MB", got / 1e6, size / 1e6);
                            ui.post(() -> p.on(pct, "Downloading build " + newest + "…  " + pct + " %  (" + mb + ")"));
                        }
                    }
                } finally {
                    conn.disconnect();
                }
                install(c, apk);
                ui.post(() -> p.on(-1, "Installing build " + newest + ": confirm on the screen."));
            } catch (IOException e) {
                apk.delete();
                ui.post(() -> p.on(-1, "Download failed: " + e.getMessage()));
            }
        }, "app-update").start();
    }

    private static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(c, Done.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
        }
        apk.delete();
    }

    /** Android's answer: show its "Install?" screen, or say what went wrong. */
    public static final class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, "Update not installed" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
            }
        }
    }
}

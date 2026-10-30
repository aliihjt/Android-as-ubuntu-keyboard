package io.github.aliihjt.tabletdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The tablet's own Android apps, for Fedora Pad as a home screen. */
final class TabletApps {
    static final class App {
        final String label, pkg, cls;
        final ResolveInfo info;

        App(String label, String pkg, String cls, ResolveInfo info) {
            this.label = label;
            this.pkg = pkg;
            this.cls = cls;
            this.info = info;
        }

        String key() {
            return pkg + "/" + cls;
        }
    }

    private static final String PINNED = "pinned_apps";

    private TabletApps() {
    }

    /** Every app with an icon in the tablet's app drawer, A to Z. */
    static List<App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            if (r.activityInfo.packageName.equals(c.getPackageName())) continue;
            out.add(new App(r.loadLabel(pm).toString(), r.activityInfo.packageName, r.activityInfo.name, r));
        }
        Collator collator = Collator.getInstance();
        out.sort((a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    static void launch(Context c, App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(a.pkg, a.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(c, "Can't open " + a.label, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static Set<String> pinned(Context c) {
        String s = prefs(c).getString(PINNED, "");
        return new LinkedHashSet<>(s.isEmpty() ? new ArrayList<>() : Arrays.asList(s.split("\n")));
    }

    static void setPinned(Context c, App a, boolean pin) {
        Set<String> p = pinned(c);
        if (pin) p.add(a.key());
        else p.remove(a.key());
        prefs(c).edit().putString(PINNED, String.join("\n", p)).apply();
    }

    /** Pinned apps, in the order they were pinned. */
    static List<App> pinnedApps(Context c) {
        List<App> all = all(c), out = new ArrayList<>();
        for (String k : pinned(c)) {
            for (App a : all) {
                if (a.key().equals(k)) {
                    out.add(a);
                    break;
                }
            }
        }
        return out;
    }

    /** The app's real icon with its name under it. */
    static View icon(Context c, Theme th, App a, int sizeDp, int labelColor, Runnable longPress) {
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView img = new ImageView(c);
        Drawable d = a.info.loadIcon(c.getPackageManager());
        img.setImageDrawable(d);
        cell.addView(img, new LinearLayout.LayoutParams(th.dp(sizeDp), th.dp(sizeDp)));
        TextView name = th.text(a.label, 13, labelColor, true);
        if (labelColor == 0xFFFFFFFF) name.setShadowLayer(th.dp(3), 0, th.dp(1), 0x99000000);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        cell.addView(name, th.fullWidth(8));
        th.click(cell, v -> {
            img.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                    .withEndAction(() -> img.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            launch(c, a);
        });
        if (longPress != null) {
            cell.setOnLongClickListener(v -> {
                longPress.run();
                return true;
            });
        }
        return cell;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }
}

package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen as on an iPad: pages of app icons you swipe through, widgets
 * on the first page, folders, and the Dock. Swipe down from the top right for
 * Control Center, from the top left for Notification Center, and in the middle
 * for Search. The Mac desktop and the Reader home are a switch away (Settings).
 */
final class IPadHome {
    interface Host {
        View controlCenter();       // the same Control Center as the Mac desktop's

        void controlCenterShown(boolean shown);
    }

    /** An icon: symbol, colour, name, and what it opens (a page, a Runnable, or a folder). */
    private static final class Item {
        final String symbol, name;
        final int color;
        final Object open;

        Item(String symbol, int color, String name, Object open) {
            this.symbol = symbol;
            this.color = color;
            this.name = name;
            this.open = open;
        }
    }

    private final Activity a;
    private final Theme th;
    private final Host host;
    private final FrameLayout root;
    private Pager pager;
    private LinearLayout dots;
    private HomeWidgets widgets;
    private View overlay;

    IPadHome(Activity a, Theme th, Host host) {
        this.a = a;
        this.th = th;
        this.host = host;
        root = new Gestures(a);
    }

    View view() {
        return root;
    }

    void resume() {
        build();
        widgets.resume();
        Devices.tabListener = this::paintHandoff;
        Devices.askHandoff();
    }

    void pause() {
        if (widgets != null) widgets.pause();
        Devices.tabListener = null;
    }

    /** Back closes an open folder or panel first. */
    boolean back() {
        if (overlay == null) return false;
        closeOverlay();
        return true;
    }

    // ---- what's on the home screen

    private Item tool(String id) {
        Tools.Tool t = Tools.get(id);
        return new Item(t.symbol, t.color, t.name, (Runnable) () -> Tools.open(a, t));
    }

    private List<Item> tools(String... ids) {
        List<Item> l = new ArrayList<>();
        for (String id : ids) l.add(tool(id));
        return l;
    }

    /** The first page: what isn't in the Dock, with the rest in two folders, so it stays calm. */
    private List<Item> mainApps() {
        List<Item> l = tools("mirror", "ptt", "sketch", "scanner", "focus", "standby");
        l.add(new Item("💻", Theme.INDIGO, "Laptop", laptopFolder()));
        l.add(new Item("📖", Theme.ORANGE, "Reading", readingFolder()));
        l.addAll(tools("settings", "applib"));
        return l;
    }

    private List<Item> laptopFolder() {
        return tools("media", "controls", "shortcuts", "apps", "windows", "text", "files", "cliphistory",
                "presenter", "streamdeck", "webcam", "controller", "wake", "stats", "terminal", "ambient");
    }

    private List<Item> readingFolder() {
        return tools("getbooks", "highlights", "words", "faal");
    }

    private List<Item> dockApps() {
        return tools("keyboard", "trackpad", "books", "notes", "claude", "devices");
    }

    @SuppressWarnings("unchecked")
    private void open(Item it, View from) {
        if (it.open instanceof Class) a.startActivity(new Intent(a, (Class<?>) it.open));
        else if (it.open instanceof Runnable) ((Runnable) it.open).run();
        else if (it.open instanceof List) folder(it.name, (List<Item>) it.open);
    }

    // ---- layout

    private boolean wide() {
        return a.getResources().getConfiguration().screenWidthDp >= 900;
    }

    private void build() {
        root.removeAllViews();
        overlay = null;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // the two grabbers at the top, like the iPad's: notifications left, Control Center right
        FrameLayout top = new FrameLayout(a);
        top.addView(grabber("🔔", this::notificationCenter), new FrameLayout.LayoutParams(th.dp(64), th.dp(28), Gravity.START));
        top.addView(grabber("◐", this::controlCenter), new FrameLayout.LayoutParams(th.dp(64), th.dp(28), Gravity.END));
        col.addView(top, new LinearLayout.LayoutParams(-1, th.dp(28)));

        pager = new Pager(a);
        col.addView(pager, new LinearLayout.LayoutParams(-1, 0, 1f));
        dots = new LinearLayout(a);
        dots.setGravity(Gravity.CENTER);
        col.addView(dots, new LinearLayout.LayoutParams(-1, th.dp(22)));
        LinearLayout dockRow = new LinearLayout(a);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setPadding(0, 0, 0, th.dp(10));
        dockRow.addView(dock());
        col.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        widgets = new HomeWidgets(a, th, true);
        pager.post(this::fillPages);  // needs the pager's size
    }

    private View grabber(String symbol, Runnable open) {
        TextView t = th.text(symbol, 13, th.onWall, true);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(th.dp(3), 0, th.dp(1), th.wallShadow);
        th.click(t, v -> open.run());
        return t;
    }

    private void fillPages() {
        int w = pager.getWidth(), h = pager.getHeight();
        if (w == 0) return;
        int cols = wide() ? 6 : a.getResources().getConfiguration().screenWidthDp >= 600 ? 5 : 4;
        int cell = th.dp(108);
        int rows = Math.max(2, (h - th.dp(16)) / cell);
        LinearLayout pages = new LinearLayout(a);
        List<Item> apps = mainApps();
        List<View> icons = new ArrayList<>();
        for (Item it : apps) icons.add(icon(it, 62, th.onWall));
        for (TabletApps.App app : TabletApps.pinnedApps(a)) {
            icons.add(TabletApps.icon(a, th, app, 62, th.onWall, () -> new android.app.AlertDialog.Builder(a)
                    .setTitle(app.label).setItems(new String[]{"Remove from Home Screen"}, (d, which) -> {
                        TabletApps.setPinned(a, app, false);
                        resume();
                    }).show()));
        }

        // page 1: widgets on top, then as many icons as fit
        LinearLayout first = pageView(w);
        first.addView(todayWidgets());
        int widgetRows = 2;
        int firstCount = Math.max(cols, (rows - widgetRows) * cols);
        int at = addGrid(first, icons, 0, firstCount, cols);
        pages.addView(first, new LinearLayout.LayoutParams(w, -1));
        int count = 1;
        while (at < icons.size()) {
            LinearLayout p = pageView(w);
            at = addGrid(p, icons, at, rows * cols, cols);
            pages.addView(p, new LinearLayout.LayoutParams(w, -1));
            count++;
        }
        pager.removeAllViews();
        pager.addView(pages);
        pager.count = count;
        paintDots(0);
        pager.onPage = this::paintDots;
    }

    private LinearLayout pageView(int w) {
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        int side = th.dp(wide() ? 48 : 20);
        p.setPadding(side, th.dp(6), side, 0);
        return p;
    }

    private int addGrid(LinearLayout page, List<View> icons, int from, int max, int cols) {
        int end = Math.min(icons.size(), from + max);
        LinearLayout row = null;
        for (int i = from; i < end; i++) {
            if ((i - from) % cols == 0) {
                row = new LinearLayout(a);
                page.addView(row, th.fullWidth(i == from ? 8 : 18));
            }
            View v = icons.get(i);
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            row.addView(v, th.weighted(0));
        }
        int rest = (end - from) % cols;
        for (int i = rest; rest != 0 && i < cols; i++) row.addView(new View(a), th.weighted(0));
        return end;
    }

    /** The first page's widgets: a big date, weather, calendar, the laptop, music, and the book you're reading. */
    private View todayWidgets() {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Date now = new Date();
        TextView day = th.text(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), 24, th.onWall, true);
        day.setShadowLayer(th.dp(4), 0, th.dp(1), th.wallShadow);
        head.addView(day, new LinearLayout.LayoutParams(0, -2, 1f));
        List<BooksActivity.Book> books = BooksActivity.library(a);
        if (!books.isEmpty()) {
            BooksActivity.Book b = books.get(0);
            TextView cont = th.text("📖  " + b.title + (b.total > 0 ? "  ·  " + (b.position + 1) + "/" + b.total : ""),
                    14, th.text, true);
            cont.setSingleLine(true);
            cont.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cont.setMaxWidth(th.dp(300));
            cont.setPadding(th.dp(14), th.dp(10), th.dp(14), th.dp(10));
            cont.setBackground(th.frosted(th.dp(16)));
            th.click(cont, v -> BooksActivity.open(a, b));
            head.addView(cont);
        }
        box.addView(head);
        box.addView(widgets.view(), th.fullWidth(10));
        return box;
    }

    private View icon(Item it, int size, int labelColor) {
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        View chip;
        if (it.open instanceof List) {
            chip = folderIcon(it, size);
        } else {
            TextView c = th.iconChip(it.symbol, it.color, size);
            c.setElevation(th.dp(3));
            chip = c;
        }
        cell.addView(chip);
        TextView name = th.text(it.name, 12.5f, labelColor, true);
        name.setShadowLayer(th.dp(3), 0, th.dp(1), labelColor == th.onWall ? th.wallShadow : 0);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        cell.addView(name, th.fullWidth(6));
        th.click(cell, v -> bounce(chip, () -> open(it, v)));
        return cell;
    }

    /** A folder: a frosted square showing its first nine apps small. */
    @SuppressWarnings("unchecked")
    private View folderIcon(Item it, int size) {
        android.widget.GridLayout g = new android.widget.GridLayout(a);
        g.setColumnCount(3);
        int pad = th.dp(size * 0.12f);
        g.setPadding(pad, pad, pad, pad);
        g.setBackground(th.frosted(th.dp(size * 0.24f)));
        List<Item> inside = (List<Item>) it.open;
        int mini = (size - 2 * (size * 12 / 100) - 6) / 3;
        for (int i = 0; i < Math.min(9, inside.size()); i++) {
            TextView m = th.iconChip(inside.get(i).symbol, inside.get(i).color, Math.max(10, mini));
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = th.dp(Math.max(10, mini));
            lp.height = th.dp(Math.max(10, mini));
            lp.setMargins(th.dp(1), th.dp(1), th.dp(1), th.dp(1));
            g.addView(m, lp);
        }
        g.setLayoutParams(new LinearLayout.LayoutParams(th.dp(size), th.dp(size)));
        return g;
    }

    private View dock() {
        LinearLayout d = new LinearLayout(a);
        d.setGravity(Gravity.CENTER_VERTICAL);
        d.setBackground(th.frosted(th.dp(28)));
        int p = th.dp(10);
        d.setPadding(p, p, p, p);
        for (Item it : dockApps()) {
            TextView chip = th.iconChip(it.symbol, it.color, 56);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
            lp.setMargins(th.dp(6), 0, th.dp(6), 0);
            th.click(chip, v -> bounce(chip, () -> open(it, v)));
            d.addView(chip);
        }
        View line = new View(a);
        line.setBackgroundColor(th.dark ? 0x40FFFFFF : 0x33000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, th.dp(1)), th.dp(44));
        lp.setMargins(th.dp(8), 0, th.dp(8), 0);
        d.addView(line, lp);
        TextView search = th.iconChip("🔍", Theme.GRAY, 56);
        th.click(search, v -> a.startActivity(new Intent(a, SpotlightActivity.class)));
        d.addView(search);
        // Handoff, as on an iPad: the page open in Chrome on the laptop, at the end of the Dock
        handoff = th.iconChip("🌐", Theme.BLUE, 56);
        LinearLayout.LayoutParams hp = (LinearLayout.LayoutParams) handoff.getLayoutParams();
        hp.setMargins(th.dp(12), 0, 0, 0);
        th.click(handoff, v -> bounce(handoff, () -> Devices.openHandoff(a)));
        handoff.setOnLongClickListener(v -> {
            org.json.JSONObject t = Devices.handoff();
            if (t != null) android.widget.Toast.makeText(a, "💻 " + t.optString("title", t.optString("url")),
                    android.widget.Toast.LENGTH_LONG).show();
            return true;
        });
        d.addView(handoff);
        paintHandoff();
        return d;
    }

    private TextView handoff;

    /** Shows the Handoff icon only while the laptop has a page open (from the last 30 minutes). */
    private void paintHandoff() {
        if (handoff == null) return;
        org.json.JSONObject t = Devices.handoff();
        handoff.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t != null) handoff.setContentDescription("Continue from the laptop: " + t.optString("title"));
    }

    private void paintDots(int page) {
        dots.removeAllViews();
        if (pager.count < 2) return;
        for (int i = 0; i < pager.count; i++) {
            View dot = new View(a);
            dot.setBackground(Theme.round(i == page ? th.onWall : (th.onWall & 0x00FFFFFF) | 0x66000000, th.dp(4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(th.dp(7), th.dp(7));
            lp.setMargins(th.dp(4), 0, th.dp(4), 0);
            dots.addView(dot, lp);
        }
    }

    private void bounce(View v, Runnable then) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).withEndAction(then).start()).start();
    }

    // ---- folders, Control Center, Notification Center: panels over the home screen

    private FrameLayout dim() {
        closeOverlay();
        FrameLayout shade = new FrameLayout(a);
        shade.setBackgroundColor(0x66000000);
        shade.setOnClickListener(v -> closeOverlay());
        shade.setAlpha(0f);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        shade.animate().alpha(1f).setDuration(160).start();
        overlay = shade;
        return shade;
    }

    void closeOverlay() {
        if (overlay == null) return;
        View o = overlay;
        overlay = null;
        host.controlCenterShown(false);
        o.animate().alpha(0f).setDuration(140).withEndAction(() -> root.removeView(o)).start();
    }

    private LinearLayout panel(FrameLayout shade, int widthDp, int gravity) {
        LinearLayout p = new LinearLayout(a);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(Theme.round(th.dark ? 0xF21C1C1E : 0xF2F2F2F7, th.dp(26)));
        p.setClickable(true);  // taps inside don't close it
        int pad = th.dp(18);
        p.setPadding(pad, pad, pad, pad);
        int w = Math.min(th.dp(widthDp), a.getResources().getDisplayMetrics().widthPixels - th.dp(24));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2, gravity);
        lp.setMargins(th.dp(12), th.dp(12), th.dp(12), th.dp(12));
        shade.addView(p, lp);
        p.setTranslationY(-th.dp(30));
        p.animate().translationY(0).setDuration(180).start();
        return p;
    }

    private void folder(String name, List<Item> items) {
        FrameLayout shade = dim();
        LinearLayout p = panel(shade, 560, Gravity.CENTER);
        TextView title = th.text(name, 22, th.text, true);
        title.setGravity(Gravity.CENTER);
        p.addView(title);
        int cols = 4;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(a);
                p.addView(row, th.fullWidth(i == 0 ? 14 : 16));
            }
            Item it = items.get(i);
            View v = icon(it, 58, th.text);
            row.addView(v, th.weighted(0));
        }
        int rest = items.size() % cols;
        for (int i = rest; rest != 0 && i < cols; i++) row.addView(new View(a), th.weighted(0));
    }

    void controlCenter() {
        FrameLayout shade = dim();
        LinearLayout p = panel(shade, 440, Gravity.TOP | Gravity.END);
        LinearLayout quick = new LinearLayout(a);
        Object[][] buttons = {
                {"🔗", "Devices", (Runnable) () -> a.startActivity(new Intent(a, DevicesActivity.class))},
                {"🔓", "Unlock PC", (Runnable) () -> a.startActivity(new Intent(a, DevicesActivity.class).putExtra("pc_actions", true))},
                {"⏱", "Focus", (Runnable) () -> a.startActivity(new Intent(a, FocusActivity.class))},
                {"🕰", "StandBy", (Runnable) () -> a.startActivity(new Intent(a, StandByActivity.class))},
                {"⌨️", "Keyboard", (Runnable) () -> a.startActivity(new Intent(a, KeyboardActivity.class))}};
        for (Object[] b : buttons) {
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView c = th.text((String) b[0], 22, th.text, false);
            c.setGravity(Gravity.CENTER);
            c.setBackground(th.mono ? th.surface(th.card, th.dp(26)) : Theme.round(th.dark ? 0xFF3A3A3C : 0xFFFFFFFF, th.dp(26)));
            cell.addView(c, new LinearLayout.LayoutParams(th.dp(52), th.dp(52)));
            TextView l = th.caption((String) b[1]);
            l.setGravity(Gravity.CENTER);
            cell.addView(l, th.fullWidth(4));
            Runnable r = (Runnable) b[2];
            th.click(cell, v -> {
                closeOverlay();
                r.run();
            });
            quick.addView(cell, th.weighted(0));
        }
        p.addView(quick);
        View cc = host.controlCenter();
        if (cc.getParent() != null) ((ViewGroup) cc.getParent()).removeView(cc);
        p.addView(cc, th.fullWidth(14));
        host.controlCenterShown(true);
    }

    void notificationCenter() {
        FrameLayout shade = dim();
        LinearLayout p = panel(shade, 520, Gravity.TOP | Gravity.START);
        Date now = new Date();
        TextView time = th.text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), 44, th.text, false);
        p.addView(time);
        p.addView(th.caption(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now)));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        p.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(th.dp(520),
                a.getResources().getDisplayMetrics().heightPixels - th.dp(220))));

        // this tablet's own notifications (when Fedora Pad may read them), then what the devices sent
        for (StatusBarNotification s : NotifyListener.active()) {
            Notification n = s.getNotification();
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) continue;
            list.addView(notice("🔔", appName(s.getPackageName()) + (title == null ? "" : " · " + title),
                    text == null ? "" : text.toString(), s.getPostTime(), n.contentIntent == null ? null : () -> {
                        try {
                            n.contentIntent.send();
                        } catch (android.app.PendingIntent.CanceledException ignored) {
                        }
                    }), th.fullWidth(10));
        }
        for (Devices.Event e : Devices.history()) {
            list.addView(notice(e.icon, e.title, e.text, e.time, null), th.fullWidth(10));
        }
        if (list.getChildCount() == 0) {
            TextView none = th.caption("No notifications.\nTurn on Devices › Show notifications to see this tablet's here too.");
            none.setPadding(0, th.dp(20), 0, th.dp(20));
            list.addView(none);
        }
        TextView clear = th.secondaryButton("Clear", v -> {
            Devices.clearHistory();
            closeOverlay();
        });
        p.addView(clear, th.fullWidth(12));
    }

    private String appName(String pkg) {
        try {
            return a.getPackageManager().getApplicationLabel(a.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private View notice(String icon, String title, String text, long time, Runnable tap) {
        LinearLayout c = new LinearLayout(a);
        c.setBackground(th.mono ? th.surface(th.card, th.dp(18)) : Theme.round(th.dark ? 0xFF2C2C2E : 0xFFFFFFFF, th.dp(18)));
        c.setPadding(th.dp(14), th.dp(12), th.dp(14), th.dp(12));
        c.addView(th.text(icon, 22, th.text, false));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(th.dp(12), 0, 0, 0);
        TextView t = th.text(title, 15, th.text, true);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        texts.addView(t);
        if (!text.isEmpty()) {
            TextView b = th.text(text, 14, th.secondary, false);
            b.setMaxLines(3);
            b.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            texts.addView(b);
        }
        c.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(th.caption(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(time))));
        if (tap != null) th.click(c, v -> {
            closeOverlay();
            tap.run();
        });
        return c;
    }

    // ---- swipes: pages sideways; down from the top right / left / middle

    /** Swipe sideways between pages, one page at a time. */
    private final class Pager extends HorizontalScrollView {
        int count = 1, page;
        java.util.function.IntConsumer onPage;
        private boolean flung;

        Pager(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        @Override
        public void fling(int vx) {
            flung = true;
            go(Math.abs(vx) < th.dp(300) ? Math.round(getScrollX() / (float) Math.max(1, getWidth()))
                    : vx > 0 ? page + 1 : page - 1);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) flung = false;
            boolean r = super.onTouchEvent(e);
            if ((e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) && !flung) {
                go(Math.round(getScrollX() / (float) Math.max(1, getWidth())));
            }
            return r;
        }

        void go(int p) {
            page = Math.max(0, Math.min(count - 1, p));
            smoothScrollTo(page * getWidth(), 0);
            if (onPage != null) onPage.accept(page);
        }
    }

    /** Takes over a downward swipe before the icons see it. */
    private final class Gestures extends FrameLayout {
        private float x0, y0;
        private boolean done;

        Gestures(Context c) {
            super(c);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (overlay != null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getX();
                    y0 = e.getY();
                    done = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getY() - y0, dx = e.getX() - x0;
                    if (!done && dy > th.dp(70) && dy > 2 * Math.abs(dx)) {
                        done = true;
                        boolean top = y0 < th.dp(90);
                        if (top && x0 > getWidth() * 0.6f) controlCenter();
                        else if (top && x0 < getWidth() * 0.4f) notificationCenter();
                        else if (y0 < getHeight() - th.dp(150)) a.startActivity(new Intent(a, SpotlightActivity.class));
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return true;
        }
    }
}

package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-page keyboard for Fedora, with a shortcut strip and the tablet's own keyboard for Persian. */
public class KeyboardActivity extends Page implements TrackpadView.TypingListener {
    private TrackpadView imeTarget;
    private boolean imeShown;

    @Override
    protected String title() {
        return "Keyboard";
    }

    @Override
    protected boolean fullScreen() {
        return true;
    }

    @Override
    protected void build() {
        content.addView(shortcutStrip(th, new String[][]{
                {"Copy", "ctrl+c"}, {"Paste", "ctrl+v"}, {"Cut", "ctrl+x"}, {"Undo", "ctrl+z"},
                {"Redo", "ctrl+shift+z"}, {"Select all", "ctrl+a"}, {"Save", "ctrl+s"}, {"Find", "ctrl+f"},
                {"Switch app", "alt+tab"}, {"Close", "ctrl+w"}}));

        TextView hint = th.caption("Ctrl, Alt and ⌘ stay pressed for the next key. Double-tap ⇧ for caps lock. "
                + "🌐 opens the tablet keyboard (for Persian and other languages).");
        content.addView(hint, th.fullWidth(14));

        // the tablet's own keyboard types through this invisible view
        imeTarget = new TrackpadView(this);
        imeTarget.setTypingListener(this);
        content.addView(imeTarget, new LinearLayout.LayoutParams(1, 1));

        View space = new View(this);
        content.addView(space, new LinearLayout.LayoutParams(1, 0, 1f));

        KeyboardView kb = new KeyboardView(th, 62);
        kb.setSystemKeyboard(this::toggleIme);
        LinearLayout tray = th.card();
        tray.setPadding(th.dp(6), th.dp(8), th.dp(6), th.dp(8));
        tray.setBackground(Theme.round(th.dark ? 0xFF1C1C1E : 0xFFD1D3D9, th.dp(18)));
        tray.addView(kb);
        content.addView(tray, th.fullWidth(12));
    }

    /** A sideways-scrolling row of shortcut buttons. */
    static View shortcutStrip(Theme th, String[][] items) {
        LinearLayout row = new LinearLayout(th.ctx);
        for (String[] it : items) {
            TextView b = th.secondaryButton(it[0], v -> HostLink.get().combo(it[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, th.dp(44));
            lp.rightMargin = th.dp(8);
            row.addView(b, lp);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(th.ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private void toggleIme() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imeShown = !imeShown;
        if (imeShown) {
            imeTarget.requestFocus();
            imm.showSoftInput(imeTarget, 0);
        } else {
            imm.hideSoftInputFromWindow(imeTarget.getWindowToken(), 0);
        }
    }

    @Override
    public void onTyped(String text) {
        link.text(text);
    }

    @Override
    public void onKey(String key) {
        link.combo(key);
    }
}

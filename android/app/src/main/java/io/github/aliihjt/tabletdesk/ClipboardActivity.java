package io.github.aliihjt.tabletdesk;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Write on the tablet, type it on Fedora in one go. Uses the clipboard, so any
 * language (Persian, emoji...) arrives exactly as written.
 */
public class ClipboardActivity extends Page {
    private EditText editor;

    @Override
    protected String title() {
        return "Text & Clipboard";
    }

    @Override
    protected void build() {
        LinearLayout card = th.card();
        int p = th.dp(14);
        card.setPadding(p, p, p, p);
        editor = new EditText(this);
        editor.setHint("Write here — any language");
        editor.setHintTextColor(th.secondary);
        editor.setTextColor(th.text);
        editor.setTextSize(18 * Ui.scale);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(6);
        editor.setBackground(null);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        card.addView(editor);
        add(card, 6);

        LinearLayout r1 = new LinearLayout(this);
        r1.addView(th.primaryButton("Type on Fedora", v -> typeOnPc(false)), th.weighted(0));
        r1.addView(th.secondaryButton("In a terminal", v -> typeOnPc(true)), th.weighted(10));
        add(r1, 12);
        add(th.caption("Click where you want the text on Fedora first. “In a terminal” pastes with Ctrl+Shift+V."), 8);

        section("Clipboard");
        add(th.group(
                action("📱", Theme.BLUE, "Send tablet clipboard to Fedora",
                        "Then paste on Fedora with Ctrl+V", this::sendClipboard),
                action("💻", Theme.GREEN, "Get Fedora's clipboard",
                        "Puts it here and on the tablet clipboard", this::getClipboard),
                action("🧹", Theme.GRAY, "Clear the text", null, () -> editor.setText(""))));
    }

    private LinearLayout action(String sym, int color, String title, String sub, Runnable run) {
        LinearLayout r = th.row(sym, color, title, sub, th.chevron());
        th.click(r, v -> run.run());
        return r;
    }

    private void typeOnPc(boolean terminal) {
        String text = editor.getText().toString();
        if (text.isEmpty()) {
            toast("Write something first");
            return;
        }
        link.setClip(text);
        // give Fedora a moment to take the clipboard, then paste
        editor.postDelayed(() -> link.combo(terminal ? "ctrl+shift+v" : "ctrl+v"), 250);
        toast("Typed on Fedora");
    }

    private void sendClipboard() {
        ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("The tablet clipboard is empty");
            return;
        }
        link.setClip(clip.getItemAt(0).coerceToText(this).toString());
        toast("Sent — paste on Fedora with Ctrl+V");
    }

    private void getClipboard() {
        link.requestClip(text -> {
            editor.setText(text);
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Fedora", text));
            toast("Fedora's clipboard is here");
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

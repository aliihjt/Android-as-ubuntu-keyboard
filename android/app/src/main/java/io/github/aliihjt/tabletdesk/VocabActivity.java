package io.github.aliihjt.tabletdesk;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Words: the words you saved while reading, with their sentence; quiz yourself with Claude. */
public class VocabActivity extends Page {
    private LinearLayout list;
    private String filter = "";

    @Override
    protected String title() {
        return "Words";
    }

    @Override
    protected void build() {
        LinearLayout top = new LinearLayout(this);
        EditText search = new EditText(this);
        search.setHint("Search words");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
        });
        top.addView(search, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(th.primaryButton("✨ Quiz me", v -> quiz()));
        add(top, 6);
        add(th.caption("In a book, double-tap a word, then “Save to Words”."), 6);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        add(list, 14);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        List<ReadingData.Word> words = ReadingData.words(this);
        int shown = 0;
        for (ReadingData.Word w : words) {
            if (!filter.isEmpty() && !w.word.toLowerCase(Locale.ROOT).contains(filter)) continue;
            String sub = w.sentence.isEmpty() ? w.book : "“" + w.sentence + "”" + (w.book.isEmpty() ? "" : " — " + w.book);
            LinearLayout r = th.row(null, 0, w.word, sub, th.chevron());
            ((TextView) ((LinearLayout) r.getChildAt(0)).getChildAt(0)).setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            th.click(r, v -> actions(w));
            list.addView(th.group(r), th.fullWidth(shown == 0 ? 0 : 8));
            shown++;
        }
        if (words.isEmpty()) list.addView(th.caption("No words yet."));
        else if (shown == 0) list.addView(th.caption("No word matches “" + filter + "”."));
    }

    private void actions(ReadingData.Word w) {
        new AlertDialog.Builder(this).setTitle(w.word)
                .setItems(new String[]{"🔎 Look it up", "🗑 Remove"}, (d, i) -> {
                    if (i == 0) WordLookup.show(this, w.word, w.sentence, w.book);
                    else {
                        ReadingData.removeWord(this, w.time);
                        refresh();
                    }
                }).show();
    }

    /** Sends the newest 30 words to Claude for a short quiz. */
    private void quiz() {
        List<ReadingData.Word> words = ReadingData.words(this);
        if (words.isEmpty()) {
            android.widget.Toast.makeText(this, "Save some words first.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder q = new StringBuilder("Quiz me on these words I met while reading. Ask one question at a time "
                + "(meaning, Persian ↔ English translation, or use it in a sentence), wait for my answer, then tell me "
                + "if I was right. Words:\n");
        for (int i = 0; i < words.size() && i < 30; i++) {
            ReadingData.Word w = words.get(i);
            q.append("- ").append(w.word);
            if (!w.sentence.isEmpty()) q.append(" (in: \"").append(w.sentence).append("\")");
            q.append('\n');
        }
        ClaudeApp.ask(this, q.toString(), null);
    }
}

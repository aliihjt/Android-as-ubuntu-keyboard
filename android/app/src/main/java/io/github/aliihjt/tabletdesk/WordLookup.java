package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * A word you tapped: translate or look it up with the apps on the tablet
 * (Google Translate, a dictionary…), Wiktionary, Claude, or keep it in Words.
 */
final class WordLookup {
    private WordLookup() {
    }

    static void show(Activity a, String word, String sentence, String book) {
        word = word.trim().replaceAll("^[\\p{Punct}«»“”‘’]+|[\\p{Punct}«»“”‘’،؛؟]+$", "");
        if (word.isEmpty()) return;
        String w = word;
        boolean fa = ReadingData.persian(w);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        // apps that offer "Translate" / "Define" in Android's text menu
        Intent process = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, w).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        for (ResolveInfo r : a.getPackageManager().queryIntentActivities(process, 0)) {
            if (r.activityInfo.packageName.equals(a.getPackageName())) continue;
            String name = String.valueOf(r.loadLabel(a.getPackageManager()));
            labels.add("🌐 " + name);
            actions.add(() -> a.startActivity(new Intent(process)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name)));
        }
        labels.add("✨ Ask Claude: meaning " + (fa ? "in English" : "in Persian"));
        actions.add(() -> ClaudeApp.ask(a, fa
                ? "What does the Persian word «" + w + "» mean? Give the English meaning, a short explanation and an example."
                + (sentence.isEmpty() ? "" : "\n\nIt appears here: " + sentence)
                : "Explain the word \"" + w + "\" simply, give its Persian (فارسی) translation and one example sentence."
                + (sentence.isEmpty() ? "" : "\n\nIt appears here: " + sentence), null));
        labels.add("📖 Wiktionary");
        actions.add(() -> a.startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://" + (fa ? "fa" : "en") + ".wiktionary.org/wiki/" + Uri.encode(w)))));
        labels.add("⭐ Save to Words");
        actions.add(() -> {
            boolean added = ReadingData.addWord(a, w, sentence, book);
            Toast.makeText(a, added ? "Saved in Words" : "Already in Words", Toast.LENGTH_SHORT).show();
        });
        labels.add("📋 Copy");
        actions.add(() -> {
            android.content.ClipboardManager cm = a.getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("word", w));
            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
        });
        new AlertDialog.Builder(a).setTitle(w)
                .setItems(labels.toArray(new String[0]), (d, i) -> actions.get(i).run())
                .show();
    }
}

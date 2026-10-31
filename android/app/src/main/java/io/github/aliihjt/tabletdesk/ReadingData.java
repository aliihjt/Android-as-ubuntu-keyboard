package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the reader remembers besides the page: minutes read per day (goal and
 * streak), highlights with notes, and saved words.
 */
final class ReadingData {
    private static final String MINUTES = "read_minutes";   // {"2026-10-31": seconds}
    private static final String GOAL = "read_goal";         // minutes a day
    private static final String HIGHLIGHTS = "highlights";
    private static final String WORDS = "vocab";
    static final String[] COLORS = {"yellow", "green", "blue", "pink"};
    static final int[] PAINT = {0xFFFFE066, 0xFF9BE58F, 0xFF8FC8FF, 0xFFFFA3C2};

    private ReadingData() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    private static String day(Calendar cal) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private static JSONObject obj(Context c, String key) {
        try {
            return new JSONObject(prefs(c).getString(key, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONArray arr(Context c, String key) {
        try {
            return new JSONArray(prefs(c).getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    // ---- time and goal ----

    /** Adds reading time to today (ignores silly amounts: a reader left open overnight). */
    static void addSeconds(Context c, long seconds) {
        if (seconds < 5) return;
        seconds = Math.min(seconds, 2 * 60 * 60);
        JSONObject all = obj(c, MINUTES);
        String today = day(Calendar.getInstance());
        try {
            all.put(today, all.optLong(today) + seconds);
            // keep a year
            Calendar old = Calendar.getInstance();
            old.add(Calendar.DAY_OF_YEAR, -370);
            String cut = day(old);
            List<String> drop = new ArrayList<>();
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.compareTo(cut) < 0) drop.add(k);
            }
            for (String k : drop) all.remove(k);
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(MINUTES, all.toString()).apply();
    }

    static int goal(Context c) {
        return prefs(c).getInt(GOAL, 20);
    }

    static void setGoal(Context c, int minutes) {
        prefs(c).edit().putInt(GOAL, Math.max(5, Math.min(240, minutes))).apply();
    }

    /** Minutes read on a day, 0 = today, 1 = yesterday… */
    static int minutes(Context c, int daysAgo) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return (int) (obj(c, MINUTES).optLong(day(cal)) / 60);
    }

    /** Days in a row the goal was met, counting today only once it is met. */
    static int streak(Context c) {
        int goal = goal(c), n = 0;
        for (int d = minutes(c, 0) >= goal ? 0 : 1; d < 370 && minutes(c, d) >= goal; d++) n++;
        return n;
    }

    /** The last 7 days, oldest first. */
    static int[] week(Context c) {
        int[] w = new int[7];
        for (int i = 0; i < 7; i++) w[i] = minutes(c, 6 - i);
        return w;
    }

    static String weekdayLetter(int daysAgo) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return new SimpleDateFormat("EEEEE", Locale.getDefault()).format(cal.getTime());
    }

    // ---- highlights ----

    static final class Highlight {
        final String book, title, text, color, note;
        final int part;
        final long time;

        Highlight(JSONObject o) {
            book = o.optString("book");
            title = o.optString("title");
            text = o.optString("text");
            color = o.optString("color", "yellow");
            note = o.optString("note");
            part = o.optInt("part");
            time = o.optLong("time");
        }
    }

    static void addHighlight(Context c, String book, String title, int part, String text, String color, String note) {
        JSONArray all = arr(c, HIGHLIGHTS);
        try {
            all.put(new JSONObject().put("book", book).put("title", title).put("part", part).put("text", text)
                    .put("color", color).put("note", note == null ? "" : note).put("time", System.currentTimeMillis()));
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(HIGHLIGHTS, all.toString()).apply();
    }

    static void removeHighlight(Context c, long time) {
        JSONArray all = arr(c, HIGHLIGHTS), kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optLong("time") != time) kept.put(o);
        }
        prefs(c).edit().putString(HIGHLIGHTS, kept.toString()).apply();
    }

    /** All highlights (book = null) or one book's, oldest first. */
    static List<Highlight> highlights(Context c, String book) {
        List<Highlight> out = new ArrayList<>();
        JSONArray all = arr(c, HIGHLIGHTS);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && (book == null || book.equals(o.optString("book")))) out.add(new Highlight(o));
        }
        return out;
    }

    static int paint(String color) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(color)) return PAINT[i];
        return PAINT[0];
    }

    /** One book's highlights and notes as Markdown, ready for Notes, Obsidian or the PC. */
    static String markdown(Context c, String book, String title) {
        StringBuilder md = new StringBuilder("# ").append(title).append("\n\n");
        String date = new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date());
        md.append("_Highlights from Fedora Pad, ").append(date).append("_\n\n");
        int part = -1;
        for (Highlight h : highlights(c, book)) {
            if (h.part != part) {
                part = h.part;
                md.append("## Part ").append(part + 1).append("\n\n");
            }
            md.append("> ").append(h.text.replace("\n", "\n> ")).append("\n");
            if (!h.note.isEmpty()) md.append("\n").append(h.note).append("\n");
            md.append("\n");
        }
        return md.toString();
    }

    // ---- saved words ----

    static final class Word {
        final String word, sentence, book;
        final long time;

        Word(JSONObject o) {
            word = o.optString("word");
            sentence = o.optString("sentence");
            book = o.optString("book");
            time = o.optLong("time");
        }
    }

    static boolean addWord(Context c, String word, String sentence, String book) {
        JSONArray all = arr(c, WORDS);
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optString("word").equalsIgnoreCase(word)) return false;
        }
        try {
            all.put(new JSONObject().put("word", word).put("sentence", sentence == null ? "" : sentence)
                    .put("book", book == null ? "" : book).put("time", System.currentTimeMillis()));
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(WORDS, all.toString()).apply();
        return true;
    }

    static void removeWord(Context c, long time) {
        JSONArray all = arr(c, WORDS), kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optLong("time") != time) kept.put(o);
        }
        prefs(c).edit().putString(WORDS, kept.toString()).apply();
    }

    /** Newest first. */
    static List<Word> words(Context c) {
        List<Word> out = new ArrayList<>();
        JSONArray all = arr(c, WORDS);
        for (int i = all.length() - 1; i >= 0; i--) {
            JSONObject o = all.optJSONObject(i);
            if (o != null) out.add(new Word(o));
        }
        return out;
    }

    /** True when the text is mostly Persian / Arabic script. */
    static boolean persian(String s) {
        int fa = 0, latin = 0;
        for (int i = 0; i < s.length() && i < 2000; i++) {
            char ch = s.charAt(i);
            if (ch >= 0x0600 && ch <= 0x06FF || ch >= 0xFB50 && ch <= 0xFEFF) fa++;
            else if (Character.isLetter(ch)) latin++;
        }
        return fa > latin;
    }
}

package io.github.aliihjt.tabletdesk;

import android.app.Activity;
import android.content.Intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every Fedora Pad tool, once: its symbol, colour and name are the same on the
 * iPad home, the Mac desktop, the Reader home and in Search. Screens ask for
 * tools by id instead of keeping their own lists.
 */
final class Tools {
    static final int CLAUDE = 0xFFD97757;
    static final int GRAPHITE = 0xFF3A3A3C;

    static final class Tool {
        final String id, symbol, name, words;
        final int color;
        final Class<?> page;

        Tool(String id, String symbol, int color, String name, Class<?> page, String words) {
            this.id = id;
            this.symbol = symbol;
            this.color = color;
            this.name = name;
            this.page = page;
            this.words = words;
        }
    }

    private static final Map<String, Tool> ALL = new LinkedHashMap<>();

    private static void add(String id, String symbol, int color, String name, Class<?> page, String words) {
        ALL.put(id, new Tool(id, symbol, color, name, page, words));
    }

    static {
        // controlling the laptop
        add("keyboard", "⌨️", Theme.BLUE, "Keyboard", KeyboardActivity.class, "type keys Persian فا");
        add("trackpad", "🖱", Theme.PURPLE, "Trackpad", TrackpadActivity.class, "mouse touchpad");
        add("mirror", "🖥", Theme.INDIGO, "Show on PC", MirrorActivity.class, "mirror screen scrcpy AirPlay");
        add("ptt", "🎙", Theme.RED, "Push to Talk", PushToTalkActivity.class, "voice typing speak dictate");
        add("media", "🎵", Theme.PINK, "Media", MediaActivity.class, "music play pause volume");
        add("controls", "🎛", Theme.ORANGE, "Controls", ControlsActivity.class, "brightness volume dark mode Wi-Fi Bluetooth shut down");
        add("shortcuts", "⚡", Theme.INDIGO, "Shortcuts", ShortcutsActivity.class, "copy paste undo screenshot");
        add("apps", "🚀", Theme.TEAL, "PC Apps", AppsActivity.class, "open apps Fedora Windows launch");
        add("windows", "🗂", Theme.INDIGO, "Windows", WindowsActivity.class, "switch close open windows");
        add("text", "📝", Theme.YELLOW, "Type Text", ClipboardActivity.class, "Text & Clipboard write type send text");
        add("files", "📁", Theme.BLUE, "Send Files", FilesActivity.class, "photos AirDrop send to PC");
        add("cliphistory", "📋", Theme.TEAL, "Clipboard", ClipHistoryActivity.class, "Clipboard History copied paste");
        add("presenter", "🎤", Theme.RED, "Presenter", PresenterActivity.class, "slides remote PowerPoint");
        add("streamdeck", "🎚", Theme.ORANGE, "Stream Deck", ButtonsActivity.class, "buttons macros");
        add("controller", "🎮", Theme.GREEN, "Controller", ControllerActivity.class, "gamepad PS4 Xbox Steam games");
        add("webcam", "📷", Theme.RED, "Webcam", WebcamActivity.class, "camera microphone Zoom Meet");
        add("wake", "⏰", Theme.INDIGO, "Wake & Sleep", WakeActivity.class, "sleep timer wake PC");
        add("stats", "📊", Theme.GREEN, "Stats", StatsActivity.class, "CPU memory battery temperature");
        add("terminal", ">_", GRAPHITE, "Terminal", TerminalActivity.class, "Terminal Pad commands");
        add("ambient", "🌈", Theme.PINK, "Ambient Light", AmbientActivity.class, "glow colours bias light");
        // all the devices
        add("devices", "🔗", Theme.GREEN, "Devices", DevicesActivity.class, "iPad iPhone ring send clipboard unlock");
        // reading
        add("books", "📚", Theme.ORANGE, "Books", BooksActivity.class, "library read PDF EPUB");
        add("getbooks", "⬇️", Theme.GREEN, "Get Books", GetBooksActivity.class, "free classics Persian poetry Ganjoor Gutenberg");
        add("highlights", "🖍", Theme.YELLOW, "Highlights", HighlightsActivity.class, "marked quotes");
        add("words", "⭐", Theme.INDIGO, "Words", VocabActivity.class, "vocabulary quiz");
        add("faal", "🎲", Theme.PINK, "فال حافظ", PoemActivity.class, "Hafez poem fal faal");
        // the tablet's own tools
        add("notes", "🗒", Theme.YELLOW, "Notes", NotesActivity.class, "sticky notes");
        add("sketch", "✍️", Theme.BLUE, "Handwriting", SketchActivity.class, "draw pen stylus");
        add("scanner", "📄", Theme.BLUE, "Scanner", ScanActivity.class, "scan page PDF camera");
        add("focus", "⏱", Theme.RED, "Focus", FocusActivity.class, "timer do not disturb");
        add("standby", "🕰", Theme.ORANGE, "StandBy", StandByActivity.class, "clock night");
        add("claude", "✨", CLAUDE, "Claude", null, "AI ask");
        add("search", "🔍", Theme.GRAY, "Search", SpotlightActivity.class, "Spotlight find");
        add("applib", "▦", Theme.TEAL, "App Library", TabletAppsActivity.class, "Tablet Apps all apps");
        add("settings", "⚙️", Theme.GRAY, "Settings", SettingsActivity.class, "options home screen update");
    }

    private Tools() {
    }

    static Tool get(String id) {
        Tool t = ALL.get(id);
        if (t == null) throw new IllegalArgumentException("no tool " + id);
        return t;
    }

    static List<Tool> all() {
        return new ArrayList<>(ALL.values());
    }

    static void open(Activity a, Tool t) {
        if ("claude".equals(t.id)) ClaudeApp.open(a);
        else if ("faal".equals(t.id)) a.startActivity(new Intent(a, PoemActivity.class).putExtra("mode", "faal"));
        else a.startActivity(new Intent(a, t.page));
    }

    static Runnable opener(Activity a, String id) {
        Tool t = get(id);
        return () -> open(a, t);
    }

    /** {symbol, colour, name, Runnable}: the shape the home screens' grids take. */
    static Object[] row(Activity a, String id) {
        Tool t = get(id);
        return new Object[]{t.symbol, t.color, t.name, (Runnable) () -> open(a, t)};
    }

    static Object[][] rows(Activity a, String... ids) {
        Object[][] out = new Object[ids.length][];
        for (int i = 0; i < ids.length; i++) out[i] = row(a, ids[i]);
        return out;
    }
}

package io.github.aliihjt.tabletdesk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Push to talk: hold the mic, speak, let go, and the words are typed where the
 * cursor is on Fedora. Two microphones:
 *  - tablet: Android's speech recognition, shows the words live (needs Google
 *    speech services; Persian works too)
 *  - laptop: Fedora's offline Whisper (host/voice-setup.sh), nothing goes online
 */
final class PushToTalk {
    interface Listener {
        /** status line, e.g. "Listening…"; text so far (may be empty) */
        void onUpdate(String status, String text);
    }

    static final String SOURCE = "ptt_source", LANG = "ptt_lang";
    private static final String[] TERMINALS = {"terminal", "ptyxis", "console", "konsole", "kitty",
            "alacritty", "wezterm", "tilix"};

    private final Context ctx;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HostLink link = HostLink.get();
    private final List<String> heard = new ArrayList<>();
    private SpeechRecognizer recognizer;
    private String partial = "";
    private boolean holding, finished = true, laptop;

    PushToTalk(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        prefs = ctx.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
    }

    static boolean useLaptop(Context c) {
        return "laptop".equals(c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
                .getString(SOURCE, "tablet"));
    }

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // ---- hold / release ----

    void down() {
        if (holding) return;
        if (!link.isConnected()) {
            listener.onUpdate("Not connected to Fedora", "");
            return;
        }
        holding = true;
        finished = false;
        heard.clear();
        partial = "";
        laptop = useLaptop(ctx);
        if (laptop) {
            link.command("voicestart");
            listener.onUpdate("🎙 Listening with the laptop mic…", "");
            return;
        }
        if (!micAllowed(ctx)) {
            holding = false;
            finished = true;
            listener.onUpdate("Allow the microphone: open Fedora Pad › Push to Talk", "");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            holding = false;
            finished = true;
            listener.onUpdate("No speech recognition on this tablet. Choose “Laptop mic”.", "");
            return;
        }
        listen();
    }

    void up() {
        if (!holding) return;
        holding = false;
        if (laptop) {
            link.command("voicestop");
            finished = true;
            listener.onUpdate("✍️ Fedora is writing it…", "");
            return;
        }
        listener.onUpdate("✍️ Finishing…", text());
        if (recognizer != null) recognizer.stopListening();
        main.postDelayed(this::finish, 2500);  // in case the last result never comes
    }

    void release() {
        holding = false;
        main.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    // ---- tablet microphone ----

    private void listen() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new Callbacks());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        String lang = prefs.getString(LANG, "auto");
        if (!"auto".equals(lang)) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        }
        // keep listening through short pauses while the button is held
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        recognizer.startListening(i);
        listener.onUpdate("🎙 Listening…", text());
    }

    private String text() {
        StringBuilder b = new StringBuilder();
        for (String s : heard) b.append(b.length() == 0 ? "" : " ").append(s);
        if (!partial.isEmpty()) b.append(b.length() == 0 ? "" : " ").append(partial);
        return b.toString().trim();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        partial = partial.trim();
        if (!partial.isEmpty()) heard.add(partial);
        partial = "";
        String t = text();
        if (t.isEmpty()) {
            listener.onUpdate("Didn't hear any words", "");
            return;
        }
        type(t);
        listener.onUpdate("✅ Typed on Fedora", t);
    }

    /** Clipboard + paste, so every language works; terminals need Ctrl+Shift+V. */
    private void type(String t) {
        String app = link.app() == null ? "" : link.app().toLowerCase(Locale.ROOT);
        boolean terminal = false;
        for (String k : TERMINALS) terminal |= app.contains(k);
        String keys = terminal ? "ctrl+shift+v" : "ctrl+v";
        link.setClip(t + " ");
        main.postDelayed(() -> link.combo(keys), 250);
    }

    private final class Callbacks implements RecognitionListener {
        @Override
        public void onResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            partial = "";
            if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) heard.add(r.get(0).trim());
            if (holding) listen();  // still holding: carry on with the next sentence
            else finish();
        }

        @Override
        public void onPartialResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) {
                partial = r.get(0);
                listener.onUpdate(holding ? "🎙 Listening…" : "✍️ Finishing…", text());
            }
        }

        @Override
        public void onError(int error) {
            boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (holding && quiet) {
                listen();
                return;
            }
            if (!holding) {
                finish();
                return;
            }
            holding = false;
            finished = true;
            listener.onUpdate(error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    ? "Speech recognition needs the internet. Try “Laptop mic” (offline)."
                    : "Couldn't listen (error " + error + "). Try again.", text());
        }

        @Override
        public void onReadyForSpeech(Bundle b) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float v) {
        }

        @Override
        public void onBufferReceived(byte[] b) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onEvent(int t, Bundle b) {
        }
    }

    // ---- the round hold-to-talk button ----

    @SuppressLint("ClickableViewAccessibility")
    TextView button(Theme th, int sizeDp) {
        TextView mic = th.text("🎙", sizeDp * 0.4f, 0xFFFFFFFF, false);
        mic.setGravity(Gravity.CENTER);
        mic.setBackground(Theme.round(Theme.RED, th.dp(sizeDp / 2f)));
        mic.setElevation(th.dp(6));
        mic.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    Ui.haptic(v);
                    v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start();
                    v.setBackground(Theme.round(0xFFFF6961, th.dp(sizeDp / 2f)));
                    down();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Ui.haptic(v);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    v.setBackground(Theme.round(Theme.RED, th.dp(sizeDp / 2f)));
                    up();
                    return true;
                default:
                    return true;
            }
        });
        return mic;
    }
}

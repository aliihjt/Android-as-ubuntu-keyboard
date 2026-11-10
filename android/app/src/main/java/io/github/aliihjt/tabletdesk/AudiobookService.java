package io.github.aliihjt.tabletdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Audiobook mode: reads a book aloud in the background, screen off, part after
 * part, with play / pause / skip on the lock screen, headphones, and from the
 * iPhone (Devices › the tablet › its music). Remembers where it stopped.
 */
public class AudiobookService extends Service {
    private static final String CHANNEL = "audiobook";
    private static final int ID = 7341;
    static final String STOP = "stop", TOGGLE = "toggle", NEXT = "next", PREV = "prev";
    private static final float[] RATES = {0.8f, 1f, 1.25f, 1.5f, 2f};

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady, playing;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private PowerManager.WakeLock wake;
    private BookText book;
    private String uri, title, type;
    private int part, para, total;
    private List<String> paras = new ArrayList<>();
    private Thread loader;

    /** where: how far into the part to start, 0..1 */
    static void start(Context c, String uri, String title, String type, int part, float where) {
        Intent i = new Intent(c, AudiobookService.class).putExtra("uri", uri).putExtra("title", title)
                .putExtra("type", type).putExtra("part", part).putExtra("where", where);
        c.startForegroundService(i);
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FedoraPad:audiobook");
        session = new MediaSession(this, "Fedora Pad audiobook");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                jump(para + 1);
            }

            @Override
            public void onSkipToPrevious() {
                jump(para - 1);
            }

            @Override
            public void onStop() {
                stopSelf();
            }
        });
        session.setActive(true);
        tts = new TextToSpeech(this, status -> main.post(() -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) {
                Toast.makeText(this, "No text-to-speech engine installed.", Toast.LENGTH_LONG).show();
                stopSelf();
                return;
            }
            int r = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).getInt("tts_rate", 1);
            tts.setSpeechRate(RATES[Math.max(0, Math.min(RATES.length - 1, r))]);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    int k = Integer.parseInt(id.split(":")[0]);
                    main.post(() -> {
                        para = k;
                        if (k % 5 == 0) BooksActivity.savePosition(AudiobookService.this, uri, part, total, 0);
                    });
                }

                @Override
                public void onDone(String id) {
                    if (id.endsWith(":end")) main.post(AudiobookService.this::nextPart);
                }

                @Override
                public void onError(String id) {
                    onDone(id);
                }
            });
            if (!paras.isEmpty()) play();
        }));
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String action = i == null ? null : i.getAction();
        if (STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (TOGGLE.equals(action)) {
            if (playing) pause();
            else play();
            return START_NOT_STICKY;
        }
        if (NEXT.equals(action)) {
            jump(para + 1);
            return START_NOT_STICKY;
        }
        if (PREV.equals(action)) {
            jump(para - 1);
            return START_NOT_STICKY;
        }
        if (i != null && i.hasExtra("uri")) {
            uri = i.getStringExtra("uri");
            title = i.getStringExtra("title");
            type = i.getStringExtra("type");
            part = i.getIntExtra("part", 0);
            book = null;
            showNotification("Loading…");
            load(part, -1 - Math.round(1000 * i.getFloatExtra("where", 0f)));  // negative: a fraction, see load
        }
        return START_NOT_STICKY;
    }

    private void load(int p, int from) {
        if (tts != null) tts.stop();
        loader = new Thread(() -> {
            try {
                if (book == null) book = new BookText(this, uri, type);
                total = book.parts();
                int at = Math.max(0, Math.min(p, total - 1));
                List<String> list = book.paragraphs(at);
                main.post(() -> {
                    part = at;
                    paras = list;
                    int start = from < 0 ? Math.round((-1 - from) / 1000f * list.size()) : from;
                    para = Math.max(0, Math.min(start, Math.max(0, list.size() - 1)));
                    BooksActivity.savePosition(this, uri, part, total, 0);
                    if (list.isEmpty()) nextPart();
                    else if (ttsReady) play();
                });
            } catch (Exception e) {
                main.post(() -> {
                    Toast.makeText(this, "Can't read this book aloud: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    stopSelf();
                });
            }
        }, "audiobook-load");
        loader.start();
    }

    private void play() {
        if (!ttsReady || paras.isEmpty()) return;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
                    }).build();
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        playing = true;
        if (!wake.isHeld()) wake.acquire(4 * 60 * 60 * 1000L);
        StringBuilder sample = new StringBuilder();
        for (int k = para; k < paras.size() && sample.length() < 1500; k++) sample.append(paras.get(k));
        Locale lang = ReadingData.persian(sample.toString()) ? new Locale("fa", "IR")
                : "fa".equals(Locale.getDefault().getLanguage()) ? Locale.US : Locale.getDefault();
        tts.setLanguage(lang);
        int max = Math.max(500, TextToSpeech.getMaxSpeechInputLength() - 100);
        boolean first = true;
        for (int k = para; k < paras.size(); k++) {
            String t = paras.get(k);
            List<String> chunks = new ArrayList<>();
            while (t.length() > max) {
                int cut = Math.max(t.lastIndexOf(". ", max), t.lastIndexOf(' ', max));
                if (cut < max / 2) cut = max;
                chunks.add(t.substring(0, cut + 1));
                t = t.substring(cut + 1);
            }
            chunks.add(t);
            for (int j = 0; j < chunks.size(); j++) {
                boolean last = k == paras.size() - 1 && j == chunks.size() - 1;
                tts.speak(chunks.get(j), first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null,
                        k + ":" + j + (last ? ":end" : ""));
                first = false;
            }
        }
        update();
    }

    private void pause() {
        playing = false;
        if (tts != null) tts.stop();
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        if (wake.isHeld()) wake.release();
        BooksActivity.savePosition(this, uri, part, total, 0);
        update();
    }

    /** Paragraph back or forward; past the end or start of a part goes to the next or previous one. */
    private void jump(int to) {
        if (to < 0 && part > 0) {
            load(part - 1, Integer.MAX_VALUE);
            return;
        }
        if (to >= paras.size()) {
            nextPart();
            return;
        }
        para = Math.max(0, to);
        if (playing) play();
        else update();
    }

    private void nextPart() {
        if (part + 1 >= total) {
            Toast.makeText(this, "📕 The end of “" + title + "”", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        load(part + 1, 0);
    }

    // ---- lock screen, notification and media keys

    private void update() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, para, 1f).build());
        String where = (total > 1 ? "Part " + (part + 1) + " of " + total + " · " : "") + "paragraph " + (para + 1);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, where).build());
        showNotification(where);
    }

    private PendingIntent action(String a, int code) {
        return PendingIntent.getService(this, code, new Intent(this, AudiobookService.class).setAction(a),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void showNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Audiobook", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, ReaderActivity.class).putExtra("uri", uri).putExtra("title", title)
                .putExtra("type", type).putExtra("pos", part);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("🎧 " + title).setContentText(text)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "⏮", action(PREV, 1)).build())
                .addAction(new Notification.Action.Builder(null, playing ? "⏸" : "▶", action(TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(null, "⏭", action(NEXT, 3)).build())
                .addAction(new Notification.Action.Builder(null, "✕", action(STOP, 4)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .setOngoing(playing).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(ID, n);
    }

    @Override
    public void onDestroy() {
        if (uri != null) BooksActivity.savePosition(this, uri, part, total, 0);
        if (tts != null) tts.shutdown();
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        if (wake.isHeld()) wake.release();
        session.release();
        if (book != null) book.close();
        stopForeground(true);
        super.onDestroy();
    }
}

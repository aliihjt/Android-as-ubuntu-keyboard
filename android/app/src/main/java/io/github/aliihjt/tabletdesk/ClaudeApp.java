package io.github.aliihjt.tabletdesk;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.OutputStream;

/**
 * AI on the tablet is the official Claude app: Fedora Pad opens it, or hands it
 * a question (and a picture of a page or of your handwriting) through Android's
 * share sheet. The question is also copied, so it can be pasted if needed.
 */
final class ClaudeApp {
    static final String PKG = "com.anthropic.claude";
    private static final String LAST_PICTURE = "claude_picture";

    private ClaudeApp() {
    }

    static boolean installed(Context c) {
        return c.getPackageManager().getLaunchIntentForPackage(PKG) != null;
    }

    /** Opens Claude, or its Play Store page when it isn't installed yet. */
    static void open(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (i == null) {
            Toast.makeText(c, "Install the Claude app (free), then come back", Toast.LENGTH_LONG).show();
            MainActivity.openStore(c, PKG);
            return;
        }
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Sends a question to Claude, with an optional picture. */
    static void ask(Context c, String question, Bitmap picture) {
        if (!installed(c)) {
            open(c);
            return;
        }
        c.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Question", question));
        Uri image = picture == null ? null : savePicture(c, picture);
        Intent send = new Intent(Intent.ACTION_SEND).setPackage(PKG)
                .putExtra(Intent.EXTRA_TEXT, question)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (image != null) {
            send.setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, image)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("page", image));
        } else {
            send.setType("text/plain");
        }
        try {
            c.startActivity(send);
            Toast.makeText(c, "Sent to Claude. The question is also copied, in case you need to paste it.",
                    Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            // this Claude version takes no shares: open it, the question is on the clipboard
            open(c);
            Toast.makeText(c, "Question copied: paste it in Claude"
                    + (image != null ? ". The picture is in Gallery › Fedora Pad." : "."), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Android shares pictures by content link; without extra libraries the
     * simplest one is the Gallery. Only the newest question picture is kept.
     */
    private static Uri savePicture(Context c, Bitmap picture) {
        if (Build.VERSION.SDK_INT < 29) return null;  // older Android needs a storage permission
        SharedPreferences prefs = c.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(LAST_PICTURE, null);
        if (old != null) {
            try {
                c.getContentResolver().delete(Uri.parse(old), null, null);
            } catch (RuntimeException ignored) {
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "Question for Claude.jpg");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Fedora Pad");
        try {
            Uri u = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            float k = Math.min(1f, 1568f / Math.max(picture.getWidth(), picture.getHeight()));
            Bitmap small = k < 1f ? Bitmap.createScaledBitmap(picture, Math.round(picture.getWidth() * k),
                    Math.round(picture.getHeight() * k), true) : picture;
            try (OutputStream out = c.getContentResolver().openOutputStream(u)) {
                small.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            prefs.edit().putString(LAST_PICTURE, u.toString()).apply();
            return u;
        } catch (Exception e) {
            return null;
        }
    }
}

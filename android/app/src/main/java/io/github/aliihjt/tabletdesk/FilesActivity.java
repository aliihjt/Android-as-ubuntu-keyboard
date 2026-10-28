package io.github.aliihjt.tabletdesk;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sends photos and files from the tablet to Fedora's Downloads › Fedora Pad, over the cable. */
public class FilesActivity extends Page {
    private static final int PICK = 7;
    private static final int CHUNK = 48 * 1024;
    private TextView status;
    private ProgressBar progress;
    private volatile boolean busy;

    @Override
    protected String title() {
        return "Send Files";
    }

    @Override
    protected void build() {
        LinearLayout card = th.card();
        int p = th.dp(22);
        card.setPadding(p, p, p, p);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        card.addView(th.iconChip("📁", Theme.BLUE, 96));
        TextView t = th.text("Send to Fedora", 22, th.text, true);
        card.addView(t, th.fullWidth(16));
        TextView sub = th.caption("Photos, videos, documents — they arrive in Downloads › Fedora Pad.");
        sub.setGravity(android.view.Gravity.CENTER);
        card.addView(sub, th.fullWidth(6));
        card.addView(th.primaryButton("Choose files", v -> pick()), th.fullWidth(20));
        status = th.caption("");
        status.setGravity(android.view.Gravity.CENTER);
        card.addView(status, th.fullWidth(14));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(th.accent));
        progress.setVisibility(android.view.View.GONE);
        card.addView(progress, th.fullWidth(8));
        add(card, 6);
    }

    private void pick() {
        if (busy) return;
        if (!link.isConnected()) {
            status.setText("Connect the tablet first.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) send(uris);
    }

    private void send(List<Uri> uris) {
        busy = true;
        progress.setVisibility(android.view.View.VISIBLE);
        new Thread(() -> {
            int done = 0;
            for (Uri uri : uris) {
                String name = nameOf(uri);
                long size = sizeOf(uri);
                String id = UUID.randomUUID().toString();
                int n = done + 1;
                runOnUiThread(() -> status.setText("Sending " + name + " (" + n + " of " + uris.size() + ")"));
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) continue;
                    byte[] buf = new byte[CHUNK];
                    long sent = 0;
                    int read;
                    byte[] pending = null;
                    int pendingLen = 0;
                    // keep one chunk back so the final one can be marked "last"
                    while ((read = readFully(in, buf)) > 0) {
                        if (pending != null && !chunk(id, name, pending, pendingLen, false)) throw new IllegalStateException();
                        pending = buf.clone();
                        pendingLen = read;
                        sent += read;
                        long s = sent;
                        if (size > 0) runOnUiThread(() -> progress.setProgress((int) (s * 1000 / size)));
                    }
                    if (!chunk(id, name, pending == null ? new byte[0] : pending, pendingLen, true)) {
                        throw new IllegalStateException();
                    }
                    done++;
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Could not send " + name + ". Is the cable connected?"));
                    break;
                }
            }
            int sentCount = done;
            runOnUiThread(() -> {
                busy = false;
                progress.setVisibility(android.view.View.GONE);
                if (sentCount == uris.size()) {
                    status.setText("✅ Sent " + sentCount + (sentCount == 1 ? " file" : " files")
                            + " to Downloads › Fedora Pad");
                }
            });
        }, "FileSender").start();
    }

    private boolean chunk(String id, String name, byte[] data, int len, boolean last) throws InterruptedException {
        return link.sendFileChunk(id, name, Base64.encodeToString(data, 0, len, Base64.NO_WRAP), last);
    }

    private static int readFully(InputStream in, byte[] buf) throws java.io.IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private String nameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return "file";
    }

    private long sizeOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }
}

package io.github.aliihjt.tabletdesk;

import android.content.Context;
import android.net.Uri;
import android.text.Html;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A book's text as parts and paragraphs, without a screen: for the audiobook player. */
final class BookText implements AutoCloseable {
    private final Context c;
    private final String uri, type;
    private ZipFile zip;
    private final List<String> chapters = new ArrayList<>();
    private List<String> plain;  // a text file: one part

    BookText(Context c, String uri, String type) throws IOException {
        this.c = c;
        this.uri = uri;
        this.type = type;
        if ("epub".equals(type)) openEpub();
        else if ("txt".equals(type)) plain = split(read(c.getContentResolver().openInputStream(Uri.parse(uri)), 8_000_000));
        else throw new IOException("PDF books can't be read aloud");
    }

    int parts() {
        return plain != null ? 1 : chapters.size();
    }

    List<String> paragraphs(int part) throws IOException {
        if (plain != null) return plain;
        ZipEntry e = zip.getEntry(chapters.get(part));
        if (e == null) return new ArrayList<>();
        String html = read(zip.getInputStream(e), 4_000_000);
        html = html.replaceAll("(?is)<head.*?</head>|<script.*?</script>|<style.*?</style>", "");
        return split(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace('￼', ' '));
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.split("\\r?\\n\\s*\\n")) {
            String t = p.replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String read(InputStream in, int max) throws IOException {
        if (in == null) throw new IOException("can't open the book");
        try (InputStream s = in) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = s.read(buf)) > 0 && all.size() < max) all.write(buf, 0, n);
            return new String(all.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- EPUB: the same copy the reader keeps, its spine in reading order

    private void openEpub() throws IOException {
        File copy = new File(c.getCacheDir(), "books/" + Integer.toHexString(uri.hashCode()) + ".epub");
        if (!copy.exists()) {
            copy.getParentFile().mkdirs();
            try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri));
                 OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("can't open the book");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        zip = new ZipFile(copy);
        try {
            String opf = attr(zip.getEntry("META-INF/container.xml"), "rootfile", "full-path", null).get(0);
            String base = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";
            Map<String, String> manifest = new HashMap<>();
            List<String> spine = new ArrayList<>();
            XmlPullParser x = Xml.newPullParser();
            try (InputStream in = zip.getInputStream(zip.getEntry(opf))) {
                x.setInput(in, null);
                for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                    if (e != XmlPullParser.START_TAG) continue;
                    String tag = x.getName().replaceAll(".*:", "");
                    if (tag.equals("item")) manifest.put(x.getAttributeValue(null, "id"), x.getAttributeValue(null, "href"));
                    else if (tag.equals("itemref")) spine.add(x.getAttributeValue(null, "idref"));
                }
            }
            for (String id : spine) {
                String href = manifest.get(id);
                if (href != null) chapters.add(normalise(base + URLDecoder.decode(href, "UTF-8")));
            }
        } catch (Exception e) {
            throw new IOException("not an EPUB");
        }
        if (chapters.isEmpty()) throw new IOException("no chapters found");
    }

    private List<String> attr(ZipEntry e, String tag, String name, List<String> into) throws Exception {
        List<String> out = into == null ? new ArrayList<>() : into;
        XmlPullParser x = Xml.newPullParser();
        try (InputStream in = zip.getInputStream(e)) {
            x.setInput(in, null);
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG && x.getName().endsWith(tag)) out.add(x.getAttributeValue(null, name));
            }
        }
        return out;
    }

    private static String normalise(String path) {
        List<String> parts = new ArrayList<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(p);
            }
        }
        return String.join("/", parts);
    }

    @Override
    public void close() {
        try {
            if (zip != null) zip.close();
        } catch (IOException ignored) {
        }
    }
}

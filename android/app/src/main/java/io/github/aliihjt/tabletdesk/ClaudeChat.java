package io.github.aliihjt.tabletdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * One streamed request to the Claude Messages API over plain HTTP (the app has
 * no libraries). The history is append-only and keeps every content block of
 * Claude's replies exactly as received, so earlier reasoning stays valid.
 */
final class ClaudeChat {
    static final String MODEL = "claude-opus-5-5";
    private static final String URL_MESSAGES = "https://api.anthropic.com/v1/messages";
    // never changes within a conversation: editing it would invalidate earlier turns
    private static final String SYSTEM = "You are the assistant on the user's Fedora Pad tablet, a reading, "
            + "note-taking and study companion that also controls their Fedora Linux laptop. Reply in the "
            + "language the user writes in (often Persian or English). Be clear and concise. The chat shows "
            + "plain text without Markdown rendering, so use short paragraphs and simple '-' lists, no "
            + "headings, tables or bold markers.";

    interface Callback {
        void onText(String textSoFar);

        /** Finished: the full assistant content (append it to the history), or null on error. */
        void onDone(JSONArray content, String stopReason, String error);

        /** The history had to be sent without its old reasoning: keep using this one. */
        void onHistoryReplaced(JSONArray history);
    }

    private ClaudeChat() {
    }

    /** Runs on the calling (background) thread. */
    static void send(String apiKey, JSONArray history, Callback cb) {
        try {
            stream(apiKey, history, cb, false);
        } catch (RetryWithoutThinking r) {
            try {
                JSONArray stripped = withoutThinking(history);
                cb.onHistoryReplaced(stripped);
                stream(apiKey, stripped, cb, true);
            } catch (Exception e) {
                cb.onDone(null, null, e.getMessage());
            }
        } catch (Exception e) {
            cb.onDone(null, null, "No connection: " + e.getMessage());
        }
    }

    private static final class RetryWithoutThinking extends Exception {
    }

    private static void stream(String apiKey, JSONArray history, Callback cb, boolean retried)
            throws IOException, JSONException, RetryWithoutThinking {
        JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 64000)
                .put("stream", true)
                .put("system", SYSTEM)
                // chat: quick answers; Claude still thinks harder when a question needs it
                .put("output_config", new JSONObject().put("effort", "low"))
                // if a safety check declines, the API answers with a fitting fallback model
                .put("fallbacks", "default")
                .put("messages", history);
        HttpURLConnection c = (HttpURLConnection) new URL(URL_MESSAGES).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(120_000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", apiKey);
        c.setRequestProperty("anthropic-version", "2026-10-21");
        c.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        if (status != 200) {
            String err = readAll(c.getErrorStream());
            c.disconnect();
            String msg = err;
            try {
                msg = new JSONObject(err).getJSONObject("error").optString("message", err);
            } catch (JSONException ignored) {
            }
            // an earlier turn no longer matches its reasoning: drop old reasoning once and retry
            if (status == 400 && !retried && msg.contains("signature")) throw new RetryWithoutThinking();
            cb.onDone(null, null, friendly(status, msg));
            return;
        }

        JSONArray blocks = new JSONArray();
        StringBuilder[] partialJson = new StringBuilder[64];
        StringBuilder text = new StringBuilder();
        String stopReason = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                JSONObject ev = new JSONObject(line.substring(5).trim());
                switch (ev.optString("type")) {
                    case "content_block_start": {
                        int i = ev.getInt("index");
                        blocks.put(i, new JSONObject(ev.getJSONObject("content_block").toString()));
                        break;
                    }
                    case "content_block_delta": {
                        int i = ev.getInt("index");
                        JSONObject b = blocks.getJSONObject(i), d = ev.getJSONObject("delta");
                        switch (d.optString("type")) {
                            case "text_delta":
                                b.put("text", b.optString("text") + d.optString("text"));
                                text.append(d.optString("text"));
                                cb.onText(text.toString());
                                break;
                            case "thinking_delta":
                                b.put("thinking", b.optString("thinking") + d.optString("thinking"));
                                break;
                            case "signature_delta":
                                b.put("signature", b.optString("signature") + d.optString("signature"));
                                break;
                            case "input_json_delta":
                                if (i < partialJson.length) {
                                    if (partialJson[i] == null) partialJson[i] = new StringBuilder();
                                    partialJson[i].append(d.optString("partial_json"));
                                }
                                break;
                            case "citations_delta":
                                JSONArray cites = b.optJSONArray("citations");
                                if (cites == null) b.put("citations", cites = new JSONArray());
                                cites.put(d.optJSONObject("citation"));
                                break;
                            default:
                                break;
                        }
                        break;
                    }
                    case "content_block_stop": {
                        int i = ev.getInt("index");
                        if (i < partialJson.length && partialJson[i] != null && partialJson[i].length() > 0) {
                            blocks.getJSONObject(i).put("input", new JSONObject(partialJson[i].toString()));
                        }
                        break;
                    }
                    case "message_delta":
                        stopReason = ev.getJSONObject("delta").optString("stop_reason", stopReason);
                        break;
                    case "error":
                        cb.onDone(null, null, friendly(529, ev.getJSONObject("error").optString("message")));
                        return;
                    default:
                        break;
                }
            }
        } finally {
            c.disconnect();
        }
        cb.onDone(echoable(blocks), stopReason, null);
    }

    /**
     * After a fallback, the blocks before the last "fallback" marker that only
     * the declining model can read (thinking, tool calls) are left out of the history.
     */
    private static JSONArray echoable(JSONArray blocks) throws JSONException {
        int last = -1;
        for (int i = 0; i < blocks.length(); i++) {
            if ("fallback".equals(blocks.getJSONObject(i).optString("type"))) last = i;
        }
        if (last < 0) return blocks;
        JSONArray out = new JSONArray();
        for (int i = 0; i < blocks.length(); i++) {
            String t = blocks.getJSONObject(i).optString("type");
            boolean internal = t.equals("thinking") || t.equals("redacted_thinking") || t.equals("tool_use");
            if (i < last && internal) continue;
            out.put(blocks.getJSONObject(i));
        }
        return out;
    }

    /** The one-time recovery: the same history without any reasoning blocks. */
    static JSONArray withoutThinking(JSONArray history) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            JSONObject m = new JSONObject(history.getJSONObject(i).toString());
            Object content = m.opt("content");
            if (content instanceof JSONArray) {
                JSONArray kept = new JSONArray();
                JSONArray arr = (JSONArray) content;
                for (int j = 0; j < arr.length(); j++) {
                    String t = arr.getJSONObject(j).optString("type");
                    if (!t.equals("thinking") && !t.equals("redacted_thinking")) kept.put(arr.get(j));
                }
                m.put("content", kept);
            }
            out.put(m);
        }
        return out;
    }

    /** All the text blocks of a reply. */
    static String textOf(JSONArray content) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) b.append(block.optString("text"));
        }
        return b.toString().trim();
    }

    private static String friendly(int status, String msg) {
        switch (status) {
            case 401:
                return "The API key isn't valid. Tap 🔑 Key and paste it again.";
            case 403:
                return "This API key isn't allowed to use Claude: " + msg;
            case 429:
                return "Too many messages for now. Wait a minute and try again.";
            case 500:
            case 502:
            case 503:
            case 529:
                return "Claude is busy right now. Try again in a moment.";
            default:
                return msg;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString().trim();
    }
}

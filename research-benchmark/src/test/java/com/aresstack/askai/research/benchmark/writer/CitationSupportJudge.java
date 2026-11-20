package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.research.runtime.writer.EvidencePassage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the cited passages carry what a sentence claims (acceptance of the writer proposal: valid citation numbers
 * are not enough). A judge model, ideally not the writer, reads each cited sentence with the full text of its cited
 * passages and answers {@code supported}, {@code partial} (the core is there, a detail is not) or
 * {@code unsupported}. The judge is a measuring instrument with its own error; the report lists every sentence it
 * rejected, so a human can check them.
 */
public final class CitationSupportJudge {

    public enum Verdict { SUPPORTED, PARTIAL, UNSUPPORTED, UNREADABLE }

    /** One judged sentence. */
    public static final class Finding {
        public final String sentence;
        public final List<Integer> cited;
        public final Verdict verdict;
        public final String reason;

        Finding(String sentence, List<Integer> cited, Verdict verdict, String reason) {
            this.sentence = sentence;
            this.cited = Collections.unmodifiableList(new ArrayList<Integer>(cited));
            this.verdict = verdict;
            this.reason = reason == null ? "" : reason;
        }
    }

    /** The verdicts of one text. */
    public static final class Result {
        public final List<Finding> findings;

        Result(List<Finding> findings) {
            this.findings = Collections.unmodifiableList(new ArrayList<Finding>(findings));
        }

        public static Result none() {
            return new Result(Collections.<Finding>emptyList());
        }

        public int count(Verdict verdict) {
            int n = 0;
            for (Finding f : findings) {
                if (f.verdict == verdict) {
                    n++;
                }
            }
            return n;
        }

        public int judged() {
            return findings.size() - count(Verdict.UNREADABLE);
        }

        /** Supported sentences over judged ones; 1.0 when nothing was judged. */
        public double supportedShare() {
            return judged() == 0 ? 1.0 : count(Verdict.SUPPORTED) / (double) judged();
        }

        public boolean isEmpty() {
            return findings.isEmpty();
        }
    }

    /** Sentences before a judged one that the judge reads as context, never judges. */
    static final int CONTEXT_SENTENCES = 2;

    static final String SYSTEM = "You check citations in a non-fiction text. You get one sentence and the full text "
            + "of the source passages it cites, sometimes with the sentences just before it as context. Decide whether the passages state what the sentence claims. "
            + "\"supported\": every fact of the sentence (names, numbers, relations, causes, evaluations) is stated "
            + "by the passages, paraphrase allowed. \"partial\": the core is stated, but the sentence adds or "
            + "sharpens a detail the passages do not state. \"unsupported\": the passages do not state the claim, "
            + "or contradict it. Judge only against the passages, never against your own knowledge. Answer only "
            + "JSON: {\"verdict\": \"supported\" | \"partial\" | \"unsupported\", \"reason\": \"<at most 20 words>\"}.";

    private final String baseUrl;
    private final String model;
    private final int timeoutMillis;

    public CitationSupportJudge(String baseUrl, String model, int timeoutMillis) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.timeoutMillis = timeoutMillis;
    }

    public String getModel() {
        return model;
    }

    /** Every cited sentence of {@code text} (pack markers) judged against the passages it cites. */
    public Result judge(String text, List<EvidencePassage> passages) {
        Map<Integer, EvidencePassage> byNumber = new java.util.HashMap<Integer, EvidencePassage>();
        for (EvidencePassage passage : passages) {
            byNumber.put(passage.getNumber(), passage);
        }
        List<Finding> findings = new ArrayList<Finding>();
        List<WriterQualityMetrics.Sentence> sentences = WriterQualityMetrics.sentences(text);
        for (int i = 0; i < sentences.size(); i++) {
            WriterQualityMetrics.Sentence sentence = sentences.get(i);
            List<Integer> cited = new ArrayList<Integer>(new java.util.LinkedHashSet<Integer>(sentence.cited));
            if (cited.isEmpty()) {
                continue;
            }
            StringBuilder user = new StringBuilder();
            String before = context(sentences, i);
            if (!before.isEmpty()) {
                user.append("CONTEXT (the sentences just before, only to resolve words like \"these\" or \"this\"; "
                        + "do not judge them):\n").append(before).append("\n\n");
            }
            user.append("SENTENCE:\n").append(sentence.plain).append("\n\nPASSAGES:\n");
            boolean any = false;
            for (Integer n : cited) {
                EvidencePassage passage = byNumber.get(n);
                if (passage != null) {
                    user.append('[').append(n).append("] ").append(passage.getText()).append('\n');
                    any = true;
                }
            }
            if (!any) {
                findings.add(new Finding(sentence.text, cited, Verdict.UNSUPPORTED, "cites no passage of the pack"));
                continue;
            }
            findings.add(ask(sentence.text, cited, user.toString()));
        }
        return new Result(findings);
    }

    /** Up to {@link #CONTEXT_SENTENCES} sentences before sentence {@code i} in its paragraph, without markers. */
    static String context(List<WriterQualityMetrics.Sentence> sentences, int i) {
        StringBuilder sb = new StringBuilder();
        for (int j = Math.max(0, i - CONTEXT_SENTENCES); j < i; j++) {
            if (sentences.get(j).paragraph == sentences.get(i).paragraph) {
                sb.append(sb.length() == 0 ? "" : " ").append(sentences.get(j).plain);
            }
        }
        return sb.toString();
    }

    /**
     * The thinking switch Ollama takes for the model: gpt-oss ignores {@code false} and knows only levels, so it
     * reasons as little as it can; every other model gets {@code false}.
     */
    static com.google.gson.JsonPrimitive think(String model) {
        return model.toLowerCase(Locale.ROOT).startsWith("gpt-oss") ? new com.google.gson.JsonPrimitive("low")
                : new com.google.gson.JsonPrimitive(false);
    }

    private Finding ask(String sentence, List<Integer> cited, String user) {
        try {
            JsonObject request = new JsonObject();
            request.addProperty("model", model);
            request.addProperty("stream", false);
            request.addProperty("format", "json");
            request.add("think", think(model));
            JsonObject options = new JsonObject();
            options.addProperty("temperature", 0);
            options.addProperty("num_ctx", 8192);
            request.add("options", options);
            JsonArray messages = new JsonArray();
            messages.add(message("system", SYSTEM));
            messages.add(message("user", user));
            request.add("messages", messages);
            JsonObject response = JsonParser.parseString(post("/api/chat", request.toString())).getAsJsonObject();
            String content = response.getAsJsonObject("message").get("content").getAsString();
            JsonObject answer = JsonParser.parseString(content.substring(content.indexOf('{'),
                    content.lastIndexOf('}') + 1)).getAsJsonObject();
            String verdict = answer.get("verdict").getAsString().trim().toLowerCase(Locale.ROOT);
            String reason = answer.has("reason") ? answer.get("reason").getAsString() : "";
            Verdict v = verdict.startsWith("support") ? Verdict.SUPPORTED : verdict.startsWith("partial")
                    ? Verdict.PARTIAL : verdict.startsWith("unsupport") || verdict.startsWith("not")
                    ? Verdict.UNSUPPORTED : Verdict.UNREADABLE;
            return new Finding(sentence, cited, v, reason);
        } catch (IOException | RuntimeException failed) {
            return new Finding(sentence, cited, Verdict.UNREADABLE, failed.getClass().getSimpleName() + ": "
                    + failed.getMessage());
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private String post(String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; in != null && (read = in.read(buffer)) > 0; ) {
            bytes.write(buffer, 0, read);
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (status >= 400) {
            throw new IOException("HTTP " + status + ": " + text);
        }
        return text;
    }
}

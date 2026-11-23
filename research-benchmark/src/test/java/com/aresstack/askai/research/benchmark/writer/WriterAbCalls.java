package com.aresstack.askai.research.benchmark.writer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.function.BiConsumer;

/**
 * The writer's model calls in the writer A/B, one JSON line each ({@code sample}, {@code kind}, {@code model},
 * {@code text}): the first draft of a part, a top-up, a repair or a continuation. With them a sentence of the final
 * text can be traced to the call that wrote it, so the judge's verdicts can be split by draft and top-up. Other
 * calls (the judge, embeddings) are left out.
 */
final class WriterAbCalls implements BiConsumer<String, String> {

    static final String DRAFT = "draft";
    static final String TOP_UP = "top-up";
    static final String REPAIR = "repair";
    static final String CONTINUATION = "continuation";

    private final File file;
    private volatile String sample = "";

    WriterAbCalls(File file) {
        this.file = file;
    }

    /** The sample the following calls belong to. */
    void begin(String heading) {
        sample = heading;
    }

    /** The kind of a writer request by the wording of SectionWriterPrompt; "" for any other call. */
    static String kind(String request) {
        if (request.contains("Your answer above is already written and stays as it is")) {
            return TOP_UP;
        }
        if (request.contains("A previous answer to this request broke the writing rules")) {
            return REPAIR;
        }
        if (request.contains("Your answer was cut off")) {
            return CONTINUATION;
        }
        // a whole-section call ("You write one section of ...") or a part call with part context ("You write one
        // part of a section of ...")
        if (request.contains("You write one section of a non-fiction book")
                || request.contains("You write one part of a section of a non-fiction book")) {
            return DRAFT;
        }
        return "";
    }

    /** The answer text of a chat or generate response, single or streamed. */
    static String text(String response) {
        StringBuilder text = new StringBuilder();
        for (String line : response.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                JsonElement element = JsonParser.parseString(trimmed);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (object.has("message") && object.get("message").isJsonObject()
                        && object.getAsJsonObject("message").has("content")) {
                    text.append(object.getAsJsonObject("message").get("content").getAsString());
                } else if (object.has("response")) {
                    text.append(object.get("response").getAsString());
                }
            } catch (RuntimeException notJson) {
                // a broken line is left out
            }
        }
        return text.toString();
    }

    public synchronized void accept(String request, String response) {
        String kind = kind(request);
        if (kind.isEmpty() || file == null) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("sample", sample);
        line.addProperty("kind", kind);
        try {
            line.addProperty("model", JsonParser.parseString(request).getAsJsonObject().get("model").getAsString());
        } catch (RuntimeException noModel) {
            line.addProperty("model", "");
        }
        line.addProperty("text", text(response));
        try {
            Files.write(file.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the record never stops a run
        }
    }
}

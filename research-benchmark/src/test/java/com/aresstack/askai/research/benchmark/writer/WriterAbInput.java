package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.writer.EvidencePassage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed input of the writer A/B: one chapter of three to four sections, each with its outline brief (purpose, key
 * points, word target) and its complete evidence pack as the app's pack builder chose it ({@link WriterAbExport}),
 * plus the pack's glossary lines, the chapter's purpose (the app's synopsis block) and the book language as the
 * concept pins it ({@code de}). Every arm writes from exactly this input, and the assignment is built from it by the
 * app's own code ({@link WriterAbPipeline}). The format is documented in the module README.
 *
 * <p>Format 1 (the chapter of 17 November: the cited passages only, a book-wide glossary, a {@code synopsis}) is
 * still read; its sections then carry the passages it has.</p>
 */
public final class WriterAbInput {

    /** The format {@link WriterAbExport} writes: complete packs and their glossary per section. */
    public static final int FORMAT = 2;

    public static final class Section {
        public final String id;
        public final String title;
        public final String brief;
        public final List<String> keyPoints;
        public final int words;
        public final List<EvidencePassage> passages;
        public final List<String> glossary;

        Section(String id, String title, String brief, List<String> keyPoints, int words,
                List<EvidencePassage> passages, List<String> glossary) {
            this.id = id;
            this.title = title;
            this.brief = brief;
            this.keyPoints = Collections.unmodifiableList(new ArrayList<String>(keyPoints));
            this.words = words;
            this.passages = Collections.unmodifiableList(new ArrayList<EvidencePassage>(passages));
            this.glossary = Collections.unmodifiableList(new ArrayList<String>(glossary));
        }
    }

    public final int format;
    public final String language;
    public final String chapterId;
    public final String chapterTitle;
    /** The chapter's purpose: what the app hands the writer as the synopsis block. */
    public final String synopsis;
    public final List<Section> sections;
    /** Where the packs came from (workspace, outline revision, settings); informational. */
    public final String origin;

    WriterAbInput(int format, String language, String chapterId, String chapterTitle, String synopsis,
                  List<Section> sections, String origin) {
        if (sections.size() < 3 || sections.size() > 4) {
            throw new IllegalArgumentException("the sample chapter needs three to four sections, got "
                    + sections.size());
        }
        this.format = format;
        this.language = language == null || language.trim().isEmpty() ? "de" : language.trim();
        this.chapterId = chapterId == null || chapterId.trim().isEmpty() ? "ab-chapter" : chapterId.trim();
        this.chapterTitle = chapterTitle == null || chapterTitle.trim().isEmpty() ? "Sample chapter"
                : chapterTitle.trim();
        this.synopsis = synopsis == null ? "" : synopsis;
        this.sections = Collections.unmodifiableList(new ArrayList<Section>(sections));
        this.origin = origin == null ? "" : origin;
    }

    public static WriterAbInput read(File file) throws IOException {
        return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    public static WriterAbInput parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int format = root.has("format") ? root.get("format").getAsInt() : 1;
        JsonObject chapter = root.has("chapter") ? root.getAsJsonObject("chapter") : new JsonObject();
        List<String> bookGlossary = strings(root.getAsJsonArray("glossary"));
        List<Section> sections = new ArrayList<Section>();
        for (JsonElement element : root.getAsJsonArray("sections")) {
            JsonObject s = element.getAsJsonObject();
            List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
            for (JsonElement p : s.getAsJsonArray("passages")) {
                JsonObject passage = p.getAsJsonObject();
                int number = passage.has("number") ? passage.get("number").getAsInt() : passages.size() + 1;
                passages.add(new EvidencePassage(number, string(passage, "passageId"), string(passage, "sourceId"),
                        string(passage, "checksum"), string(passage, "text")));
            }
            sections.add(new Section(string(s, "id"), string(s, "title"), string(s, "brief"),
                    strings(s.getAsJsonArray("keyPoints")), s.has("words") ? s.get("words").getAsInt() : 900,
                    passages, s.has("glossary") ? strings(s.getAsJsonArray("glossary")) : bookGlossary));
        }
        String synopsis = chapter.has("purpose") ? string(chapter, "purpose") : string(root, "synopsis");
        return new WriterAbInput(format, root.has("language") ? string(root, "language") : "de",
                string(chapter, "id"), string(chapter, "title"), synopsis, sections, string(root, "origin"));
    }

    /** The input as JSON in the current format. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("language", language);
        root.addProperty("origin", origin);
        JsonObject chapter = new JsonObject();
        chapter.addProperty("id", chapterId);
        chapter.addProperty("title", chapterTitle);
        chapter.addProperty("purpose", synopsis);
        root.add("chapter", chapter);
        JsonArray list = new JsonArray();
        for (Section section : sections) {
            JsonObject s = new JsonObject();
            s.addProperty("id", section.id);
            s.addProperty("title", section.title);
            s.addProperty("brief", section.brief);
            s.add("keyPoints", array(section.keyPoints));
            s.addProperty("words", section.words);
            s.add("glossary", array(section.glossary));
            JsonArray passages = new JsonArray();
            for (EvidencePassage passage : section.passages) {
                JsonObject p = new JsonObject();
                p.addProperty("number", passage.getNumber());
                p.addProperty("passageId", passage.getPassageId());
                p.addProperty("sourceId", passage.getSourceId());
                p.addProperty("checksum", passage.getSourceChecksum());
                p.addProperty("text", passage.getText());
                passages.add(p);
            }
            s.add("passages", passages);
            list.add(s);
        }
        root.add("sections", list);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(root);
    }

    /** The one-chapter outline the app's writing job reads: the chapter's purpose and each section's brief. */
    public OutlineChapter outlineChapter() {
        List<OutlineSection> outline = new ArrayList<OutlineSection>();
        for (Section section : sections) {
            outline.add(new OutlineSection(section.id, chapterId, outline.size() + 1, section.title,
                    new OutlineBrief(section.brief, section.keyPoints, section.words), OutlineProvenance.NONE,
                    false, ""));
        }
        return new OutlineChapter(chapterId, 1, chapterTitle, new OutlineBrief(synopsis, new ArrayList<String>(), 0),
                OutlineProvenance.NONE, false, "", outline);
    }

    /** The sources of every pack (id to checksum): the accepted set the app's context hands the pack. */
    public Map<String, String> sourceChecksums() {
        Map<String, String> checksums = new LinkedHashMap<String, String>();
        for (Section section : sections) {
            for (EvidencePassage passage : section.passages) {
                checksums.put(passage.getSourceId(), passage.getSourceChecksum());
            }
        }
        return checksums;
    }

    public Section section(String id) {
        for (Section section : sections) {
            if (section.id.equals(id)) {
                return section;
            }
        }
        throw new IllegalArgumentException("no section " + id + " in the A/B input");
    }

    static WriterAbInput of(String language, String chapterId, String chapterTitle, String synopsis,
                            List<Section> sections, String origin) {
        return new WriterAbInput(FORMAT, language, chapterId, chapterTitle, synopsis, sections, origin);
    }

    static Section section(String id, String title, String brief, List<String> keyPoints, int words,
                           List<EvidencePassage> passages, List<String> glossary) {
        return new Section(id, title, brief, keyPoints, words, passages, glossary);
    }

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<String>();
        if (array != null) {
            for (JsonElement element : array) {
                result.add(element.getAsString());
            }
        }
        return result;
    }
}

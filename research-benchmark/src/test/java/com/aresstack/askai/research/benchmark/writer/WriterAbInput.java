package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.research.runtime.writer.EvidencePassage;
import com.aresstack.askai.research.runtime.writer.SectionAssignment;
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
import java.util.List;

/**
 * The fixed input of the writer A/B: one chapter of three to four sections with brief, key points and the evidence
 * pack (8 x 300 per section, numbered [1]..[k]), the book's glossary block (PR 38) and its language. Every model
 * writes from exactly this input. The file is exported from a real project after its Outline phase; the format is
 * documented in the module README.
 */
public final class WriterAbInput {

    public static final class Section {
        public final String id;
        public final String title;
        public final String brief;
        public final List<String> keyPoints;
        public final int words;
        public final List<EvidencePassage> passages;

        Section(String id, String title, String brief, List<String> keyPoints, int words,
                List<EvidencePassage> passages) {
            this.id = id;
            this.title = title;
            this.brief = brief;
            this.keyPoints = Collections.unmodifiableList(keyPoints);
            this.words = words;
            this.passages = Collections.unmodifiableList(passages);
        }
    }

    public final String language;
    public final String synopsis;
    public final List<String> glossary;
    public final List<Section> sections;

    WriterAbInput(String language, String synopsis, List<String> glossary, List<Section> sections) {
        if (sections.size() < 3 || sections.size() > 4) {
            throw new IllegalArgumentException("the sample chapter needs three to four sections, got "
                    + sections.size());
        }
        this.language = language;
        this.synopsis = synopsis;
        this.glossary = Collections.unmodifiableList(glossary);
        this.sections = Collections.unmodifiableList(sections);
    }

    public static WriterAbInput read(File file) throws IOException {
        return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    public static WriterAbInput parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Section> sections = new ArrayList<Section>();
        for (JsonElement element : root.getAsJsonArray("sections")) {
            JsonObject s = element.getAsJsonObject();
            List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
            for (JsonElement p : s.getAsJsonArray("passages")) {
                JsonObject passage = p.getAsJsonObject();
                passages.add(new EvidencePassage(passages.size() + 1, string(passage, "sourceId"),
                        string(passage, "checksum"), string(passage, "passageId"), string(passage, "text")));
            }
            sections.add(new Section(string(s, "id"), string(s, "title"), string(s, "brief"),
                    strings(s.getAsJsonArray("keyPoints")), s.has("words") ? s.get("words").getAsInt() : 900,
                    passages));
        }
        return new WriterAbInput(root.has("language") ? string(root, "language") : "de", string(root, "synopsis"),
                strings(root.getAsJsonArray("glossary")), sections);
    }

    /** The language name the writer prompt expects ("German", "English"). */
    public String languageName() {
        return "en".equalsIgnoreCase(language) ? "English" : "de".equalsIgnoreCase(language) ? "German" : language;
    }

    /**
     * The writer's assignment for one section under an arm; {@code previousDigest} chains the sample chapter. Without
     * further properties the section gets every passage in every part and no top-up (the comparison of 17 Nov 2026).
     * The properties below give it what the product's {@code EvidenceAssignments} gives a section, and the section
     * plan under test:
     * <ul>
     * <li>{@code askai.benchmark.writerAb.wordsPerPassage}: caps the section's words at passages times this value
     * (product default 40);</li>
     * <li>{@code askai.benchmark.writerAb.passagesPerCall}: passages one part gets; {@code auto} = the pack divided
     * by the section's parts, as the product sizes it;</li>
     * <li>{@code askai.benchmark.writerAb.topUp}: {@code share,calls} of the top-up (product default
     * {@code 0.85,2});</li>
     * <li>{@code askai.benchmark.writerAb.plan}: {@code off}, {@code continuity} or {@code semantic}
     * ({@code SectionPlanCriteria}), and {@code askai.benchmark.writerAb.yield}: words per evidence word of the
     * semantic plan (0 keeps the word target).</li>
     * </ul>
     */
    public SectionAssignment assignment(Section section, WriterAbArm arm, int answerTokens, String previousDigest) {
        String p = "askai.benchmark.writerAb.";
        int words = section.words;
        int wordsPerPassage = Integer.getInteger(p + "wordsPerPassage", 0);
        if (wordsPerPassage > 0) {
            words = Math.min(words, Math.max(SectionAssignment.MIN_WORDS_PER_CALL,
                    section.passages.size() * wordsPerPassage));
        }
        String perCallSetting = System.getProperty(p + "passagesPerCall", "0").trim();
        int parts = SectionAssignment.partCount(words, arm.getWordsPerCall(), section.keyPoints.size());
        int perCall = "auto".equalsIgnoreCase(perCallSetting) ? (section.passages.size() + parts - 1) / parts
                : Integer.parseInt(perCallSetting);
        com.aresstack.askai.research.runtime.writer.TopUpCriteria topUp =
                com.aresstack.askai.research.runtime.writer.TopUpCriteria.off();
        String topUpSetting = System.getProperty(p + "topUp", "").trim();
        if (!topUpSetting.isEmpty()) {
            String[] values = topUpSetting.split(",");
            topUp = new com.aresstack.askai.research.runtime.writer.TopUpCriteria(Double.parseDouble(values[0].trim()),
                    Integer.parseInt(values[1].trim()));
        }
        com.aresstack.askai.research.runtime.writer.SectionPlanCriteria plan =
                com.aresstack.askai.research.runtime.writer.SectionPlanCriteria.of(
                        com.aresstack.askai.research.runtime.writer.SectionPlanCriteria.Mode.valueOf(
                                System.getProperty(p + "plan", "off").trim().toUpperCase(java.util.Locale.ROOT)));
        String yield = System.getProperty(p + "yield", "").trim();
        if (!yield.isEmpty()) {
            plan = plan.withWordsPerEvidenceWord(Double.parseDouble(yield));
        }
        return SectionAssignment.builder(section.id).title(section.title).brief(section.brief)
                .keyPoints(section.keyPoints).passages(section.passages).synopsis(synopsis)
                .previousDigest(previousDigest).glossary(glossary).bookLanguage(languageName())
                .sectionWords(words).wordsPerCall(arm.getWordsPerCall()).answerTokens(answerTokens)
                .passagesPerCall(perCall).topUp(topUp).plan(plan).build();
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

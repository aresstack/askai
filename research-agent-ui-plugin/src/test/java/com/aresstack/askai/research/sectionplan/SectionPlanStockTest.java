package com.aresstack.askai.research.sectionplan;

import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.PassageText;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.TopicEvidence;
import com.aresstack.askai.research.missionreport.Placement;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The section plan on stored data of the book of 17 Nov, on the owner's machine; skipped without its files. The
 * sample test runs the same code on self-written files about city traffic in every build.
 * <p>
 * The research memory the plan reads is not stored anywhere yet (the components before it are not wired), so the
 * test builds one from the architect's test data:
 * <ul>
 * <li>{@code fragen}: fragen-1711.json, the mind-map cards ({@code themen}) as concept topics and the 20 questions
 * (only id, card and text are read);</li>
 * <li>{@code belege}: one evidence verdict per line ({@code frage}, {@code passage}, {@code quelle}, {@code urteil},
 * optional {@code text}, {@code zitat}, {@code domain}): belegpaare-1711.jsonl, or the {@code urteile.jsonl} of a run
 * of the evidence judge (component 6);</li>
 * <li>{@code themen} (optional): themen-1711.json, the found topics with the architect's fence {@code urteil}
 * (KEIN_THEMA counts as outside), counts of passages and sources, the card a topic names, a sample of passages;</li>
 * <li>{@code themenbelege} (optional): how found topics get direct evidence, since no judge has seen questions on them
 * yet. {@code quellen} (default): one text per source the table counts, so the weight is twice the sources;
 * {@code passagen}: one text per passage and the sources counted; {@code stichprobe}: the sampled passages, with
 * their text from {@code belege} where it has one; {@code keine}; or a file of verdicts with {@code thema} (sheet,
 * topic id or name) instead of {@code frage};</li>
 * <li>{@code karten} (optional): {@code uuid=1,uuid=3}, the card number of a card id in the topic table, so a found
 * topic stands under its card;</li>
 * <li>{@code woerter}: the length asked for, {@code 6000-9000} (default; the plan gets the middle) or {@code 7500};
 * {@code anteile} (optional): {@code 1=40,2=30,3=20,4=10}; {@code abschnitte}: the section range, default
 * {@code 12-18};</li>
 * <li>{@code chatModel} (optional): an Ollama model for the choice of a section ({@code url} default
 * {@code http://localhost:11434}); without it no model is called.</li>
 * </ul>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*SectionPlanStockTest*' \
 *   -Daskai.stock.plan.fragen=fragen-1711.json -Daskai.stock.plan.belege=belegpaare-1711.jsonl \
 *   -Daskai.stock.plan.themen=themen-1711.json -Daskai.stock.plan.out=&lt;folder&gt;
 * </pre>
 * Criteria proposed (the architect fixes them before the run): G1 the number of sections within the range; G2 the
 * planned words within the length asked for; G3 every topic of the memory has one fate, every topic inside the fence
 * with direct evidence is in a section, every topic left out has a reason; G4 no section under 350 words and no more
 * chapters than words / 900 or the cards with material (the collection apart); G5 in every chapter no topic that went
 * into a section is heavier than the lightest topic with a section of its own besides the chapter's root; G6 with
 * shares, a
 * card chapter holds its share within 1 %; G7 without a model, a second plan is the same. Report only: found topics
 * with a section, a chapter, gone into one, in the collection; words and sections per chapter.
 * <p>
 * {@code <out>/result.json} holds ids and counts, {@code plan.json} and {@code plan.txt} the plan (card, topic and
 * question names, passage ids, counts); none holds a passage.
 */
public class SectionPlanStockTest {

    private static final String P = "askai.stock.plan.";
    private static final long TIME = 1_763_800_000_000L;

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    // ------------------------------------------------------------------------------------------------- data

    static final class Question {
        final String id;
        final String card;
        final String text;

        Question(String id, String card, String text) {
            this.id = id;
            this.card = card;
            this.text = text;
        }
    }

    static final class Evidence {
        final String owner;
        final String passage;
        final String source;
        final String label;
        final String text;
        final String quote;
        final String domain;

        Evidence(String owner, String passage, String source, String label, String text, String quote,
                 String domain) {
            this.owner = owner;
            this.passage = passage;
            this.source = source;
            this.label = label;
            this.text = text;
            this.quote = quote;
            this.domain = domain;
        }
    }

    static final class Found {
        final String sheet;
        final String id;
        final String name;
        final ResearchTopic.Fence fence;
        final String fenceReason;
        final int passages;
        final int sources;
        final String card;
        final List<String> sample;

        Found(String sheet, String id, String name, ResearchTopic.Fence fence, String fenceReason, int passages,
              int sources, String card, List<String> sample) {
            this.sheet = sheet;
            this.id = id;
            this.name = name;
            this.fence = fence;
            this.fenceReason = fenceReason;
            this.passages = passages;
            this.sources = sources;
            this.card = card;
            this.sample = sample;
        }
    }

    static final class Data {
        final Map<String, String> cards = new LinkedHashMap<String, String>();
        final List<Question> questions = new ArrayList<Question>();
        final List<Evidence> evidence = new ArrayList<Evidence>();
        final List<Found> topics = new ArrayList<Found>();
        final List<Evidence> topicEvidence = new ArrayList<Evidence>();
        String topicProxy = "quellen";
        final Map<String, Double> shares = new HashMap<String, Double>();
        final Map<String, String> cardOfUuid = new HashMap<String, String>();
        int minWords = 6000;
        int maxWords = 9000;
        int minSections = 12;
        int maxSections = 18;
    }

    /** fragen-1711.json: {@code themen} (number to card name) and {@code fragen} (id, thema, text). */
    static void readQuestions(String json, Data data) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> card : root.getAsJsonObject("themen").entrySet()) {
            data.cards.put(card.getKey(), card.getValue().getAsString());
        }
        for (JsonElement element : root.getAsJsonArray("fragen")) {
            JsonObject q = element.getAsJsonObject();
            data.questions.add(new Question(string(q, "id"), string(q, "thema"), string(q, "text")));
        }
    }

    /** One verdict per line; {@code key} names the field that says whose evidence it is. */
    static List<Evidence> readEvidence(String jsonl, String key) throws IOException {
        List<Evidence> list = new ArrayList<Evidence>();
        int number = 0;
        for (String line : jsonl.split("\n")) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject e = object(line, number);
            String label = string(e, "urteil").toUpperCase(Locale.ROOT);
            if (string(e, key).isEmpty() || string(e, "passage").isEmpty() || label.isEmpty()) {
                throw new IOException("evidence line " + number + " needs " + key + ", passage and urteil");
            }
            list.add(new Evidence(string(e, key), string(e, "passage"), string(e, "quelle"), label,
                    string(e, "text"), string(e, "zitat"), string(e, "domain")));
        }
        return list;
    }

    /** themen-1711.json: {@code blatt}, {@code topicId}, {@code titel}, counts, {@code karte}, {@code urteil}. */
    static void readTopics(String json, Data data) {
        int number = 0;
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("themen")) {
            number++;
            JsonObject t = element.getAsJsonObject();
            String verdict = string(t, "urteil").toUpperCase(Locale.ROOT);
            ResearchTopic.Fence fence = "INNEN".equals(verdict) ? ResearchTopic.Fence.INNEN
                    : "UNKLAR".equals(verdict) ? ResearchTopic.Fence.UNKLAR : ResearchTopic.Fence.AUSSEN;
            String reason = "KEIN_THEMA".equals(verdict) ? "kein Thema (Urteil des Architekten)"
                    : "Urteil des Architekten";
            String sheet = string(t, "blatt").isEmpty() ? String.format("E%02d", number) : string(t, "blatt");
            String id = string(t, "topicId").isEmpty() ? "thema-" + sheet : string(t, "topicId");
            String name = string(t, "titel").isEmpty() ? string(t, "name") : string(t, "titel");
            List<String> sample = new ArrayList<String>();
            if (t.has("stichprobe") && t.get("stichprobe").isJsonArray()) {
                for (JsonElement passage : t.getAsJsonArray("stichprobe")) {
                    sample.add(passage.getAsString());
                }
            }
            data.topics.add(new Found(sheet, id, name.replaceAll("\\s+", " ").trim(), fence, reason,
                    integer(t, "passagen"), integer(t, "quellen"), string(t, "karte"), sample));
        }
    }

    /** {@code 1=40,2=30}: shares in percent by card number. */
    static void readShares(String text, Data data) {
        for (String part : text.split(",")) {
            String[] pair = part.trim().split("=");
            if (pair.length == 2) {
                data.shares.put(pair[0].trim(), Double.parseDouble(pair[1].trim()) / 100.0);
            }
        }
    }

    /** {@code uuid=1,uuid=3}: the card number of a card id in the topic table. */
    static void readCards(String text, Data data) {
        for (String part : text.split(",")) {
            String[] pair = part.trim().split("=");
            if (pair.length == 2) {
                data.cardOfUuid.put(pair[0].trim(), pair[1].trim());
            }
        }
    }

    /** {@code 6000-9000} or {@code 7500}. */
    static int[] range(String text) {
        String[] parts = text.trim().split("\\s*-\\s*");
        int min = Integer.parseInt(parts[0].replace(".", ""));
        int max = parts.length > 1 ? Integer.parseInt(parts[1].replace(".", "")) : min;
        return new int[]{min, max};
    }

    // ------------------------------------------------------------------------------------------------- memory

    static String cardId(String number) {
        return "karte-" + number;
    }

    static ResearchMemory memory(Data data, JsonObject counts) throws IOException {
        ResearchMemory memory = new ResearchMemory();
        for (Map.Entry<String, String> card : data.cards.entrySet()) {
            Double share = data.shares.get(card.getKey());
            memory.addTopic(ResearchTopic.ofCard(cardId(card.getKey()), card.getValue(),
                    "anchor-" + cardId(card.getKey()), share == null ? 0 : share, TIME));
        }
        Map<String, String> textOfPassage = new HashMap<String, String>();
        Set<String> known = new HashSet<String>();
        for (Question question : data.questions) {
            memory.addQuestion(ResearchQuestion.open(question.id, question.text, cardId(question.card),
                    ResearchQuestion.Origin.KONZEPT, TIME));
            known.add(question.id);
        }
        int withoutQuestion = 0;
        for (Evidence e : data.evidence) {
            if (!known.contains(e.owner)) {
                withoutQuestion++;
                continue;
            }
            EvidenceVerdict verdict = verdict(e.owner, e);
            memory.record(verdict);
            if (!e.text.isEmpty()) {
                textOfPassage.put(e.passage, e.text);
            }
        }
        counts.addProperty("belegeOhneFrage", withoutQuestion);

        Map<String, Found> foundByKey = new HashMap<String, Found>();
        for (Found topic : data.topics) {
            String card = data.cardOfUuid.get(topic.card);
            memory.addTopic(new ResearchTopic(topic.id, topic.name.isEmpty() ? topic.sheet : topic.name, "",
                    ResearchTopic.Origin.GEFUNDEN, card == null ? "" : "anchor-" + cardId(card), null, null,
                    topic.fence, topic.fenceReason, 0, "", TIME));
            foundByKey.put(topic.sheet, topic);
            foundByKey.put(topic.id, topic);
            foundByKey.put(topic.name, topic);
        }
        int proxyVerdicts = 0;
        int topicVerdicts = 0;
        if (!data.topicEvidence.isEmpty()) {
            Map<String, String> questionOf = new HashMap<String, String>();
            for (Evidence e : data.topicEvidence) {
                Found topic = foundByKey.get(e.owner);
                if (topic == null) {
                    continue;
                }
                String questionId = questionOf.get(topic.id);
                if (questionId == null) {
                    questionId = topicQuestion(memory, topic);
                    questionOf.put(topic.id, questionId);
                }
                memory.record(verdict(questionId, e));
                topicVerdicts++;
            }
        } else if (!"keine".equals(data.topicProxy)) {
            for (Found topic : data.topics) {
                List<String[]> proxies = proxies(topic, data.topicProxy, textOfPassage);
                if (proxies.isEmpty()) {
                    continue;
                }
                String questionId = topicQuestion(memory, topic);
                for (String[] proxy : proxies) {
                    memory.record(EvidenceVerdict.judged(questionId, proxy[0], proxy[1], "", proxy[2],
                            EvidenceVerdict.Kind.BELEGT, proxy[2], "Ersatz", "ersatz", 1, TIME));
                    proxyVerdicts++;
                }
            }
        }
        counts.addProperty("themenbelege", data.topicEvidence.isEmpty() ? data.topicProxy : "datei");
        counts.addProperty("ersatzbelege", proxyVerdicts);
        counts.addProperty("themenurteileAusDatei", topicVerdicts);
        return memory;
    }

    static String topicQuestion(ResearchMemory memory, Found topic) throws IOException {
        String id = "frage-" + topic.sheet;
        memory.addQuestion(ResearchQuestion.open(id, "Was sagen die Quellen zu „" + topic.name + "“?", topic.id,
                ResearchQuestion.Origin.FINDER, TIME));
        return id;
    }

    /** {passage id, source id, text} per stand-in evidence of a found topic. */
    static List<String[]> proxies(Found topic, String kind, Map<String, String> textOfPassage) {
        List<String[]> list = new ArrayList<String[]>();
        if ("stichprobe".equals(kind)) {
            for (String passage : topic.sample) {
                String text = textOfPassage.get(passage);
                String source = passage.contains("#") ? passage.substring(0, passage.indexOf('#')) : passage;
                list.add(new String[]{passage, source, text == null ? "Stichprobe zum Thema " + topic.sheet + ": "
                        + passage : text});
            }
            return list;
        }
        int sources = Math.max(0, topic.sources);
        int texts = "passagen".equals(kind) ? Math.max(sources, topic.passages) : sources;
        for (int i = 0; i < texts; i++) {
            // A text of its own per stand-in, long enough to be its own quote (shorter quotes never hold).
            String source = topic.sheet + "-q" + (sources == 0 ? 0 : i % sources);
            list.add(new String[]{topic.sheet + "-b" + i, source,
                    "Ersatzbeleg " + i + " zum Thema " + topic.sheet + " aus der Quelle " + source});
        }
        return list;
    }

    static EvidenceVerdict verdict(String questionId, Evidence e) {
        String passageText = e.text.isEmpty() ? e.quote : e.text;
        EvidenceVerdict.Kind kind;
        try {
            kind = EvidenceVerdict.Kind.valueOf(e.label);
        } catch (IllegalArgumentException unknown) {
            kind = EvidenceVerdict.Kind.FEHLER;
        }
        if (kind == EvidenceVerdict.Kind.FEHLER || passageText.isEmpty()) {
            return EvidenceVerdict.failed(questionId, e.passage, e.source, e.domain, passageText, "no verdict",
                    "referenz", 1, TIME);
        }
        String quote = !e.quote.isEmpty() && PassageText.holdsQuote(passageText, e.quote) ? e.quote : passageText;
        return EvidenceVerdict.judged(questionId, e.passage, e.source, e.domain, passageText, kind, quote, "",
                "referenz", 1, TIME);
    }

    // ------------------------------------------------------------------------------------------------- running

    /** The chooser with every answer kept, for {@code wahl.jsonl}. */
    static final class Recording implements HostChooser {
        final HostChooser delegate;
        final JsonArray choices = new JsonArray();

        Recording(HostChooser delegate) {
            this.delegate = delegate;
        }

        public Choice choose(Option topic, List<Option> candidates) {
            Choice choice = delegate.choose(topic, candidates);
            JsonObject line = new JsonObject();
            line.addProperty("thema", topic.getTopicId());
            line.addProperty("kandidaten", candidates.size());
            line.addProperty("wahl", choice.getTopicId());
            line.addProperty("fehler", choice.isFailed());
            JsonArray answers = new JsonArray();
            for (String answer : choice.getAnswers()) {
                answers.add(answer);
            }
            line.add("antworten", answers);
            choices.add(line);
            return choice;
        }

        public String modelName() {
            return delegate.modelName();
        }
    }

    /** Builds the memory and the plan, writes plan.json, plan.txt and result.json, returns the result. */
    static JsonObject run(Data data, HostChooser chooser, File out) throws IOException {
        JsonObject counts = new JsonObject();
        ResearchMemory memory = memory(data, counts);
        int target = (data.minWords + data.maxWords) / 2;
        Recording recording = chooser == null ? null : new Recording(chooser);
        SectionPlan plan = new SectionPlanner(memory).targetWords(target).hostChooser(recording).plan(TIME);

        Files.createDirectories(out.toPath());
        GsonBuilder pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
        Files.write(new File(out, "plan.json").toPath(), pretty.create().toJson(plan.toJson())
                .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "plan.txt").toPath(), text(plan).getBytes(StandardCharsets.UTF_8));
        if (recording != null) {
            StringBuilder lines = new StringBuilder();
            for (JsonElement line : recording.choices) {
                lines.append(line.toString()).append('\n');
            }
            Files.write(new File(out, "wahl.jsonl").toPath(), lines.toString().getBytes(StandardCharsets.UTF_8));
        }
        JsonObject result = criteria(plan, memory, data);
        if (chooser == null) {
            SectionPlan again = new SectionPlanner(memory(data, new JsonObject())).targetWords(target).plan(TIME);
            result.add("G7", criterion(plan.toJson().equals(again.toJson()) ? 1 : 0, 1,
                    plan.toJson().equals(again.toJson())));
        }
        for (Map.Entry<String, JsonElement> entry : counts.entrySet()) {
            result.add(entry.getKey(), entry.getValue());
        }
        result.addProperty("modell", chooser == null ? "" : chooser.modelName());
        result.addProperty("modellwahlen", recording == null ? 0 : recording.choices.size());
        Files.write(new File(out, "result.json").toPath(), pretty.create().toJson(result)
                .getBytes(StandardCharsets.UTF_8));
        return result;
    }

    /** The plan as an outline to read: chapters, sections, words, topics; then the topics left out. */
    static String text(SectionPlan plan) {
        StringBuilder text = new StringBuilder();
        text.append("Abschnittsplan: ").append(plan.getPlannedWords()).append(" Wörter, ")
                .append(plan.getSections().size()).append(" Abschnitte, ").append(plan.getChapters().size())
                .append(" Kapitel\n");
        int c = 0;
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            c++;
            text.append("\nKapitel ").append(c).append(": ").append(chapter.getTitle()).append(" (")
                    .append(chapter.getKind()).append(", ").append(chapter.getWords()).append(" Wörter")
                    .append(Double.isNaN(chapter.getShare()) ? "" : ", Anteil " + Math.round(chapter.getShare() * 100)
                            + " %").append(")\n");
            int s = 0;
            for (SectionPlan.Section section : chapter.getSections()) {
                s++;
                text.append("  ").append(c).append('.').append(s).append(' ').append(section.getTitle()).append(": ")
                        .append(section.getWords()).append(" Wörter, Gewicht ").append(section.getWeight())
                        .append(", ").append(section.getEvidence().size()).append(" Belege, ")
                        .append(section.getLeads().size()).append(" Hinweise\n");
                for (String topicId : section.getTopicIds()) {
                    if (!topicId.equals(section.getTopicId())) {
                        SectionPlan.TopicLine line = plan.topic(topicId);
                        text.append("      + ").append(line.getName()).append(" (Gewicht ").append(line.getWeight())
                                .append("): ").append(line.getPlacement().getReason()).append('\n');
                    }
                }
            }
        }
        text.append("\nNicht aufgenommen:\n");
        for (SectionPlan.TopicLine line : plan.getTopics()) {
            if (line.getPlacement().getFate() == Placement.Fate.NICHT_AUFGENOMMEN) {
                text.append("  - ").append(line.getName()).append(": ").append(line.getPlacement().getReason())
                        .append('\n');
            }
        }
        if (!plan.getNotes().isEmpty()) {
            text.append("\nVermerke:\n");
            for (String note : plan.getNotes()) {
                text.append("  - ").append(note).append('\n');
            }
        }
        return text.toString();
    }

    // ------------------------------------------------------------------------------------------------- criteria

    static JsonObject criteria(SectionPlan plan, ResearchMemory memory, Data data) {
        JsonObject result = new JsonObject();
        int sections = plan.getSections().size();
        result.add("G1", criterion(sections, data.maxSections, sections >= data.minSections
                && sections <= data.maxSections));
        int words = plan.getPlannedWords();
        result.add("G2", criterion(words, data.maxWords, words >= data.minWords && words <= data.maxWords));

        Map<String, Integer> lines = new HashMap<String, Integer>();
        for (SectionPlan.TopicLine line : plan.getTopics()) {
            lines.merge(line.getTopicId(), 1, Integer::sum);
        }
        int fates = 0;
        int all = 0;
        int inFence = 0;
        int inFencePlaced = 0;
        int outWithoutReason = 0;
        for (ResearchTopic topic : memory.topics()) {
            all++;
            fates += lines.getOrDefault(topic.getId(), 0) == 1 ? 1 : 0;
            TopicEvidence evidence = memory.evidenceOfTopic(topic.getId());
            Placement placement = plan.topic(topic.getId()) == null ? null
                    : plan.topic(topic.getId()).getPlacement();
            if (topic.getFence() == ResearchTopic.Fence.INNEN && evidence.getDirectCount() > 0) {
                inFence++;
                inFencePlaced += placement != null && placement.getFate() != Placement.Fate.NICHT_AUFGENOMMEN
                        ? 1 : 0;
            }
            if (placement != null && placement.getFate() == Placement.Fate.NICHT_AUFGENOMMEN
                    && placement.getReason().isEmpty()) {
                outWithoutReason++;
            }
        }
        JsonObject g3 = criterion(fates + inFencePlaced, all + inFence, fates == all && inFencePlaced == inFence
                && outWithoutReason == 0);
        g3.addProperty("themen", all);
        g3.addProperty("innenMitBelegen", inFence);
        g3.addProperty("innenMitBelegenImPlan", inFencePlaced);
        g3.addProperty("ohneGrund", outWithoutReason);
        result.add("G3", g3);

        int shortSections = 0;
        for (SectionPlan.Section section : plan.getSections()) {
            shortSections += section.getWords() < SectionPlanner.SECTION_FLOOR ? 1 : 0;
        }
        int cardChapters = 0;
        int chapters = 0;
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            if (chapter.getKind() == SectionPlan.ChapterKind.KARTE) {
                cardChapters++;
            }
            if (chapter.getKind() != SectionPlan.ChapterKind.SAMMEL) {
                chapters++;
            }
        }
        int cap = Math.max(cardChapters, Math.max(1, plan.getTargetWords() / SectionPlanner.CHAPTER_WORDS));
        JsonObject g4 = criterion(shortSections, 0, shortSections == 0 && chapters <= cap);
        g4.addProperty("kapitel", chapters);
        g4.addProperty("kapitelGrenze", cap);
        result.add("G4", g4);

        int violations = 0;
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            long lightestOwn = Long.MAX_VALUE;
            long heaviestHosted = Long.MIN_VALUE;
            for (SectionPlan.Section section : chapter.getSections()) {
                if (!section.getTopicId().equals(chapter.getRootTopicId())) {
                    lightestOwn = Math.min(lightestOwn, plan.topic(section.getTopicId()).getWeight());
                }
                for (String topicId : section.getTopicIds()) {
                    if (!topicId.equals(section.getTopicId())) {
                        heaviestHosted = Math.max(heaviestHosted, plan.topic(topicId).getWeight());
                    }
                }
            }
            if (lightestOwn != Long.MAX_VALUE && heaviestHosted > lightestOwn) {
                violations++;
            }
        }
        result.add("G5", criterion(violations, 0, violations == 0));

        if (!data.shares.isEmpty()) {
            int held = 0;
            int withShare = 0;
            for (SectionPlan.Chapter chapter : plan.getChapters()) {
                if (!Double.isNaN(chapter.getShare())) {
                    withShare++;
                    double wanted = chapter.getShare() * plan.getTargetWords();
                    held += Math.abs(chapter.getWords() - wanted) <= 0.01 * plan.getTargetWords() ? 1 : 0;
                }
            }
            result.add("G6", criterion(held, withShare, held == withShare));
        }

        JsonObject found = new JsonObject();
        int own = 0;
        int hosted = 0;
        int collected = 0;
        int foundChapters = 0;
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            foundChapters += chapter.getKind() == SectionPlan.ChapterKind.GEFUNDEN ? 1 : 0;
        }
        for (SectionPlan.TopicLine line : plan.getTopics()) {
            if (!"GEFUNDEN".equals(line.getOrigin())) {
                continue;
            }
            Placement placement = line.getPlacement();
            if (placement.getFate() == Placement.Fate.EIGENER_ABSCHNITT) {
                own++;
            } else if (placement.getFate() == Placement.Fate.AUFGEGANGEN_IN) {
                hosted++;
            }
            for (SectionPlan.Chapter chapter : plan.getChapters()) {
                if (chapter.getKind() == SectionPlan.ChapterKind.SAMMEL
                        && plan.section(placement.getSectionId()) != null
                        && chapter.getSections().contains(plan.section(placement.getSectionId()))) {
                    collected++;
                }
            }
        }
        found.addProperty("eigenerAbschnitt", own);
        found.addProperty("aufgegangen", hosted);
        found.addProperty("eigeneKapitel", foundChapters);
        found.addProperty("imSammelkapitel", collected);
        result.add("gefundeneThemen", found);

        JsonArray chapterList = new JsonArray();
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kapitelId", chapter.getId());
            entry.addProperty("art", chapter.getKind().name());
            entry.addProperty("woerter", chapter.getWords());
            entry.addProperty("abschnitte", chapter.getSections().size());
            chapterList.add(entry);
        }
        result.add("kapitel", chapterList);
        result.addProperty("abschnitte", sections);
        result.addProperty("geplanteWoerter", words);
        result.addProperty("zielwoerter", plan.getTargetWords());
        result.addProperty("vermerke", plan.getNotes().size());
        return result;
    }

    static JsonObject criterion(long value, long of, boolean passed) {
        JsonObject criterion = new JsonObject();
        criterion.addProperty("wert", value);
        criterion.addProperty("von", of);
        criterion.addProperty("bestanden", passed);
        return criterion;
    }

    static boolean passed(JsonObject result, String key) {
        return !result.has(key) || result.getAsJsonObject(key).get("bestanden").getAsBoolean();
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    public void storedStock() throws IOException {
        String fragen = System.getProperty(P + "fragen");
        String belege = System.getProperty(P + "belege");
        String out = System.getProperty(P + "out");
        Assume.assumeTrue("set " + P + "fragen, " + P + "belege and " + P + "out to run on the owner's machine",
                fragen != null && belege != null && out != null);
        Data data = new Data();
        readQuestions(read(fragen), data);
        data.evidence.addAll(readEvidence(read(belege), "frage"));
        String themen = System.getProperty(P + "themen");
        if (themen != null) {
            readTopics(read(themen), data);
        }
        String themenbelege = System.getProperty(P + "themenbelege", "quellen");
        if (new File(themenbelege).isFile()) {
            data.topicEvidence.addAll(readEvidence(read(themenbelege), "thema"));
        } else {
            data.topicProxy = themenbelege;
        }
        if (System.getProperty(P + "karten") != null) {
            readCards(System.getProperty(P + "karten"), data);
        }
        if (System.getProperty(P + "anteile") != null) {
            readShares(System.getProperty(P + "anteile"), data);
        }
        int[] words = range(System.getProperty(P + "woerter", "6000-9000"));
        data.minWords = words[0];
        data.maxWords = words[1];
        int[] sections = range(System.getProperty(P + "abschnitte", "12-18"));
        data.minSections = sections[0];
        data.maxSections = sections[1];
        HostChooser chooser = null;
        String model = System.getProperty(P + "chatModel");
        if (model != null && !model.trim().isEmpty()) {
            chooser = new ModelHostChooser(new OllamaStockChat(System.getProperty(P + "url",
                    "http://localhost:11434"), model.trim(), null, Boolean.FALSE, 120_000), true);
        }

        JsonObject result = run(data, chooser, new File(out));

        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
        for (String key : new String[]{"G1", "G2", "G3", "G4", "G5", "G6", "G7"}) {
            assertTrue(key + " failed: " + result.get(key), passed(result, key));
        }
    }

    @Test
    public void sampleFilesRunWithoutTheOwnersMachine() throws IOException {
        File folder = scratch.newFolder("probe");
        String fragen = "{\"bestand\": \"Probe\", \"themen\": {\"1\": \"Radverkehr\", \"2\": \"Fußverkehr\"},"
                + " \"fragen\": [{\"id\": \"F01\", \"thema\": 1, \"text\": \"Wo dürfen Radfahrer fahren?\","
                + " \"stand\": \"x\", \"beantwortbar\": true}, {\"id\": \"F02\", \"thema\": 2, \"text\": \"Wie breit"
                + " muss ein Gehweg sein?\"}]}";
        StringBuilder belege = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            belege.append("{\"frage\": \"F0").append(1 + i % 2).append("\", \"passage\": \"p").append(i)
                    .append("\", \"quelle\": \"q").append(i % 3).append("\", \"urteil\": \"BELEGT\", \"text\": \"Text ")
                    .append(i).append(": Radfahrer und Fußgänger teilen sich in der Stadt oft den Raum.\"}\n");
        }
        String themen = "{\"themen\": [{\"blatt\": \"T01\", \"topicId\": \"topic-a\", \"titel\": \"Lastenräder\","
                + " \"passagen\": 40, \"quellen\": 9, \"karte\": \"uuid-rad\", \"urteil\": \"INNEN\"},"
                + " {\"blatt\": \"T02\", \"topicId\": \"topic-b\", \"titel\": \"Autobahnbau\", \"passagen\": 30,"
                + " \"quellen\": 8, \"karte\": \"\", \"urteil\": \"AUSSEN\"},"
                + " {\"blatt\": \"T03\", \"topicId\": \"topic-c\", \"titel\": \"Gehwegparken\", \"passagen\": 6,"
                + " \"quellen\": 2, \"karte\": \"\", \"urteil\": \"INNEN\"},"
                + " {\"blatt\": \"T04\", \"topicId\": \"topic-d\", \"titel\": \"Werbung\", \"passagen\": 3,"
                + " \"quellen\": 1, \"karte\": \"\", \"urteil\": \"KEIN_THEMA\"}]}";
        Data data = new Data();
        readQuestions(fragen, data);
        data.evidence.addAll(readEvidence(belege.toString(), "frage"));
        readTopics(themen, data);
        readCards("uuid-rad=1", data);
        readShares("1=60", data);
        data.minWords = 1800;
        data.maxWords = 2200;
        data.minSections = 2;
        data.maxSections = 6;

        JsonObject result = run(data, null, folder);

        for (String key : new String[]{"G1", "G2", "G3", "G4", "G5", "G6", "G7"}) {
            assertTrue(key + " failed: " + result.get(key), passed(result, key));
        }
        SectionPlan plan = SectionPlan.fromJson(JsonParser.parseString(read(new File(folder, "plan.json")
                .getPath())).getAsJsonObject());
        // Card 1 holds its share and the bicycle topic under it; nothing ties the pavement topic to a chapter.
        assertEquals(Arrays.asList("kapitel-karte-1", "kapitel-karte-2", SectionPlanner.COLLECTION_ID),
                SectionPlannerTest.chapterIds(plan));
        assertEquals(1200, plan.getChapters().get(0).getWords());
        assertEquals("abschnitt-topic-a", plan.topic("topic-a").getPlacement().getSectionId());
        assertEquals(18L, plan.topic("topic-a").getWeight());
        assertEquals("abschnitt-topic-c", plan.topic("topic-c").getPlacement().getSectionId());
        assertEquals("außerhalb des Weidezauns: Urteil des Architekten",
                plan.topic("topic-b").getPlacement().getReason());
        assertEquals("außerhalb des Weidezauns: kein Thema (Urteil des Architekten)",
                plan.topic("topic-d").getPlacement().getReason());
        JsonObject found = result.getAsJsonObject("gefundeneThemen");
        assertEquals(2, found.get("eigenerAbschnitt").getAsInt());
        assertEquals(1, found.get("imSammelkapitel").getAsInt());
        assertEquals("quellen", result.get("themenbelege").getAsString());
        assertEquals(20, result.get("ersatzbelege").getAsInt());
        String files = read(new File(folder, "plan.json").getPath()) + read(new File(folder, "plan.txt").getPath())
                + read(new File(folder, "result.json").getPath());
        assertFalse("no passage text in the files", files.contains("teilen sich in der Stadt"));
        assertTrue(read(new File(folder, "plan.txt").getPath()).startsWith(
                "Abschnittsplan: 2000 Wörter, 4 Abschnitte, 3 Kapitel\n"));
        assertFalse(new File(folder, "wahl.jsonl").exists());

        // With a chooser the pavement topic goes to walking, and every answer lands in wahl.jsonl.
        File chosen = scratch.newFolder("wahl");
        HostChooser walking = new HostChooser() {
            public Choice choose(Option topic, List<Option> candidates) {
                for (Option candidate : candidates) {
                    if (candidate.getName().equals("Fußverkehr")) {
                        return Choice.of(candidate.getTopicId(), "Gehwege gehören zum Fußverkehr",
                                Arrays.asList("{\"wahl\": \"A2\"}"));
                    }
                }
                return Choice.none("nichts passt", null);
            }

            public String modelName() {
                return "skript";
            }
        };
        JsonObject withModel = run(data, walking, chosen);

        assertFalse("G7 needs a plan without a model", withModel.has("G7"));
        assertEquals(1, withModel.get("modellwahlen").getAsInt());
        SectionPlan second = SectionPlan.fromJson(JsonParser.parseString(read(new File(chosen, "plan.json")
                .getPath())).getAsJsonObject());
        assertEquals(Arrays.asList("kapitel-karte-1", "kapitel-karte-2"), SectionPlannerTest.chapterIds(second));
        assertEquals(Placement.Fate.AUFGEGANGEN_IN, second.topic("topic-c").getPlacement().getFate());
        assertEquals("abschnitt-karte-2", second.topic("topic-c").getPlacement().getSectionId());
        assertEquals("{\"thema\":\"topic-c\",\"kandidaten\":3,\"wahl\":\"karte-2\",\"fehler\":false,"
                + "\"antworten\":[\"{\\\"wahl\\\": \\\"A2\\\"}\"]}\n", read(new File(chosen, "wahl.jsonl").getPath()));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static String read(String path) throws IOException {
        String text = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    static JsonObject object(String line, int number) throws IOException {
        try {
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException unreadable) {
            throw new IOException("line " + number + " is no JSON object: " + unreadable.getMessage());
        }
    }

    static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }
}

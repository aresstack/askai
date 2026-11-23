package com.aresstack.askai.research.missionreport;

import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.PassageText;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Verdict;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The mission report on stored data of the book of 17 Nov, on the owner's machine; skipped without its files. The
 * sample tests run the same code on self-written files about city traffic in every build.
 * <p>
 * Inputs (the architect's test data; the report never sees "stand" or "beantwortbar"):
 * <ul>
 * <li>{@code fragen}: fragen-1711.json, the mind-map cards ({@code themen}) as concept topics and the 20
 * questions;</li>
 * <li>{@code belege}: one evidence verdict per line ({@code frage}, {@code passage}, {@code quelle}, {@code urteil},
 * optional {@code text}, {@code zitat}, {@code domain}): the architect's pairs belegpaare-1711.jsonl, or the
 * {@code urteile.jsonl} of a run of the evidence judge (component 6);</li>
 * <li>{@code saetze} (optional): saetze-1711-mit-text.jsonl; its rated pairs (S01-S60, with {@code urteil}) become
 * the text, one sentence per paragraph citing its passage, grouped by section; swapped pairs are left out;</li>
 * <li>{@code satzurteile} (optional): the {@code urteile.jsonl} of a run of the sentence check (component 11), whose
 * verdicts replace the architect's ({@code blatt}, {@code urteil});</li>
 * <li>{@code themen} (optional): erwartete-themen-1711.json, found topics with the architect's fence;</li>
 * <li>{@code quellen} (optional): quellen-1711.json, kind and legal space by domain;</li>
 * <li>{@code woerter} (optional): the length asked for, {@code 6000-9000} or {@code 7500}.</li>
 * </ul>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*MissionCheckStockTest*' \
 *   -Daskai.stock.mission.fragen=fragen-1711.json -Daskai.stock.mission.belege=belegpaare-1711.jsonl \
 *   -Daskai.stock.mission.saetze=saetze-1711-mit-text.jsonl -Daskai.stock.mission.themen=erwartete-themen-1711.json \
 *   -Daskai.stock.mission.quellen=quellen-1711.json -Daskai.stock.mission.out=&lt;folder&gt;
 * </pre>
 * Criteria proposed (the architect fixes them before the run): M1 every question listed once; M2 a question shows
 * "Belegt" exactly when the evidence file holds a BELEGT for it; M3 the report's own words hold no claim of
 * completion; M4 a card is "behandelt" exactly when a GEDECKT sentence cites a passage of the evidence or leads of
 * one of its questions, recounted from the files; M5 the sentence counts equal the verdicts of the file, so an
 * unchecked sentence never counts as covered. Report only: the questions the file marks not answerable that show
 * "Belegt", per card the covered sentences, the found topics, the kinds of the cited sources.
 * <p>
 * {@code <out>/result.json} holds ids and counts, {@code bericht.txt} and {@code bericht.json} the report (question
 * texts, card and topic names, counts); none holds a sentence, a quote or a passage.
 */
public class MissionCheckStockTest {

    private static final String P = "askai.stock.mission.";
    private static final long TIME = 1_763_800_000_000L;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Words the report's own wording must not use (M3). */
    static final String[] CLAIMS = {"erfüllt", "vollständig", "abgedeckt", "beantwortet", "alle ", "fertig",
            "ausreichend", "genügt"};

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    // ------------------------------------------------------------------------------------------------- data

    static final class Question {
        final String id;
        final String card;
        final String text;
        final boolean answerable;

        Question(String id, String card, String text, boolean answerable) {
            this.id = id;
            this.card = card;
            this.text = text;
            this.answerable = answerable;
        }
    }

    static final class Evidence {
        final String question;
        final String passage;
        final String source;
        final String label;
        final String text;
        final String quote;
        final String domain;

        Evidence(String question, String passage, String source, String label, String text, String quote,
                 String domain) {
            this.question = question;
            this.passage = passage;
            this.source = source;
            this.label = label;
            this.text = text;
            this.quote = quote;
            this.domain = domain;
        }
    }

    static final class Sample {
        final String id;
        final String sentence;
        final String passageId;
        final String passageText;
        final String label;
        final String section;
        final String domain;

        Sample(String id, String sentence, String passageId, String passageText, String label, String section,
               String domain) {
            this.id = id;
            this.sentence = sentence;
            this.passageId = passageId;
            this.passageText = passageText;
            this.label = label;
            this.section = section;
            this.domain = domain;
        }
    }

    static final class Data {
        final Map<String, String> cards = new LinkedHashMap<String, String>();
        final List<Question> questions = new ArrayList<Question>();
        final List<Evidence> evidence = new ArrayList<Evidence>();
        final List<Sample> samples = new ArrayList<Sample>();
        final Map<String, String> sentenceVerdicts = new HashMap<String, String>();
        boolean sentenceRun;
        final Map<String, ResearchTopic.Fence> topics = new LinkedHashMap<String, ResearchTopic.Fence>();
        final Map<String, MissionCheck.SourceInfo> sources = new HashMap<String, MissionCheck.SourceInfo>();
        int minWords;
        int maxWords;
    }

    /** fragen-1711.json: {@code themen} (number to card name) and {@code fragen} (id, thema, text, beantwortbar). */
    static void readQuestions(String json, Data data) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> card : root.getAsJsonObject("themen").entrySet()) {
            data.cards.put("karte-" + card.getKey(), card.getValue().getAsString());
        }
        for (JsonElement element : root.getAsJsonArray("fragen")) {
            JsonObject q = element.getAsJsonObject();
            JsonElement answerable = q.get("beantwortbar");
            data.questions.add(new Question(string(q, "id"), "karte-" + string(q, "thema"), string(q, "text"),
                    answerable == null || answerable.getAsBoolean()));
        }
    }

    static void readEvidence(String jsonl, Data data) throws IOException {
        int number = 0;
        for (String line : jsonl.split("\n")) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject e = object(line, number);
            String label = string(e, "urteil").toUpperCase(Locale.ROOT);
            if (string(e, "frage").isEmpty() || string(e, "passage").isEmpty() || label.isEmpty()) {
                throw new IOException("evidence line " + number + " needs frage, passage and urteil");
            }
            data.evidence.add(new Evidence(string(e, "frage"), string(e, "passage"), string(e, "quelle"), label,
                    string(e, "text"), string(e, "zitat"), string(e, "domain")));
        }
    }

    /** The rated pairs of saetze-1711-mit-text.jsonl (with {@code urteil}); swapped pairs are no sentences. */
    static void readSamples(String jsonl, Data data) throws IOException {
        int number = 0;
        for (String line : jsonl.split("\n")) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject s = object(line, number);
            if (!s.has("urteil")) {
                continue;
            }
            String id = string(s, "blatt").isEmpty() ? string(s, "id") : string(s, "blatt");
            if (id.isEmpty() || string(s, "sentence").isEmpty() || string(s, "passageId").isEmpty()) {
                throw new IOException("sentence line " + number + " needs blatt, sentence and passageId");
            }
            String section = string(s, "title").isEmpty() ? string(s, "section") : string(s, "title");
            data.samples.add(new Sample(id, string(s, "sentence"), string(s, "passageId"), string(s, "passageText"),
                    string(s, "urteil").toUpperCase(Locale.ROOT), section.isEmpty() ? "Stichprobe" : section,
                    string(s, "domain")));
        }
    }

    /** The {@code urteile.jsonl} of a sentence-check run: {@code blatt} and the checked {@code urteil}. */
    static void readSentenceVerdicts(String jsonl, Data data) throws IOException {
        int number = 0;
        for (String line : jsonl.split("\n")) {
            number++;
            if (!line.trim().isEmpty()) {
                JsonObject v = object(line, number);
                data.sentenceVerdicts.put(string(v, "blatt"), string(v, "urteil").toUpperCase(Locale.ROOT));
            }
        }
        data.sentenceRun = true;
    }

    /** erwartete-themen-1711.json: {@code themen} with {@code name} and the fence {@code erwartet}. */
    static void readTopics(String json, Data data) {
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject t = element.getAsJsonObject();
            String fence = string(t, "erwartet").toUpperCase(Locale.ROOT);
            data.topics.put(string(t, "name"), "AUSSEN".equals(fence) ? ResearchTopic.Fence.AUSSEN
                    : "UNKLAR".equals(fence) ? ResearchTopic.Fence.UNKLAR : ResearchTopic.Fence.INNEN);
        }
    }

    /** quellen-1711.json: {@code domains} with {@code domain}, {@code quellenart}, {@code rechtsraum}. */
    static void readSources(String json, Data data) {
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("domains")) {
            JsonObject d = element.getAsJsonObject();
            data.sources.put(string(d, "domain").toLowerCase(Locale.ROOT),
                    new MissionCheck.SourceInfo(string(d, "quellenart"), string(d, "rechtsraum")));
        }
    }

    // ------------------------------------------------------------------------------------------------- running

    /** Builds the report from the data, writes bericht.txt, bericht.json and result.json, returns the result. */
    static JsonObject run(Data data, File out) throws IOException {
        ResearchMemory memory = new ResearchMemory();
        for (Map.Entry<String, String> card : data.cards.entrySet()) {
            memory.addTopic(ResearchTopic.ofCard(card.getKey(), card.getValue(), card.getKey(), 0, TIME));
        }
        Set<String> known = new HashSet<String>();
        for (Question question : data.questions) {
            memory.addQuestion(ResearchQuestion.open(question.id, question.text, question.card,
                    ResearchQuestion.Origin.KONZEPT, TIME));
            known.add(question.id);
        }
        int withoutQuestion = 0;
        for (Evidence e : data.evidence) {
            if (!known.contains(e.question)) {
                withoutQuestion++;
                continue;
            }
            memory.record(verdict(e));
        }
        int topicNumber = 0;
        for (Map.Entry<String, ResearchTopic.Fence> topic : data.topics.entrySet()) {
            topicNumber++;
            memory.addTopic(new ResearchTopic(String.format("thema-%04d", topicNumber), topic.getKey(), "",
                    ResearchTopic.Origin.GEFUNDEN, "", null, null, topic.getValue(), "Erwartung des Architekten", 0,
                    "", TIME));
        }

        Map<String, List<Sample>> bySection = new LinkedHashMap<String, List<Sample>>();
        for (Sample sample : data.samples) {
            List<Sample> list = bySection.get(sample.section);
            if (list == null) {
                list = new ArrayList<Sample>();
                bySection.put(sample.section, list);
            }
            list.add(sample);
        }
        final Map<BookSection.Citation, Sample> sampleOf = new IdentityHashMap<BookSection.Citation, Sample>();
        List<BookSection> sections = new ArrayList<BookSection>();
        int sectionNumber = 0;
        for (Map.Entry<String, List<Sample>> entry : bySection.entrySet()) {
            sectionNumber++;
            StringBuilder text = new StringBuilder();
            List<BookSection.Citation> citations = new ArrayList<BookSection.Citation>();
            int marker = 0;
            for (Sample sample : entry.getValue()) {
                marker++;
                BookSection.Citation citation = new BookSection.Citation(marker, sample.passageId,
                        sample.domain.isEmpty() ? sample.passageId : sample.domain.toLowerCase(Locale.ROOT),
                        sample.passageText);
                sampleOf.put(citation, sample);
                citations.add(citation);
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(withMarker(SentenceVerdict.plain(sample.sentence), marker));
            }
            sections.add(new BookSection("abschnitt-" + sectionNumber, entry.getKey(), 0, text.toString(),
                    citations));
        }
        final Data in = data;
        SentenceLookup lookup = (sentence, cited) -> {
            Sample sample = sampleOf.get(cited.get(0));
            String label = sample == null ? "" : in.sentenceRun ? in.sentenceVerdicts.getOrDefault(sample.id, "")
                    : sample.label;
            return new SentenceLookup.State(verdict(label), false, false);
        };
        MissionReport report = new MissionCheck(memory).sections(sections).sentences(lookup).sources(data.sources)
                .requested(data.minWords, data.maxWords).report(System.currentTimeMillis());

        Files.createDirectories(out.toPath());
        String text = report.text(ZONE);
        Files.write(new File(out, "bericht.txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "bericht.json").toPath(), new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(report.toJson()).getBytes(StandardCharsets.UTF_8));
        JsonObject result = criteria(report, data);
        result.addProperty("belegeOhneFrage", withoutQuestion);
        result.addProperty("satzurteileAusLauf", data.sentenceRun);
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(result).getBytes(StandardCharsets.UTF_8));
        return result;
    }

    static EvidenceVerdict verdict(Evidence e) {
        String passageText = e.text.isEmpty() ? e.quote : e.text;
        EvidenceVerdict.Kind kind;
        try {
            kind = EvidenceVerdict.Kind.valueOf(e.label);
        } catch (IllegalArgumentException unknown) {
            kind = EvidenceVerdict.Kind.FEHLER;
        }
        if (kind == EvidenceVerdict.Kind.FEHLER || passageText.isEmpty()) {
            return EvidenceVerdict.failed(e.question, e.passage, e.source, e.domain, passageText, "no verdict",
                    "referenz", 1, TIME);
        }
        String quote = !e.quote.isEmpty() && PassageText.holdsQuote(passageText, e.quote) ? e.quote : passageText;
        return EvidenceVerdict.judged(e.question, e.passage, e.source, e.domain, passageText, kind, quote, "",
                "referenz", 1, TIME);
    }

    static Verdict verdict(String label) {
        try {
            return label.isEmpty() ? null : Verdict.valueOf(label);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** The sentence with its marker before its closing mark: {@code "Ein Satz [3]."}. */
    static String withMarker(String sentence, int marker) {
        String text = sentence.trim();
        int end = text.length();
        while (end > 0 && ".!?…\"“”»'’)".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end) + " [" + marker + "]" + text.substring(end);
    }

    // ------------------------------------------------------------------------------------------------- criteria

    /** M1 to M5 recounted from the files, and what is only reported. */
    static JsonObject criteria(MissionReport report, Data data) {
        JsonObject criteria = new JsonObject();

        Map<String, Integer> listed = new HashMap<String, Integer>();
        Map<String, MissionReport.QuestionLine> lines = new HashMap<String, MissionReport.QuestionLine>();
        for (MissionReport.QuestionLine line : report.getQuestions()) {
            String id = line.getQuestion().getId();
            listed.put(id, listed.getOrDefault(id, 0) + 1);
            lines.put(id, line);
        }
        int listedOnce = 0;
        for (Question question : data.questions) {
            listedOnce += listed.getOrDefault(question.id, 0) == 1 ? 1 : 0;
        }
        criteria.add("M1", criterion(listedOnce, data.questions.size(), listedOnce == data.questions.size()
                && report.getQuestions().size() == data.questions.size()));

        Map<String, Set<String>> keysOf = new HashMap<String, Set<String>>();
        Set<String> withDirect = new HashSet<String>();
        for (Evidence e : data.evidence) {
            EvidenceVerdict verdict = verdict(e);
            if (verdict.getKind() == EvidenceVerdict.Kind.BELEGT) {
                withDirect.add(e.question);
            }
            if (verdict.getKind() == EvidenceVerdict.Kind.BELEGT || verdict.getKind() == EvidenceVerdict.Kind.HINWEIS) {
                Set<String> keys = keysOf.get(e.question);
                if (keys == null) {
                    keys = new HashSet<String>();
                    keysOf.put(e.question, keys);
                }
                keys.addAll(MissionCheck.keysOf(e.passage, e.text.isEmpty() ? e.quote : e.text));
            }
        }
        int agree = 0;
        JsonArray notAnswerable = new JsonArray();
        for (Question question : data.questions) {
            MissionReport.QuestionLine line = lines.get(question.id);
            boolean direct = line != null && line.getEvidence().getDirectCount() > 0;
            agree += direct == withDirect.contains(question.id) ? 1 : 0;
            if (!question.answerable) {
                JsonObject q = new JsonObject();
                q.addProperty("frage", question.id);
                q.addProperty("stand", line == null ? "fehlt" : direct ? "BELEGT"
                        : line.getEvidence().getLeadCount() > 0 ? "HINWEIS" : "KEIN_BELEG");
                notAnswerable.add(q);
            }
        }
        criteria.add("M2", criterion(agree, data.questions.size(), agree == data.questions.size()));

        String own = report.text(ZONE);
        for (Question question : data.questions) {
            own = own.replace(question.text, "");
        }
        for (String name : data.cards.values()) {
            own = own.replace(name, "");
        }
        for (String name : data.topics.keySet()) {
            own = own.replace(name, "");
        }
        JsonArray claims = new JsonArray();
        String lower = own.toLowerCase(Locale.GERMANY);
        for (String claim : CLAIMS) {
            if (lower.contains(claim)) {
                claims.add(claim.trim());
            }
        }
        JsonObject m3 = criterion(claims.size(), 0, claims.size() == 0);
        m3.add("funde", claims);
        criteria.add("M3", m3);

        Map<String, Integer> coveredPerCard = new LinkedHashMap<String, Integer>();
        for (String card : data.cards.keySet()) {
            Set<String> keys = new HashSet<String>();
            for (Question question : data.questions) {
                if (question.card.equals(card) && keysOf.containsKey(question.id)) {
                    keys.addAll(keysOf.get(question.id));
                }
            }
            int covered = 0;
            for (Sample sample : data.samples) {
                String label = data.sentenceRun ? data.sentenceVerdicts.getOrDefault(sample.id, "") : sample.label;
                if ("GEDECKT".equals(label) && !Collections.disjoint(keys,
                        MissionCheck.keysOf(sample.passageId, sample.passageText))) {
                    covered++;
                }
            }
            coveredPerCard.put(card, covered);
        }
        int cardsAgree = 0;
        JsonArray cards = new JsonArray();
        for (MissionReport.TopicLine line : report.getConceptTopics()) {
            int expected = coveredPerCard.getOrDefault(line.getTopic().getId(), -1);
            boolean same = expected == line.getCounts().getCovered() && line.isTreated() == expected > 0;
            cardsAgree += same ? 1 : 0;
            JsonObject card = new JsonObject();
            card.addProperty("karte", line.getTopic().getId());
            card.addProperty("behandelt", line.isTreated());
            card.addProperty("gedeckteSaetze", line.getCounts().getCovered());
            card.addProperty("nachgezaehlt", expected);
            card.addProperty("verlinkteSaetze", line.getCounts().getSentences());
            cards.add(card);
        }
        criteria.add("M4", criterion(cardsAgree, data.cards.size(), cardsAgree == data.cards.size()
                && report.getConceptTopics().size() == data.cards.size()));

        Map<String, Integer> labels = new HashMap<String, Integer>();
        for (Sample sample : data.samples) {
            String label = data.sentenceRun ? data.sentenceVerdicts.getOrDefault(sample.id, "") : sample.label;
            Verdict verdict = verdict(label);
            String key = verdict == null ? "UNGEPRUEFT" : verdict.name();
            labels.put(key, labels.getOrDefault(key, 0) + 1);
        }
        MissionReport.Counts counts = report.getSentences();
        boolean sameCounts = counts.getCovered() == labels.getOrDefault("GEDECKT", 0)
                && counts.getPartial() == labels.getOrDefault("TEILWEISE", 0)
                && counts.getNotCovered() == labels.getOrDefault("NICHT_GEDECKT", 0)
                && counts.getUnchecked() == labels.getOrDefault("UNGEPRUEFT", 0);
        JsonObject m5 = criterion(counts.getCovered(), labels.getOrDefault("GEDECKT", 0), sameCounts);
        m5.add("saetze", counts.toJson());
        criteria.add("M5", m5);

        boolean passed = true;
        for (String name : new String[]{"M1", "M2", "M3", "M4", "M5"}) {
            passed &= criteria.getAsJsonObject(name).get("erfuellt").getAsBoolean();
        }
        JsonObject result = new JsonObject();
        result.addProperty("bestanden", passed);
        result.add("kriterien", criteria);
        JsonObject only = new JsonObject();
        only.add("nichtBeantwortbareFragen", notAnswerable);
        only.add("karten", cards);
        JsonObject stands = new JsonObject();
        for (MissionReport.QuestionLine line : report.getQuestions()) {
            stands.addProperty(line.getQuestion().getId(), line.getEvidence().getDirectCount() > 0 ? "BELEGT"
                    : line.getEvidence().getLeadCount() > 0 ? "HINWEIS" : "KEIN_BELEG");
        }
        only.add("fragen", stands);
        Map<String, Integer> fates = new LinkedHashMap<String, Integer>();
        for (MissionReport.TopicLine line : report.getFoundTopics()) {
            String fate = line.getPlacement() != null ? line.getPlacement().getFate().name()
                    : "OHNE_VERBLEIB_" + line.getTopic().getFence().name();
            fates.put(fate, fates.getOrDefault(fate, 0) + 1);
        }
        JsonObject found = new JsonObject();
        for (Map.Entry<String, Integer> fate : fates.entrySet()) {
            found.addProperty(fate.getKey(), fate.getValue());
        }
        only.add("gefundeneThemen", found);
        only.add("quellenarten", report.toJson().getAsJsonObject("quellenarten"));
        only.add("saetze", report.toJson().getAsJsonObject("saetze"));
        result.add("nurBericht", only);
        return result;
    }

    private static JsonObject criterion(int value, int target, boolean met) {
        JsonObject json = new JsonObject();
        json.addProperty("wert", value);
        json.addProperty("ziel", target);
        json.addProperty("erfuellt", met);
        return json;
    }

    // ------------------------------------------------------------------------------------------------- the stock

    @Test
    public void theMissionReportOnTheBookOf17Nov() throws Exception {
        String questions = System.getProperty(P + "fragen", "").trim();
        String evidence = System.getProperty(P + "belege", "").trim();
        String out = System.getProperty(P + "out", "").trim();
        Assume.assumeTrue("needs -D" + P + "fragen, -D" + P + "belege and -D" + P + "out",
                !questions.isEmpty() && !evidence.isEmpty() && !out.isEmpty());
        Data data = new Data();
        readQuestions(read(questions), data);
        readEvidence(read(evidence), data);
        String samples = System.getProperty(P + "saetze", "").trim();
        if (!samples.isEmpty()) {
            readSamples(read(samples), data);
        }
        String verdicts = System.getProperty(P + "satzurteile", "").trim();
        if (!verdicts.isEmpty()) {
            readSentenceVerdicts(read(verdicts), data);
        }
        String topics = System.getProperty(P + "themen", "").trim();
        if (!topics.isEmpty()) {
            readTopics(read(topics), data);
        }
        String sources = System.getProperty(P + "quellen", "").trim();
        if (!sources.isEmpty()) {
            readSources(read(sources), data);
        }
        words(System.getProperty(P + "woerter", "").trim(), data);
        JsonObject result = run(data, new File(out));
        System.out.println("[mission-report stock] " + result.getAsJsonObject("kriterien"));
        assertTrue("criteria not met, see " + new File(out, "result.json"), result.get("bestanden").getAsBoolean());
    }

    static void words(String value, Data data) {
        if (value.isEmpty()) {
            return;
        }
        String[] parts = value.split("-");
        data.minWords = Integer.parseInt(parts[0].trim());
        data.maxWords = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : data.minWords;
    }

    // ------------------------------------------------------------------------------------------------- samples

    static final String QUESTIONS = "{\"themen\":{\"1\":\"Tempo 30\",\"2\":\"Radwege\",\"3\":\"Parken\"},"
            + "\"fragen\":["
            + "{\"id\":\"F01\",\"thema\":\"1\",\"text\":\"Wie wirkt Tempo 30 auf die Zahl der Unfälle?\","
            + "\"stand\":\"x\",\"beantwortbar\":true},"
            + "{\"id\":\"F02\",\"thema\":\"2\",\"text\":\"Welche Radwege trennen Rad- und Autoverkehr sicher?\","
            + "\"beantwortbar\":true},"
            + "{\"id\":\"F03\",\"thema\":\"3\",\"text\":\"Was kostet Anwohnerparken in deutschen Städten?\","
            + "\"beantwortbar\":false},"
            + "{\"id\":\"F04\",\"thema\":\"3\",\"text\":\"Wie wirkt der Wegfall von Parkplätzen auf den Handel?\","
            + "\"beantwortbar\":false}]}";

    static final String[][] EVIDENCE = {
            {"F01", "p1", "q1", "BELEGT", MissionCheckTest.P1, "amt.example"},
            {"F01", "p4", "q4", "KEIN", MissionCheckTest.P4, "kanzlei.example"},
            {"F02", "p2", "q2", "BELEGT", MissionCheckTest.P2, "verband.example"},
            {"F03", "p3", "q3", "HINWEIS", MissionCheckTest.P3, "rat.example"},
            {"F04", "p6", "q6", "KEIN", MissionCheckTest.P6, "hersteller.example"},
            {"F99", "p5", "q5", "BELEGT", MissionCheckTest.P5, "schule.example"}};

    static final String[][] SAMPLES = {
            {"S01", "In Tempo-30-Zonen sank die Zahl der Unfälle um 25 Prozent [12].", "p1", "GEDECKT",
                    "Tempo 30 in der Stadt", "amt.example", MissionCheckTest.P1},
            {"S02", "Die Stadt plant weitere Zonen [3, 5].", "p1", "TEILWEISE", "Tempo 30 in der Stadt",
                    "amt.example", MissionCheckTest.P1},
            {"S03", "Geschützte Radwege trennen Rad- und Autoverkehr, z. B. durch Poller [7].", "p2", "GEDECKT",
                    "Radwege", "verband.example", MissionCheckTest.P2},
            {"S04", "Lieferwagen halten oft in zweiter Reihe [9].", "p6", "NICHT_GEDECKT", "Radwege",
                    "hersteller.example", MissionCheckTest.P6}};

    static Data sampleData(boolean withSamples) throws IOException {
        Data data = new Data();
        readQuestions(QUESTIONS, data);
        StringBuilder evidence = new StringBuilder();
        for (String[] row : EVIDENCE) {
            JsonObject line = new JsonObject();
            line.addProperty("frage", row[0]);
            line.addProperty("passage", row[1]);
            line.addProperty("quelle", row[2]);
            line.addProperty("urteil", row[3]);
            line.addProperty("text", row[4]);
            line.addProperty("domain", row[5]);
            evidence.append(line).append('\n');
        }
        readEvidence(evidence.toString(), data);
        if (withSamples) {
            StringBuilder samples = new StringBuilder();
            for (String[] row : SAMPLES) {
                JsonObject line = new JsonObject();
                line.addProperty("blatt", row[0]);
                line.addProperty("sentence", row[1]);
                line.addProperty("passageId", row[2]);
                line.addProperty("urteil", row[3]);
                line.addProperty("title", row[4]);
                line.addProperty("domain", row[5]);
                line.addProperty("passageText", row[6]);
                samples.append(line).append('\n');
            }
            JsonObject swapped = new JsonObject();
            swapped.addProperty("blatt", "V01");
            swapped.addProperty("sentence", "Ein vertauschter Satz [1].");
            swapped.addProperty("passageId", "p3");
            swapped.addProperty("erwartet", "NICHT_GEDECKT");
            samples.append(swapped).append('\n');
            readSamples(samples.toString(), data);
        }
        readTopics("{\"themen\":[{\"name\":\"Schulwege\",\"erwartet\":\"INNEN\",\"blaetter\":[]},"
                + "{\"name\":\"Fahrradhelme im Ausland\",\"erwartet\":\"AUSSEN\",\"blaetter\":[]}]}", data);
        readSources("{\"domains\":[{\"domain\":\"amt.example\",\"zitate\":3,\"quellenart\":"
                + "\"BEHOERDE_UNFALLVERSICHERUNG\",\"rechtsraum\":\"DE\",\"eigeninteresse\":\"NEIN\"},"
                + "{\"domain\":\"verband.example\",\"zitate\":1,\"quellenart\":\"VERBAND\",\"rechtsraum\":\"DE\","
                + "\"eigeninteresse\":\"JA\"}]}", data);
        words("6000-9000", data);
        return data;
    }

    @Test
    public void onTheSampleTheReportPassesListsTheGapsAndWritesNoPassage() throws Exception {
        File out = scratch.newFolder("aus");
        JsonObject result = run(sampleData(true), out);
        assertTrue(result.toString(), result.get("bestanden").getAsBoolean());
        assertEquals("the evidence of a question not in the file is left out", 1,
                result.get("belegeOhneFrage").getAsInt());
        JsonObject only = result.getAsJsonObject("nurBericht");
        assertEquals("HINWEIS", only.getAsJsonObject("fragen").get("F03").getAsString());
        assertEquals("KEIN_BELEG", only.getAsJsonObject("fragen").get("F04").getAsString());
        assertEquals(2, only.getAsJsonArray("nichtBeantwortbareFragen").size());
        JsonArray cards = only.getAsJsonArray("karten");
        assertTrue("Tempo 30: S01 covers p1", cards.get(0).getAsJsonObject().get("behandelt").getAsBoolean());
        assertEquals(2, cards.get(0).getAsJsonObject().get("verlinkteSaetze").getAsInt());
        assertTrue("Radwege: S03", cards.get(1).getAsJsonObject().get("behandelt").getAsBoolean());
        assertFalse("Parken: only a lead, no sentence", cards.get(2).getAsJsonObject().get("behandelt")
                .getAsBoolean());
        assertEquals(1, only.getAsJsonObject("gefundeneThemen").get("OHNE_VERBLEIB_AUSSEN").getAsInt());
        assertEquals(2, only.getAsJsonObject("saetze").get("gedeckt").getAsInt());

        String report = new String(Files.readAllBytes(new File(out, "bericht.txt").toPath()), StandardCharsets.UTF_8);
        assertTrue(report, report.contains("  F04 Wie wirkt der Wegfall von Parkplätzen auf den Handel? [Parken]: Kein "
                + "Beleg gefunden (1 Passage geprüft). Im Text zitiert: 0 Sätze.\n"));
        assertTrue(report, report.contains("  Tempo 30: behandelt, 1 gedeckter Satz (1 teilweise, 0 nicht gedeckt, 0 "
                + "nicht geprüft). Kein Verbleib im Abschnittsplan vermerkt."));
        String files = report + new String(Files.readAllBytes(new File(out, "bericht.json").toPath()),
                StandardCharsets.UTF_8) + new String(Files.readAllBytes(new File(out, "result.json").toPath()),
                StandardCharsets.UTF_8);
        for (String passage : new String[]{MissionCheckTest.P1, MissionCheckTest.P2, MissionCheckTest.P6}) {
            assertFalse("no passage text in the output", files.contains(passage.substring(0, 30)));
        }
        assertFalse("no sentence in the output", files.contains("zweiter Reihe"));
        assertFalse("the report never sees beantwortbar or stand", report.contains("beantwortbar"));
    }

    @Test
    public void aSentenceCheckRunReplacesTheArchitectsVerdictsAndAMissingOneIsUnchecked() throws Exception {
        Data data = sampleData(true);
        readSentenceVerdicts("{\"blatt\":\"S01\",\"urteil\":\"TEILWEISE\"}\n{\"blatt\":\"S03\",\"urteil\":\"\"}\n"
                + "{\"blatt\":\"S04\",\"urteil\":\"NICHT_GEDECKT\"}\n", data);
        JsonObject result = run(data, scratch.newFolder("aus"));
        assertTrue(result.toString(), result.get("bestanden").getAsBoolean());
        JsonObject sentences = result.getAsJsonObject("nurBericht").getAsJsonObject("saetze");
        assertEquals(0, sentences.get("gedeckt").getAsInt());
        assertEquals("S03 failed, S02 has no verdict in the run", 2, sentences.get("nichtGeprueft").getAsInt());
        assertFalse(result.getAsJsonObject("nurBericht").getAsJsonArray("karten").get(0).getAsJsonObject()
                .get("behandelt").getAsBoolean());
    }

    @Test
    public void theCriteriaCatchAReportThatDoesNotFitTheFiles() throws Exception {
        Data data = sampleData(true);
        File out = scratch.newFolder("aus");
        run(data, out);
        Data other = sampleData(true);
        other.evidence.add(new Evidence("F03", "p9", "q9", "BELEGT", MissionCheckTest.P5, "", "schule.example"));
        other.questions.add(new Question("F05", "karte-1", "Wer prüft die Zonen?", true));
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("karte-1", "Tempo 30", "karte-1", 0, TIME));
        memory.addQuestion(ResearchQuestion.open("F01", "Wie wirkt Tempo 30 auf die Zahl der Unfälle?", "karte-1",
                ResearchQuestion.Origin.KONZEPT, TIME));
        MissionReport thin = new MissionCheck(memory).report(TIME);
        JsonObject criteria = criteria(thin, other).getAsJsonObject("kriterien");
        assertFalse("questions missing", criteria.getAsJsonObject("M1").get("erfuellt").getAsBoolean());
        assertFalse("F01 and F02 have BELEGT in the file", criteria.getAsJsonObject("M2").get("erfuellt")
                .getAsBoolean());
        assertFalse("cards missing", criteria.getAsJsonObject("M4").get("erfuellt").getAsBoolean());
        assertFalse("no sentence counted", criteria.getAsJsonObject("M5").get("erfuellt").getAsBoolean());
        assertTrue(criteria.getAsJsonObject("M3").get("erfuellt").getAsBoolean());
        assertEquals("Ein Satz [3].", withMarker("Ein Satz.", 3));
        assertEquals("Wer zahlt [4]?“", withMarker("Wer zahlt?“", 4));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static JsonObject object(String line, int number) throws IOException {
        try {
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException bad) {
            throw new IOException("line " + number + " is no JSON object");
        }
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}

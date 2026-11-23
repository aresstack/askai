package com.aresstack.askai.research.contradictions;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Candidates;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Judgement;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Options;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Pair;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.memory.Contradiction;
import com.aresstack.askai.research.memory.Contradiction.Scope;
import com.aresstack.askai.research.memory.Contradiction.Side;
import com.aresstack.askai.research.memory.Contradiction.Verdict;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.PassageText;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Component 17 on stored data, on the owner's machine. Two runs, each skipped without its files; the sample tests run
 * the same paths on self-written data with a scripted model in every build.
 *
 * <p>{@link #storedPairs()}: the check against labeled pairs, each judged as in the product (one call, one repair,
 * thinking on). Criteria, each a property: W1 at least {@code .minGefunden} (0.70) of the pairs labeled WIDERSPRUCH are
 * judged WIDERSPRUCH, on all pairs and on the natural ones; W2 at most {@code .maxVereinbarAlsWiderspruch} (0.15) of
 * the pairs labeled VEREINBAR are judged WIDERSPRUCH; W3 at most {@code .maxUnverbundenAlsWiderspruch} (0.05) of the
 * pairs labeled UNVERBUNDEN are judged WIDERSPRUCH; W4 at most {@code .maxFehler} (0.02) of all pairs end as FEHLER.
 * A criterion needs a minimum of pairs of its label, else it is not measurable and fails: {@code .minAnzahlWiderspruch}
 * (12), {@code .minAnzahlVereinbar} (20), {@code .minAnzahlUnverbunden} (20). W1 on the natural pairs decides from
 * {@code .minNatuerlicheWidersprueche} (5) natural WIDERSPRUCH pairs on and is reported only below that. Natural and
 * built pairs ({@code "herkunft":"GEBAUT"}) are reported apart; UNKLAR has no criterion, {@code unklarAlsWiderspruch}
 * counts how often the model calls such a pair WIDERSPRUCH. Several models run one after the other
 * ({@code .chatModel=gemma4:12b,gemma4:e2b}); the first decides.</p>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*ContradictionStockTest*'
 *   -Daskai.stock.contradictions.pairs=widerspruchspaare.jsonl (one pair per line, see {@link #readPairs})
 *   -Daskai.stock.contradictions.chatUrl=http://127.0.0.1:11434 -Daskai.stock.contradictions.chatModel=gemma4:12b
 *   -Daskai.stock.contradictions.corpus=replay-corpus.json (mission, language, passage texts for the context)
 *   -Daskai.stock.contradictions.out=build/component-stock/contradictions
 *   optional: .missionFile (UTF-8 text instead of the corpus' mission), .minGefunden, .maxVereinbarAlsWiderspruch,
 *             .maxUnverbundenAlsWiderspruch, .maxFehler, .minAnzahlWiderspruch, .minAnzahlVereinbar,
 *             .minAnzahlUnverbunden, .minNatuerlicheWidersprueche, .parallel (2), .antwortTokens (6144),
 *             .umfeldZeichen (1200; 0 shows the quotes only), .numCtx (16384; "omit" sends none), .timeoutSeconds
 *             (300), .limit (0 = all)
 * </pre>
 *
 * <p>{@link #storedCandidates()}: the candidates the product would put forward on a judged stock: the final BELEGT of
 * a component 6 stock run ({@code urteile.jsonl}) go into a memory with the questions and topics of
 * {@code fragen-1711.json} (only id, text and topic are read), bge-m3 sorts, the check picks. With {@code .chatModel}
 * it judges them too. {@code kandidaten.jsonl} holds each candidate in the form of the labeled pairs without a label
 * and without the model's verdict (for labeling blind), {@code modell-urteile.jsonl} the model's verdicts.</p>
 * <pre>
 *   -Daskai.stock.contradictions.verdicts=urteile.jsonl -Daskai.stock.contradictions.questions=fragen-1711.json
 *   -Daskai.stock.contradictions.corpus=replay-corpus.json -Daskai.stock.contradictions.embedUrl=http://127.0.0.1:11434
 *   optional: .embedModel (bge-m3), .embedDim (1024), .paareJeBereich (6), .chatUrl/.chatModel (judge them)
 * </pre>
 * {@code result.json} holds ids and counts, no quote, no passage text, no question text and no reason of the model.
 * The other files carry words of the sources and stay on the machine.
 */
public class ContradictionStockTest {

    private static final String P = "askai.stock.contradictions.";
    /** UTF-8 read as Latin-1 ("Ã¤", "â€", "Â§"): a damaged copy of the corpus looks like this. */
    static final Pattern MOJIBAKE = Pattern.compile("\\u00C3[\\u0080-\\u00BF]|\\u00E2\\u20AC|\\u00C2[\\u00A0-\\u00BF]");
    static final List<String> LABELS = Arrays.asList("WIDERSPRUCH", "VEREINBAR", "UNVERBUNDEN", "UNKLAR");

    @Rule
    public TemporaryFolder scratch = new TemporaryFolder();

    // ------------------------------------------------------------------------------------------------- data

    /** One labeled pair of the file. */
    static final class LabeledPair {
        final String id;
        final Pair pair;
        final String expected;
        final String expectedDifference;
        final Map<String, String> contexts;
        /** A built pair: quote B changed on purpose at a number or a statement ({@code "herkunft":"GEBAUT"}). */
        final boolean built;

        LabeledPair(String id, Pair pair, String expected, String expectedDifference, Map<String, String> contexts,
                    boolean built) {
            this.id = id;
            this.pair = pair;
            this.expected = expected;
            this.expectedDifference = expectedDifference;
            this.contexts = contexts;
            this.built = built;
        }
    }

    /** Mission, language and passages of a corpus in the format of the outline replay (vectors skipped). */
    static final class Corpus {
        String mission = "";
        String language = "de";
        final Map<String, String> texts = new HashMap<String, String>();
        final Map<String, String> sources = new HashMap<String, String>();
        final Map<String, String> domains = new HashMap<String, String>();
    }

    /** Criteria and how the run works. */
    static final class Settings {
        List<String> models = Collections.singletonList("gemma4:12b");
        Options options = Options.defaults();
        double minFound = 0.70;
        double maxCompatibleAsContradiction = 0.15;
        double maxUnrelatedAsContradiction = 0.05;
        double maxErrors = 0.02;
        /** Fewer pairs of a label make its criterion not measurable (architect, 22.11.). */
        int minContradictions = 12;
        int minCompatible = 20;
        int minUnrelated = 20;
        /** W1 on the natural pairs decides from this many natural WIDERSPRUCH pairs; below it is reported only. */
        int minNatural = 5;
        int limit;
    }

    /**
     * One pair per line: {@code {"id":"W01","art":"FRAGE","frage":"...","a":{...},"b":{...},"erwartet":"..."}}, or
     * with {@code "art":"THEMA"} the fields {@code "thema"}, {@code "frageA"} and {@code "frageB"} instead of
     * {@code "frage"}. A side is {@code {"passageId":"...","quelle":"address or domain","zitat":"...","umfeld":"..."}}
     * ({@code "umfeld"} optional; without it the corpus gives the passage). {@code "erwartet"} is WIDERSPRUCH,
     * VEREINBAR, UNVERBUNDEN or UNKLAR; {@code "unterschied"} (optional) what separates a VEREINBAR pair;
     * {@code "herkunft"} (optional) NATUERLICH (the default: two passages of the stock) or GEBAUT (quote B changed on
     * purpose at a number or a statement). Side B of a built pair never takes its context from the corpus, which would
     * show the unchanged statement; only its own {@code "umfeld"} counts.
     */
    static List<LabeledPair> readPairs(List<String> lines) {
        List<LabeledPair> pairs = new ArrayList<LabeledPair>();
        int number = 0;
        for (String line : lines) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject row;
            try {
                row = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException unreadable) {
                throw new IllegalArgumentException("line " + number + " is no JSON object");
            }
            String id = text(row, "id");
            String expected = text(row, "erwartet").toUpperCase(Locale.ROOT);
            if (id.isEmpty() || !LABELS.contains(expected)) {
                throw new IllegalArgumentException("line " + number + ": needs \"id\" and \"erwartet\" (one of "
                        + LABELS + ")");
            }
            Scope scope = "THEMA".equalsIgnoreCase(text(row, "art")) ? Scope.THEMA : Scope.FRAGE;
            JsonObject a = row.getAsJsonObject("a");
            JsonObject b = row.getAsJsonObject("b");
            if (a == null || b == null || text(a, "zitat").isEmpty() || text(b, "zitat").isEmpty()) {
                throw new IllegalArgumentException("pair " + id + ": both sides need a \"zitat\"");
            }
            String origin = text(row, "herkunft").toUpperCase(Locale.ROOT);
            if (!origin.isEmpty() && !"NATUERLICH".equals(origin) && !"GEBAUT".equals(origin)) {
                throw new IllegalArgumentException("pair " + id + ": \"herkunft\" is NATUERLICH or GEBAUT");
            }
            Map<String, String> contexts = new HashMap<String, String>();
            Side sideA = side(id, "a", a, contexts, false);
            Side sideB = side(id, "b", b, contexts, "GEBAUT".equals(origin));
            if (sideA.getTextKey().equals(sideB.getTextKey())) {
                throw new IllegalArgumentException("pair " + id + ": both sides quote the same text");
            }
            String name = scope == Scope.FRAGE ? text(row, "frage") : text(row, "thema");
            Pair pair = new Pair(scope, id, name, sideA, sideB, text(a, "zitat"), text(b, "zitat"),
                    scope == Scope.FRAGE ? name : text(row, "frageA"),
                    scope == Scope.FRAGE ? name : text(row, "frageB"), 0);
            pairs.add(new LabeledPair(id, pair, expected, text(row, "unterschied").toUpperCase(Locale.ROOT),
                    contexts, "GEBAUT".equals(origin)));
        }
        return pairs;
    }

    private static Side side(String id, String name, JsonObject json, Map<String, String> contexts, boolean built) {
        String passage = built ? "" : text(json, "passageId");
        String passageId = passage.isEmpty() ? id + "-" + name : passage;
        String context = text(json, "umfeld");
        if (!context.isEmpty()) {
            contexts.put(passageId, context);
        }
        String source = text(json, "quelle");
        String url = source.isEmpty() || source.contains("://") ? source : "https://" + source;
        return new Side(id + "-" + name, passageId, source.isEmpty() ? id + "-" + name : source, url,
                "zitat:" + PassageText.key(text(json, "zitat")));
    }

    /** "mission", "language" and the passages (id, text, sourceId, domain) of a corpus; vectors are skipped. */
    static Corpus readCorpus(Reader in) throws IOException {
        Corpus corpus = new Corpus();
        JsonReader reader = new JsonReader(in);
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("mission".equals(name) && reader.peek() == JsonToken.STRING) {
                corpus.mission = reader.nextString();
            } else if ("language".equals(name) && reader.peek() == JsonToken.STRING) {
                corpus.language = reader.nextString();
            } else if ("passages".equals(name) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                while (reader.hasNext()) {
                    Map<String, String> row = new HashMap<String, String>();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String field = reader.nextName();
                        if (reader.peek() == JsonToken.STRING) {
                            row.put(field, reader.nextString());
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                    String id = row.getOrDefault("id", "");
                    if (!id.isEmpty()) {
                        corpus.texts.put(id, row.getOrDefault("text", ""));
                        corpus.sources.put(id, row.getOrDefault("sourceId", ""));
                        corpus.domains.put(id, row.getOrDefault("domain", ""));
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return corpus;
    }

    // ------------------------------------------------------------------------------------------------- pairs run

    /** Every pair through the model as in the product; {@code urteile.jsonl} in {@code dir}; the counts. */
    static JsonObject judgePairs(List<LabeledPair> pairs, Corpus corpus, MainModelChat chat, Settings settings,
                                 File dir) throws IOException {
        Map<String, String> contexts = new HashMap<String, String>();
        List<Pair> plain = new ArrayList<Pair>();
        for (LabeledPair labeled : pairs) {
            contexts.putAll(labeled.contexts);
            plain.add(labeled.pair);
        }
        ContradictionCheck.Passages passages = id -> contexts.containsKey(id) ? contexts.get(id)
                : corpus.texts.get(id);
        ContradictionCheck check = new ContradictionCheck(chat, null, corpus.language, settings.options, passages,
                null, null);
        long start = System.currentTimeMillis();
        List<Judgement> judgements = check.judgeAll(plain, corpus.mission);
        long millis = System.currentTimeMillis() - start;
        Files.createDirectories(dir.toPath());
        try (Writer out = Files.newBufferedWriter(new File(dir, "urteile.jsonl").toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < pairs.size(); i++) {
                Contradiction record = judgements.get(i).getContradiction();
                JsonObject line = new JsonObject();
                line.addProperty("id", pairs.get(i).id);
                line.addProperty("erwartet", pairs.get(i).expected);
                line.addProperty("status", record.getStatus().name());
                line.addProperty("urteil", record.getVerdict() == null ? "" : record.getVerdict().name());
                line.addProperty("unterschied", record.getDifference().name());
                line.addProperty("grund", record.getReason());
                line.addProperty("fehler", record.getProblem());
                line.addProperty("ms", judgements.get(i).getMillis());
                line.add("antworten", new GsonBuilder().create().toJsonTree(judgements.get(i).getAnswers()));
                out.write(line.toString());
                out.write('\n');
            }
        }
        return counts(pairs, judgements, settings, millis);
    }

    /**
     * The criteria W1 to W4 and the report; counts only. A criterion needs a minimum of pairs of its label, else it is
     * not measurable and fails; W1 also holds on the natural pairs when there are {@link Settings#minNatural} of them
     * (reported only below that). Natural and built pairs are reported apart; UNKLAR has no criterion, but how often
     * the model calls such a pair WIDERSPRUCH is counted.
     */
    static JsonObject counts(List<LabeledPair> pairs, List<Judgement> judgements, Settings settings, long millis) {
        Map<String, Map<String, Integer>> all = matrix();
        Map<String, Map<String, Integer>> natural = matrix();
        Map<String, Map<String, Integer>> built = matrix();
        int differenceAsked = 0;
        int differenceSame = 0;
        List<Long> times = new ArrayList<Long>();
        List<String> missed = new ArrayList<String>();
        List<String> falseAlarms = new ArrayList<String>();
        for (int i = 0; i < pairs.size(); i++) {
            LabeledPair labeled = pairs.get(i);
            Contradiction record = judgements.get(i).getContradiction();
            String given = record.getVerdict() == null ? "FEHLER" : record.getVerdict().name();
            all.get(labeled.expected).merge(given, 1, Integer::sum);
            (labeled.built ? built : natural).get(labeled.expected).merge(given, 1, Integer::sum);
            times.add(judgements.get(i).getMillis());
            if ("WIDERSPRUCH".equals(labeled.expected) && !"WIDERSPRUCH".equals(given)) {
                missed.add(labeled.id);
            }
            if (!"WIDERSPRUCH".equals(labeled.expected) && "WIDERSPRUCH".equals(given)) {
                falseAlarms.add(labeled.id);
            }
            if ("VEREINBAR".equals(labeled.expected) && !labeled.expectedDifference.isEmpty()
                    && record.getVerdict() == Verdict.VEREINBAR) {
                differenceAsked++;
                differenceSame += labeled.expectedDifference.equals(record.getDifference().name()) ? 1 : 0;
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("paare", pairs.size());
        result.addProperty("natuerlich", total(natural));
        result.addProperty("gebaut", total(built));
        result.add("erwartetGegenUrteil", table(all));
        result.add("natuerlichErwartetGegenUrteil", table(natural));
        result.add("gebautErwartetGegenUrteil", table(built));
        int errors = 0;
        for (Map<String, Integer> row : all.values()) {
            errors += row.get("FEHLER");
        }
        JsonObject criteria = new JsonObject();
        boolean passed = true;
        passed &= criterion(criteria, "W1_gefunden", all.get("WIDERSPRUCH"), settings.minContradictions,
                share(all.get("WIDERSPRUCH"), "WIDERSPRUCH"), settings.minFound, true, true);
        boolean naturalDecides = total(natural.get("WIDERSPRUCH")) >= settings.minNatural;
        passed &= criterion(criteria, "W1_gefundenNatuerlich", natural.get("WIDERSPRUCH"), settings.minNatural,
                share(natural.get("WIDERSPRUCH"), "WIDERSPRUCH"), settings.minFound, true, naturalDecides);
        passed &= criterion(criteria, "W2_vereinbarAlsWiderspruch", all.get("VEREINBAR"), settings.minCompatible,
                share(all.get("VEREINBAR"), "WIDERSPRUCH"), settings.maxCompatibleAsContradiction, false, true);
        passed &= criterion(criteria, "W3_unverbundenAlsWiderspruch", all.get("UNVERBUNDEN"), settings.minUnrelated,
                share(all.get("UNVERBUNDEN"), "WIDERSPRUCH"), settings.maxUnrelatedAsContradiction, false, true);
        passed &= criterion(criteria, "W4_fehler", null, 1, pairs.isEmpty() ? null : errors / (double) pairs.size(),
                settings.maxErrors, false, true);
        result.add("kriterien", criteria);
        result.addProperty("bestanden", passed);
        result.addProperty("unklarPaare", total(all.get("UNKLAR")));
        result.addProperty("unklarAlsWiderspruch", all.get("UNKLAR").get("WIDERSPRUCH"));
        result.addProperty("unterschiedGefragt", differenceAsked);
        result.addProperty("unterschiedGleich", differenceSame);
        result.add("verfehlt", new GsonBuilder().create().toJsonTree(missed));
        result.add("falscherAlarm", new GsonBuilder().create().toJsonTree(falseAlarms));
        Collections.sort(times);
        result.addProperty("msGesamt", millis);
        result.addProperty("msMedian", times.isEmpty() ? 0 : times.get(times.size() / 2));
        result.addProperty("msP90", times.isEmpty() ? 0 : times.get(Math.min(times.size() - 1,
                (int) Math.ceil(times.size() * 0.9) - 1)));
        return result;
    }

    private static Map<String, Map<String, Integer>> matrix() {
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<String, Map<String, Integer>>();
        for (String label : LABELS) {
            Map<String, Integer> row = new LinkedHashMap<String, Integer>();
            for (String given : Arrays.asList("WIDERSPRUCH", "VEREINBAR", "UNKLAR", "FEHLER")) {
                row.put(given, 0);
            }
            matrix.put(label, row);
        }
        return matrix;
    }

    private static JsonObject table(Map<String, Map<String, Integer>> matrix) {
        JsonObject table = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> row : matrix.entrySet()) {
            JsonObject cells = new JsonObject();
            for (Map.Entry<String, Integer> cell : row.getValue().entrySet()) {
                cells.addProperty(cell.getKey(), cell.getValue());
            }
            table.add(row.getKey(), cells);
        }
        return table;
    }

    private static int total(Map<String, ?> rowOrMatrix) {
        int n = 0;
        for (Object value : rowOrMatrix.values()) {
            if (value instanceof Integer) {
                n += (Integer) value;
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Integer> row = (Map<String, Integer>) value;
                n += total(row);
            }
        }
        return n;
    }

    private static Double share(Map<String, Integer> row, String given) {
        int n = 0;
        for (int count : row.values()) {
            n += count;
        }
        return n == 0 ? null : row.get(given) / (double) n;
    }

    /**
     * One criterion with its value and bound. With fewer than {@code min} pairs of its label ({@code row}; {@code null}
     * for all pairs) it is not measurable; a deciding criterion then fails, one reported only never does.
     */
    private static boolean criterion(JsonObject criteria, String name, Map<String, Integer> row, int min, Double value,
                                     double bound, boolean atLeast, boolean deciding) {
        JsonObject json = new JsonObject();
        int n = row == null ? min : total(row);
        boolean measurable = value != null && n >= min;
        boolean met = measurable && (atLeast ? value >= bound : value <= bound);
        if (row != null) {
            json.addProperty("paare", n);
            json.addProperty("mindestensPaare", min);
        }
        if (measurable) {
            json.addProperty("wert", Math.round(value * 1000) / 1000.0);
        } else {
            json.addProperty("wert", "nicht messbar");
        }
        json.addProperty(atLeast ? "mindestens" : "hoechstens", bound);
        json.addProperty("entscheidet", deciding);
        json.addProperty("bestanden", met);
        criteria.add(name, json);
        return met || !deciding;
    }

    // ------------------------------------------------------------------------------------------------- candidates run

    /** The questions of a file in the form of fragen-1711.json: only id, text and topic. */
    static ResearchMemory memoryOf(String questionsJson) throws IOException {
        JsonObject root = JsonParser.parseString(questionsJson).getAsJsonObject();
        Map<String, String> topicNames = new LinkedHashMap<String, String>();
        JsonObject topics = root.getAsJsonObject("themen");
        if (topics != null) {
            for (Map.Entry<String, JsonElement> topic : topics.entrySet()) {
                topicNames.put(topic.getKey(), topic.getValue().getAsString());
            }
        }
        ResearchMemory memory = new ResearchMemory();
        Map<String, Boolean> added = new HashMap<String, Boolean>();
        for (JsonElement element : root.getAsJsonArray("fragen")) {
            JsonObject question = element.getAsJsonObject();
            String topic = text(question, "thema");
            if (!topic.isEmpty() && added.put(topic, true) == null) {
                memory.addTopic(ResearchTopic.ofCard(topic, topicNames.getOrDefault(topic, "Thema " + topic),
                        "anchor-" + topic, 0, 1L));
            }
            memory.addQuestion(ResearchQuestion.open(text(question, "id"), text(question, "text"), topic,
                    ResearchQuestion.Origin.KONZEPT, 1L));
        }
        return memory;
    }

    /**
     * The final BELEGT of a component 6 run into the memory, text key from the corpus passage (so copies on other
     * sources are one group); the counts of what was taken.
     */
    static JsonObject fill(ResearchMemory memory, List<String> verdictLines, Corpus corpus) throws IOException {
        int read = 0;
        int taken = 0;
        int noPassage = 0;
        int lowered = 0;
        int unknownQuestion = 0;
        for (String line : verdictLines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            read++;
            JsonObject row = JsonParser.parseString(line).getAsJsonObject();
            if (!"BELEGT".equals(text(row, "urteil"))) {
                continue;
            }
            String question = text(row, "frage");
            if (memory.question(question) == null) {
                unknownQuestion++;
                continue;
            }
            String passage = text(row, "passage");
            String quote = text(row, "zitat");
            String passageText = corpus.texts.get(passage);
            if (passageText == null || passageText.isEmpty()) {
                noPassage++;
                passageText = quote;
            }
            String domain = text(row, "domain");
            String source = text(row, "quelle");
            EvidenceVerdict verdict = EvidenceVerdict.judged(question, passage, source.isEmpty() ? passage : source,
                    domain.isEmpty() ? "" : "https://" + domain, passageText, EvidenceVerdict.Kind.BELEGT, quote,
                    "", "stock", 1, 1L);
            if (verdict.getKind() != EvidenceVerdict.Kind.BELEGT) {
                lowered++;
            }
            memory.record(verdict);
            taken++;
        }
        JsonObject counts = new JsonObject();
        counts.addProperty("urteileGelesen", read);
        counts.addProperty("belegtUebernommen", taken);
        counts.addProperty("ohnePassageImBestand", noPassage);
        counts.addProperty("vomZitatabgleichHerabgestuft", lowered);
        counts.addProperty("unbekannteFrage", unknownQuestion);
        return counts;
    }

    /** The candidates of the memory, judged when a chat is given; the files in {@code dir}; the counts. */
    static JsonObject candidates(ResearchMemory memory, Corpus corpus, EmbeddingPort embeddings, MainModelChat chat,
                                 Options options, File dir) throws IOException {
        ContradictionCheck check = new ContradictionCheck(chat == null ? new Unused() : chat, embeddings,
                corpus.language, options, corpus.texts::get, null, null);
        Candidates candidates = check.candidates(memory);
        JsonObject result = new JsonObject();
        result.addProperty("paare", candidates.getPairs());
        result.addProperty("gleicheQuellgruppe", candidates.getSameGroup());
        result.addProperty("unterAehnlichkeit", candidates.getBelowSimilarity());
        result.addProperty("ueberObergrenze", candidates.getOverCap());
        result.addProperty("doppelt", candidates.getTwice());
        result.addProperty("kandidaten", candidates.getToJudge().size());
        result.addProperty("problem", candidates.getProblem());
        Map<String, Integer> perScope = new LinkedHashMap<String, Integer>();
        for (Pair pair : candidates.getToJudge()) {
            perScope.merge(pair.getScope().name() + ":" + pair.getScopeId(), 1, Integer::sum);
        }
        result.add("kandidatenJeBereich", new GsonBuilder().create().toJsonTree(perScope));
        Files.createDirectories(dir.toPath());
        List<String> ids = new ArrayList<String>();
        try (Writer out = Files.newBufferedWriter(new File(dir, "kandidaten.jsonl").toPath(), StandardCharsets.UTF_8)) {
            int n = 0;
            for (Pair pair : candidates.getToJudge()) {
                String id = String.format(Locale.ROOT, "K%03d", ++n);
                ids.add(id);
                JsonObject line = new JsonObject();
                line.addProperty("id", id);
                line.addProperty("art", pair.getScope().name());
                if (pair.getScope() == Scope.FRAGE) {
                    line.addProperty("frage", pair.getScopeName());
                } else {
                    line.addProperty("thema", pair.getScopeName());
                    line.addProperty("frageA", pair.getQuestionA());
                    line.addProperty("frageB", pair.getQuestionB());
                }
                line.add("a", sideJson(pair.getA(), pair.getQuoteA()));
                line.add("b", sideJson(pair.getB(), pair.getQuoteB()));
                line.addProperty("aehnlichkeit", Math.round(pair.getSimilarity() * 1000) / 1000.0);
                line.addProperty("erwartet", "");
                out.write(line.toString());
                out.write('\n');
            }
        }
        if (chat == null) {
            return result;
        }
        long start = System.currentTimeMillis();
        List<Judgement> judgements = check.judgeAll(candidates.getToJudge(), corpus.mission);
        result.addProperty("msGesamt", System.currentTimeMillis() - start);
        Map<String, Integer> verdicts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> differences = new LinkedHashMap<String, Integer>();
        try (Writer out = Files.newBufferedWriter(new File(dir, "modell-urteile.jsonl").toPath(),
                StandardCharsets.UTF_8)) {
            for (int i = 0; i < judgements.size(); i++) {
                Contradiction record = judgements.get(i).getContradiction();
                String given = record.getVerdict() == null ? "FEHLER" : record.getVerdict().name();
                verdicts.merge(record.getScope().name() + ":" + given, 1, Integer::sum);
                if (record.getVerdict() == Verdict.VEREINBAR) {
                    differences.merge(record.getDifference().name(), 1, Integer::sum);
                }
                JsonObject line = new JsonObject();
                line.addProperty("id", ids.get(i));
                line.addProperty("urteil", given);
                line.addProperty("unterschied", record.getDifference().name());
                line.addProperty("grund", record.getReason());
                line.addProperty("fehler", record.getProblem());
                line.addProperty("ms", judgements.get(i).getMillis());
                out.write(line.toString());
                out.write('\n');
            }
        }
        result.add("urteile", new GsonBuilder().create().toJsonTree(verdicts));
        result.add("unterschiede", new GsonBuilder().create().toJsonTree(differences));
        return result;
    }

    private static JsonObject sideJson(Side side, String quote) {
        JsonObject json = new JsonObject();
        json.addProperty("passageId", side.getPassageId());
        json.addProperty("quelle", side.getSourceUrl().isEmpty() ? side.getSourceId() : side.getSourceUrl());
        json.addProperty("zitat", quote);
        return json;
    }

    /** No model: the candidates run without judging. */
    private static final class Unused implements MainModelChat {
        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            throw new IllegalStateException("no chat model in this run");
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            throw new IllegalStateException("no chat model in this run");
        }

        @Override
        public String modelName() {
            return "";
        }
    }

    // ------------------------------------------------------------------------------------------------- sample runs

    static final String SAMPLE_PAIRS = String.join("\n",
            "{\"id\":\"W01\",\"art\":\"FRAGE\",\"frage\":\"Wie schnell darf man in Tempo-30-Zonen fahren?\","
                    + "\"a\":{\"passageId\":\"p1\",\"quelle\":\"stadt.example\",\"zitat\":\"In Tempo-30-Zonen gilt "
                    + "für alle Fahrzeuge höchstens 30 km/h.\"},\"b\":{\"passageId\":\"p2\",\"quelle\":"
                    + "\"https://verkehr.example.org/x\",\"zitat\":\"In Tempo-30-Zonen dürfen alle Fahrzeuge 50 km/h "
                    + "fahren.\"},\"erwartet\":\"WIDERSPRUCH\"}",
            "{\"id\":\"W02\",\"art\":\"FRAGE\",\"frage\":\"Gibt es eine Helmpflicht für Radfahrer?\","
                    + "\"a\":{\"passageId\":\"p3\",\"quelle\":\"rad.example\","
                    + "\"zitat\":\"In Deutschland gibt es keine Helmpflicht für "
                    + "Radfahrer.\"},\"b\":{\"passageId\":\"p4\",\"quelle\":\"helm.example\",\"zitat\":\"In "
                    + "Deutschland müssen alle Radfahrer einen Helm tragen.\"},\"erwartet\":\"WIDERSPRUCH\"}",
            "{\"id\":\"V01\",\"art\":\"FRAGE\",\"frage\":\"Gibt es eine Helmpflicht für Radfahrer?\","
                    + "\"a\":{\"passageId\":\"p3\",\"quelle\":\"rad.example\","
                    + "\"zitat\":\"In Deutschland gibt es keine Helmpflicht für "
                    + "Radfahrer.\"},\"b\":{\"passageId\":\"p5\",\"quelle\":\"oesterreich.example\",\"zitat\":\"In "
                    + "Österreich müssen Kinder unter zwölf Jahren beim Radfahren einen Helm tragen.\",\"umfeld\":"
                    + "\"Seit 2011 gilt in Österreich: Kinder unter zwölf Jahren müssen beim Radfahren einen Helm "
                    + "tragen.\"},\"erwartet\":\"VEREINBAR\",\"unterschied\":\"RECHTSRAUM\"}",
            "{\"id\":\"V02\",\"art\":\"FRAGE\",\"frage\":\"Was kostet zu schnelles Fahren innerorts?\",\"a\":"
                    + "{\"passageId\":\"p6\",\"quelle\":\"bussgeld.example\",\"zitat\":\"Seit 2021 kostet zu schnelles "
                    + "Fahren innerorts mehr.\"},\"b\":{\"passageId\":\"p7\",\"quelle\":\"archiv.example\",\"zitat\":"
                    + "\"Bis 2020 waren die Bußgelder für Tempoverstöße innerorts niedriger.\"},\"erwartet\":"
                    + "\"VEREINBAR\",\"unterschied\":\"JAHR\"}",
            "{\"id\":\"U01\",\"art\":\"THEMA\",\"thema\":\"Regeln im Ort\",\"frageA\":\"Wo darf man parken?\","
                    + "\"frageB\":\"Was bringt Tempo 30?\",\"a\":{\"passageId\":\"p8\",\"quelle\":\"park.example\","
                    + "\"zitat\":\"Parkscheiben müssen gut sichtbar hinter der Windschutzscheibe liegen.\"},\"b\":"
                    + "{\"passageId\":\"p9\",\"quelle\":\"unfall.example\",\"zitat\":\"Tempo 30 senkt die Zahl "
                    + "schwerer Unfälle mit Kindern.\"},\"erwartet\":\"UNVERBUNDEN\"}",
            "",
            "{\"id\":\"X01\",\"art\":\"FRAGE\",\"frage\":\"Wann gilt Tempo 30 vor Schulen?\",\"a\":{\"passageId\":"
                    + "\"p10\",\"quelle\":\"schule.example\",\"zitat\":\"Vor Schulen gilt Tempo 30 oft nur zu den "
                    + "Schulzeiten.\"},\"b\":{\"passageId\":\"p11\",\"quelle\":\"amt.example\",\"zitat\":\"Die "
                    + "Behörde prüft jeden Fall einzeln.\"},\"erwartet\":\"UNKLAR\"}",
            "{\"id\":\"W03\",\"art\":\"FRAGE\",\"frage\":\"Wie schnell darf man in Tempo-30-Zonen fahren?\","
                    + "\"herkunft\":\"GEBAUT\",\"a\":{\"passageId\":\"p1\",\"quelle\":\"stadt.example\",\"zitat\":"
                    + "\"In Tempo-30-Zonen gilt für alle Fahrzeuge höchstens 30 km/h.\"},\"b\":{\"passageId\":\"p2\","
                    + "\"quelle\":\"verkehr.example\",\"zitat\":\"In Tempo-30-Zonen gilt für alle Fahrzeuge höchstens "
                    + "40 km/h.\"},\"erwartet\":\"WIDERSPRUCH\"}");

    /** The sample is small: lower minimums, so that every criterion can be measured on it. */
    static Settings sampleSettings() {
        Settings settings = new Settings();
        settings.minContradictions = 3;
        settings.minCompatible = 2;
        settings.minUnrelated = 1;
        settings.minNatural = 2;
        return settings;
    }

    static final String SAMPLE_CORPUS = "{\"dim\":2,\"mission\":\"Ein Sachbuch über Regeln im Straßenverkehr.\","
            + "\"language\":\"de\",\"passages\":[{\"id\":\"p1\",\"sourceId\":\"s1\",\"domain\":\"stadt.example\","
            + "\"text\":\"Die Stadt informiert: In Tempo-30-Zonen gilt für alle Fahrzeuge höchstens 30 km/h.\","
            + "\"vector\":[0.1,0.2]},{\"id\":\"p2\",\"sourceId\":\"s2\",\"text\":\"In Tempo-30-Zonen dürfen alle "
            + "Fahrzeuge 50 km/h fahren.\",\"vector\":[0.3,0.4]}]}";

    /** Answers by the words of the pair, the way a careful model would. */
    static String careful(String prompt) {
        if (prompt.contains("50 km/h fahren") || prompt.contains("müssen alle Radfahrer")
                || prompt.contains("höchstens 40 km/h")) {
            return answer("WIDERSPRUCH", "KEINER");
        }
        if (prompt.contains("Österreich")) {
            return answer("VEREINBAR", "RECHTSRAUM");
        }
        if (prompt.contains("Bis 2020")) {
            return answer("VEREINBAR", "JAHR");
        }
        if (prompt.contains("Parkscheiben")) {
            return answer("VEREINBAR", "SACHE");
        }
        return answer("UNKLAR", "KEINER");
    }

    static String answer(String verdict, String difference) {
        JsonObject json = new JsonObject();
        json.addProperty("grund", "So steht es in den beiden Aussagen.");
        json.addProperty("urteil", verdict);
        json.addProperty("unterschied", difference);
        return json.toString();
    }

    /** A chat model that answers each prompt through a function. */
    static final class ScriptedChat implements MainModelChat {
        final Function<String, String> answer;
        final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());

        ScriptedChat(Function<String, String> answer) {
            this.answer = answer;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            prompts.add(messages.get(1).getContent());
            return MainModelChatResult.ok(answer.apply(messages.get(1).getContent()));
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }

    @Test
    public void samplePairs() throws Exception {
        List<LabeledPair> pairs = readPairs(Arrays.asList(SAMPLE_PAIRS.split("\n")));
        assertEquals(7, pairs.size());
        Corpus corpus = readCorpus(new StringReader(SAMPLE_CORPUS));
        assertEquals("Ein Sachbuch über Regeln im Straßenverkehr.", corpus.mission);
        assertEquals(2, corpus.texts.size());
        File out = scratch.newFolder("out");
        ScriptedChat careful = new ScriptedChat(ContradictionStockTest::careful);

        JsonObject result = judgePairs(pairs, corpus, careful, sampleSettings(), new File(out, "careful"));

        assertTrue(result.toString(), result.get("bestanden").getAsBoolean());
        assertEquals(6, result.get("natuerlich").getAsInt());
        assertEquals(1, result.get("gebaut").getAsInt());
        assertEquals(1, result.getAsJsonObject("gebautErwartetGegenUrteil").getAsJsonObject("WIDERSPRUCH")
                .get("WIDERSPRUCH").getAsInt());
        assertEquals(2, result.getAsJsonObject("natuerlichErwartetGegenUrteil").getAsJsonObject("WIDERSPRUCH")
                .get("WIDERSPRUCH").getAsInt());
        JsonObject criteria = result.getAsJsonObject("kriterien");
        assertEquals(1.0, criteria.getAsJsonObject("W1_gefunden").get("wert").getAsDouble(), 0.0);
        assertEquals(3, criteria.getAsJsonObject("W1_gefunden").get("paare").getAsInt());
        JsonObject natural = criteria.getAsJsonObject("W1_gefundenNatuerlich");
        assertEquals(2, natural.get("paare").getAsInt());
        assertTrue("two natural contradictions decide with the sample minimum 2", natural.get("entscheidet")
                .getAsBoolean());
        assertEquals(0.0, criteria.getAsJsonObject("W2_vereinbarAlsWiderspruch").get("wert").getAsDouble(), 0.0);
        assertEquals(0.0, criteria.getAsJsonObject("W3_unverbundenAlsWiderspruch").get("wert").getAsDouble(), 0.0);
        assertEquals(0.0, criteria.getAsJsonObject("W4_fehler").get("wert").getAsDouble(), 0.0);
        assertEquals(2, result.get("unterschiedGefragt").getAsInt());
        assertEquals(2, result.get("unterschiedGleich").getAsInt());
        assertEquals(1, result.getAsJsonObject("erwartetGegenUrteil").getAsJsonObject("UNKLAR").get("UNKLAR")
                .getAsInt());
        assertEquals(1, result.get("unklarPaare").getAsInt());
        assertEquals(0, result.get("unklarAlsWiderspruch").getAsInt());
        String prompts = String.join("\n", careful.prompts);
        assertTrue("the context of the file", prompts.contains("Umfeld: „Seit 2011 gilt in Österreich"));
        assertTrue("the context of the corpus", prompts.contains("Umfeld: „Die Stadt informiert"));
        assertTrue(prompts.contains("aus example.org"));
        assertFalse("no id goes to the model", prompts.contains("W01"));
        assertEquals(7, Files.readAllLines(new File(out, "careful/urteile.jsonl").toPath(), StandardCharsets.UTF_8)
                .size());
        String built = careful.prompts.stream().filter(prompt -> prompt.contains("höchstens 40 km/h")).findFirst()
                .orElseThrow(AssertionError::new);
        String builtB = built.substring(built.indexOf("Aussage B"));
        assertFalse("side B of a built pair shows no unchanged passage: " + builtB, builtB.contains("Umfeld"));
        assertFalse(built.contains("50 km/h"));
        assertFalse("counts only: " + result, result.toString().contains("Tempo-30-Zonen"));
        assertFalse(result.toString().contains("So steht es"));

        ScriptedChat alarmed = new ScriptedChat(prompt -> answer("WIDERSPRUCH", "KEINER"));
        JsonObject worse = judgePairs(pairs, corpus, alarmed, sampleSettings(), new File(out, "alarmed"));
        assertFalse(worse.get("bestanden").getAsBoolean());
        assertEquals("UNKLAR has no criterion, but is counted", 1, worse.get("unklarAlsWiderspruch").getAsInt());
        assertEquals(1.0, worse.getAsJsonObject("kriterien").getAsJsonObject("W2_vereinbarAlsWiderspruch")
                .get("wert").getAsDouble(), 0.0);
        assertEquals(Arrays.asList("V01", "V02", "U01", "X01").toString(), worse.get("falscherAlarm").toString()
                .replace("\"", "").replace(",", ", "));
    }

    /** Architect, 22.11.: at least 12 WIDERSPRUCH, 20 VEREINBAR and 20 UNVERBUNDEN, else not measurable. */
    @Test
    public void minimumCountsMakeACriterionMeasurable() throws Exception {
        List<LabeledPair> pairs = readPairs(Arrays.asList(SAMPLE_PAIRS.split("\n")));
        Corpus corpus = readCorpus(new StringReader(SAMPLE_CORPUS));
        ScriptedChat careful = new ScriptedChat(ContradictionStockTest::careful);
        File out = scratch.newFolder("out");

        JsonObject strict = judgePairs(pairs, corpus, careful, new Settings(), new File(out, "strict"));

        assertFalse("too few pairs for the default minimums", strict.get("bestanden").getAsBoolean());
        JsonObject criteria = strict.getAsJsonObject("kriterien");
        for (String name : new String[]{"W1_gefunden", "W2_vereinbarAlsWiderspruch", "W3_unverbundenAlsWiderspruch",
                "W1_gefundenNatuerlich"}) {
            assertEquals(name, "nicht messbar", criteria.getAsJsonObject(name).get("wert").getAsString());
        }
        assertEquals(12, criteria.getAsJsonObject("W1_gefunden").get("mindestensPaare").getAsInt());
        assertEquals(20, criteria.getAsJsonObject("W2_vereinbarAlsWiderspruch").get("mindestensPaare").getAsInt());
        assertEquals(20, criteria.getAsJsonObject("W3_unverbundenAlsWiderspruch").get("mindestensPaare").getAsInt());
        assertFalse("2 natural contradictions of 5: reported only", criteria.getAsJsonObject("W1_gefundenNatuerlich")
                .get("entscheidet").getAsBoolean());
        assertTrue(criteria.getAsJsonObject("W4_fehler").get("bestanden").getAsBoolean());

        Settings fewNatural = sampleSettings();
        fewNatural.minNatural = 5;
        JsonObject reported = judgePairs(pairs, corpus, careful, fewNatural, new File(out, "reported"));
        assertTrue("a criterion reported only never fails the run: " + reported, reported.get("bestanden")
                .getAsBoolean());
        assertFalse(reported.getAsJsonObject("kriterien").getAsJsonObject("W1_gefundenNatuerlich").get("bestanden")
                .getAsBoolean());

        List<LabeledPair> builtOnly = readPairs(Collections.singletonList(SAMPLE_PAIRS.split("\n")[7]));
        Settings one = sampleSettings();
        one.minContradictions = 1;
        one.minCompatible = 0;
        one.minUnrelated = 0;
        JsonObject onlyBuilt = judgePairs(builtOnly, corpus, careful, one, new File(out, "built"));
        assertTrue(onlyBuilt.toString(), onlyBuilt.getAsJsonObject("kriterien").getAsJsonObject("W1_gefunden")
                .get("bestanden").getAsBoolean());
        assertFalse("no natural contradiction: W1 on the natural pairs decides nothing", onlyBuilt
                .getAsJsonObject("kriterien").getAsJsonObject("W1_gefundenNatuerlich").get("entscheidet")
                .getAsBoolean());
    }

    @Test
    public void aFileWithoutContradictionsCannotPass() throws Exception {
        List<LabeledPair> pairs = readPairs(Collections.singletonList(SAMPLE_PAIRS.split("\n")[2]));
        JsonObject result = judgePairs(pairs, readCorpus(new StringReader(SAMPLE_CORPUS)),
                new ScriptedChat(ContradictionStockTest::careful), new Settings(), scratch.newFolder("out"));
        assertFalse(result.get("bestanden").getAsBoolean());
        assertEquals("nicht messbar", result.getAsJsonObject("kriterien").getAsJsonObject("W1_gefunden").get("wert")
                .getAsString());
    }

    @Test
    public void badLinesAreRefused() {
        for (String bad : new String[]{"kaputt", "{\"id\":\"W9\",\"erwartet\":\"VIELLEICHT\"}",
                "{\"id\":\"W9\",\"erwartet\":\"WIDERSPRUCH\",\"a\":{\"zitat\":\"x\"}}",
                "{\"id\":\"W9\",\"erwartet\":\"WIDERSPRUCH\",\"a\":{\"zitat\":\"Gleich.\"},"
                        + "\"b\":{\"zitat\":\"gleich.\"}}"}) {
            try {
                readPairs(Collections.singletonList(bad));
                throw new AssertionError("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    static final String SAMPLE_QUESTIONS = "{\"bestand\":\"Beispiel\",\"themen\":{\"T1\":\"Tempo im Ort\"},"
            + "\"fragen\":[{\"id\":\"F01\",\"text\":\"Wo gilt Tempo 30?\",\"thema\":\"T1\",\"beantwortbar\":true},"
            + "{\"id\":\"F02\",\"text\":\"Wer ordnet Tempo 30 an?\",\"thema\":\"T1\"},"
            + "{\"id\":\"F03\",\"text\":\"Was kostet Parken?\",\"thema\":\"\"}]}";

    static final String SAMPLE_STOCK = "{\"mission\":\"Ein Sachbuch über Tempo 30.\",\"language\":\"de\",\"passages\":["
            + "{\"id\":\"p1\",\"sourceId\":\"s1\",\"domain\":\"stadt.example\",\"text\":\"Die Stadt schreibt: In "
            + "Tempo-30-Zonen gilt höchstens 30 km/h.\"},"
            + "{\"id\":\"p2\",\"sourceId\":\"s2\",\"domain\":\"lkw.example\",\"text\":\"In Tempo-30-Zonen dürfen "
            + "Lastwagen 50 km/h fahren.\"},"
            + "{\"id\":\"p3\",\"sourceId\":\"s3\",\"domain\":\"kopie.example\",\"text\":\"Die Stadt schreibt: In "
            + "Tempo-30-Zonen gilt höchstens 30 km/h.\"},"
            + "{\"id\":\"p4\",\"sourceId\":\"s4\",\"domain\":\"amt.example\",\"text\":\"Die Gemeinde ordnet Tempo 30 "
            + "an, die Polizei nicht.\"},"
            + "{\"id\":\"p5\",\"sourceId\":\"s3\",\"domain\":\"kopie.example\",\"text\":\"Auf Landstraßen gilt für "
            + "Lastwagen 60 km/h.\"}]}";

    static final String SAMPLE_VERDICTS = String.join("\n",
            "{\"frage\":\"F01\",\"passage\":\"p1\",\"quelle\":\"s1\",\"domain\":\"stadt.example\","
                    + "\"urteil\":\"BELEGT\","
                    + "\"zitat\":\"In Tempo-30-Zonen gilt höchstens 30 km/h.\"}",
            "{\"frage\":\"F01\",\"passage\":\"p2\",\"quelle\":\"s2\",\"domain\":\"lkw.example\",\"urteil\":\"BELEGT\","
                    + "\"zitat\":\"In Tempo-30-Zonen dürfen Lastwagen 50 km/h fahren.\"}",
            "{\"frage\":\"F01\",\"passage\":\"p3\",\"quelle\":\"s3\",\"domain\":\"kopie.example\","
                    + "\"urteil\":\"BELEGT\","
                    + "\"zitat\":\"In Tempo-30-Zonen gilt höchstens 30 km/h.\"}",
            "{\"frage\":\"F02\",\"passage\":\"p4\",\"quelle\":\"s4\",\"domain\":\"amt.example\",\"urteil\":\"BELEGT\","
                    + "\"zitat\":\"Die Gemeinde ordnet Tempo 30 an\"}",
            "{\"frage\":\"F01\",\"passage\":\"p5\",\"quelle\":\"s3\",\"domain\":\"kopie.example\","
                    + "\"urteil\":\"BELEGT\","
                    + "\"zitat\":\"Auf Landstraßen gilt für Lastwagen 60 km/h.\"}",
            "{\"frage\":\"F02\",\"passage\":\"p2\",\"quelle\":\"s2\",\"domain\":\"lkw.example\",\"urteil\":\"HINWEIS\","
                    + "\"zitat\":\"Lastwagen\"}",
            "{\"frage\":\"F09\",\"passage\":\"p2\",\"quelle\":\"s2\",\"urteil\":\"BELEGT\",\"zitat\":\"Lastwagen\"}",
            "{\"frage\":\"F02\",\"passage\":\"p9\",\"quelle\":\"s9\",\"urteil\":\"BELEGT\",\"zitat\":\"Die Polizei "
                    + "ordnet nichts an.\"}");

    /** bge-m3 in the sample: a vector from the letters of the text. */
    static final class LetterEmbeddings implements EmbeddingPort {
        @Override
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>();
            for (String text : texts) {
                float[] values = new float[26];
                for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
                    if (c >= 'a' && c <= 'z') {
                        values[c - 'a']++;
                    }
                }
                vectors.add(new EmbeddingVector("letters", "letters", values));
            }
            return vectors;
        }
    }

    @Test
    public void sampleCandidates() throws Exception {
        ResearchMemory memory = memoryOf(SAMPLE_QUESTIONS);
        Corpus corpus = readCorpus(new StringReader(SAMPLE_STOCK));
        JsonObject taken = fill(memory, Arrays.asList(SAMPLE_VERDICTS.split("\n")), corpus);
        assertEquals(8, taken.get("urteileGelesen").getAsInt());
        assertEquals(6, taken.get("belegtUebernommen").getAsInt());
        assertEquals(1, taken.get("unbekannteFrage").getAsInt());
        assertEquals(1, taken.get("ohnePassageImBestand").getAsInt());
        assertEquals(0, taken.get("vomZitatabgleichHerabgestuft").getAsInt());
        File out = scratch.newFolder("out");

        JsonObject blind = candidates(memory, corpus, new LetterEmbeddings(), null, Options.defaults(),
                new File(out, "blind"));

        // F01: s1 and s3 carry the same text (p1, p3), so s1's text and s3's other text (p5) never meet;
        // F02: p4 and p9; the topic: the three texts of F01 with the two of F02
        assertEquals(3 + 1 + 6, blind.get("paare").getAsInt());
        assertEquals(1, blind.get("gleicheQuellgruppe").getAsInt());
        assertEquals(2 + 1 + 6, blind.get("kandidaten").getAsInt());
        assertEquals(2, blind.getAsJsonObject("kandidatenJeBereich").get("FRAGE:F01").getAsInt());
        assertEquals(1, blind.getAsJsonObject("kandidatenJeBereich").get("FRAGE:F02").getAsInt());
        assertEquals(6, blind.getAsJsonObject("kandidatenJeBereich").get("THEMA:T1").getAsInt());
        List<String> lines = Files.readAllLines(new File(out, "blind/kandidaten.jsonl").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(9, lines.size());
        List<LabeledPair> again = readPairs(Arrays.asList(lines.get(0).replace("\"erwartet\":\"\"",
                "\"erwartet\":\"WIDERSPRUCH\"")));
        assertEquals("K001", again.get(0).id);
        assertFalse(new File(out, "blind/modell-urteile.jsonl").exists());
        assertFalse("counts only: " + blind, blind.toString().contains("Tempo-30-Zonen"));
        assertFalse(blind.toString().contains("Wo gilt"));

        ScriptedChat chat = new ScriptedChat(prompt -> prompt.contains("50 km/h") && prompt.contains("höchstens")
                ? answer("WIDERSPRUCH", "KEINER") : answer("VEREINBAR", "SACHE"));
        JsonObject judged = candidates(refill(corpus), corpus, new LetterEmbeddings(), chat, Options.defaults(),
                new File(out, "judged"));
        assertEquals(9, chat.prompts.size());
        assertEquals(judged.toString(), 1, judged.getAsJsonObject("urteile").get("FRAGE:WIDERSPRUCH").getAsInt());
        assertEquals(8, judged.getAsJsonObject("urteile").get("FRAGE:VEREINBAR").getAsInt()
                + judged.getAsJsonObject("urteile").get("THEMA:VEREINBAR").getAsInt());
        assertEquals(9, Files.readAllLines(new File(out, "judged/modell-urteile.jsonl").toPath(),
                StandardCharsets.UTF_8).size());
        assertFalse(judged.toString().contains("So steht es"));
    }

    private static ResearchMemory refill(Corpus corpus) throws IOException {
        ResearchMemory memory = memoryOf(SAMPLE_QUESTIONS);
        fill(memory, Arrays.asList(SAMPLE_VERDICTS.split("\n")), corpus);
        return memory;
    }

    @Test
    public void aDamagedMissionIsRefused() {
        assertTrue(MOJIBAKE.matcher("Im MÃ¤rz").find());
        assertFalse(MOJIBAKE.matcher("Im März, Straße").find());
    }

    // ------------------------------------------------------------------------------------------------- stored runs

    @Test
    public void storedPairs() throws Exception {
        String pairsPath = System.getProperty(P + "pairs", "");
        String url = System.getProperty(P + "chatUrl", "");
        Assume.assumeTrue("set -D" + P + "pairs and -D" + P + "chatUrl", !pairsPath.isEmpty() && !url.isEmpty());
        Settings settings = settings();
        List<LabeledPair> pairs = readPairs(Files.readAllLines(new File(pairsPath).toPath(), StandardCharsets.UTF_8));
        if (settings.limit > 0 && pairs.size() > settings.limit) {
            pairs = new ArrayList<LabeledPair>(pairs.subList(0, settings.limit));
        }
        Corpus corpus = corpus();
        File out = new File(System.getProperty(P + "out", "build/component-stock/contradictions"));
        JsonObject result = new JsonObject();
        JsonObject task = new JsonObject();
        task.addProperty("paare", pairs.size());
        Map<String, Integer> labels = new LinkedHashMap<String, Integer>();
        for (LabeledPair pair : pairs) {
            labels.merge(pair.expected, 1, Integer::sum);
        }
        task.add("etiketten", new GsonBuilder().create().toJsonTree(labels));
        task.addProperty("auftragZeichen", corpus.mission.length());
        task.addProperty("sprache", corpus.language);
        task.addProperty("promptVersion", ContradictionCheck.PROMPT_VERSION);
        task.addProperty("gleichzeitig", settings.options.getParallel());
        task.addProperty("antwortTokens", settings.options.getAnswerTokens());
        task.addProperty("umfeldZeichen", settings.options.getContextChars());
        result.add("aufgabe", task);
        JsonObject models = new JsonObject();
        boolean decided = true;
        for (String model : settings.models) {
            OllamaStockChat chat = chat(url, model);
            JsonObject block = judgePairs(pairs, corpus, chat, settings, new File(out, safe(model)));
            block.addProperty("aufrufe", chat.calls.get());
            block.addProperty("fehlgeschlageneAufrufe", chat.failures.get());
            block.addProperty("ausgabeTokens", chat.evalTokens.get());
            block.addProperty("denkZeichen", chat.thinkingChars.get());
            models.add(model, block);
            if (model.equals(settings.models.get(0))) {
                decided = block.get("bestanden").getAsBoolean();
            }
            write(out, result, models, settings.models.get(0));
            System.out.println("[contradictions] " + model + ": " + block.getAsJsonObject("kriterien"));
        }
        assertTrue("criteria W1-W4 of " + settings.models.get(0) + ": see " + new File(out, "result.json"), decided);
    }

    @Test
    public void storedCandidates() throws Exception {
        String verdictsPath = System.getProperty(P + "verdicts", "");
        String embedUrl = System.getProperty(P + "embedUrl", "");
        Assume.assumeTrue("set -D" + P + "verdicts, .questions, .corpus and .embedUrl", !verdictsPath.isEmpty()
                && !embedUrl.isEmpty());
        ResearchMemory memory = memoryOf(new String(Files.readAllBytes(new File(required("questions")).toPath()),
                StandardCharsets.UTF_8));
        Corpus corpus = corpus();
        JsonObject taken = fill(memory, Files.readAllLines(new File(verdictsPath).toPath(), StandardCharsets.UTF_8),
                corpus);
        int dim = Integer.parseInt(System.getProperty(P + "embedDim", "1024"));
        EmbeddingEndpointDescriptor descriptor = new EmbeddingEndpointDescriptor(System.getProperty(P + "embedModel",
                "bge-m3"), embedUrl, "/api/embed", dim, "", "", 120000L);
        HttpEmbeddingPortAdapter embeddings = new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport(120000));
        String url = System.getProperty(P + "chatUrl", "");
        Settings settings = settings();
        OllamaStockChat chat = url.isEmpty() ? null : chat(url, settings.models.get(0));
        Options options = settings.options.withPairsPerScope(Integer.parseInt(System.getProperty(P + "paareJeBereich",
                String.valueOf(settings.options.getPairsPerScope()))));
        File out = new File(System.getProperty(P + "out", "build/component-stock/contradictions-candidates"));
        JsonObject result = candidates(memory, corpus, embeddings, chat, options, out);
        result.add("uebernommen", taken);
        result.addProperty("paareJeBereich", options.getPairsPerScope());
        result.addProperty("modell", chat == null ? "" : chat.modelName());
        if (chat != null) {
            result.addProperty("aufrufe", chat.calls.get());
            result.addProperty("fehlgeschlageneAufrufe", chat.failures.get());
        }
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting().create()
                .toJson(result).getBytes(StandardCharsets.UTF_8));
        System.out.println("[contradictions] candidates: " + result);
        assertEquals("", result.get("problem").getAsString());
    }

    private static Settings settings() {
        Settings settings = new Settings();
        String models = System.getProperty(P + "chatModel", "gemma4:12b");
        settings.models = new ArrayList<String>();
        for (String model : models.split(",")) {
            if (!model.trim().isEmpty()) {
                settings.models.add(model.trim());
            }
        }
        settings.minFound = number("minGefunden", settings.minFound);
        settings.maxCompatibleAsContradiction = number("maxVereinbarAlsWiderspruch",
                settings.maxCompatibleAsContradiction);
        settings.maxUnrelatedAsContradiction = number("maxUnverbundenAlsWiderspruch",
                settings.maxUnrelatedAsContradiction);
        settings.maxErrors = number("maxFehler", settings.maxErrors);
        settings.minContradictions = (int) number("minAnzahlWiderspruch", settings.minContradictions);
        settings.minCompatible = (int) number("minAnzahlVereinbar", settings.minCompatible);
        settings.minUnrelated = (int) number("minAnzahlUnverbunden", settings.minUnrelated);
        settings.minNatural = (int) number("minNatuerlicheWidersprueche", settings.minNatural);
        settings.limit = (int) number("limit", 0);
        Options options = Options.defaults();
        options = options.withParallel((int) number("parallel", options.getParallel()));
        options = options.withAnswerTokens((int) number("antwortTokens", options.getAnswerTokens()));
        options = options.withContextChars((int) number("umfeldZeichen", options.getContextChars()));
        settings.options = options;
        return settings;
    }

    private static Corpus corpus() throws IOException {
        String corpusPath = System.getProperty(P + "corpus", "");
        String missionPath = System.getProperty(P + "missionFile", "");
        Corpus corpus = new Corpus();
        if (!corpusPath.isEmpty()) {
            try (Reader in = Files.newBufferedReader(new File(corpusPath).toPath(), StandardCharsets.UTF_8)) {
                corpus = readCorpus(in);
            }
        }
        if (!missionPath.isEmpty()) {
            corpus.mission = new String(Files.readAllBytes(new File(missionPath).toPath()), StandardCharsets.UTF_8)
                    .trim();
        }
        if (corpus.mission.trim().isEmpty()) {
            throw new IllegalArgumentException("no mission: set -D" + P + "corpus or -D" + P + "missionFile");
        }
        if (MOJIBAKE.matcher(corpus.mission).find()) {
            throw new IllegalArgumentException("the mission looks damaged (UTF-8 read as Latin-1); use a clean copy");
        }
        return corpus;
    }

    private static OllamaStockChat chat(String url, String model) {
        String numCtx = System.getProperty(P + "numCtx", String.valueOf(ContradictionCheck.CONTEXT_TOKENS));
        int timeout = (int) number("timeoutSeconds", 300) * 1000;
        return new OllamaStockChat(url, model, "omit".equals(numCtx) ? null : Integer.valueOf(numCtx), Boolean.TRUE,
                timeout);
    }

    private static void write(File out, JsonObject result, JsonObject models, String deciding) throws IOException {
        result.add("modelle", models);
        result.addProperty("entscheidet", deciding);
        Files.createDirectories(out.toPath());
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting().create()
                .toJson(result).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static String safe(String model) {
        return model.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static String required(String name) {
        String value = System.getProperty(P + name, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("the contradiction stock test needs -D" + P + name);
        }
        return value;
    }

    private static double number(String name, double fallback) {
        String value = System.getProperty(P + name, "");
        return value.isEmpty() ? fallback : Double.parseDouble(value);
    }
}

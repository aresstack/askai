package com.aresstack.askai.research.pagenoise;

import com.aresstack.askai.research.memory.SourceDomain;
import com.aresstack.askai.research.pagenoise.PageNoiseCheck.Options;
import com.aresstack.askai.research.pagenoise.PageNoiseCheck.Passage;
import com.aresstack.askai.research.pagenoise.PageNoiseCheck.Result;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The page-noise check on stored data, on the owner's machine; skipped without its files. The sample tests run the
 * same path on self-written data with a scripted model in every build.
 *
 * <p>{@link #storedPassages()}: the check on the passages of a stock as in the product (stage 1 the prose rules,
 * stage 2 one call per text, one repair, thinking off). With labels it measures against them: R1 at least
 * {@code .minTrefferRauschen} (0.90) of the passages labeled RAUSCHEN are marked RAUSCHEN; R2 at most
 * {@code .maxInhaltVerworfen} (0.05) of the passages labeled INHALT are marked RAUSCHEN; R3 at most {@code .maxFehler}
 * (0.02) of the checked passages end as FEHLER. R1 needs at least {@code .minAnzahlRauschen} (30) RAUSCHEN labels and
 * R2 at least {@code .minAnzahlInhalt} (100) INHALT labels, else it is not measurable and fails. MISCH labels count for
 * neither and are reported apart.</p>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*PageNoiseStockTest*'
 *   -Daskai.stock.noise.corpus=replay-corpus.json (passages: id, sourceId, domain or url, text; vectors are skipped)
 *   -Daskai.stock.noise.labels=a.json,b.json (optional; without labels every passage is checked)
 *   -Daskai.stock.noise.chatUrl=http://127.0.0.1:11434 -Daskai.stock.noise.chatModel=gemma4:12b
 *   -Daskai.stock.noise.out=build/component-stock/page-noise
 *   optional: .nurRegeln=true (stage 1 only, no model), .alle=true (every passage, not only the labeled ones),
 *             .neu=true (forget the marks of an earlier run), .limit (0 = all), .parallel (4), .antwortTokens (512),
 *             .passageZeichen (2000), .numCtx (8192; "omit" sends none), .timeoutSeconds (120), .minTrefferRauschen,
 *             .maxInhaltVerworfen, .maxFehler, .minAnzahlRauschen, .minAnzahlInhalt
 * </pre>
 * Labels: one or more files, each a JSON object from passage id to label ({@code "INHALT"} or
 * {@code {"etikett":"RAUSCHEN","klasse":"WERBUNG"}}), a JSON array of objects with {@code "passageId"} (or
 * {@code "id"}) and {@code "etikett"} (or {@code "urteil"}, {@code "label"}), such an array under {@code "etiketten"},
 * {@code "labels"} or {@code "passagen"}, or one such object per line. A label is INHALT, RAUSCHEN or MISCH (CONTENT,
 * NOISE and MIXED are read too). The marks go to {@code <out>/<model>/page-noise/marks.jsonl}, and a run that stops
 * continues from them. {@code <out>/<model>/urteile.jsonl} holds per checked passage its label, its mark and the
 * model's reason; {@code result.json} holds ids and counts only, no passage text and no reason of the model. The
 * other files carry words of the sources and stay on the machine.
 */
public class PageNoiseStockTest {

    private static final String P = "askai.stock.noise.";
    /** UTF-8 read as Latin-1 ("Ã¤", "â€", "Â§"): a damaged copy of the corpus looks like this. */
    static final Pattern MOJIBAKE = Pattern.compile("\\u00C3[\\u0080-\\u00BF]|\\u00E2\\u20AC|\\u00C2[\\u00A0-\\u00BF]");
    static final List<String> LABELS = Arrays.asList("INHALT", "RAUSCHEN", "MISCH");
    static final List<String> GIVEN = Arrays.asList("INHALT", "RAUSCHEN", "MISCH", "FEHLER");

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    /** The passages of a stock, in its order. */
    static final class Corpus {
        String mission = "";
        String language = "de";
        final List<Passage> passages = new ArrayList<Passage>();
    }

    /** A passage's label. */
    static final class Label {
        final String verdict;
        final String kind;

        Label(String verdict, String kind) {
            this.verdict = verdict;
            this.kind = kind;
        }
    }

    static final class Settings {
        Options options = Options.defaults();
        double minRecall = 0.90;
        double maxDropped = 0.05;
        double maxErrors = 0.02;
        int minNoise = 30;
        int minContent = 100;
        boolean all;
        boolean fresh;
        int limit;
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** "mission", "language" and the passages (id, sourceId, domain or url, text) of a corpus; vectors are skipped. */
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
                        String domain = row.getOrDefault("domain", "");
                        corpus.passages.add(new Passage(id, row.getOrDefault("sourceId", ""),
                                domain.isEmpty() ? SourceDomain.of(row.getOrDefault("url", "")) : domain,
                                row.getOrDefault("text", "")));
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

    /** The labels of one or more files (see the class comment), by passage id. */
    static Map<String, Label> readLabels(List<String> files) {
        Map<String, Label> labels = new LinkedHashMap<String, Label>();
        for (String content : files) {
            for (Map.Entry<String, JsonElement> item : items(content)) {
                String id = item.getKey();
                Label label = label(id, item.getValue());
                Label known = labels.get(id);
                if (known != null && !known.verdict.equals(label.verdict)) {
                    throw new IllegalArgumentException("passage " + id + " has two different labels");
                }
                labels.put(id, label);
            }
        }
        return labels;
    }

    private static List<Map.Entry<String, JsonElement>> items(String content) {
        List<Map.Entry<String, JsonElement>> items = new ArrayList<Map.Entry<String, JsonElement>>();
        JsonElement whole;
        try {
            whole = JsonParser.parseString(content);
        } catch (RuntimeException severalLines) {
            int number = 0;
            for (String line : content.split("\n")) {
                number++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    items.add(item(JsonParser.parseString(line).getAsJsonObject()));
                } catch (IllegalStateException | com.google.gson.JsonParseException notAnObject) {
                    throw new IllegalArgumentException("labels: line " + number + " is no JSON object");
                }
            }
            return items;
        }
        if (whole.isJsonArray()) {
            for (JsonElement element : whole.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("labels: an entry of the list is no JSON object");
                }
                items.add(item(element.getAsJsonObject()));
            }
            return items;
        }
        if (!whole.isJsonObject()) {
            throw new IllegalArgumentException("labels: neither a JSON object nor a list");
        }
        JsonObject object = whole.getAsJsonObject();
        for (String field : new String[]{"etiketten", "labels", "passagen"}) {
            if (object.has(field) && object.get(field).isJsonArray()) {
                return items(object.get(field).toString());
            }
        }
        if (!idOf(object).isEmpty() && !verdictOf(object).isEmpty()) {
            items.add(item(object));
            return items;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            items.add(new java.util.AbstractMap.SimpleEntry<String, JsonElement>(entry.getKey(), entry.getValue()));
        }
        return items;
    }

    private static Map.Entry<String, JsonElement> item(JsonObject object) {
        String id = idOf(object);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("labels: an entry has no \"passageId\"");
        }
        return new java.util.AbstractMap.SimpleEntry<String, JsonElement>(id, object);
    }

    private static Label label(String id, JsonElement value) {
        String verdict;
        String kind = "";
        if (value != null && value.isJsonPrimitive()) {
            verdict = value.getAsString();
        } else if (value != null && value.isJsonObject()) {
            verdict = verdictOf(value.getAsJsonObject());
            kind = first(value.getAsJsonObject(), "klasse", "class", "art");
        } else {
            throw new IllegalArgumentException("labels: passage " + id + " has no label");
        }
        String normal = verdict.trim().toUpperCase(Locale.ROOT);
        normal = "CONTENT".equals(normal) ? "INHALT" : "NOISE".equals(normal) ? "RAUSCHEN"
                : "MIXED".equals(normal) ? "MISCH" : normal;
        if (!LABELS.contains(normal)) {
            throw new IllegalArgumentException("labels: passage " + id + " is labeled '" + verdict + "' (one of "
                    + LABELS + ")");
        }
        return new Label(normal, kind.trim().toUpperCase(Locale.ROOT));
    }

    private static String idOf(JsonObject object) {
        return first(object, "passageId", "passage", "id");
    }

    private static String verdictOf(JsonObject object) {
        return first(object, "etikett", "urteil", "label", "erwartet", "verdict");
    }

    private static String first(JsonObject object, String... fields) {
        for (String field : fields) {
            JsonElement value = object.get(field);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().trim().isEmpty()) {
                return value.getAsString().trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------------------------------------- the run

    /**
     * The check on the labeled passages of the corpus (all of them with {@link Settings#all} or without labels), the
     * marks in {@code <out>/<model>/page-noise}, {@code urteile.jsonl} and {@code result.json} next to them.
     */
    static JsonObject run(Corpus corpus, Map<String, Label> labels, MainModelChat chat, Settings settings, File out)
            throws IOException {
        Set<String> inCorpus = new HashSet<String>();
        for (Passage passage : corpus.passages) {
            inCorpus.add(passage.getId());
        }
        List<String> missing = new ArrayList<String>();
        for (String id : labels.keySet()) {
            if (!inCorpus.contains(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(missing.size() + " labeled passages are not in the corpus, e.g. "
                    + missing.get(0));
        }
        List<Passage> checked = new ArrayList<Passage>();
        for (Passage passage : corpus.passages) {
            if ((settings.all || labels.isEmpty() || labels.containsKey(passage.getId()))
                    && (settings.limit <= 0 || checked.size() < settings.limit)) {
                checked.add(passage);
            }
        }
        List<String> wanted = new ArrayList<String>();
        for (Passage passage : checked) {
            wanted.add(passage.getId());
        }
        String model = chat == null ? "regeln" : chat.modelName();
        File dir = new File(out, model.replaceAll("[^A-Za-z0-9._-]", "_"));
        NoiseMarks marks = new NoiseMarks(dir);
        if (settings.fresh) {
            Files.deleteIfExists(marks.logFile().toPath());
        }
        try {
            marks.open();
        } catch (Exception unreadable) {
            throw new IOException("the marks of an earlier run cannot be read: " + unreadable.getMessage(),
                    unreadable);
        }
        PageNoiseCheck check = new PageNoiseCheck(chat, corpus.language, settings.options, null, Clock.systemUTC());
        long start = System.currentTimeMillis();
        Result run = check.check(corpus.passages, wanted, marks);
        long millis = System.currentTimeMillis() - start;
        Files.createDirectories(dir.toPath());
        try (Writer lines = Files.newBufferedWriter(new File(dir, "urteile.jsonl").toPath(), StandardCharsets.UTF_8)) {
            for (Passage passage : checked) {
                NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage));
                Label label = labels.get(passage.getId());
                JsonObject line = new JsonObject();
                line.addProperty("id", passage.getId());
                line.addProperty("etikett", label == null ? "" : label.verdict);
                line.addProperty("etikettKlasse", label == null ? "" : label.kind);
                line.addProperty("urteil", given(mark));
                line.addProperty("klasse", mark == null ? "" : mark.getKind().name());
                line.addProperty("stufe", mark == null ? 0 : mark.getStage());
                line.addProperty("grund", mark == null ? "" : mark.getReason());
                line.addProperty("fehler", mark == null ? "" : mark.getProblem());
                line.addProperty("ms", mark == null ? 0 : mark.getMillis());
                lines.write(line.toString());
                lines.write('\n');
            }
        }
        JsonObject result = counts(checked, labels, marks, run, settings, millis);
        result.addProperty("modell", model);
        result.addProperty("fassung", PageNoiseCheck.VERSION);
        result.addProperty("sprache", corpus.language);
        write(dir, result);
        return result;
    }

    private static String given(NoiseMark mark) {
        return mark == null ? "OHNE_MARKE" : mark.getStatus() == NoiseMark.Status.OK ? mark.getVerdict().name()
                : "FEHLER";
    }

    /** Counts and the criteria R1 to R3; ids and numbers only. */
    static JsonObject counts(List<Passage> checked, Map<String, Label> labels, NoiseMarks marks, Result run,
                             Settings settings, long millis) {
        Map<String, Integer> byVerdict = new LinkedHashMap<String, Integer>();
        Map<String, Integer> byKind = new LinkedHashMap<String, Integer>();
        Map<String, Integer> byStage = new LinkedHashMap<String, Integer>();
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<String, Map<String, Integer>>();
        for (String label : LABELS) {
            Map<String, Integer> row = new LinkedHashMap<String, Integer>();
            for (String given : GIVEN) {
                row.put(given, 0);
            }
            matrix.put(label, row);
        }
        List<String> missed = new ArrayList<String>();
        List<String> dropped = new ArrayList<String>();
        int caughtByRules = 0;
        int caughtByModel = 0;
        int droppedByRules = 0;
        int droppedByModel = 0;
        int errors = 0;
        int withoutMark = 0;
        for (Passage passage : checked) {
            NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage));
            String given = given(mark);
            byVerdict.merge(given, 1, Integer::sum);
            errors += "FEHLER".equals(given) ? 1 : 0;
            withoutMark += mark == null ? 1 : 0;
            boolean noise = mark != null && mark.isNoise();
            if (noise) {
                byKind.merge(mark.getKind().name(), 1, Integer::sum);
                byStage.merge(String.valueOf(mark.getStage()), 1, Integer::sum);
            }
            Label label = labels.get(passage.getId());
            if (label == null) {
                continue;
            }
            matrix.get(label.verdict).merge(mark == null ? "FEHLER" : given, 1, Integer::sum);
            if ("RAUSCHEN".equals(label.verdict)) {
                if (!noise) {
                    missed.add(passage.getId());
                } else if (mark.getStage() == NoiseMark.RULES) {
                    caughtByRules++;
                } else {
                    caughtByModel++;
                }
            } else if ("INHALT".equals(label.verdict) && noise) {
                dropped.add(passage.getId());
                if (mark.getStage() == NoiseMark.RULES) {
                    droppedByRules++;
                } else {
                    droppedByModel++;
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("passagen", checked.size());
        result.addProperty("texte", run.getTexts());
        result.addProperty("wiederverwendet", run.getReused());
        result.addProperty("durchRegeln", run.getByRules());
        result.addProperty("gefragt", run.getAsked());
        int overruled = 0;
        Map<String, Integer> overruledByLabel = new LinkedHashMap<String, Integer>();
        for (Passage passage : checked) {
            if (PageNoiseCheck.isOverruled(marks.mark(PageNoiseCheck.textKey(passage)))) {
                overruled++;
                Label label = labels.get(passage.getId());
                overruledByLabel.merge(label == null ? "OHNE_ETIKETT" : label.verdict, 1, Integer::sum);
            }
        }
        result.addProperty("artAusBeschreibung", overruled);
        result.add("artAusBeschreibungNachEtikett", tree(overruledByLabel));
        result.add("nachUrteil", tree(byVerdict));
        result.add("rauschenNachKlasse", tree(byKind));
        result.add("rauschenNachStufe", tree(byStage));
        result.addProperty("ohneMarke", withoutMark);
        if (!labels.isEmpty()) {
            JsonObject table = new JsonObject();
            for (Map.Entry<String, Map<String, Integer>> row : matrix.entrySet()) {
                table.add(row.getKey(), tree(row.getValue()));
            }
            result.add("etikettGegenUrteil", table);
            JsonObject criteria = new JsonObject();
            boolean passed = true;
            Map<String, Integer> noiseRow = matrix.get("RAUSCHEN");
            Map<String, Integer> contentRow = matrix.get("INHALT");
            passed &= criterion(criteria, "R1_trefferRauschen", total(noiseRow), settings.minNoise,
                    share(noiseRow, "RAUSCHEN"), settings.minRecall, true);
            passed &= criterion(criteria, "R2_inhaltVerworfen", total(contentRow), settings.minContent,
                    share(contentRow, "RAUSCHEN"), settings.maxDropped, false);
            passed &= criterion(criteria, "R3_fehler", checked.size(), 1,
                    checked.isEmpty() ? null : errors / (double) checked.size(), settings.maxErrors, false);
            result.add("kriterien", criteria);
            result.addProperty("bestanden", passed);
            result.addProperty("rauschenGefundenDurchRegeln", caughtByRules);
            result.addProperty("rauschenGefundenDurchModell", caughtByModel);
            result.addProperty("inhaltVerworfenDurchRegeln", droppedByRules);
            result.addProperty("inhaltVerworfenDurchModell", droppedByModel);
            result.addProperty("mischEtiketten", total(matrix.get("MISCH")));
            result.addProperty("mischVerworfen", matrix.get("MISCH").get("RAUSCHEN"));
            result.add("verfehlt", new GsonBuilder().create().toJsonTree(missed));
            result.add("verworfen", new GsonBuilder().create().toJsonTree(dropped));
        }
        List<Long> times = new ArrayList<Long>();
        for (NoiseMark mark : run.getMarks()) {
            if (mark.getStage() == NoiseMark.MODEL) {
                times.add(mark.getMillis());
            }
        }
        Collections.sort(times);
        result.addProperty("msGesamt", millis);
        result.addProperty("msMedian", times.isEmpty() ? 0 : times.get(times.size() / 2));
        result.addProperty("msP90", times.isEmpty() ? 0 : times.get(Math.min(times.size() - 1,
                (int) Math.ceil(times.size() * 0.9) - 1)));
        return result;
    }

    /** One criterion; with fewer than {@code min} pairs of its label it is not measurable and fails. */
    private static boolean criterion(JsonObject criteria, String name, int n, int min, Double value, double bound,
                                     boolean atLeast) {
        JsonObject json = new JsonObject();
        boolean measurable = value != null && n >= min;
        boolean met = measurable && (atLeast ? value >= bound : value <= bound);
        json.addProperty("passagen", n);
        json.addProperty("mindestensPassagen", min);
        if (measurable) {
            json.addProperty("wert", Math.round(value * 1000) / 1000.0);
        } else {
            json.addProperty("wert", "nicht messbar");
        }
        json.addProperty(atLeast ? "mindestens" : "hoechstens", bound);
        json.addProperty("bestanden", met);
        criteria.add(name, json);
        return met;
    }

    private static Double share(Map<String, Integer> row, String given) {
        int n = total(row);
        return n == 0 ? null : row.get(given) / (double) n;
    }

    private static int total(Map<String, Integer> row) {
        int n = 0;
        for (int value : row.values()) {
            n += value;
        }
        return n;
    }

    private static JsonObject tree(Map<String, Integer> counts) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static void write(File dir, JsonObject result) throws IOException {
        Files.createDirectories(dir.toPath());
        Files.write(new File(dir, "result.json").toPath(), new GsonBuilder().setPrettyPrinting().create()
                .toJson(result).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------- stored run

    @Test
    public void storedPassages() throws Exception {
        String corpusPath = System.getProperty(P + "corpus", "");
        String outPath = System.getProperty(P + "out", "");
        Assume.assumeTrue("set -D" + P + "corpus and -D" + P + "out", !corpusPath.isEmpty() && !outPath.isEmpty());
        Settings settings = settings();
        Corpus corpus;
        try (Reader in = Files.newBufferedReader(new File(corpusPath).toPath(), StandardCharsets.UTF_8)) {
            corpus = readCorpus(in);
        }
        int damaged = 0;
        for (Passage passage : corpus.passages) {
            damaged += MOJIBAKE.matcher(passage.getText()).find() ? 1 : 0;
        }
        if (damaged > Math.max(3, corpus.passages.size() / 100)) {
            throw new IllegalArgumentException(damaged + " passages look damaged (UTF-8 read as Latin-1); use a "
                    + "clean copy of the corpus");
        }
        List<String> files = new ArrayList<String>();
        for (String path : System.getProperty(P + "labels", "").split(",")) {
            if (!path.trim().isEmpty()) {
                files.add(new String(Files.readAllBytes(new File(path.trim()).toPath()), StandardCharsets.UTF_8));
            }
        }
        Map<String, Label> labels = readLabels(files);
        OllamaStockChat chat = null;
        if (!Boolean.parseBoolean(System.getProperty(P + "nurRegeln", "false"))) {
            String numCtx = System.getProperty(P + "numCtx", String.valueOf(PageNoiseCheck.CONTEXT_TOKENS));
            chat = new OllamaStockChat(System.getProperty(P + "chatUrl", "http://127.0.0.1:11434"),
                    System.getProperty(P + "chatModel", "gemma4:12b"), "omit".equals(numCtx) ? null
                    : Integer.valueOf(numCtx), Boolean.FALSE, (int) number("timeoutSeconds", 120) * 1000);
        }
        File out = new File(outPath);
        JsonObject result = run(corpus, labels, chat, settings, out);
        if (chat != null) {
            result.addProperty("aufrufe", chat.calls.get());
            result.addProperty("fehlgeschlageneAufrufe", chat.failures.get());
            result.addProperty("ausgabeTokens", chat.evalTokens.get());
            write(new File(out, chat.modelName().replaceAll("[^A-Za-z0-9._-]", "_")), result);
        }
        System.out.println("[page-noise] " + result.get("nachUrteil") + " " + (result.has("kriterien")
                ? result.get("kriterien") : "(no labels)"));
        if (result.has("bestanden")) {
            assertTrue("criteria R1-R3: see result.json in " + out, result.get("bestanden").getAsBoolean());
        }
    }

    private static Settings settings() {
        Settings settings = new Settings();
        settings.minRecall = number("minTrefferRauschen", settings.minRecall);
        settings.maxDropped = number("maxInhaltVerworfen", settings.maxDropped);
        settings.maxErrors = number("maxFehler", settings.maxErrors);
        settings.minNoise = (int) number("minAnzahlRauschen", settings.minNoise);
        settings.minContent = (int) number("minAnzahlInhalt", settings.minContent);
        settings.all = Boolean.parseBoolean(System.getProperty(P + "alle", "false"));
        settings.fresh = Boolean.parseBoolean(System.getProperty(P + "neu", "false"));
        settings.limit = (int) number("limit", 0);
        Options options = Options.defaults();
        options = options.withParallel((int) number("parallel", options.getParallel()));
        options = options.withAnswerTokens((int) number("antwortTokens", options.getAnswerTokens()));
        options = options.withPassageChars((int) number("passageZeichen", options.getPassageChars()));
        settings.options = options;
        return settings;
    }

    private static double number(String name, double fallback) {
        String value = System.getProperty(P + name, "");
        return value.isEmpty() ? fallback : Double.parseDouble(value);
    }

    // ------------------------------------------------------------------------------------------------- sample runs

    /** Three pages of self-written road traffic passages; p10 has no label. */
    static String sampleCorpus() {
        JsonObject corpus = new JsonObject();
        corpus.addProperty("mission", "Ein Sachbuch über Regeln im Straßenverkehr.");
        corpus.addProperty("language", "de");
        JsonArray passages = new JsonArray();
        String[][] rows = {
                {"p1", "s1", "verkehr.example", PageNoiseCheckTest.CONTENT},
                {"p2", "s1", "verkehr.example", PageNoiseCheckTest.MENU},
                {"p3", "s1", "verkehr.example", PageNoiseCheckTest.COOKIES},
                {"p4", "s1", "verkehr.example", PageNoiseCheckTest.CONTENT_2},
                {"p5", "s2", "", PageNoiseCheckTest.AD},
                {"p6", "s2", "", PageNoiseCheckTest.COUPON},
                {"p7", "s2", "", PageNoiseCheckTest.MIXED},
                {"p8", "s3", "verlag.example", PageNoiseCheckTest.CONTENTS},
                {"p9", "s3", "verlag.example", "Radfahrer dürfen Gehwege nur benutzen, wenn ein Schild es erlaubt, "
                        + "und Kinder bis zum achten Geburtstag müssen dort sogar fahren."},
                {"p10", "s1", "verkehr.example", "Die Vorfahrt regelt das Schild an der Kreuzung, und wo keines "
                        + "steht, gilt rechts vor links für alle, die dort fahren."}};
        for (String[] row : rows) {
            JsonObject passage = new JsonObject();
            passage.addProperty("id", row[0]);
            passage.addProperty("sourceId", row[1]);
            if (row[2].isEmpty()) {
                passage.addProperty("url", "https://www.fahrschule.example/angebot");
            } else {
                passage.addProperty("domain", row[2]);
            }
            passage.addProperty("text", row[3]);
            JsonArray vector = new JsonArray();
            vector.add(0.1);
            vector.add(0.2);
            passage.add("vector", vector);
            passages.add(passage);
        }
        corpus.add("passages", passages);
        return corpus.toString();
    }

    static final String LABELS_MAP = "{\"p1\":\"INHALT\",\"p2\":{\"etikett\":\"RAUSCHEN\",\"klasse\":\"NAVIGATION\"},"
            + "\"p3\":\"noise\",\"p4\":\"Inhalt\"}";
    static final String LABELS_LIST = "[{\"passageId\":\"p5\",\"urteil\":\"RAUSCHEN\",\"klasse\":\"WERBUNG\"},"
            + "{\"passageId\":\"p6\",\"urteil\":\"RAUSCHEN\"},{\"id\":\"p7\",\"label\":\"MISCH\"}]";
    static final String LABELS_LINES = "{\"passageId\":\"p8\",\"etikett\":\"RAUSCHEN\"}\n\n"
            + "{\"passageId\":\"p9\",\"etikett\":\"CONTENT\"}\n";

    static Map<String, Label> sampleLabels() {
        return readLabels(Arrays.asList(LABELS_MAP, LABELS_LIST, LABELS_LINES));
    }

    /** The sample is small: lower minimums, so that every criterion can be measured on it. */
    static Settings sampleSettings() {
        Settings settings = new Settings();
        settings.minNoise = 3;
        settings.minContent = 2;
        return settings;
    }

    @Test
    public void sampleRun() throws Exception {
        Corpus corpus = readCorpus(new StringReader(sampleCorpus()));
        assertEquals(10, corpus.passages.size());
        assertEquals("fahrschule.example", corpus.passages.get(4).getDomain());
        File out = scratch.newFolder("out");
        PageNoiseCheckTest.ScriptedChat chat = new PageNoiseCheckTest.ScriptedChat(PageNoiseCheckTest::careful);

        JsonObject result = run(corpus, sampleLabels(), chat, sampleSettings(), out);

        assertTrue(result.toString(), result.get("bestanden").getAsBoolean());
        assertEquals("the labeled passages only", 9, result.get("passagen").getAsInt());
        assertEquals(3, result.get("durchRegeln").getAsInt());
        assertEquals(6, result.get("gefragt").getAsInt());
        JsonObject criteria = result.getAsJsonObject("kriterien");
        assertEquals(1.0, criteria.getAsJsonObject("R1_trefferRauschen").get("wert").getAsDouble(), 0.0);
        assertEquals(5, criteria.getAsJsonObject("R1_trefferRauschen").get("passagen").getAsInt());
        assertEquals(0.0, criteria.getAsJsonObject("R2_inhaltVerworfen").get("wert").getAsDouble(), 0.0);
        assertEquals(3, criteria.getAsJsonObject("R2_inhaltVerworfen").get("passagen").getAsInt());
        assertEquals(0.0, criteria.getAsJsonObject("R3_fehler").get("wert").getAsDouble(), 0.0);
        assertEquals(3, result.get("rauschenGefundenDurchRegeln").getAsInt());
        assertEquals(2, result.get("rauschenGefundenDurchModell").getAsInt());
        assertEquals(1, result.get("mischEtiketten").getAsInt());
        assertEquals(0, result.get("mischVerworfen").getAsInt());
        assertEquals("a mixed passage whose biggest part is a statement is content", 1,
                result.getAsJsonObject("etikettGegenUrteil").getAsJsonObject("MISCH").get("INHALT").getAsInt());
        assertEquals(2, result.getAsJsonObject("rauschenNachKlasse").get("WERBUNG").getAsInt());
        assertEquals(3, result.getAsJsonObject("rauschenNachStufe").get("1").getAsInt());
        assertEquals("gemma4:e2b", result.get("modell").getAsString());
        assertFalse("counts only: " + result, result.toString().contains("Tempo"));
        assertFalse(result.toString().contains("wirbt"));
        File dir = new File(out, "gemma4_e2b");
        assertTrue(new File(dir, "result.json").isFile());
        List<String> lines = Files.readAllLines(new File(dir, "urteile.jsonl").toPath(), StandardCharsets.UTF_8);
        assertEquals(9, lines.size());
        JsonObject ad = JsonParser.parseString(lines.get(4)).getAsJsonObject();
        assertEquals("p5", ad.get("id").getAsString());
        assertEquals("RAUSCHEN", ad.get("etikett").getAsString());
        assertEquals("WERBUNG", ad.get("etikettKlasse").getAsString());
        assertEquals("RAUSCHEN", ad.get("urteil").getAsString());
        assertEquals(2, ad.get("stufe").getAsInt());
        assertTrue(new File(dir, "page-noise/marks.jsonl").isFile());

        JsonObject again = run(corpus, sampleLabels(), chat, sampleSettings(), out);
        assertEquals("a second run continues from the marks", 0, again.get("gefragt").getAsInt());
        assertEquals(9, again.get("wiederverwendet").getAsInt());
        assertEquals(criteria.toString(), again.getAsJsonObject("kriterien").toString());

        Settings fresh = sampleSettings();
        fresh.fresh = true;
        fresh.all = true;
        JsonObject all = run(corpus, sampleLabels(), chat, fresh, out);
        assertEquals(10, all.get("passagen").getAsInt());
        assertEquals("forgotten marks are given again", 7, all.get("gefragt").getAsInt());
    }

    @Test
    public void theRulesAloneMissWhatOnlyTheModelSees() throws Exception {
        Corpus corpus = readCorpus(new StringReader(sampleCorpus()));

        JsonObject result = run(corpus, sampleLabels(), null, sampleSettings(), scratch.newFolder("out"));

        assertFalse(result.get("bestanden").getAsBoolean());
        assertEquals(0.6, result.getAsJsonObject("kriterien").getAsJsonObject("R1_trefferRauschen").get("wert")
                .getAsDouble(), 0.0);
        assertEquals(Arrays.asList("p5", "p8").toString(), result.get("verfehlt").toString().replace("\"", "")
                .replace(",", ", "));
        assertEquals("regeln", result.get("modell").getAsString());
        assertEquals(0, result.get("gefragt").getAsInt());
    }

    @Test
    public void aModelThatCallsEverythingNoiseFails() throws Exception {
        Corpus corpus = readCorpus(new StringReader(sampleCorpus()));
        PageNoiseCheckTest.ScriptedChat alarmed = new PageNoiseCheckTest.ScriptedChat(prompt ->
                PageNoiseCheckTest.answer("Alles ist Rest der Seite.", "SONSTIGES"));

        JsonObject result = run(corpus, sampleLabels(), alarmed, sampleSettings(), scratch.newFolder("out"));

        assertFalse(result.get("bestanden").getAsBoolean());
        assertEquals(1.0, result.getAsJsonObject("kriterien").getAsJsonObject("R2_inhaltVerworfen").get("wert")
                .getAsDouble(), 0.0);
        assertEquals(3, result.get("inhaltVerworfenDurchModell").getAsInt());
        assertEquals(1, result.get("mischVerworfen").getAsInt());
        assertEquals(Arrays.asList("p1", "p4", "p9").toString(), result.get("verworfen").toString()
                .replace("\"", "").replace(",", ", "));
    }

    @Test
    public void tooFewLabelsAreNotMeasurable() throws Exception {
        Corpus corpus = readCorpus(new StringReader(sampleCorpus()));
        PageNoiseCheckTest.ScriptedChat chat = new PageNoiseCheckTest.ScriptedChat(PageNoiseCheckTest::careful);

        JsonObject result = run(corpus, sampleLabels(), chat, new Settings(), scratch.newFolder("out"));

        assertFalse(result.get("bestanden").getAsBoolean());
        JsonObject criteria = result.getAsJsonObject("kriterien");
        assertEquals("nicht messbar", criteria.getAsJsonObject("R1_trefferRauschen").get("wert").getAsString());
        assertEquals(30, criteria.getAsJsonObject("R1_trefferRauschen").get("mindestensPassagen").getAsInt());
        assertEquals("nicht messbar", criteria.getAsJsonObject("R2_inhaltVerworfen").get("wert").getAsString());
        assertEquals(100, criteria.getAsJsonObject("R2_inhaltVerworfen").get("mindestensPassagen").getAsInt());
        assertTrue(criteria.getAsJsonObject("R3_fehler").get("bestanden").getAsBoolean());
    }

    @Test
    public void withoutLabelsEveryPassageIsCheckedAndCounted() throws Exception {
        Corpus corpus = readCorpus(new StringReader(sampleCorpus()));
        PageNoiseCheckTest.ScriptedChat chat = new PageNoiseCheckTest.ScriptedChat(PageNoiseCheckTest::careful);
        Settings settings = sampleSettings();
        settings.limit = 4;

        JsonObject result = run(corpus, Collections.<String, Label>emptyMap(), chat, settings,
                scratch.newFolder("out"));

        assertEquals(4, result.get("passagen").getAsInt());
        assertFalse(result.has("kriterien"));
        assertEquals(2, result.getAsJsonObject("nachUrteil").get("RAUSCHEN").getAsInt());
        assertEquals(2, result.getAsJsonObject("nachUrteil").get("INHALT").getAsInt());
    }

    @Test
    public void labelsAreReadInEveryShapeAndBadOnesAreRefused() {
        Map<String, Label> labels = sampleLabels();
        assertEquals(9, labels.size());
        assertEquals("RAUSCHEN", labels.get("p3").verdict);
        assertEquals("INHALT", labels.get("p4").verdict);
        assertEquals("INHALT", labels.get("p9").verdict);
        assertEquals("NAVIGATION", labels.get("p2").kind);
        Map<String, Label> wrapped = readLabels(Collections.singletonList("{\"etiketten\":" + LABELS_LIST + "}"));
        assertEquals(3, wrapped.size());
        Map<String, Label> single = readLabels(Collections.singletonList("{\"passageId\":\"p8\",\"etikett\":"
                + "\"RAUSCHEN\"}"));
        assertEquals("RAUSCHEN", single.get("p8").verdict);
        assertEquals("the same label twice is fine", 1, readLabels(Arrays.asList("{\"p1\":\"INHALT\"}",
                "[{\"id\":\"p1\",\"etikett\":\"inhalt\"}]")).size());
        for (List<String> bad : Arrays.asList(Collections.singletonList("{\"p1\":\"VIELLEICHT\"}"),
                Collections.singletonList("[{\"etikett\":\"INHALT\"}]"),
                Collections.singletonList("[1]"),
                Collections.singletonList("{\"a\":1}\nkaputt"),
                Arrays.asList("{\"p1\":\"INHALT\"}", "{\"p1\":\"RAUSCHEN\"}"))) {
            try {
                readLabels(bad);
                throw new AssertionError("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void labelsOfPassagesOutsideTheCorpusStopTheRun() throws Exception {
        Corpus corpus = readCorpus(new StringReader(sampleCorpus()));
        try {
            run(corpus, readLabels(Collections.singletonList("{\"p99\":\"INHALT\"}")), null, sampleSettings(),
                    scratch.newFolder("out"));
            throw new AssertionError("ran on a label without passage");
        } catch (IllegalArgumentException expected) {
            assertEquals("1 labeled passages are not in the corpus, e.g. p99", expected.getMessage());
        }
    }
}

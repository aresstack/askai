package com.aresstack.askai.research.repeat;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.runtime.writer.BookRepeatCheck;
import com.aresstack.askai.research.runtime.writer.RepeatCheck;
import com.aresstack.askai.research.runtime.writer.SentenceVectors;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Component 12 on the judged sentence pairs of the book of 17 Nov ({@code repeat-stock/wiederholung-1711.json}, the
 * architect's 24 GLEICH, 32 VERSCHIEDEN and 4 TEILWEISE). The similarity bound is open: without a bound the test
 * writes the table of every bound from 0.70 to 0.99 (and a words-only table) and asserts nothing about the counts;
 * with {@code -Daskai.stock.repeat.minSimilarity} it counts against the targets fixed before the run.
 * {@link #samplePairs()} runs the whole path on six self-written pairs with a fake sentence model in every build;
 * {@link #storedPairs()} needs the sentence model and is skipped without {@code .url} or {@code .vectors}:
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*BookRepeatStockTest*'
 *   -Daskai.stock.repeat.url=http://127.0.0.1:11434   (the sentences are embedded once with the product's adapter)
 *   -Daskai.stock.repeat.vectors=sentence-vectors.json (instead of url: the vectors of an earlier run)
 *   -Daskai.stock.repeat.embedModel=bge-m3:latest -Daskai.stock.repeat.dimension=1024
 *   -Daskai.stock.repeat.out=build/component-stock/repeat
 *   -Daskai.stock.repeat.pairs=wiederholung-1711.json  (optional: else the copy in the repository)
 *   -Daskai.stock.repeat.minSimilarity=0.xx            (optional: the frozen bound; else the tables only)
 *   -Daskai.stock.repeat.minFound=20 -Daskai.stock.repeat.maxFalse=2 (targets of the architect's file)
 *   -Daskai.stock.repeat.minShare / .minWords          (optional: else the writer's defaults)
 * </pre>
 * The output folder gets {@code result.json} and {@code sentence-vectors.json}, so a second run needs no model.
 */
public class BookRepeatStockTest {

    private static final String P = "askai.stock.repeat.";

    @Rule
    public TemporaryFolder scratch = new TemporaryFolder();

    /** Cosine of each sample pair's later sentence to its earlier one, set by the fake model. */
    private static final Map<String, Double> SAMPLE_COSINES = new HashMap<String, Double>();

    static {
        SAMPLE_COSINES.put("S1", 0.955);
        SAMPLE_COSINES.put("S2", 0.905);
        SAMPLE_COSINES.put("S3", 0.805);
        SAMPLE_COSINES.put("S4", 0.935);
        SAMPLE_COSINES.put("S5", 0.975);
        SAMPLE_COSINES.put("S6", 0.925);
    }

    @Test
    public void samplePairs() throws Exception {
        List<RepeatStock.Case> cases = RepeatStock.read(resource("sample-pairs.json"));
        File out = scratch.newFolder("out");
        Run run = run(cases, new RepeatStock.CachingVectors(fake(cases)), "fake", 16, RepeatCheck.Criteria.defaults(),
                null, 1, 0, "sample-pairs.json", out);

        assertEquals(30, run.meaning.size());
        assertRow(run.meaning.get(0), 0.70, 2, Arrays.asList("S3", "S4"), 1);
        assertRow(row(run.meaning, 0.80), 0.80, 2, Arrays.asList("S3", "S4"), 1);
        assertRow(row(run.meaning, 0.81), 0.81, 2, Arrays.asList("S4"), 1);
        assertRow(row(run.meaning, 0.91), 0.91, 1, Arrays.asList("S4"), 1);
        assertEquals(Arrays.asList("S2"), row(run.meaning, 0.91).sameMissed);
        assertRow(row(run.meaning, 0.93), 0.93, 1, Arrays.asList("S4"), 0);
        assertRow(row(run.meaning, 0.94), 0.94, 1, new ArrayList<String>(), 0);
        assertRow(row(run.meaning, 0.96), 0.96, 0, new ArrayList<String>(), 0);
        assertTrue("S5 states another number: never a repeat", run.neverReported("S5"));
        assertEquals(Arrays.asList(0.94, 0.95), run.boundsMeetingTargets);
        assertEquals("one embedding call for all twelve sentences", 1, run.modelCalls);

        JsonObject s3 = run.pair("S3");
        assertEquals(0.805, s3.get("aehnlichkeit").getAsDouble(), 1e-3);
        assertEquals("beleg-S3", s3.getAsJsonArray("gemeinsameBelege").get(0).getAsString());
        assertFalse(run.pair("S5").get("zahlenGedeckt").getAsBoolean());
        assertEquals(1 / 7.0, run.pair("S3").get("wortanteil").getAsDouble(), 1e-4);
        assertTrue(new File(out, "result.json").isFile());
        assertTrue(new File(out, "sentence-vectors.json").isFile());

        RepeatStock.CachingVectors stored = RepeatStock.CachingVectors.fromJson(read(new File(out,
                "sentence-vectors.json").getPath()));
        Run frozen = run(cases, stored, "fake", 16, RepeatCheck.Criteria.defaults(), 0.94, 1, 0,
                "sample-pairs.json", scratch.newFolder("frozen"));
        assertEquals("the stored vectors need no model", 0, frozen.modelCalls);
        JsonObject verdict = frozen.result.getAsJsonObject("ergebnis");
        assertTrue(verdict.get("bestanden").getAsBoolean());
        assertEquals(1, verdict.get("gleichErkannt").getAsInt());
        assertEquals(0, verdict.get("verschiedenGemeldet").getAsInt());
        assertEquals(2, frozen.result.getAsJsonObject("zaehlung").get("GLEICH").getAsInt());
        assertTrue(frozen.pair("S1").get("gemeldetBeiSchwelle").getAsBoolean());
        assertEquals("MEANING", frozen.pair("S1").get("grund").getAsString());
        assertFalse(frozen.pair("S4").get("gemeldetBeiSchwelle").getAsBoolean());

        Run strict = run(cases, stored, "fake", 16, RepeatCheck.Criteria.defaults(), 0.96, 1, 0,
                "sample-pairs.json", scratch.newFolder("strict"));
        assertFalse(strict.result.getAsJsonObject("ergebnis").get("bestanden").getAsBoolean());
    }

    @Test
    public void storedPairs() throws Exception {
        String url = System.getProperty(P + "url", "");
        String vectorsPath = System.getProperty(P + "vectors", "");
        Assume.assumeTrue("repeat stock test skipped (no -D" + P + "url or -D" + P + "vectors)",
                !url.isEmpty() || !vectorsPath.isEmpty());
        String pairsPath = System.getProperty(P + "pairs", "");
        String json = pairsPath.isEmpty() ? resource("wiederholung-1711.json") : read(pairsPath);
        List<RepeatStock.Case> cases = RepeatStock.read(json);
        File out = new File(System.getProperty(P + "out", "build/component-stock/repeat"));
        Files.createDirectories(out.toPath());
        String model = System.getProperty(P + "embedModel", "bge-m3:latest");
        int dimension = Integer.getInteger(P + "dimension", 1024);
        RepeatStock.CachingVectors vectors = vectorsPath.isEmpty()
                ? new RepeatStock.CachingVectors(ollama(url, model, dimension))
                : RepeatStock.CachingVectors.fromJson(read(vectorsPath));
        RepeatCheck.Criteria defaults = RepeatCheck.Criteria.defaults();
        RepeatCheck.Criteria base = new RepeatCheck.Criteria(Integer.getInteger(P + "minWords",
                defaults.getMinWords()), Double.parseDouble(System.getProperty(P + "minShare",
                String.valueOf(defaults.getMinShare()))), defaults.getMinSimilarity());
        String frozenText = System.getProperty(P + "minSimilarity", "");
        Double frozen = frozenText.isEmpty() ? null : Double.valueOf(frozenText);
        int minFound = Integer.getInteger(P + "minFound", 20);
        int maxFalse = Integer.getInteger(P + "maxFalse", 2);
        System.out.println("[repeat] " + cases.size() + " pairs " + RepeatStock.counts(cases) + ", model " + model
                + (vectorsPath.isEmpty() ? " at " + url : " (stored vectors)") + ", " + base);

        Run run = run(cases, vectors, model, dimension, base, frozen, minFound, maxFalse,
                pairsPath.isEmpty() ? "Repo-Kopie wiederholung-1711.json" : pairsPath, out);
        System.out.println("[repeat] result " + new File(out, "result.json").getAbsolutePath());
        assertTrue("every check ran complete: " + run.incomplete, run.incomplete.isEmpty());
        if (frozen != null) {
            JsonObject verdict = run.result.getAsJsonObject("ergebnis");
            assertTrue(verdict.get("befund").getAsString(), verdict.get("bestanden").getAsBoolean());
        }
    }

    /** What one run produced. */
    static final class Run {
        JsonObject result;
        List<RepeatStock.Row> meaning;
        List<RepeatStock.Row> words;
        List<Double> boundsMeetingTargets = new ArrayList<Double>();
        List<String> incomplete = new ArrayList<String>();
        int modelCalls;

        boolean neverReported(String sheet) {
            for (List<RepeatStock.Row> table : Arrays.asList(meaning, words)) {
                for (RepeatStock.Row row : table) {
                    if (row.sameFound.contains(sheet) || row.differentFlagged.contains(sheet)
                            || row.partFlagged.contains(sheet)) {
                        return false;
                    }
                }
            }
            return true;
        }

        JsonObject pair(String sheet) {
            for (JsonElement e : result.getAsJsonArray("paarwerte")) {
                if (sheet.equals(e.getAsJsonObject().get("blatt").getAsString())) {
                    return e.getAsJsonObject();
                }
            }
            throw new AssertionError("no pair " + sheet);
        }
    }

    static Run run(List<RepeatStock.Case> cases, RepeatStock.CachingVectors vectors, String model, int dimension,
                   RepeatCheck.Criteria base, Double frozen, int minFound, int maxFalse, String source, File out)
            throws Exception {
        int callsBefore = vectors.calls;
        vectors.warm(RepeatStock.texts(cases), BookRepeatCheck.Options.DEFAULT_BATCH_SIZE);
        Run run = new Run();
        run.modelCalls = vectors.calls - callsBefore;

        JsonObject result = new JsonObject();
        result.addProperty("stand", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()));
        result.addProperty("datei", source);
        result.addProperty("paare", cases.size());
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> e : RepeatStock.counts(cases).entrySet()) {
            counts.addProperty(e.getKey(), e.getValue());
        }
        result.add("zaehlung", counts);
        JsonObject modelInfo = new JsonObject();
        modelInfo.addProperty("name", model);
        modelInfo.addProperty("dimension", dimension);
        modelInfo.addProperty("aufrufe", run.modelCalls);
        result.add("modell", modelInfo);
        JsonObject rules = new JsonObject();
        rules.addProperty("mindestInhaltswoerter", base.getMinWords());
        rules.addProperty("wortanteil", base.getMinShare());
        rules.addProperty("voreinstellungAehnlichkeit", RepeatCheck.Criteria.DEFAULT_MIN_SIMILARITY);
        rules.addProperty("nurFruehereAbschnitte", true);
        result.add("regeln", rules);
        JsonObject targets = new JsonObject();
        targets.addProperty("gleichMindestens", minFound);
        targets.addProperty("verschiedenHoechstens", maxFalse);
        result.add("vorgaben", targets);

        run.meaning = new ArrayList<RepeatStock.Row>();
        JsonArray meaning = new JsonArray();
        for (double bound : RepeatStock.similarityBounds()) {
            RepeatStock.Row row = RepeatStock.row(cases, RepeatStock.check(base, bound, vectors), bound, minFound,
                    maxFalse);
            run.meaning.add(row);
            run.incomplete.addAll(row.incomplete);
            meaning.add(row.toJson("schwelle"));
            if (row.meetsTargets) {
                run.boundsMeetingTargets.add(bound);
            }
            System.out.println(row.line("Aehnlichkeit"));
        }
        result.add("tabelleBedeutung", meaning);
        run.words = new ArrayList<RepeatStock.Row>();
        JsonArray words = new JsonArray();
        for (double share : RepeatStock.shareBounds()) {
            RepeatStock.Row row = RepeatStock.row(cases, RepeatStock.wordsOnly(base, share), share, minFound,
                    maxFalse);
            run.words.add(row);
            words.add(row.toJson("wortanteil"));
            System.out.println(row.line("nur Woerter, Anteil"));
        }
        result.add("tabelleNurWoerter", words);
        JsonArray meeting = new JsonArray();
        for (double bound : run.boundsMeetingTargets) {
            meeting.add(bound);
        }
        result.add("schwellenDieVorgabenErfuellen", meeting);

        double shown = frozen == null ? RepeatCheck.Criteria.DEFAULT_MIN_SIMILARITY : frozen;
        String reportedKey = frozen == null ? "gemeldetBeiVoreinstellung" : "gemeldetBeiSchwelle";
        result.add("paarwerte", RepeatStock.pairValues(cases, RepeatStock.check(base, shown, vectors), reportedKey));
        if (frozen != null) {
            result.addProperty("eingefroreneSchwelle", frozen);
            RepeatStock.Row row = RepeatStock.row(cases, RepeatStock.check(base, frozen, vectors), frozen, minFound,
                    maxFalse);
            JsonObject verdict = row.toJson("schwelle");
            boolean passed = row.meetsTargets;
            verdict.addProperty("bestanden", passed);
            verdict.addProperty("befund", String.format(java.util.Locale.ROOT,
                    "Schwelle %.2f: GLEICH %d von %d erkannt (mindestens %d), VERSCHIEDEN %d von %d gemeldet "
                            + "(hoechstens %d), TEILWEISE %d von %d gemeldet (nur Bericht)%s", frozen,
                    row.sameFound.size(), row.same, minFound, row.differentFlagged.size(), row.different, maxFalse,
                    row.partFlagged.size(), row.part, row.incomplete.isEmpty() ? "" : ", unvollstaendig"));
            result.add("ergebnis", verdict);
            System.out.println("[repeat] " + verdict.get("befund").getAsString());
        }
        if (!run.incomplete.isEmpty()) {
            result.add("unvollstaendig", RepeatStock.strings(run.incomplete));
        }
        run.result = result;
        Files.write(new File(out, "result.json").toPath(), RepeatStock.pretty(result).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "sentence-vectors.json").toPath(),
                vectors.toJson(model, dimension).getBytes(StandardCharsets.UTF_8));
        return run;
    }

    /** The product's adapter as the sentence slot uses it: documents, one call per batch. */
    static SentenceVectors ollama(String url, String model, int dimension) {
        EmbeddingEndpointDescriptor descriptor = new EmbeddingEndpointDescriptor(model, url, "/api/embed", dimension,
                "", "", 120000L);
        final HttpEmbeddingPortAdapter adapter = new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport(120000));
        return new SentenceVectors() {
            public List<float[]> embed(List<String> sentences) {
                List<float[]> out = new ArrayList<float[]>(sentences.size());
                for (EmbeddingPort.EmbeddingVector v : adapter.embed(EmbeddingPort.Role.DOCUMENT, sentences)) {
                    out.add(v.getValues());
                }
                return out;
            }
        };
    }

    /** Each pair's earlier sentence on an axis of its own, the later one at the pair's cosine to it. */
    static SentenceVectors fake(List<RepeatStock.Case> cases) {
        final Map<String, float[]> vectors = new HashMap<String, float[]>();
        int axis = 0;
        for (RepeatStock.Case c : cases) {
            List<BookRepeatCheck.Sentence> book = c.book();
            float[] earlier = new float[16];
            earlier[axis] = 1;
            double cos = SAMPLE_COSINES.get(c.sheet);
            float[] later = new float[16];
            later[axis] = (float) cos;
            later[axis + 1] = (float) Math.sqrt(1 - cos * cos);
            vectors.put(book.get(0).plainText(), earlier);
            vectors.put(book.get(1).plainText(), later);
            axis += 2;
        }
        return new SentenceVectors() {
            public List<float[]> embed(List<String> sentences) {
                List<float[]> out = new ArrayList<float[]>();
                for (String s : sentences) {
                    out.add(vectors.get(s));
                }
                return out;
            }
        };
    }

    private static void assertRow(RepeatStock.Row row, double bound, int sameFound, List<String> differentFlagged,
                                  int partFlagged) {
        assertEquals(bound, row.value, 1e-9);
        assertEquals("GLEICH at " + bound, sameFound, row.sameFound.size());
        assertEquals("VERSCHIEDEN at " + bound, differentFlagged, row.differentFlagged);
        assertEquals("TEILWEISE at " + bound, partFlagged, row.partFlagged.size());
    }

    private static RepeatStock.Row row(List<RepeatStock.Row> table, double bound) {
        for (RepeatStock.Row row : table) {
            if (Math.abs(row.value - bound) < 1e-9) {
                return row;
            }
        }
        throw new AssertionError("no row " + bound);
    }

    static String resource(String name) throws IOException {
        InputStream in = BookRepeatStockTest.class.getResourceAsStream("/repeat-stock/" + name);
        if (in == null) {
            throw new IOException("missing test resource " + name);
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}

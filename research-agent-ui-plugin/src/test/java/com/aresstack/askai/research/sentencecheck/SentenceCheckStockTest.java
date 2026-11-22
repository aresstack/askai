package com.aresstack.askai.research.sentencecheck;

import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.sentencecheck.SentenceCheck.Item;
import com.aresstack.askai.research.sentencecheck.SentenceCheck.Options;
import com.aresstack.askai.research.sentencecheck.SentenceCheck.Result;
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

import java.io.BufferedReader;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The sentence check on stored pairs, on the owner's machine; skipped without its file. The sample tests run the same
 * path on self-written pairs with a scripted model in every build.
 *
 * <p>{@link #storedPairs()}: the architect's pairs of the book of 17.11.2026 ({@code saetze-1711-mit-text.jsonl}): 60
 * rated pairs of a sentence and ONE passage it cites (S01 to S60: 47 GEDECKT, 10 TEILWEISE, 3 NICHT_GEDECKT) and 60
 * swapped pairs (V01 to V60: the sentence with the passage of another section and source, expected NICHT_GEDECKT; two
 * are {@code themennah}). One call per pair, as in the product; the model never sees a label. Criteria fixed by the
 * architect before the build (21.11.2026, 20:18 CET):</p>
 * <ul>
 * <li>T1: of the 58 swapped pairs that are not {@code themennah}, at least 54 NICHT_GEDECKT;</li>
 * <li>T2: of the 60 swapped pairs, at most 1 GEDECKT;</li>
 * <li>T3: of the 47 covered pairs, at least 40 GEDECKT and at most 2 NICHT_GEDECKT;</li>
 * <li>T4: of the 10 partly covered pairs, at most 5 GEDECKT;</li>
 * <li>T5: at most 3 of the 120 pairs FEHLER (no valid verdict after one repair).</li>
 * </ul>
 * <p>Passed only when T1 to T5 all hold. A group of another size is not measurable and fails. The verdict after the
 * quote check decides ({@code .urteil=modell}: the model's); the criteria on the other one are reported. Reported
 * only: the three real NICHT_GEDECKT, run time and throughput, the kind of the passage (6 of the 60 are no statement)
 * and what the quote check took down.</p>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*SentenceCheckStockTest*'
 *   -Daskai.stock.sentence.paare=saetze-1711-mit-text.jsonl -Daskai.stock.sentence.out=&lt;folder&gt;
 *   -Daskai.stock.sentence.chatUrl=http://127.0.0.1:11434 -Daskai.stock.sentence.chatModel=gemma4:12b
 *   optional: .urteil (geprueft; modell), .neu=true (forget the verdicts of an earlier run), .limit (0 = all),
 *             .parallel (4), .antwortTokens (768), .passageZeichen (4000), .numCtx (8192; "omit" sends none),
 *             .think (false; "omit" sends none), .timeoutSeconds (120)
 * </pre>
 * The verdicts go to {@code <out>/<model>/projekt/sentence-check/verdicts.jsonl}, and a run that stops continues from
 * them; they hold quotes and reasons, words of the sources, and stay on the machine.
 * {@code <out>/<model>/urteile.jsonl} holds per pair its label and verdicts, {@code result.json} ids and counts;
 * neither holds a sentence, a quote, a passage or a reason.
 */
public class SentenceCheckStockTest {

    private static final String P = "askai.stock.sentence.";
    /** UTF-8 read as Latin-1 ("Ã¤", "â€", "Â§"): a damaged copy looks like this. */
    static final Pattern MOJIBAKE = Pattern.compile("\\u00C3[\\u0080-\\u00BF]|\\u00E2\\u20AC|\\u00C2[\\u00A0-\\u00BF]");
    static final List<String> LABELS = Arrays.asList("GEDECKT", "TEILWEISE", "NICHT_GEDECKT");

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    /** One pair of the file: a sentence, one passage it cites (or a swapped one) and the label. */
    static final class Pair {
        final String id;
        final String sentence;
        final String passageId;
        final String passageText;
        final String label;
        final String kind;
        final boolean swapped;
        final boolean near;
        final boolean several;

        Pair(String id, String sentence, String passageId, String passageText, String label, String kind,
             boolean swapped, boolean near, boolean several) {
            this.id = id;
            this.sentence = sentence;
            this.passageId = passageId;
            this.passageText = passageText;
            this.label = label;
            this.kind = kind;
            this.swapped = swapped;
            this.near = near;
            this.several = several;
        }
    }

    /** The criteria's groups and bounds; the defaults are the architect's for the 120 pairs of 17.11. */
    static final class Settings {
        Options options = Options.defaults();
        int swappedFar = 58;
        int t1Min = 54;
        int swapped = 60;
        int t2Max = 1;
        int covered = 47;
        int t3Min = 40;
        int t3MaxNot = 2;
        int partial = 10;
        int t4Max = 5;
        int all = 120;
        int t5Max = 3;
        boolean byModel;
        boolean fresh;
        int limit;
    }

    // ------------------------------------------------------------------------------------------------- reading

    /**
     * One JSON object per line: {@code blatt} (or {@code id}), {@code sentence}, {@code passageId}, {@code passageText}
     * and the label: {@code urteil} for a rated pair (with {@code belegart} and {@code mehrereBelege}),
     * {@code erwartet} for a swapped one (with {@code themennah}).
     */
    static List<Pair> readPairs(Reader in) throws IOException {
        BufferedReader lines = new BufferedReader(in);
        List<Pair> pairs = new ArrayList<Pair>();
        Set<String> ids = new HashSet<String>();
        String line;
        int number = 0;
        while ((line = lines.readLine()) != null) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject json;
            try {
                json = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException bad) {
                throw new IOException("line " + number + " is no JSON object");
            }
            String id = string(json, "blatt").isEmpty() ? string(json, "id") : string(json, "blatt");
            if (!json.has("passageText")) {
                throw new IOException("line " + number + " has no passageText: give the file with the passage texts");
            }
            boolean swapped = !json.has("urteil") && json.has("erwartet");
            String label = string(json, swapped ? "erwartet" : "urteil").toUpperCase(Locale.ROOT);
            if (id.isEmpty() || string(json, "sentence").isEmpty() || string(json, "passageId").isEmpty()
                    || !LABELS.contains(label)) {
                throw new IOException("line " + number + " needs blatt, sentence, passageId and a label (urteil or "
                        + "erwartet: GEDECKT, TEILWEISE or NICHT_GEDECKT)");
            }
            if (!ids.add(id)) {
                throw new IOException("line " + number + ": the pair " + id + " comes twice");
            }
            pairs.add(new Pair(id, string(json, "sentence"), string(json, "passageId"), string(json, "passageText"),
                    label, string(json, "belegart").toUpperCase(Locale.ROOT), swapped, bool(json, "themennah"),
                    bool(json, "mehrereBelege")));
        }
        return pairs;
    }

    /** Stops on a copy whose texts were read as Latin-1 on the way. */
    static void checkEncoding(List<Pair> pairs) throws IOException {
        List<String> damaged = new ArrayList<String>();
        for (Pair pair : pairs) {
            if (MOJIBAKE.matcher(pair.sentence + " " + pair.passageText).find()) {
                damaged.add(pair.id);
            }
        }
        if (!damaged.isEmpty()) {
            throw new IOException(damaged.size() + " pairs look like UTF-8 read as Latin-1 (first: " + damaged.get(0)
                    + "); give an undamaged copy");
        }
    }

    // ------------------------------------------------------------------------------------------------- run

    /** Checks the pairs, writes urteile.jsonl and result.json to {@code <out>/<model>/} and returns the result. */
    static JsonObject run(List<Pair> pairs, MainModelChat chat, String model, File out, Settings settings)
            throws Exception {
        File folder = new File(out, safe(model));
        SentenceVerdicts store = new SentenceVerdicts(new File(folder, "projekt"));
        if (settings.fresh && store.logFile().isFile()) {
            Files.delete(store.logFile().toPath());
        }
        store.open();
        List<Item> items = new ArrayList<Item>();
        for (Pair pair : pairs) {
            items.add(new Item(pair.id, pair.sentence, pair.passageId, "", pair.passageText));
        }
        long began = System.currentTimeMillis();
        Result result = new SentenceCheck(chat, "de", settings.options, Clock.systemUTC()).check(items, store);
        long took = System.currentTimeMillis() - began;
        Map<String, SentenceVerdict> verdicts = new LinkedHashMap<String, SentenceVerdict>();
        for (int i = 0; i < pairs.size(); i++) {
            verdicts.put(pairs.get(i).id, store.verdict(items.get(i).sentenceKey(), items.get(i).textKey()));
        }

        Files.createDirectories(folder.toPath());
        try (Writer lines = Files.newBufferedWriter(new File(folder, "urteile.jsonl").toPath(),
                StandardCharsets.UTF_8)) {
            for (Pair pair : pairs) {
                SentenceVerdict verdict = verdicts.get(pair.id);
                JsonObject line = new JsonObject();
                line.addProperty("blatt", pair.id);
                line.addProperty("gruppe", pair.swapped ? "vertauscht" : "bewertet");
                line.addProperty("erwartet", pair.label);
                line.addProperty("themennah", pair.near);
                line.addProperty("mehrereBelege", pair.several);
                line.addProperty("urteilModell", name(verdict, true));
                line.addProperty("urteil", name(verdict, false));
                line.addProperty("belegartErwartet", pair.kind);
                line.addProperty("belegart", verdict == null || verdict.getKind() == null ? ""
                        : verdict.getKind().name());
                line.addProperty("status", verdict == null ? "" : verdict.getStatus().name());
                line.addProperty("herabgestuft", verdict != null && verdict.isDowngraded());
                line.addProperty("ms", verdict == null ? 0 : verdict.getMillis());
                lines.write(line.toString() + "\n");
            }
        }

        JsonObject json = new JsonObject();
        json.addProperty("modell", model);
        json.addProperty("fassung", SentenceCheck.VERSION);
        json.addProperty("paare", pairs.size());
        json.addProperty("gefragt", result.getAsked());
        json.addProperty("wiederverwendet", result.getReused());
        json.addProperty("entscheidet", settings.byModel ? "modell" : "geprueft");
        JsonObject deciding = criteria(pairs, verdicts, settings.byModel, settings);
        json.add("kriterien", deciding);
        json.add("kriterienAndereUrteile", criteria(pairs, verdicts, !settings.byModel, settings));
        json.addProperty("bestanden", deciding.get("bestanden").getAsBoolean());
        JsonObject downgraded = new JsonObject();
        downgraded.addProperty("alle", count(pairs, p -> true, verdicts, v -> v.isDowngraded()));
        downgraded.addProperty("gedeckt", count(pairs, Covered.COVERED, verdicts, v -> v.isDowngraded()));
        downgraded.addProperty("teilweise", count(pairs, Covered.PARTIAL, verdicts, v -> v.isDowngraded()));
        downgraded.addProperty("vertauscht", count(pairs, p -> p.swapped, verdicts, v -> v.isDowngraded()));
        json.add("herabgestuft", downgraded);
        JsonArray notCovered = new JsonArray();
        for (Pair pair : pairs) {
            if (!pair.swapped && "NICHT_GEDECKT".equals(pair.label)) {
                JsonObject row = new JsonObject();
                row.addProperty("blatt", pair.id);
                row.addProperty("urteilModell", name(verdicts.get(pair.id), true));
                row.addProperty("urteil", name(verdicts.get(pair.id), false));
                notCovered.add(row);
            }
        }
        json.add("echteNichtGedeckt", notCovered);
        json.add("belegart", kinds(pairs, verdicts));
        json.add("laufzeit", times(verdicts.values(), took, result.getAsked()));
        JsonArray failures = new JsonArray();
        for (Pair pair : pairs) {
            SentenceVerdict verdict = verdicts.get(pair.id);
            if (verdict == null || verdict.getStatus() != SentenceVerdict.Status.OK) {
                JsonObject row = new JsonObject();
                row.addProperty("blatt", pair.id);
                String problem = verdict == null ? "no verdict" : verdict.getProblem();
                row.addProperty("fehler", problem.length() > 160 ? problem.substring(0, 160) : problem);
                failures.add(row);
            }
        }
        json.add("fehler", failures);
        Files.write(new File(folder, "result.json").toPath(), new GsonBuilder().setPrettyPrinting().create()
                .toJson(json).getBytes(StandardCharsets.UTF_8));
        return json;
    }

    /** The groups of the rated pairs. */
    enum Covered implements Predicate<Pair> {
        COVERED("GEDECKT"), PARTIAL("TEILWEISE"), NOT("NICHT_GEDECKT");

        private final String label;

        Covered(String label) {
            this.label = label;
        }

        @Override
        public boolean test(Pair pair) {
            return !pair.swapped && label.equals(pair.label);
        }
    }

    /** T1 to T5 on the model's verdicts ({@code byModel}) or on those after the quote check. */
    static JsonObject criteria(List<Pair> pairs, Map<String, SentenceVerdict> verdicts, boolean byModel,
                               Settings settings) {
        JsonObject criteria = new JsonObject();
        Predicate<Pair> far = pair -> pair.swapped && !pair.near;
        Predicate<Pair> swapped = pair -> pair.swapped;
        boolean t1 = criterion(criteria, "T1", size(pairs, far), settings.swappedFar,
                count(pairs, far, verdicts, is(Verdict.NICHT_GEDECKT, byModel)), settings.t1Min, true);
        boolean t2 = criterion(criteria, "T2", size(pairs, swapped), settings.swapped,
                count(pairs, swapped, verdicts, is(Verdict.GEDECKT, byModel)), settings.t2Max, false);
        int covered = size(pairs, Covered.COVERED);
        int coveredYes = count(pairs, Covered.COVERED, verdicts, is(Verdict.GEDECKT, byModel));
        int coveredNo = count(pairs, Covered.COVERED, verdicts, is(Verdict.NICHT_GEDECKT, byModel));
        JsonObject row = new JsonObject();
        boolean measurable = covered == settings.covered;
        boolean t3 = measurable && coveredYes >= settings.t3Min && coveredNo <= settings.t3MaxNot;
        row.addProperty("n", covered);
        row.addProperty("erwartetN", settings.covered);
        row.addProperty("gedeckt", coveredYes);
        row.addProperty("grenzeGedeckt", ">= " + settings.t3Min);
        row.addProperty("nichtGedeckt", coveredNo);
        row.addProperty("grenzeNichtGedeckt", "<= " + settings.t3MaxNot);
        row.addProperty("messbar", measurable);
        row.addProperty("erfuellt", t3);
        criteria.add("T3", row);
        boolean t4 = criterion(criteria, "T4", size(pairs, Covered.PARTIAL), settings.partial,
                count(pairs, Covered.PARTIAL, verdicts, is(Verdict.GEDECKT, byModel)), settings.t4Max, false);
        boolean t5 = criterion(criteria, "T5", pairs.size(), settings.all,
                count(pairs, pair -> true, verdicts, null), settings.t5Max, false);
        criteria.addProperty("bestanden", t1 && t2 && t3 && t4 && t5);
        return criteria;
    }

    private static boolean criterion(JsonObject criteria, String name, int n, int expected, int value, int bound,
                                     boolean atLeast) {
        boolean measurable = n == expected;
        boolean met = measurable && (atLeast ? value >= bound : value <= bound);
        JsonObject row = new JsonObject();
        row.addProperty("n", n);
        row.addProperty("erwartetN", expected);
        row.addProperty("wert", value);
        row.addProperty("grenze", (atLeast ? ">= " : "<= ") + bound);
        row.addProperty("messbar", measurable);
        row.addProperty("erfuellt", met);
        criteria.add(name, row);
        return met;
    }

    private static Predicate<SentenceVerdict> is(Verdict wanted, boolean byModel) {
        return verdict -> verdict.getStatus() == SentenceVerdict.Status.OK
                && (byModel ? verdict.getSaid() : verdict.getVerdict()) == wanted;
    }

    private static int size(List<Pair> pairs, Predicate<Pair> group) {
        int n = 0;
        for (Pair pair : pairs) {
            n += group.test(pair) ? 1 : 0;
        }
        return n;
    }

    /** Pairs of the group whose verdict passes {@code test}; {@code test == null} counts the pairs without one. */
    private static int count(List<Pair> pairs, Predicate<Pair> group, Map<String, SentenceVerdict> verdicts,
                             Predicate<SentenceVerdict> test) {
        int n = 0;
        for (Pair pair : pairs) {
            if (!group.test(pair)) {
                continue;
            }
            SentenceVerdict verdict = verdicts.get(pair.id);
            boolean failed = verdict == null || verdict.getStatus() != SentenceVerdict.Status.OK;
            n += test == null ? (failed ? 1 : 0) : (!failed && test.test(verdict) ? 1 : 0);
        }
        return n;
    }

    /** The kinds of passage of the rated pairs: label against verdict, and how many non-statements were seen. */
    static JsonObject kinds(List<Pair> pairs, Map<String, SentenceVerdict> verdicts) {
        Map<String, Map<String, Integer>> matrix = new TreeMap<String, Map<String, Integer>>();
        int noStatement = 0;
        int seen = 0;
        int textAsNone = 0;
        for (Pair pair : pairs) {
            SentenceVerdict verdict = verdicts.get(pair.id);
            if (pair.swapped || pair.kind.isEmpty() || verdict == null || verdict.getKind() == null) {
                continue;
            }
            String given = verdict.getKind().name();
            matrix.computeIfAbsent(pair.kind, ignored -> new TreeMap<String, Integer>()).merge(given, 1,
                    Integer::sum);
            boolean labeledText = "TEXT".equals(pair.kind);
            boolean saidText = verdict.getKind() == SentenceVerdict.Kind.TEXT;
            noStatement += labeledText ? 0 : 1;
            seen += !labeledText && !saidText ? 1 : 0;
            textAsNone += labeledText && !saidText ? 1 : 0;
        }
        JsonObject json = new JsonObject();
        JsonObject rows = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> row : matrix.entrySet()) {
            JsonObject cells = new JsonObject();
            for (Map.Entry<String, Integer> cell : row.getValue().entrySet()) {
                cells.addProperty(cell.getKey(), cell.getValue());
            }
            rows.add(row.getKey(), cells);
        }
        json.add("erwartetGegenUrteil", rows);
        json.addProperty("keineAussage", noStatement);
        json.addProperty("keineAussageErkannt", seen);
        json.addProperty("textAlsKeineAussage", textAsNone);
        return json;
    }

    private static JsonObject times(java.util.Collection<SentenceVerdict> verdicts, long took, int asked) {
        List<Long> millis = new ArrayList<Long>();
        for (SentenceVerdict verdict : verdicts) {
            if (verdict != null && verdict.getMillis() > 0) {
                millis.add(verdict.getMillis());
            }
        }
        Collections.sort(millis);
        long sum = 0;
        for (long value : millis) {
            sum += value;
        }
        JsonObject json = new JsonObject();
        json.addProperty("msGesamt", took);
        json.addProperty("msJeAufrufMedian", millis.isEmpty() ? 0 : millis.get(millis.size() / 2));
        json.addProperty("msJeAufrufMittel", millis.isEmpty() ? 0 : sum / millis.size());
        json.addProperty("paareJeMinute", took <= 0 ? 0 : Math.round(asked * 60000.0 / took * 10) / 10.0);
        return json;
    }

    private static String name(SentenceVerdict verdict, boolean model) {
        if (verdict == null || verdict.getStatus() != SentenceVerdict.Status.OK) {
            return "FEHLER";
        }
        return (model ? verdict.getSaid() : verdict.getVerdict()).name();
    }

    // ------------------------------------------------------------------------------------------------- the real run

    @Test
    public void storedPairs() throws Exception {
        String file = System.getProperty(P + "paare", "").trim();
        String out = System.getProperty(P + "out", "").trim();
        Assume.assumeTrue("needs -D" + P + "paare and -D" + P + "out", !file.isEmpty() && !out.isEmpty());
        Settings settings = new Settings();
        settings.options = Options.defaults().withParallel(number("parallel", 4))
                .withAnswerTokens(number("antwortTokens", 768)).withPassageChars(number("passageZeichen", 4000));
        settings.byModel = "modell".equalsIgnoreCase(System.getProperty(P + "urteil", "geprueft").trim());
        settings.fresh = Boolean.parseBoolean(System.getProperty(P + "neu", "false").trim());
        settings.limit = number("limit", 0);
        List<Pair> pairs;
        try (Reader reader = Files.newBufferedReader(new File(file).toPath(), StandardCharsets.UTF_8)) {
            pairs = readPairs(reader);
        }
        checkEncoding(pairs);
        if (settings.limit > 0 && settings.limit < pairs.size()) {
            pairs = new ArrayList<Pair>(pairs.subList(0, settings.limit));
        }
        String model = System.getProperty(P + "chatModel", "gemma4:12b").trim();
        String numCtx = System.getProperty(P + "numCtx", "8192").trim();
        String think = System.getProperty(P + "think", "false").trim();
        OllamaStockChat chat = new OllamaStockChat(System.getProperty(P + "chatUrl", "http://127.0.0.1:11434").trim(),
                model, "omit".equalsIgnoreCase(numCtx) ? null : Integer.valueOf(numCtx),
                "omit".equalsIgnoreCase(think) ? null : Boolean.valueOf(think), number("timeoutSeconds", 120) * 1000);

        JsonObject result = run(pairs, chat, model, new File(out), settings);

        System.out.println("[sentence-check stock] " + model + ": " + result.getAsJsonObject("kriterien")
                + "; calls " + chat.calls.get() + ", failed calls " + chat.failures.get() + ", tokens "
                + chat.evalTokens.get() + "; " + result.get("laufzeit"));
        assertTrue("criteria not met, see " + new File(new File(out, safe(model)), "result.json"),
                result.get("bestanden").getAsBoolean());
    }

    // ------------------------------------------------------------------------------------------------- samples

    static final String P1 = "In den Tempo-30-Zonen der Stadt sank die Zahl der Unfälle mit Verletzten im ersten Jahr "
            + "um 25 Prozent. Die Verwaltung führt das auf kürzere Bremswege zurück.";
    static final String P2 = "Fahrradstraßen sind Straßen, in denen der Radverkehr Vorrang hat. Radfahrer dürfen dort "
            + "nebeneinander fahren, Autos nur mit einem Zusatzzeichen.";
    static final String P3 = "Anwohnerparkausweise kosten in vielen Städten seit 2023 zwischen 30 und 360 Euro im "
            + "Jahr. Die Gebühr legen die Länder oder die Gemeinden fest.";
    static final String P4 = "Schulstraßen werden vor Unterrichtsbeginn für eine halbe Stunde für Autos gesperrt. "
            + "Eltern bringen ihre Kinder dann zu Fuß oder mit dem Rad.";
    static final String P5 = "Kreisverkehre senken die Zahl schwerer Unfälle an Kreuzungen deutlich, weil alle "
            + "langsamer fahren.";
    static final String P6 = "Startseite | Verkehr | Parken | Radverkehr | Kontakt | Impressum";
    static final String P7 = "Ein Poller trennt den Radweg von der Fahrbahn. Er kostet je nach Ausführung einige "
            + "hundert Euro.";

    static final String[][] SAMPLE = {
            // id, sentence, passage id, passage, label, kind, near
            {"S01", "In Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 Prozent [1].",
                    "p1", P1, "GEDECKT", "TEXT", ""},
            {"S02", "In Fahrradstraßen dürfen Radfahrer nebeneinander fahren [2].", "p2", P2, "GEDECKT", "TEXT", ""},
            {"S03", "Ein Anwohnerparkausweis kostet seit 2023 zwischen 30 und 360 Euro im Jahr [3].", "p3", P3,
                    "GEDECKT", "TEXT", ""},
            {"S04", "Schulstraßen werden vor Unterrichtsbeginn für eine halbe Stunde für Autos gesperrt [4].", "p4", P4,
                    "GEDECKT", "TEXT", ""},
            {"S05", "Kreisverkehre halbieren die Zahl aller Unfälle in der Stadt [5].", "p5", P5, "TEILWEISE", "TEXT",
                    ""},
            {"S06", "Poller schützen Radfahrer zuverlässig vor abbiegenden Lastwagen und kosten wenig [6].", "p7", P7,
                    "TEILWEISE", "TEXT", ""},
            {"S07", "Der Radverkehr hat sich in allen Städten verdoppelt [7].", "p6", P6, "NICHT_GEDECKT", "LINKLISTE",
                    ""},
            {"V01", "In Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 Prozent [1].",
                    "p3", P3, "NICHT_GEDECKT", "", ""},
            {"V02", "In Fahrradstraßen dürfen Radfahrer nebeneinander fahren [2].", "p4", P4, "NICHT_GEDECKT", "", ""},
            {"V03", "Ein Anwohnerparkausweis kostet seit 2023 zwischen 30 und 360 Euro im Jahr [3].", "p5", P5,
                    "NICHT_GEDECKT", "", ""},
            {"V04", "Schulstraßen werden vor Unterrichtsbeginn für eine halbe Stunde für Autos gesperrt [4].", "p2", P2,
                    "NICHT_GEDECKT", "", "near"}};

    static String sampleLines() {
        StringBuilder lines = new StringBuilder();
        for (String[] row : SAMPLE) {
            JsonObject json = new JsonObject();
            json.addProperty("blatt", row[0]);
            json.addProperty("sentence", row[1]);
            json.addProperty("passageId", row[2]);
            json.addProperty("passageText", row[3]);
            if (row[0].startsWith("V")) {
                json.addProperty("erwartet", row[4]);
                json.addProperty("themennah", !row[6].isEmpty());
                json.addProperty("satzVon", "S0" + row[0].charAt(2));
            } else {
                json.addProperty("urteil", row[4]);
                json.addProperty("belegart", row[5]);
                json.addProperty("mehrereBelege", false);
            }
            lines.append(json).append('\n');
        }
        return lines.toString();
    }

    static Settings sampleSettings() {
        Settings settings = new Settings();
        settings.swappedFar = 3;
        settings.t1Min = 3;
        settings.swapped = 4;
        settings.t2Max = 0;
        settings.covered = 4;
        settings.t3Min = 4;
        settings.t3MaxNot = 0;
        settings.partial = 2;
        settings.t4Max = 0;
        settings.all = 11;
        settings.t5Max = 0;
        return settings;
    }

    /** A model that knows the labels: the first sentence of the passage as quote, the kind as labeled. */
    static SentenceCheckTest.Scripted careful() {
        return new SentenceCheckTest.Scripted(messages -> {
            String prompt = messages.get(1).getContent();
            for (String[] row : SAMPLE) {
                if (prompt.contains("„" + SentenceVerdict.plain(row[1]) + "“") && prompt.contains("„" + row[3] + "“")) {
                    String quote = "GEDECKT".equals(row[4]) ? row[3].substring(0, row[3].indexOf(". ") + 1) : "";
                    return MainModelChatResult.ok(SentenceCheckTest.answer(row[5].isEmpty() ? "TEXT" : row[5], quote,
                            "Der Satz und die Passage im Vergleich.", row[4]));
                }
            }
            return MainModelChatResult.ok("kein JSON");
        });
    }

    @Test
    public void aSampleRunWithACarefulModelPassesAndTheFilesHoldNoText() throws Exception {
        List<Pair> pairs = readPairs(new StringReader(sampleLines()));
        assertEquals(11, pairs.size());
        File out = scratch.newFolder("aus");

        JsonObject result = run(pairs, careful(), "test-model", out, sampleSettings());

        assertTrue(result.toString(), result.get("bestanden").getAsBoolean());
        JsonObject criteria = result.getAsJsonObject("kriterien");
        assertEquals(3, criteria.getAsJsonObject("T1").get("wert").getAsInt());
        assertEquals(4, criteria.getAsJsonObject("T3").get("gedeckt").getAsInt());
        assertEquals(0, criteria.getAsJsonObject("T5").get("wert").getAsInt());
        assertEquals(11, result.get("gefragt").getAsInt());
        assertEquals(0, result.getAsJsonObject("herabgestuft").get("alle").getAsInt());
        assertEquals("S07", result.getAsJsonArray("echteNichtGedeckt").get(0).getAsJsonObject().get("blatt")
                .getAsString());
        JsonObject kinds = result.getAsJsonObject("belegart");
        assertEquals(1, kinds.get("keineAussage").getAsInt());
        assertEquals(1, kinds.get("keineAussageErkannt").getAsInt());
        assertEquals(0, kinds.get("textAlsKeineAussage").getAsInt());

        File folder = new File(out, "test-model");
        String files = new String(Files.readAllBytes(new File(folder, "result.json").toPath()), StandardCharsets.UTF_8)
                + new String(Files.readAllBytes(new File(folder, "urteile.jsonl").toPath()), StandardCharsets.UTF_8);
        for (String[] row : SAMPLE) {
            assertFalse(row[0], files.contains(row[1].substring(0, 20)));
            assertFalse(row[0], files.contains(row[3].substring(0, 20)));
        }
        assertEquals(11, Files.readAllLines(new File(folder, "urteile.jsonl").toPath()).size());
        assertTrue("the verdicts with their quotes stay in the project folder",
                new File(folder, "projekt/sentence-check/verdicts.jsonl").isFile());

        SentenceCheckTest.Scripted again = careful();
        assertEquals("a second run continues from the verdicts", 0, run(pairs, again, "test-model", out,
                sampleSettings()).get("gefragt").getAsInt());
        Settings fresh = sampleSettings();
        fresh.fresh = true;
        assertEquals(11, run(pairs, again, "test-model", out, fresh).get("gefragt").getAsInt());
    }

    @Test
    public void aModelThatCallsEverythingCoveredFailsAndTheQuoteCheckIsReported() throws Exception {
        List<Pair> pairs = readPairs(new StringReader(sampleLines()));
        SentenceCheckTest.Scripted careless = new SentenceCheckTest.Scripted(messages -> {
            String prompt = messages.get(1).getContent();
            for (String[] row : SAMPLE) {
                if (prompt.contains(row[3])) {
                    return MainModelChatResult.ok(SentenceCheckTest.answer("TEXT", row[3].substring(0, 30),
                            "Alles gedeckt.", "GEDECKT"));
                }
            }
            return MainModelChatResult.ok("kein JSON");
        });

        JsonObject result = run(pairs, careless, "test-model", scratch.newFolder("aus"), sampleSettings());

        assertFalse(result.get("bestanden").getAsBoolean());
        JsonObject criteria = result.getAsJsonObject("kriterien");
        assertFalse(criteria.getAsJsonObject("T1").get("erfuellt").getAsBoolean());
        assertEquals(0, criteria.getAsJsonObject("T1").get("wert").getAsInt());
        assertTrue("the quote check took the GEDECKT with numbers down",
                result.getAsJsonObject("herabgestuft").get("alle").getAsInt() > 0);
        JsonObject byModel = result.getAsJsonObject("kriterienAndereUrteile");
        assertEquals(4, byModel.getAsJsonObject("T2").get("wert").getAsInt());
        assertTrue(criteria.getAsJsonObject("T2").get("wert").getAsInt()
                < byModel.getAsJsonObject("T2").get("wert").getAsInt());

        Settings other = sampleSettings();
        other.swapped = 60;
        JsonObject unmeasurable = criteria(pairs, Collections.<String, SentenceVerdict>emptyMap(), false, other);
        assertFalse("a group of another size is not measurable", unmeasurable.getAsJsonObject("T2").get("messbar")
                .getAsBoolean());
        assertEquals("every pair without a verdict is FEHLER", 11, unmeasurable.getAsJsonObject("T5").get("wert")
                .getAsInt());
    }

    @Test
    public void theFileMustHoldThePassagesALabelAndNoDamagedText() throws Exception {
        String line = sampleLines().split("\n")[0];
        for (String bad : new String[]{line.replace("\"passageText\"", "\"passage\""),
                line.replace("\"urteil\":\"GEDECKT\"", "\"urteil\":\"JA\""), line + "\n" + line, "kein JSON"}) {
            try {
                readPairs(new StringReader(bad));
                fail("read: " + bad);
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("line"));
            }
        }
        List<Pair> damaged = readPairs(new StringReader(line.replace("Unfälle", "UnfÃ¤lle")));
        try {
            checkEncoding(damaged);
            fail("a damaged copy was taken");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Latin-1"));
        }
        Pair swapped = readPairs(new StringReader(sampleLines().split("\n")[10])).get(0);
        assertTrue(swapped.swapped);
        assertTrue(swapped.near);
        assertEquals("NICHT_GEDECKT", swapped.label);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static int number(String name, int fallback) {
        String value = System.getProperty(P + name, "").trim();
        return value.isEmpty() ? fallback : Integer.parseInt(value);
    }

    static String safe(String model) {
        return model.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString().trim();
    }

    private static boolean bool(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}

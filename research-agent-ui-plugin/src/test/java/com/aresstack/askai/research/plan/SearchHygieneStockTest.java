package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Component 4 on stored data: the search hygiene over the 69 searches of the 17 Nov run ({@code suchen-1711.json},
 * 13 of them judged good). Pre-registered (architect, 2026-11-21): no good search is rejected, every rejection has
 * its reason in a formal error, a repeat or the blacklist, and the repeat in the data is found (searches #61 and #68
 * hold the same words in another order).
 * <p>The searches run one after the other in three orders: as in the file, reversed, and the base searches first
 * (base, variant, cycle, worker, then the writer's gap searches), as the app plans them. An admitted search counts as
 * searched for the ones after it, a rejected one never ran (the queue's rule). The blacklist of that run was empty.
 * The test reads the copy in {@code src/test/resources/search-hygiene} in the normal test run; on the owner's machine
 * the file of the test data folder can be named instead:
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*SearchHygieneStockTest*'
 *   -Daskai.stock.hygiene.searches=C:\Users\...\testdata\suchen-1711.json   (optional: else the repository copy)
 *   -Daskai.stock.hygiene.out=build/component-stock/search-hygiene         (optional: where result.json goes)
 * </pre>
 * {@code result.json} holds each search with its verdict, reason and how many earlier searches had the same words
 * with another {@code site:} (counted, never a reason), per order.
 */
public class SearchHygieneStockTest {

    private static final String P = "askai.stock.hygiene.";
    private static final String RESOURCE = "/search-hygiene/suchen-1711.json";
    /** 17 Nov: "DGUV Richtlinien Wearables Sensoren Baustellen" and "... Baustellen Sensoren" (numbers from 1). */
    private static final int[] REPEAT_PAIR = {61, 68};
    private static final List<String> BASE_FIRST = Arrays.asList("base", "variant", "cycle", "worker", "priority");

    /** One search of the file. */
    static final class Search {
        final int number;
        final String query;
        final String origin;
        final String grade;

        Search(int number, String query, String origin, String grade) {
            this.number = number;
            this.query = query;
            this.origin = origin;
            this.grade = grade;
        }
    }

    /** The verdicts of one order, by search number. */
    static final class Run {
        final String name;
        final List<Search> order;
        final Map<Integer, SearchHygiene.Verdict> verdicts = new LinkedHashMap<Integer, SearchHygiene.Verdict>();

        Run(String name, List<Search> order) {
            this.name = name;
            this.order = order;
        }
    }

    @Test
    public void theSearchesOfThe17NovRun() throws Exception {
        String named = System.getProperty(P + "searches", "").trim();
        byte[] bytes = named.isEmpty() ? resource() : Files.readAllBytes(new File(named).toPath());
        List<Search> searches = searches(new String(bytes, StandardCharsets.UTF_8));
        File out = new File(System.getProperty(P + "out", "build/component-stock/search-hygiene").trim());

        List<Run> runs = new ArrayList<Run>();
        runs.add(run("file", searches));
        List<Search> reversed = new ArrayList<Search>(searches);
        Collections.reverse(reversed);
        runs.add(run("reversed", reversed));
        List<Search> baseFirst = new ArrayList<Search>(searches);
        baseFirst.sort((a, b) -> Integer.compare(rank(a.origin), rank(b.origin)));
        runs.add(run("baseFirst", baseFirst));

        File written = write(out, named.isEmpty() ? "repository copy " + RESOURCE : named, sha256(bytes), searches,
                runs);
        System.out.println("[search-hygiene stock] " + searches.size() + " searches, result " + written);
        for (Run run : runs) {
            System.out.println("[search-hygiene stock] " + summary(run));
        }

        List<Search> good = new ArrayList<Search>();
        for (Search search : searches) {
            if ("gut".equals(search.grade)) {
                good.add(search);
            }
        }
        assertEquals("good searches in the file", 13, good.size());
        EnumSet<SearchHygiene.Reason> allowed = EnumSet.of(SearchHygiene.Reason.NO_WORDS,
                SearchHygiene.Reason.TOO_SHORT, SearchHygiene.Reason.TOO_LONG, SearchHygiene.Reason.REPEAT,
                SearchHygiene.Reason.BLACKLIST);
        for (Run run : runs) {
            for (Search search : good) {
                SearchHygiene.Verdict verdict = run.verdicts.get(search.number);
                assertTrue(run.name + ": good search #" + search.number + " \"" + search.query + "\" " + verdict,
                        verdict.isAdmitted());
            }
            for (Map.Entry<Integer, SearchHygiene.Verdict> verdict : run.verdicts.entrySet()) {
                if (!verdict.getValue().isAdmitted()) {
                    assertTrue(run.name + ": #" + verdict.getKey() + " " + verdict.getValue(),
                            allowed.contains(verdict.getValue().getReason())
                                    && !verdict.getValue().text().isEmpty());
                }
            }
            Search first = null;
            Search second = null;
            for (Search search : run.order) {
                if (search.number == REPEAT_PAIR[0] || search.number == REPEAT_PAIR[1]) {
                    if (first == null) {
                        first = search;
                    } else {
                        second = search;
                    }
                }
            }
            assertTrue(run.name + ": both searches of the pair", first != null && second != null);
            assertTrue(run.name + ": #" + first.number + " runs", run.verdicts.get(first.number).isAdmitted());
            SearchHygiene.Verdict repeat = run.verdicts.get(second.number);
            assertEquals(run.name + ": #" + second.number, SearchHygiene.Reason.REPEAT, repeat.getReason());
            assertEquals(run.name + ": #" + second.number + " names #" + first.number,
                    first.query.replaceAll("\\s+", " ").trim(), repeat.getDetail());
        }
    }

    /** The searches one after the other: an admitted one counts as searched for the next ones. */
    static Run run(String name, List<Search> order) {
        Run run = new Run(name, order);
        List<String> searched = new ArrayList<String>();
        for (Search search : order) {
            SearchHygiene.Verdict verdict = SearchHygiene.check(search.query, searched, TypedBlacklist.empty());
            run.verdicts.put(search.number, verdict);
            if (verdict.isAdmitted()) {
                searched.add(search.query);
            }
        }
        return run;
    }

    static List<Search> searches(String json) {
        JsonArray array = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("suchen");
        List<Search> searches = new ArrayList<Search>();
        for (int index = 0; index < array.size(); index++) {
            JsonObject search = array.get(index).getAsJsonObject();
            searches.add(new Search(index + 1, text(search, "suche"), text(search, "herkunft"),
                    text(search, "klasse")));
        }
        return searches;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int rank(String origin) {
        int rank = BASE_FIRST.indexOf(origin);
        return rank < 0 ? BASE_FIRST.size() : rank;
    }

    static String summary(Run run) {
        int admitted = 0;
        int counted = 0;
        Map<String, Integer> reasons = new LinkedHashMap<String, Integer>();
        for (SearchHygiene.Verdict verdict : run.verdicts.values()) {
            if (verdict.isAdmitted()) {
                admitted++;
                if (!verdict.getSameWordsOtherSite().isEmpty()) {
                    counted++;
                }
            } else {
                reasons.merge(verdict.getReason().name(), 1, Integer::sum);
            }
        }
        return run.name + ": " + admitted + " admitted, rejected " + reasons + ", " + counted
                + " admitted with the same words under another site:";
    }

    private static File write(File out, String input, String sha256, List<Search> searches, List<Run> runs)
            throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("input", input);
        result.addProperty("sha256", sha256);
        result.addProperty("rule", "rejected only for a formal error (no search word, under 3 or over 160 "
                + "characters), a repeat of an earlier search (same words and same site:, case, punctuation, order "
                + "and repeated words do not count) or the blacklist (empty in this run)");
        JsonObject orders = new JsonObject();
        for (Run run : runs) {
            JsonObject order = new JsonObject();
            order.addProperty("summary", summary(run));
            JsonArray rows = new JsonArray();
            for (Search search : run.order) {
                SearchHygiene.Verdict verdict = run.verdicts.get(search.number);
                JsonObject row = new JsonObject();
                row.addProperty("nr", search.number);
                row.addProperty("suche", search.query);
                row.addProperty("herkunft", search.origin);
                row.addProperty("klasse", search.grade);
                row.addProperty("urteil", verdict.isAdmitted() ? "zugelassen" : verdict.getReason().name());
                row.addProperty("grund", verdict.text());
                row.addProperty("gleicheWoerterAndereSite", verdict.getSameWordsOtherSite().size());
                JsonArray others = new JsonArray();
                for (String other : verdict.getSameWordsOtherSite()) {
                    others.add(other);
                }
                row.add("andereSite", others);
                rows.add(row);
            }
            order.add("suchen", rows);
            orders.add(run.name, order);
        }
        result.add("reihenfolgen", orders);
        result.addProperty("suchenGesamt", searches.size());
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IOException("cannot create " + out);
        }
        File file = new File(out, "result.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.write(file.toPath(), gson.toJson(result).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static byte[] resource() throws IOException {
        try (InputStream in = SearchHygieneStockTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing test resource " + RESOURCE);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}

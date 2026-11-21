package com.aresstack.askai.research.repeat;

import com.aresstack.askai.research.runtime.writer.BookRepeatCheck;
import com.aresstack.askai.research.runtime.writer.RepeatCheck;
import com.aresstack.askai.research.runtime.writer.SentenceVectors;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The judged sentence pairs of component 12 ({@code wiederholung-1711.json}: an earlier and a later sentence of the
 * book of 17 Nov with the architect's verdict GLEICH, TEILWEISE or VERSCHIEDEN) and how {@link BookRepeatCheck} does
 * on them. Each pair is checked as a book of its own: the earlier sentence in one section, the later one in the next,
 * citing the same passage where the pair does.
 */
final class RepeatStock {

    static final String SAME = "GLEICH";
    static final String PART = "TEILWEISE";
    static final String DIFFERENT = "VERSCHIEDEN";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private RepeatStock() {
    }

    /** One judged pair. */
    static final class Case {
        final String sheet;
        final String stratum;
        final String verdict;
        final double overlap;
        final boolean sameEvidence;
        final String earlierSection;
        final String earlierText;
        final String laterSection;
        final String laterText;
        /** The architect's extra field {@code grenzfall} as written, {@code null} when the file has none. */
        final JsonElement borderline;

        Case(String sheet, String stratum, String verdict, double overlap, boolean sameEvidence,
             String earlierSection, String earlierText, String laterSection, String laterText) {
            this(sheet, stratum, verdict, overlap, sameEvidence, earlierSection, earlierText, laterSection, laterText,
                    null);
        }

        Case(String sheet, String stratum, String verdict, double overlap, boolean sameEvidence,
             String earlierSection, String earlierText, String laterSection, String laterText,
             JsonElement borderline) {
            this.sheet = sheet;
            this.stratum = stratum;
            this.verdict = verdict;
            this.overlap = overlap;
            this.sameEvidence = sameEvidence;
            this.earlierSection = earlierSection;
            this.earlierText = earlierText;
            this.laterSection = laterSection;
            this.laterText = laterText;
            this.borderline = borderline;
        }

        /** The pair as a book: the sections are distinct by construction, the passages shared where judged so. */
        List<BookRepeatCheck.Sentence> book() {
            String earlierPassage = "beleg-" + sheet + (sameEvidence ? "" : "-a");
            String laterPassage = "beleg-" + sheet + (sameEvidence ? "" : "-b");
            return Arrays.asList(
                    new BookRepeatCheck.Sentence("frueher", earlierText, Arrays.asList(earlierPassage)),
                    new BookRepeatCheck.Sentence("spaeter", laterText, Arrays.asList(laterPassage)));
        }
    }

    /**
     * The judged pairs: {@code blatt}, {@code urteil} and both {@code satz} are required; {@code schicht},
     * {@code ueberlappung}, {@code gleicherBeleg} (false), the sections and the extra field {@code grenzfall} may be
     * missing. Unknown fields are ignored.
     */
    static List<Case> read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Case> cases = new ArrayList<Case>();
        for (JsonElement element : root.getAsJsonArray("paare")) {
            JsonObject p = element.getAsJsonObject();
            JsonObject earlier = p.getAsJsonObject("frueher");
            JsonObject later = p.getAsJsonObject("spaeter");
            JsonElement overlap = p.get("ueberlappung");
            JsonElement sameEvidence = p.get("gleicherBeleg");
            JsonElement borderline = p.get("grenzfall");
            cases.add(new Case(p.get("blatt").getAsString(), optional(p, "schicht"), p.get("urteil").getAsString(),
                    overlap == null || overlap.isJsonNull() ? Double.NaN : overlap.getAsDouble(),
                    sameEvidence != null && !sameEvidence.isJsonNull() && sameEvidence.getAsBoolean(),
                    optional(earlier, "abschnitt"), earlier.get("satz").getAsString(), optional(later, "abschnitt"),
                    later.get("satz").getAsString(), borderline == null || borderline.isJsonNull() ? null
                    : borderline));
        }
        return cases;
    }

    private static String optional(JsonObject o, String field) {
        JsonElement value = o.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /** Every text the check embeds, each once, in pair order. */
    static List<String> texts(List<Case> cases) {
        Set<String> out = new LinkedHashSet<String>();
        for (Case c : cases) {
            for (BookRepeatCheck.Sentence s : c.book()) {
                out.add(s.plainText());
            }
        }
        return new ArrayList<String>(out);
    }

    /** The sentence model behind a cache, so a table of many bounds embeds each sentence once. */
    static final class CachingVectors implements SentenceVectors {
        private final SentenceVectors model;
        final Map<String, float[]> cache = new LinkedHashMap<String, float[]>();
        int calls;

        /** @param model the sentence model; {@code null} when every vector comes from a file */
        CachingVectors(SentenceVectors model) {
            this.model = model;
        }

        public synchronized List<float[]> embed(List<String> sentences) throws Exception {
            List<String> missing = new ArrayList<String>();
            for (String s : sentences) {
                if (!cache.containsKey(s) && !missing.contains(s)) {
                    missing.add(s);
                }
            }
            if (!missing.isEmpty()) {
                if (model == null) {
                    throw new IllegalStateException("no stored vector for: " + missing.get(0));
                }
                calls++;
                List<float[]> embedded = model.embed(missing);
                if (embedded == null || embedded.size() != missing.size()) {
                    throw new IllegalStateException("the sentence model returned " + (embedded == null ? 0
                            : embedded.size()) + " vectors for " + missing.size() + " sentences");
                }
                for (int i = 0; i < missing.size(); i++) {
                    cache.put(missing.get(i), embedded.get(i));
                }
            }
            List<float[]> out = new ArrayList<float[]>(sentences.size());
            for (String s : sentences) {
                out.add(cache.get(s));
            }
            return out;
        }

        /** Embeds every text not yet cached, in batches. */
        void warm(List<String> texts, int batch) throws Exception {
            for (int from = 0; from < texts.size(); from += batch) {
                embed(texts.subList(from, Math.min(texts.size(), from + batch)));
            }
        }

        String toJson(String model, int dimension) {
            JsonObject root = new JsonObject();
            root.addProperty("modell", model);
            root.addProperty("dimension", dimension);
            JsonArray vectors = new JsonArray();
            for (Map.Entry<String, float[]> e : cache.entrySet()) {
                JsonObject v = new JsonObject();
                v.addProperty("satz", e.getKey());
                JsonArray values = new JsonArray();
                for (float f : e.getValue()) {
                    values.add(f);
                }
                v.add("vektor", values);
                vectors.add(v);
            }
            root.add("vektoren", vectors);
            return GSON.toJson(root);
        }

        static CachingVectors fromJson(String json) {
            CachingVectors stored = new CachingVectors(null);
            for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("vektoren")) {
                JsonObject v = element.getAsJsonObject();
                JsonArray values = v.getAsJsonArray("vektor");
                float[] vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = values.get(i).getAsFloat();
                }
                stored.cache.put(v.get("satz").getAsString(), vector);
            }
            return stored;
        }
    }

    /** How one setting of the check does on the pairs. */
    static final class Row {
        final double value;
        final List<String> sameFound = new ArrayList<String>();
        final List<String> sameMissed = new ArrayList<String>();
        final List<String> differentFlagged = new ArrayList<String>();
        final List<String> partFlagged = new ArrayList<String>();
        final List<String> incomplete = new ArrayList<String>();
        int same;
        int different;
        int part;
        boolean meetsTargets;

        Row(double value) {
            this.value = value;
        }

        String line(String what) {
            return String.format(Locale.ROOT, "[repeat] %s %.2f: GLEICH %d/%d, VERSCHIEDEN %d/%d %s, TEILWEISE %d/%d%s",
                    what, value, sameFound.size(), same, differentFlagged.size(), different, differentFlagged,
                    partFlagged.size(), part, meetsTargets ? "  <- Vorgaben erfuellt" : "");
        }

        JsonObject toJson(String key) {
            JsonObject o = new JsonObject();
            o.addProperty(key, value);
            o.addProperty("gleichErkannt", sameFound.size());
            o.addProperty("verschiedenGemeldet", differentFlagged.size());
            o.addProperty("teilweiseGemeldet", partFlagged.size());
            o.add("gleichVerfehlt", strings(sameMissed));
            o.add("verschiedenFalsch", strings(differentFlagged));
            o.add("teilweiseGemeldetBlaetter", strings(partFlagged));
            o.addProperty("vorgabenErfuellt", meetsTargets);
            if (!incomplete.isEmpty()) {
                o.add("unvollstaendig", strings(incomplete));
            }
            return o;
        }
    }

    /** Checks every pair with one setting; a pair counts as reported when the check names it. */
    static Row row(List<Case> cases, BookRepeatCheck check, double value, int minFound, int maxFalse) {
        Row row = new Row(value);
        for (Case c : cases) {
            BookRepeatCheck.Result result = check.check(c.book());
            if (result.getStatus() != BookRepeatCheck.Status.COMPLETE) {
                row.incomplete.add(c.sheet + ": " + result.getProblem());
            }
            boolean reported = !result.getPairs().isEmpty();
            if (SAME.equals(c.verdict)) {
                row.same++;
                (reported ? row.sameFound : row.sameMissed).add(c.sheet);
            } else if (DIFFERENT.equals(c.verdict)) {
                row.different++;
                if (reported) {
                    row.differentFlagged.add(c.sheet);
                }
            } else {
                row.part++;
                if (reported) {
                    row.partFlagged.add(c.sheet);
                }
            }
        }
        row.meetsTargets = row.incomplete.isEmpty() && row.sameFound.size() >= minFound
                && row.differentFlagged.size() <= maxFalse;
        return row;
    }

    /** The bounds of the meaning table: 0.70 to 0.99 in steps of 0.01. */
    static List<Double> similarityBounds() {
        List<Double> out = new ArrayList<Double>();
        for (int k = 70; k <= 99; k++) {
            out.add(k / 100.0);
        }
        return out;
    }

    /** The shares of the words-only table: 0.40 to 0.80 in steps of 0.05. */
    static List<Double> shareBounds() {
        List<Double> out = new ArrayList<Double>();
        for (int k = 40; k <= 80; k += 5) {
            out.add(k / 100.0);
        }
        return out;
    }

    /** Both measures of every pair (the later sentence's nearest earlier sentence), with the verdict at a bound. */
    static JsonArray pairValues(List<Case> cases, BookRepeatCheck check, String reportedKey) {
        JsonArray out = new JsonArray();
        for (Case c : cases) {
            BookRepeatCheck.Result result = check.check(c.book());
            JsonObject o = new JsonObject();
            o.addProperty("blatt", c.sheet);
            o.addProperty("schicht", c.stratum);
            o.addProperty("urteil", c.verdict);
            o.addProperty("gleicherBeleg", c.sameEvidence);
            if (!Double.isNaN(c.overlap)) {
                o.addProperty("ueberlappungArchitekt", c.overlap);
            }
            List<BookRepeatCheck.Pair> nearest = result.getNearest();
            o.addProperty("geprueft", !nearest.isEmpty());
            if (!nearest.isEmpty()) {
                BookRepeatCheck.Pair n = nearest.get(0);
                o.addProperty("wortanteil", round(n.getShare()));
                o.addProperty("aehnlichkeit", round(n.getSimilarity()));
                o.addProperty("zahlenGedeckt", n.isNumbersCovered());
                o.add("gemeinsameBelege", strings(n.getSharedPassageIds()));
            }
            BookRepeatCheck.Pair pair = result.getPairs().isEmpty() ? null : result.getPairs().get(0);
            o.addProperty(reportedKey, pair != null);
            o.addProperty("grund", pair == null ? "" : pair.getReason().name());
            if (result.getStatus() != BookRepeatCheck.Status.COMPLETE) {
                o.addProperty("unvollstaendig", result.getProblem());
            }
            out.add(o);
        }
        return out;
    }

    static BookRepeatCheck check(RepeatCheck.Criteria base, double minSimilarity, SentenceVectors vectors) {
        return new BookRepeatCheck(new RepeatCheck.Criteria(base.getMinWords(), base.getMinShare(), minSimilarity),
                null, vectors, null, null);
    }

    static BookRepeatCheck wordsOnly(RepeatCheck.Criteria base, double minShare) {
        return new BookRepeatCheck(new RepeatCheck.Criteria(base.getMinWords(), minShare, 0), null, null, null,
                null);
    }

    static Map<String, Integer> counts(List<Case> cases) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        out.put(SAME, 0);
        out.put(DIFFERENT, 0);
        out.put(PART, 0);
        for (Case c : cases) {
            out.put(c.verdict, out.containsKey(c.verdict) ? out.get(c.verdict) + 1 : 1);
        }
        return out;
    }

    static JsonArray strings(List<String> values) {
        JsonArray out = new JsonArray();
        for (String v : values) {
            out.add(v);
        }
        return out;
    }

    static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    static String pretty(JsonObject o) {
        return GSON.toJson(o);
    }
}

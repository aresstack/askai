package com.aresstack.askai.research.benchmark.retrieval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The retrieval benchmark's inputs (plan 5.4 item 7). Checked in ({@code benchmark/retrieval/retrieval-queries.json}):
 * the queries in three groups, their target passage ids, the concept nodes and the owner's passage labels. Kept in
 * the benchmark's LOCAL data directory, because it is third-party text: {@code corpus.jsonl} (one passage per line:
 * {@code id}, {@code language}, {@code text}) and {@code serp/<queryId>.json} (20 search results with {@code id},
 * {@code title}, {@code snippet}, and the {@code targetResultId}). Loading validates the references completely.
 */
public final class RetrievalBenchmarkData {

    public static final String QUERIES_RESOURCE = "/benchmark/retrieval/retrieval-queries.json";

    /** DE→DE, DE→EN and EN→EN (query language → passage language). */
    public enum Group { DE_DE, DE_EN, EN_EN }

    /**
     * Where a target or label points: a passage id, or a source id with terms that must all occur
     * (case-insensitive) in exactly one passage of that source. Terms survive a re-capture of the pages, ids do not.
     */
    public static final class Locator {
        public final String passageId;
        public final String source;
        public final List<String> terms;

        Locator(String passageId, String source, List<String> terms) {
            this.passageId = passageId;
            this.source = source;
            this.terms = Collections.unmodifiableList(terms);
        }

        static Locator of(JsonObject json, String idKey, String sourceKey, String termsKey) {
            List<String> terms = new ArrayList<String>();
            for (JsonElement term : array(json, termsKey)) {
                if (!term.getAsString().trim().isEmpty()) {
                    terms.add(term.getAsString().trim());
                }
            }
            Locator locator = new Locator(str(json, idKey), str(json, sourceKey), terms);
            if (locator.passageId.isEmpty() && (locator.source.isEmpty() || terms.isEmpty())) {
                throw new IllegalArgumentException("needs " + idKey + " or " + sourceKey + " with " + termsKey
                        + ": " + json);
            }
            return locator;
        }

        /** The passage id in this corpus; fails when the passage is missing or the terms are ambiguous. */
        String resolve(Map<String, Passage> corpus, String what) {
            if (!passageId.isEmpty()) {
                if (!corpus.containsKey(passageId)) {
                    throw new IllegalArgumentException(what + ": passage " + passageId + " is not in the corpus");
                }
                return passageId;
            }
            List<String> hits = new ArrayList<String>();
            for (Passage passage : corpus.values()) {
                if (!source.equals(passage.source)) {
                    continue;
                }
                String text = passage.text.toLowerCase(java.util.Locale.ROOT);
                boolean all = true;
                for (String term : terms) {
                    all &= text.contains(term.toLowerCase(java.util.Locale.ROOT));
                }
                if (all) {
                    hits.add(passage.id);
                }
            }
            if (hits.size() != 1) {
                throw new IllegalArgumentException(what + ": terms " + terms + " match " + hits.size()
                        + " passages of " + source + (hits.isEmpty() ? "" : " " + hits));
            }
            return hits.get(0);
        }
    }

    public static final class Query {
        public final String id;
        public final Group group;
        public final String text;
        /** Resolved against the corpus by {@link #loadCorpus}; empty before that when the file uses terms. */
        public final String targetPassageId;
        final Locator target;

        Query(String id, Group group, String text, Locator target, String resolvedTarget) {
            this.id = id;
            this.group = group;
            this.text = text;
            this.target = target;
            this.targetPassageId = resolvedTarget;
        }
    }

    public static final class Passage {
        public final String id;
        public final String source;
        public final String language;
        public final String text;

        public Passage(String id, String source, String language, String text) {
            this.id = id;
            this.source = source == null ? "" : source;
            this.language = language;
            this.text = text;
        }
    }

    public static final class SearchResult {
        public final String id;
        public final String title;
        public final String snippet;
        public final String url;

        public SearchResult(String id, String title, String snippet, String url) {
            this.id = id;
            this.title = title;
            this.snippet = snippet;
            this.url = url;
        }
    }

    public static final class SearchResults {
        public final List<SearchResult> results;
        public final String targetResultId;

        public SearchResults(List<SearchResult> results, String targetResultId) {
            this.results = Collections.unmodifiableList(new ArrayList<SearchResult>(results));
            this.targetResultId = targetResultId;
        }
    }

    /** The checked-in part. */
    public static final class QuerySet {
        public final String topic;
        public final List<Query> queries;
        /** concept node id → its full label path ("Root > Chapter > Section"). */
        public final Map<String, String> conceptLabelPaths;
        /** labelled passage id → the concept node it belongs to (filled by {@link #resolve}). */
        public final Map<String, String> passageLabels;
        final List<Map.Entry<Locator, String>> labelLocators;

        QuerySet(String topic, List<Query> queries, Map<String, String> conceptLabelPaths,
                 Map<String, String> passageLabels, List<Map.Entry<Locator, String>> labelLocators) {
            this.topic = topic;
            this.queries = Collections.unmodifiableList(queries);
            this.conceptLabelPaths = Collections.unmodifiableMap(conceptLabelPaths);
            this.passageLabels = Collections.unmodifiableMap(passageLabels);
            this.labelLocators = labelLocators;
        }

        /** The same set with every target and label resolved to a passage id of this corpus. */
        public QuerySet resolve(Map<String, Passage> corpus) {
            List<Query> resolved = new ArrayList<Query>();
            List<String> problems = new ArrayList<String>();
            for (Query q : queries) {
                try {
                    resolved.add(new Query(q.id, q.group, q.text, q.target, q.target.resolve(corpus, q.id)));
                } catch (IllegalArgumentException missing) {
                    problems.add(missing.getMessage());
                }
            }
            Map<String, String> labels = new LinkedHashMap<String, String>();
            for (Map.Entry<Locator, String> label : labelLocators) {
                try {
                    String id = label.getKey().resolve(corpus, "label " + label.getKey().terms);
                    String previous = labels.put(id, label.getValue());
                    if (previous != null && !previous.equals(label.getValue())) {
                        problems.add("passage " + id + " is labelled " + previous + " and " + label.getValue());
                    }
                } catch (IllegalArgumentException missing) {
                    problems.add(missing.getMessage());
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(problems.size() + " unresolved: " + String.join("; ", problems));
            }
            return new QuerySet(topic, resolved, conceptLabelPaths, labels, labelLocators);
        }

        public boolean isEmpty() {
            return queries.isEmpty();
        }
    }

    private RetrievalBenchmarkData() {
    }

    public static QuerySet loadQueries() throws IOException {
        InputStream in = RetrievalBenchmarkData.class.getResourceAsStream(QUERIES_RESOURCE);
        if (in == null) {
            throw new IOException("missing resource " + QUERIES_RESOURCE);
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parseQueries(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    static QuerySet parseQueries(JsonObject root) {
        List<Query> queries = new ArrayList<Query>();
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (JsonElement element : array(root, "queries")) {
            JsonObject q = element.getAsJsonObject();
            Locator target = Locator.of(q, "targetPassageId", "targetSource", "targetTerms");
            Query query = new Query(str(q, "id"), Group.valueOf(str(q, "group")), str(q, "text"), target,
                    target.passageId);
            if (query.id.isEmpty() || query.text.isEmpty()) {
                throw new IllegalArgumentException("query needs id and text: " + q);
            }
            if (!ids.add(query.id)) {
                throw new IllegalArgumentException("duplicate query id " + query.id);
            }
            queries.add(query);
        }
        Map<String, String> nodes = new LinkedHashMap<String, String>();
        for (JsonElement element : array(root, "conceptNodes")) {
            JsonObject n = element.getAsJsonObject();
            nodes.put(str(n, "id"), str(n, "labelPath"));
        }
        Map<String, String> labels = new LinkedHashMap<String, String>();
        List<Map.Entry<Locator, String>> locators = new ArrayList<Map.Entry<Locator, String>>();
        for (JsonElement element : array(root, "labelledPassages")) {
            JsonObject l = element.getAsJsonObject();
            String node = str(l, "nodeId");
            if (!nodes.containsKey(node)) {
                throw new IllegalArgumentException("labelled passage points at unknown node " + node);
            }
            Locator locator = Locator.of(l, "passageId", "source", "terms");
            locators.add(new java.util.AbstractMap.SimpleEntry<Locator, String>(locator, node));
            if (!locator.passageId.isEmpty()) {
                labels.put(locator.passageId, node);
            }
        }
        return new QuerySet(str(root, "topic"), queries, nodes, labels, locators);
    }

    /** The local corpus; every target and every labelled passage must resolve in it (see {@link QuerySet#resolve}). */
    public static Map<String, Passage> loadCorpus(File dataDirectory, QuerySet set) throws IOException {
        File file = new File(dataDirectory, "corpus.jsonl");
        Map<String, Passage> corpus = new LinkedHashMap<String, Passage>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject p = JsonParser.parseString(line).getAsJsonObject();
            Passage passage = new Passage(str(p, "id"), str(p, "source"), str(p, "language"), str(p, "text"));
            if (corpus.put(passage.id, passage) != null) {
                throw new IllegalArgumentException("duplicate passage id " + passage.id);
            }
        }
        set.resolve(corpus); // fails with every unresolved target and label
        return corpus;
    }

    /** The search results of one query, or null when the data directory has none for it. */
    public static SearchResults loadSearchResults(File dataDirectory, String queryId) throws IOException {
        File file = new File(new File(dataDirectory, "serp"), queryId + ".json");
        if (!file.isFile()) {
            return null;
        }
        JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8)).getAsJsonObject();
        List<SearchResult> results = new ArrayList<SearchResult>();
        for (JsonElement element : array(root, "results")) {
            JsonObject r = element.getAsJsonObject();
            results.add(new SearchResult(str(r, "id"), str(r, "title"), str(r, "snippet"), str(r, "url")));
        }
        String target = str(root, "targetResultId");
        boolean found = false;
        for (SearchResult result : results) {
            found |= result.id.equals(target);
        }
        if (!found) {
            throw new IllegalArgumentException("targetResultId " + target + " is not among the results of "
                    + queryId);
        }
        return new SearchResults(results, target);
    }

    private static JsonArray array(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }
}

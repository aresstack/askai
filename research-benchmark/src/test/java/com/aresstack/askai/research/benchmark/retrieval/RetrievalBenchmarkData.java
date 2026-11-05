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

    public static final class Query {
        public final String id;
        public final Group group;
        public final String text;
        public final String targetPassageId;

        Query(String id, Group group, String text, String targetPassageId) {
            this.id = id;
            this.group = group;
            this.text = text;
            this.targetPassageId = targetPassageId;
        }
    }

    public static final class Passage {
        public final String id;
        public final String language;
        public final String text;

        public Passage(String id, String language, String text) {
            this.id = id;
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
        /** labelled passage id → the concept node it belongs to. */
        public final Map<String, String> passageLabels;

        QuerySet(String topic, List<Query> queries, Map<String, String> conceptLabelPaths,
                 Map<String, String> passageLabels) {
            this.topic = topic;
            this.queries = Collections.unmodifiableList(queries);
            this.conceptLabelPaths = Collections.unmodifiableMap(conceptLabelPaths);
            this.passageLabels = Collections.unmodifiableMap(passageLabels);
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
            Query query = new Query(str(q, "id"), Group.valueOf(str(q, "group")), str(q, "text"),
                    str(q, "targetPassageId"));
            if (query.id.isEmpty() || query.text.isEmpty() || query.targetPassageId.isEmpty()) {
                throw new IllegalArgumentException("query needs id, text and targetPassageId: " + q);
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
        for (JsonElement element : array(root, "labelledPassages")) {
            JsonObject l = element.getAsJsonObject();
            String node = str(l, "nodeId");
            if (!nodes.containsKey(node)) {
                throw new IllegalArgumentException("labelled passage points at unknown node " + node);
            }
            labels.put(str(l, "passageId"), node);
        }
        return new QuerySet(str(root, "topic"), queries, nodes, labels);
    }

    /** The local corpus; every target and every labelled passage must be in it. */
    public static Map<String, Passage> loadCorpus(File dataDirectory, QuerySet set) throws IOException {
        File file = new File(dataDirectory, "corpus.jsonl");
        Map<String, Passage> corpus = new LinkedHashMap<String, Passage>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject p = JsonParser.parseString(line).getAsJsonObject();
            Passage passage = new Passage(str(p, "id"), str(p, "language"), str(p, "text"));
            if (corpus.put(passage.id, passage) != null) {
                throw new IllegalArgumentException("duplicate passage id " + passage.id);
            }
        }
        for (Query query : set.queries) {
            if (!corpus.containsKey(query.targetPassageId)) {
                throw new IllegalArgumentException("target " + query.targetPassageId + " of " + query.id
                        + " is not in the corpus");
            }
        }
        for (String passageId : set.passageLabels.keySet()) {
            if (!corpus.containsKey(passageId)) {
                throw new IllegalArgumentException("labelled passage " + passageId + " is not in the corpus");
            }
        }
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

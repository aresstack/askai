package com.aresstack.askai.research.candidates;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.lucene.CompositeSemanticKnowledgeIndex;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Test tooling (outside CI on the real stock, not product code): component 5 on a stored stock. The passages and their
 * vectors are read in the format of the outline replay (#255), indexed by the product's
 * {@link CompositeSemanticKnowledgeIndex} in a scratch folder, and searched by {@link CandidateSearch} in both modes.
 * With a file of judged pairs (question, passage, verdict BELEGT / HINWEIS / KEIN) the lists are scored as in the
 * candidate search comparison of 21.11.2026: a question is <em>served</em> when its list holds at least three direct
 * hits (BELEGT) from two independent source groups; a source group is a set of domains that carry the same text.
 * A <em>false "Kein Beleg gefunden"</em> is a list without a direct hit for a question that has one among the judged
 * pairs. A candidate without a verdict of its own takes the verdict of a judged passage with the same text (same
 * {@link CandidateText#key}); a candidate without any verdict is counted as unjudged, never as a hit.
 */
final class CandidateStock {

    static final String WORLD = "stock";
    static final String PROJECT = "stock";
    static final int SERVED_HITS = 3;
    static final int SERVED_GROUPS = 2;

    /** One stored passage. */
    static final class Passage {
        final String id;
        final String captureId;
        final String sourceId;
        final String domain;
        final String heading;
        final String text;
        final String key;
        final float[] vector;

        Passage(String id, String captureId, String sourceId, String domain, String heading, String text,
                float[] vector) {
            this.id = id;
            this.captureId = captureId;
            this.sourceId = sourceId;
            this.domain = domain;
            this.heading = heading;
            this.text = text;
            this.key = CandidateText.key(text);
            this.vector = vector;
        }
    }

    /** One question of the test data. */
    static final class Question {
        final String id;
        final String text;
        final boolean answerable;

        Question(String id, String text, boolean answerable) {
            this.id = id;
            this.text = text;
            this.answerable = answerable;
        }
    }

    final List<Passage> passages = new ArrayList<Passage>();
    final Map<String, Passage> byId = new LinkedHashMap<String, Passage>();
    final int dim;

    private CandidateStock(int dim) {
        this.dim = dim;
    }

    /** The corpus: {@code dim} and {@code passages}; vectors from {@code raw} (float32 little endian) or each row. */
    static CandidateStock read(String corpusJson, ByteBuffer raw) {
        JsonObject corpus = JsonParser.parseString(corpusJson).getAsJsonObject();
        int dim = corpus.get("dim").getAsInt();
        JsonArray rows = corpus.getAsJsonArray("passages");
        if (raw != null && raw.capacity() != rows.size() * dim * 4) {
            throw new IllegalStateException("vector file holds " + raw.capacity() + " bytes, expected "
                    + rows.size() * dim * 4);
        }
        CandidateStock stock = new CandidateStock(dim);
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            String id = row.get("id").getAsString();
            float[] vector = new float[dim];
            if (raw == null) {
                JsonArray values = row.getAsJsonArray("vector");
                if (values == null || values.size() != dim) {
                    throw new IllegalStateException("passage " + id + " carries no vector of " + dim + " values");
                }
                for (int d = 0; d < dim; d++) {
                    vector[d] = values.get(d).getAsFloat();
                }
            } else {
                for (int d = 0; d < dim; d++) {
                    vector[d] = raw.getFloat((i * dim + d) * 4);
                }
            }
            Passage passage = new Passage(id, string(row, "captureId"), string(row, "sourceId"),
                    string(row, "domain"), string(row, "heading"), string(row, "text"), vector);
            stock.passages.add(passage);
            stock.byId.put(id, passage);
        }
        return stock;
    }

    /** {@code fragen}: id, text, beantwortbar. */
    static List<Question> questions(String json) {
        List<Question> out = new ArrayList<Question>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("fragen")) {
            JsonObject row = element.getAsJsonObject();
            out.add(new Question(row.get("id").getAsString(), row.get("text").getAsString(),
                    row.has("beantwortbar") && row.get("beantwortbar").getAsBoolean()));
        }
        return out;
    }

    /** Question id to vector: {@code {"F01": [..], ...}}. */
    static Map<String, float[]> questionVectors(String json) {
        Map<String, float[]> out = new LinkedHashMap<String, float[]>();
        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
            JsonArray values = entry.getValue().getAsJsonArray();
            float[] vector = new float[values.size()];
            for (int d = 0; d < vector.length; d++) {
                vector[d] = values.get(d).getAsFloat();
            }
            out.put(entry.getKey(), vector);
        }
        return out;
    }

    static String questionVectorsJson(Map<String, float[]> vectors) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
            JsonArray values = new JsonArray();
            for (float value : entry.getValue()) {
                values.add(value);
            }
            out.add(entry.getKey(), values);
        }
        return out.toString();
    }

    /** The judged pairs, one JSON object per line ({@code frage}, {@code passage}, {@code urteil}). */
    final class Verdicts {
        final Map<String, String> byPassage = new HashMap<String, String>();
        final Map<String, String> byText = new HashMap<String, String>();
        final Set<String> withDirectHit = new TreeSet<String>();
        int pairs;
        int unknownPassages;
        int conflicts;

        Verdicts(String jsonl) {
            for (String line : jsonl.split("\\r?\\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                String question = row.get("frage").getAsString();
                String passageId = row.get("passage").getAsString();
                String verdict = row.get("urteil").getAsString();
                pairs++;
                byPassage.put(question + "\t" + passageId, verdict);
                if ("BELEGT".equals(verdict)) {
                    withDirectHit.add(question);
                }
                Passage passage = byId.get(passageId);
                if (passage == null) {
                    unknownPassages++;
                    continue;
                }
                String textKey = question + "\t" + passage.key;
                String earlier = byText.get(textKey);
                if (earlier == null) {
                    byText.put(textKey, verdict);
                } else if (!earlier.equals(verdict)) {
                    conflicts++;
                }
            }
        }

        /** BELEGT, HINWEIS, KEIN, or {@code null} when neither the passage nor its text was judged. */
        String of(String question, Candidate candidate) {
            String own = byPassage.get(question + "\t" + candidate.getPassageId());
            return own != null ? own : byText.get(question + "\t" + candidate.getTextKey());
        }
    }

    Verdicts verdicts(String jsonl) {
        return new Verdicts(jsonl);
    }

    /** The product's index over the stock, in a scratch folder. */
    CompositeSemanticKnowledgeIndex index(File folder) {
        CompositeSemanticKnowledgeIndex index = new CompositeSemanticKnowledgeIndex(folder, PROJECT);
        List<PassageIndexDocument> documents = new ArrayList<PassageIndexDocument>(passages.size());
        for (Passage passage : passages) {
            documents.add(new PassageIndexDocument(passage.id, passage.captureId, passage.sourceId, passage.text,
                    passage.heading, "stock", WORLD, passage.vector));
        }
        index.indexPassages(PROJECT, documents);
        return index;
    }

    /** An embedding port that answers with the stored vector of each question text. */
    static EmbeddingPort storedVectors(final List<Question> questions, final Map<String, float[]> vectors) {
        final Map<String, float[]> byText = new HashMap<String, float[]>();
        for (Question question : questions) {
            float[] vector = vectors.get(question.id);
            if (vector == null) {
                throw new IllegalStateException("no stored vector for question " + question.id);
            }
            byText.put(question.text.trim(), vector);
        }
        return new EmbeddingPort() {
            @Override
            public List<EmbeddingVector> embed(Role role, List<String> texts) {
                List<EmbeddingVector> out = new ArrayList<EmbeddingVector>(texts.size());
                for (String text : texts) {
                    float[] vector = byText.get(text.trim());
                    if (vector == null) {
                        throw new IllegalStateException("no stored vector for '" + text + "'");
                    }
                    out.add(new EmbeddingVector("stored", WORLD, vector));
                }
                return out;
            }
        };
    }

    /** The lists of one mode, scored. */
    final class Scored {
        final CandidateSearchSettings settings;
        final List<Question> questions;
        final List<CandidateList> lists;
        final List<String> served = new ArrayList<String>();
        final List<String> unserved = new ArrayList<String>();
        final List<String> falseNoEvidence = new ArrayList<String>();
        final List<String> failed = new ArrayList<String>();
        int answerable;
        int candidates;
        int direct;
        int hints;
        int none;
        int unjudged;
        final JsonArray perQuestion = new JsonArray();

        Scored(CandidateSearchSettings settings, List<Question> questions, List<CandidateList> lists,
               Verdicts verdicts, Map<String, String> groups) {
            this.settings = settings;
            this.questions = questions;
            this.lists = lists;
            for (int i = 0; i < questions.size(); i++) {
                score(questions.get(i), lists.get(i), verdicts, groups);
            }
        }

        private void score(Question question, CandidateList list, Verdicts verdicts, Map<String, String> groups) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", question.id);
            entry.addProperty("question", question.text);
            entry.addProperty("answerable", question.answerable);
            entry.addProperty("similarity", list.getSimilarity().name());
            entry.addProperty("keywords", list.getKeywords().name());
            JsonArray notes = new JsonArray();
            for (String note : list.getNotes()) {
                notes.add(note);
            }
            entry.add("notes", notes);
            if (list.isFailed()) {
                failed.add(question.id);
            }
            int ownDirect = 0;
            int ownHints = 0;
            int ownNone = 0;
            int ownUnjudged = 0;
            Set<String> directGroups = new TreeSet<String>();
            JsonArray rows = new JsonArray();
            int n = 0;
            for (Candidate candidate : list.getCandidates()) {
                Passage passage = byId.get(candidate.getPassageId());
                String domain = passage == null ? "" : passage.domain;
                String verdict = verdicts == null ? null : verdicts.of(question.id, candidate);
                if ("BELEGT".equals(verdict)) {
                    ownDirect++;
                    directGroups.add(groupOf(domain, candidate, groups));
                } else if ("HINWEIS".equals(verdict)) {
                    ownHints++;
                } else if ("KEIN".equals(verdict)) {
                    ownNone++;
                } else {
                    ownUnjudged++;
                }
                JsonObject row = new JsonObject();
                row.addProperty("n", ++n);
                row.addProperty("passageId", candidate.getPassageId());
                row.addProperty("sourceId", candidate.getSourceId());
                row.addProperty("domain", domain);
                row.addProperty("via", candidate.getVia().name());
                row.addProperty("similarityRank", candidate.getSimilarityRank());
                row.addProperty("keywordRank", candidate.getKeywordRank());
                if (!Double.isNaN(candidate.getSimilarity())) {
                    row.addProperty("similarity", Math.round(candidate.getSimilarity() * 10000) / 10000.0);
                }
                row.addProperty("verdict", verdict == null ? "" : verdict);
                JsonArray same = new JsonArray();
                for (String other : candidate.getSameTextPassageIds()) {
                    same.add(other);
                }
                row.add("sameText", same);
                rows.add(row);
            }
            boolean isServed = ownDirect >= SERVED_HITS && directGroups.size() >= SERVED_GROUPS;
            if (question.answerable) {
                answerable++;
                (isServed ? served : unserved).add(question.id);
            }
            if (verdicts != null && ownDirect == 0 && verdicts.withDirectHit.contains(question.id)) {
                falseNoEvidence.add(question.id);
            }
            candidates += list.size();
            direct += ownDirect;
            hints += ownHints;
            none += ownNone;
            unjudged += ownUnjudged;
            entry.addProperty("served", isServed);
            entry.addProperty("direct", ownDirect);
            entry.addProperty("directGroups", directGroups.size());
            entry.addProperty("hints", ownHints);
            entry.addProperty("none", ownNone);
            entry.addProperty("unjudged", ownUnjudged);
            entry.add("candidates", rows);
            perQuestion.add(entry);
        }

        String summary(boolean withVerdicts) {
            String head = "[candidates] " + settings + ": " + candidates + " candidates for " + questions.size()
                    + " questions";
            if (!failed.isEmpty()) {
                head += ", FAILED lists " + failed;
            }
            if (!withVerdicts) {
                return head;
            }
            return head + "; served " + served.size() + " of " + answerable + " answerable (unserved " + unserved
                    + "); direct " + direct + ", hints " + hints + ", no " + none + ", unjudged " + unjudged
                    + "; false 'Kein Beleg gefunden' " + (falseNoEvidence.isEmpty() ? "none" : falseNoEvidence);
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("mode", settings.getMode().name());
            out.addProperty("perList", settings.getPerList());
            out.addProperty("perSource", settings.getPerSource());
            out.addProperty("depth", settings.getDepth());
            out.addProperty("answerable", answerable);
            out.add("served", strings(served));
            out.add("unserved", strings(unserved));
            out.add("falseNoEvidence", strings(falseNoEvidence));
            out.add("failed", strings(failed));
            out.addProperty("candidates", candidates);
            out.addProperty("direct", direct);
            out.addProperty("hints", hints);
            out.addProperty("none", none);
            out.addProperty("unjudged", unjudged);
            return out;
        }
    }

    /**
     * Source groups, as in the comparison of 21.11.: domains are joined when a text of the lists (same key) stands on
     * both, wherever in the stock it stands. A passage without a domain counts as its own source.
     */
    Map<String, String> groups(List<List<CandidateList>> runs) {
        Map<String, Set<String>> domainsByKey = new HashMap<String, Set<String>>();
        for (Passage passage : passages) {
            if (passage.domain.isEmpty()) {
                continue;
            }
            Set<String> domains = domainsByKey.get(passage.key);
            if (domains == null) {
                domains = new LinkedHashSet<String>();
                domainsByKey.put(passage.key, domains);
            }
            domains.add(passage.domain);
        }
        Set<String> pool = new TreeSet<String>();
        for (List<CandidateList> run : runs) {
            for (CandidateList list : run) {
                for (Candidate candidate : list.getCandidates()) {
                    pool.add(candidate.getTextKey());
                }
            }
        }
        Map<String, String> parent = new HashMap<String, String>();
        for (String key : pool) {
            Set<String> domains = domainsByKey.get(key);
            if (domains == null) {
                continue;
            }
            List<String> sorted = new ArrayList<String>(new TreeSet<String>(domains));
            for (String domain : sorted) {
                find(parent, domain);
            }
            for (int i = 1; i < sorted.size(); i++) {
                parent.put(find(parent, sorted.get(i)), find(parent, sorted.get(0)));
            }
        }
        Map<String, String> groups = new HashMap<String, String>();
        for (String domain : new ArrayList<String>(parent.keySet())) {
            groups.put(domain, find(parent, domain));
        }
        return groups;
    }

    private static String groupOf(String domain, Candidate candidate, Map<String, String> groups) {
        if (domain.isEmpty()) {
            return "source:" + candidate.getSourceId();
        }
        String group = groups.get(domain);
        return group == null ? domain : group;
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        if (!parent.containsKey(root)) {
            parent.put(root, root);
            return root;
        }
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        String walk = x;
        while (!parent.get(walk).equals(root)) {
            String next = parent.get(walk);
            parent.put(walk, root);
            walk = next;
        }
        return root;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray out = new JsonArray();
        for (String value : values) {
            out.add(value);
        }
        return out;
    }

    private static String string(JsonObject row, String name) {
        JsonElement value = row.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static List<String> texts(List<Question> questions) {
        List<String> out = new ArrayList<String>(questions.size());
        for (Question question : questions) {
            out.add(question.text);
        }
        return out;
    }

    static void write(File file, JsonElement json) throws IOException {
        Files.write(file.toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json)
                .getBytes(StandardCharsets.UTF_8));
    }
}

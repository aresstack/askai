package com.aresstack.askai.research.benchmark.retrieval;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.research.benchmark.LocalRerankerRuntime;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.VectorMath;
import com.aresstack.askai.research.knowledge.processing.embedding.EmbeddingPromptScheme;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.runtime.rerank.Bm25CandidateReranker;
import com.aresstack.askai.research.runtime.rerank.CandidateReranker;
import com.aresstack.askai.research.runtime.rerank.HttpRerankerClient;
import com.aresstack.askai.research.runtime.rerank.SearchResultReranker;
import com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The retrieval benchmark of plan 5.4 item 7, run only by {@code gradlew :research-benchmark:benchmark}. It needs
 * the owner's query file, the local data directory ({@code -Daskai.benchmark.retrievalData=...}) and a reachable
 * Ollama with the candidates installed; otherwise it skips with the reason. Every candidate runs through the
 * productive {@link HttpEmbeddingPortAdapter} with the prompts of its model card; the current preset (first
 * candidate) runs once more raw, as before PR 04. Results go to a Markdown report without machine paths.
 */
public class RetrievalBenchmarkLiveTest {

    private static final String P = "askai.benchmark.";
    static final String DEFAULT_CANDIDATES = "nomic-embed-text,nomic-embed-text-v2-moe,bge-m3,embeddinggemma,"
            + "snowflake-arctic-embed2,granite-embedding:278m";

    private static final class Run {
        final String label;
        final String model;
        final boolean raw;
        final Map<RetrievalBenchmarkData.Group, RetrievalMetrics.Summary> groups =
                new LinkedHashMap<RetrievalBenchmarkData.Group, RetrievalMetrics.Summary>();
        int assignedRight;
        int assignedTotal;
        double millisPer100;
        long gpuMiB = -1;
        long longestPassageTokens = -1;
        long contextLength = -1;
        String problem = "";

        Run(String label, String model, boolean raw) {
            this.label = label;
            this.model = model;
            this.raw = raw;
        }

        double meanRecall10() {
            double sum = 0;
            for (RetrievalMetrics.Summary s : groups.values()) {
                sum += s.recallAt10;
            }
            return groups.isEmpty() ? 0 : sum / groups.size();
        }
    }

    @Test
    public void retrievalBenchmark() throws Exception {
        RetrievalBenchmarkData.QuerySet set = RetrievalBenchmarkData.loadQueries();
        Assume.assumeFalse("retrieval-queries.json has no queries yet (the owner chooses topic and queries)",
                set.isEmpty());
        String dataDir = System.getProperty(P + "retrievalData", "").trim();
        Assume.assumeFalse("set -Daskai.benchmark.retrievalData to the local corpus directory", dataDir.isEmpty());
        File data = new File(dataDir);
        Map<String, RetrievalBenchmarkData.Passage> corpus = RetrievalBenchmarkData.loadCorpus(data, set);
        String ollama = System.getProperty(P + "ollamaUrl", "http://127.0.0.1:11434").replaceAll("/+$", "");
        ProtocolProbes probes = new ProtocolProbes(ollama, 600_000);
        Assume.assumeFalse("Ollama not reachable at the configured URL", "unknown".equals(probes.version()));
        List<String> candidates = ProtocolProbes.models(System.getProperty(P + "embeddingCandidates",
                DEFAULT_CANDIDATES));
        int visitTopN = Integer.getInteger(P + "visitTopN", 5);

        List<Run> runs = new ArrayList<Run>();
        for (int i = 0; i < candidates.size(); i++) {
            String model = candidates.get(i);
            runs.add(measure(probes, ollama, model, false, set, corpus));
            if (i == 0) {
                runs.add(measure(probes, ollama, model, true, set, corpus));
            }
        }

        // Ranking of planned queries: BM25, cross-encoder, embedding (current preset, prompted), RRF.
        Map<String, Map<RetrievalBenchmarkData.Group, List<Integer>>> strategyRanks =
                new LinkedHashMap<String, Map<RetrievalBenchmarkData.Group, List<Integer>>>();
        Map<String, Integer> nothing = new LinkedHashMap<String, Integer>();
        Map<String, Integer> failures = new LinkedHashMap<String, Integer>();
        String rankingNote = rankPlannedQueries(probes, ollama, candidates.get(0), set, data, visitTopN,
                strategyRanks, nothing, failures);

        String report = report(probes.version(), set, runs, strategyRanks, nothing, failures, visitTopN,
                rankingNote);
        File dir = new File(System.getProperty(P + "reportDir", "build/reports/research-benchmark"));
        dir.mkdirs();
        File out = new File(dir, "retrieval-benchmark-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".md");
        Files.write(out.toPath(), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
        System.out.println("[retrieval-benchmark] report " + out.getName());
    }

    private static Run measure(ProtocolProbes probes, String ollama, String model, boolean raw,
                               RetrievalBenchmarkData.QuerySet set,
                               Map<String, RetrievalBenchmarkData.Passage> corpus) {
        EmbeddingPromptScheme scheme = EmbeddingPromptScheme.forModel(model);
        Run run = new Run(model + (raw ? " (raw, as before PR 04)"
                : scheme.isRaw() ? " (raw: no known prompt scheme)" : " (" + scheme.id() + ")"), model, raw);
        try {
            int dimension = probes.embeddingDimension(model);
            EmbeddingPort port = new HttpEmbeddingPortAdapter(new EmbeddingEndpointDescriptor(model, ollama,
                    "/api/embed", dimension, "none", "", 600_000L), new UrlConnectionEmbeddingHttpTransport(600_000));
            List<String> ids = new ArrayList<String>(corpus.keySet());
            List<String> texts = new ArrayList<String>();
            for (String id : ids) {
                texts.add(corpus.get(id).text);
            }
            long started = System.nanoTime();
            List<EmbeddingPort.EmbeddingVector> docs = new ArrayList<EmbeddingPort.EmbeddingVector>();
            for (int from = 0; from < texts.size(); from += 32) {
                List<String> batch = texts.subList(from, Math.min(texts.size(), from + 32));
                docs.addAll(raw ? port.embedRaw(batch) : port.embedDocuments(batch));
            }
            run.millisPer100 = (System.nanoTime() - started) / 1e6 * 100.0 / Math.max(1, texts.size());
            run.gpuMiB = ProtocolProbes.gpuMemoryUsedMiB();
            // Token order differs from character order (and per model), so every passage is counted.
            for (String text : texts) {
                run.longestPassageTokens = Math.max(run.longestPassageTokens, probes.embeddingTokens(model, text));
            }
            run.contextLength = probes.contextLength(model);
            Map<String, EmbeddingPort.EmbeddingVector> byId = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
            for (int i = 0; i < ids.size(); i++) {
                byId.put(ids.get(i), docs.get(i));
            }
            Map<RetrievalBenchmarkData.Group, List<Integer>> ranks =
                    new LinkedHashMap<RetrievalBenchmarkData.Group, List<Integer>>();
            for (RetrievalBenchmarkData.Query query : set.queries) {
                EmbeddingPort.EmbeddingVector q = (raw ? port.embedRaw(Arrays.asList(query.text))
                        : port.embedQueries(Arrays.asList(query.text))).get(0);
                ranks.computeIfAbsent(query.group, g -> new ArrayList<Integer>())
                        .add(RetrievalMetrics.rankOf(nearest(q, byId), query.targetPassageId));
            }
            for (Map.Entry<RetrievalBenchmarkData.Group, List<Integer>> e : ranks.entrySet()) {
                run.groups.put(e.getKey(), RetrievalMetrics.summarize(e.getValue()));
            }
            // Concept assignment (rule of PR 22): label path as query, passage to its nearest node.
            if (!set.passageLabels.isEmpty()) {
                List<String> nodeIds = new ArrayList<String>(set.conceptLabelPaths.keySet());
                List<EmbeddingPort.EmbeddingVector> nodes = raw
                        ? port.embedRaw(new ArrayList<String>(set.conceptLabelPaths.values()))
                        : port.embedQueries(new ArrayList<String>(set.conceptLabelPaths.values()));
                for (Map.Entry<String, String> label : set.passageLabels.entrySet()) {
                    EmbeddingPort.EmbeddingVector passage = byId.get(label.getKey());
                    int best = 0;
                    for (int n = 1; n < nodes.size(); n++) {
                        if (VectorMath.cosine(passage, nodes.get(n)) > VectorMath.cosine(passage, nodes.get(best))) {
                            best = n;
                        }
                    }
                    run.assignedTotal++;
                    if (nodeIds.get(best).equals(label.getValue())) {
                        run.assignedRight++;
                    }
                }
            }
        } catch (Exception | Error failed) {
            run.problem = failed.getClass().getSimpleName() + ": " + failed.getMessage();
        }
        return run;
    }

    private static List<String> nearest(EmbeddingPort.EmbeddingVector q,
                                        Map<String, EmbeddingPort.EmbeddingVector> byId) {
        List<Map.Entry<String, Double>> scored = new ArrayList<Map.Entry<String, Double>>();
        for (Map.Entry<String, EmbeddingPort.EmbeddingVector> e : byId.entrySet()) {
            scored.add(new java.util.AbstractMap.SimpleEntry<String, Double>(e.getKey(),
                    VectorMath.cosine(q, e.getValue())));
        }
        scored.sort((a, b) -> {
            int c = Double.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> ids = new ArrayList<String>();
        for (Map.Entry<String, Double> e : scored) {
            ids.add(e.getKey());
        }
        return ids;
    }

    private static String rankPlannedQueries(ProtocolProbes probes, String ollama, String presetModel,
                                             RetrievalBenchmarkData.QuerySet set, File data, int visitTopN,
                                             Map<String, Map<RetrievalBenchmarkData.Group, List<Integer>>> ranks,
                                             Map<String, Integer> nothing, Map<String, Integer> failures) {
        StringBuilder note = new StringBuilder();
        LocalRerankerRuntime runtime = null;
        CandidateReranker crossEncoder = null;
        try {
            File root = new File(System.getProperty(P + "localModelRoot",
                    LocalRerankerRuntime.defaultModelRoot().getPath()));
            String model = LocalRerankerRuntime.rerankModelOrNull(root);
            if (model == null) {
                note.append("No installed cross-encoder: its column is empty. ");
            } else {
                runtime = LocalRerankerRuntime.start(root, model);
                crossEncoder = new SearchResultReranker(new HttpRerankerClient(new RerankerEndpointDescriptor(
                        RerankerProvider.ASKAI_LOCAL, runtime.baseUrl(), model,
                        Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 60_000L,
                        RerankerSelectionConfiguration.topN(visitTopN))),
                        new SearchResultSelectionPolicy(RerankerSelectionConfiguration.topN(visitTopN)),
                        model, RerankerScoreSemantics.RAW_LOGIT);
                note.append("Cross-encoder: ").append(model).append(". ");
            }
            int dimension = probes.embeddingDimension(presetModel);
            EmbeddingPort port = new HttpEmbeddingPortAdapter(new EmbeddingEndpointDescriptor(presetModel, ollama,
                    "/api/embed", dimension, "none", "", 600_000L), new UrlConnectionEmbeddingHttpTransport(600_000));
            note.append("Embedding similarity and fusion use ").append(presetModel).append(". ");
            CandidateReranker bm25 = new Bm25CandidateReranker();
            int missing = 0;
            for (RetrievalBenchmarkData.Query query : set.queries) {
                RetrievalBenchmarkData.SearchResults results = RetrievalBenchmarkData.loadSearchResults(data,
                        query.id);
                if (results == null) {
                    missing++;
                    continue;
                }
                RankingStrategies.Ranking lexical = RankingStrategies.byReranker(bm25, query.text, results.results);
                RankingStrategies.Ranking embedding = RankingStrategies.byEmbedding(port, query.text,
                        results.results);
                record(ranks, nothing, failures, "BM25", query, lexical, results.targetResultId);
                record(ranks, nothing, failures, "Embedding similarity", query, embedding, results.targetResultId);
                record(ranks, nothing, failures, "BM25 + embedding (RRF)", query,
                        RankingStrategies.byFusion(lexical, embedding), results.targetResultId);
                if (crossEncoder != null) {
                    record(ranks, nothing, failures, "Cross-encoder", query,
                            RankingStrategies.byReranker(crossEncoder, query.text, results.results),
                            results.targetResultId);
                }
            }
            if (missing > 0) {
                note.append(missing).append(" queries have no search result file. ");
            }
        } catch (Exception failed) {
            note.append("Ranking failed: ").append(failed.getMessage()).append(". ");
        } finally {
            if (runtime != null) {
                runtime.close();
            }
        }
        return note.toString().trim();
    }

    private static void record(Map<String, Map<RetrievalBenchmarkData.Group, List<Integer>>> ranks,
                               Map<String, Integer> nothing, Map<String, Integer> failures, String strategy,
                               RetrievalBenchmarkData.Query query, RankingStrategies.Ranking ranking, String target) {
        // An operational failure (timeout, unloadable model) is not a retrieval miss: it is counted apart and
        // stays out of the hit-rate denominator. A fusion warning about one failed path is not a failure.
        if (!ranking.problem.isEmpty() && ranking.ids.isEmpty()) {
            failures.merge(strategy, 1, Integer::sum);
            ranks.computeIfAbsent(strategy, s -> new LinkedHashMap<RetrievalBenchmarkData.Group, List<Integer>>());
            return;
        }
        ranks.computeIfAbsent(strategy, s -> new LinkedHashMap<RetrievalBenchmarkData.Group, List<Integer>>())
                .computeIfAbsent(query.group, g -> new ArrayList<Integer>())
                .add(RetrievalMetrics.rankOf(ranking.ids, target));
        nothing.merge(strategy, ranking.selectedNothing ? 1 : 0, Integer::sum);
    }

    static String report(String ollamaVersion, RetrievalBenchmarkData.QuerySet set, List<Run> runs,
                         Map<String, Map<RetrievalBenchmarkData.Group, List<Integer>>> strategyRanks,
                         Map<String, Integer> nothing, Map<String, Integer> failures, int visitTopN,
                         String rankingNote) {
        StringBuilder md = new StringBuilder();
        md.append("# Retrieval benchmark\n\n");
        md.append("Ollama ").append(ollamaVersion).append(", topic: ").append(set.topic).append(", ")
                .append(set.queries.size()).append(" queries.\n\n");
        md.append("## Embedding candidates\n\n");
        md.append("| Candidate | Group | Recall@5 | Recall@10 | MRR |\n|---|---|---|---|---|\n");
        for (Run run : runs) {
            if (!run.problem.isEmpty()) {
                md.append("| ").append(run.label).append(" | failed: ").append(run.problem.replace('|', '/'))
                        .append(" | | | |\n");
                continue;
            }
            for (Map.Entry<RetrievalBenchmarkData.Group, RetrievalMetrics.Summary> g : run.groups.entrySet()) {
                md.append(String.format(Locale.ROOT, "| %s | %s | %.3f | %.3f | %.3f |%n", run.label, g.getKey(),
                        g.getValue().recallAt5, g.getValue().recallAt10, g.getValue().meanReciprocalRank));
            }
        }
        md.append("\n| Candidate | Concept assignment | ms per 100 passages | GPU MiB (nvidia-smi) | Longest passage "
                + "tokens / context |\n|---|---|---|---|---|\n");
        for (Run run : runs) {
            if (!run.problem.isEmpty()) {
                continue;
            }
            md.append(String.format(Locale.ROOT, "| %s | %d of %d | %.0f | %s | %s / %s |%n", run.label,
                    run.assignedRight, run.assignedTotal, run.millisPer100,
                    run.gpuMiB < 0 ? "not measured" : String.valueOf(run.gpuMiB),
                    run.longestPassageTokens < 0 ? "?" : String.valueOf(run.longestPassageTokens),
                    run.contextLength < 0 ? "?" : String.valueOf(run.contextLength)));
        }
        md.append("\n## Ranking of planned queries (target in the visited top ").append(visitTopN).append(")\n\n");
        md.append(rankingNote).append("\n\n| Strategy | DE_DE | DE_EN | EN_EN | Mean | Selected nothing | Failed calls |\n"
                + "|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, Map<RetrievalBenchmarkData.Group, List<Integer>>> s : strategyRanks.entrySet()) {
            md.append("| ").append(s.getKey());
            double sum = 0;
            for (RetrievalBenchmarkData.Group g : RetrievalBenchmarkData.Group.values()) {
                double rate = RetrievalMetrics.hitRate(s.getValue().get(g), visitTopN);
                sum += rate;
                md.append(String.format(Locale.ROOT, " | %.3f", rate));
            }
            md.append(String.format(Locale.ROOT, " | %.3f | %d | %d |%n", sum / 3, nothing.getOrDefault(s.getKey(), 0),
                    failures.getOrDefault(s.getKey(), 0)));
        }
        md.append("\n## Rule of 5.4 item 7\n\n").append(winner(runs)).append('\n');
        return md.toString();
    }

    /**
     * Among the candidates whose DE→EN Recall@10 is not below the current preset's, the best mean Recall@10.
     * Whether a candidate fits beside the phase models is a GPU measurement and stays for the owner to confirm.
     */
    static String winner(List<Run> runs) {
        // The first run is the configured preset (prompted); it is never replaced by another candidate.
        Run preset = runs.isEmpty() ? null : runs.get(0);
        if (preset == null || preset.raw || !preset.problem.isEmpty()
                || preset.groups.get(RetrievalBenchmarkData.Group.DE_EN) == null) {
            return "No result for the current preset; no choice.";
        }
        double floor = preset.groups.get(RetrievalBenchmarkData.Group.DE_EN).recallAt10;
        Run best = null;
        for (Run run : runs) {
            RetrievalMetrics.Summary deEn = run.groups.get(RetrievalBenchmarkData.Group.DE_EN);
            if (run.raw || !run.problem.isEmpty() || deEn == null || deEn.recallAt10 < floor) {
                continue;
            }
            if (best == null || run.meanRecall10() > best.meanRecall10()) {
                best = run;
            }
        }
        return best == null ? "No candidate qualifies." : String.format(Locale.ROOT,
                "Best qualifying candidate: %s (mean Recall@10 %.3f). It becomes the preset only if it fits beside "
                        + "the phase models (GPU column).", best.label, best.meanRecall10());
    }
}

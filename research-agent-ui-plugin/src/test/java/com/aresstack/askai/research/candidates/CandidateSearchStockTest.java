package com.aresstack.askai.research.candidates;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.lucene.CompositeSemanticKnowledgeIndex;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Component 5 on a stored stock: both modes of {@link CandidateSearch} over the product's index, scored against
 * judged pairs ({@link CandidateStock}). {@link #sampleStock()} runs the whole path on the small self-written stock in
 * {@code src/test/resources/candidates-stock} in the normal test run. {@link #storedStock()} runs on a real stock and
 * is skipped without {@code -Daskai.stock.candidates.corpus}:
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*CandidateSearchStockTest*'
 *   -Daskai.stock.candidates.corpus=replay-corpus.json   (format of the outline replay, #255)
 *   -Daskai.stock.candidates.vectors=replay-vectors.f32  (optional: float32 little endian, passages x dim)
 *   -Daskai.stock.candidates.questions=fragen-1711.json
 *   -Daskai.stock.candidates.verdicts=belegpaare-1711.jsonl         (optional: without it only the lists)
 *   -Daskai.stock.candidates.questionVectors=question-vectors.json (optional: else embedded once by Ollama)
 *   -Daskai.stock.candidates.url=http://localhost:11434 -Daskai.stock.candidates.embedModel=bge-m3
 *   -Daskai.stock.candidates.out=build/candidates-stock
 *   -Daskai.stock.candidates.perList / .perSource / .depth          (optional: else the product defaults)
 * </pre>
 * The questions are embedded with the product's adapter in the query role; the vectors are written to
 * {@code question-vectors.json} in the output folder, so a second run needs no model. Nothing of the stock is written
 * to the repository; the index lives in a scratch folder.
 */
public class CandidateSearchStockTest {

    private static final String P = "askai.stock.candidates.";

    @Rule
    public TemporaryFolder scratch = new TemporaryFolder();

    @Test
    public void sampleStock() throws Exception {
        String corpus = resource("corpus.json");
        List<CandidateStock.Question> questions = CandidateStock.questions(resource("questions.json"));
        Map<String, float[]> vectors = CandidateStock.questionVectors(resource("question-vectors.json"));
        CandidateSearchSettings settings = new CandidateSearchSettings(CandidateSearchSettings.Mode.UNION, 3, 2,
                100);
        File out = scratch.newFolder("out");
        Run run = run(CandidateStock.read(corpus, null), questions, vectors, resource("verdicts.jsonl"), settings,
                scratch.newFolder("index"), out);

        CandidateStock.Scored union = run.union;
        assertEquals(Arrays.asList("p1", "p3", "p2", "p4"), union.lists.get(0).passageIds());
        Candidate p1 = union.lists.get(0).getCandidates().get(0);
        assertEquals(Candidate.Via.BOTH, p1.getVia());
        assertEquals("p5 carries the text of p1", Arrays.asList("p5"), p1.getSameTextPassageIds());
        assertEquals(Arrays.asList("p6", "p7", "p8", "p1"), union.lists.get(1).passageIds());
        assertEquals(Arrays.asList("Q1"), union.served);
        assertEquals(Arrays.asList("Q2"), union.unserved);
        assertEquals("Q3 has a direct hit among the pairs, not in its list", Arrays.asList("Q3"),
                union.falseNoEvidence);
        assertEquals(12, union.candidates);
        assertEquals(4, union.direct);
        assertEquals("p3 for Q1; p7 and p1 (judged as p5, same text) for Q2", 3, union.hints);
        assertEquals(5, union.unjudged);

        CandidateStock.Scored fusion = run.fusion;
        assertEquals(Arrays.asList("p1", "p2", "p4"), fusion.lists.get(0).passageIds());
        assertEquals(Arrays.asList("p6", "p7", "p1"), fusion.lists.get(1).passageIds());
        assertEquals(Arrays.asList("Q1"), fusion.served);
        assertEquals(9, fusion.candidates);
        assertEquals(4, fusion.direct);
        assertEquals(2, fusion.hints);
        assertEquals(3, fusion.unjudged);
        assertTrue(new File(out, "result.json").isFile());
        assertTrue(new File(out, "lists-union.json").isFile());
        assertTrue(new File(out, "lists-fusion.json").isFile());
    }

    @Test
    public void storedStock() throws Exception {
        String corpusPath = System.getProperty(P + "corpus", "");
        Assume.assumeTrue("candidate stock test skipped (no -D" + P + "corpus)", !corpusPath.isEmpty());
        String questionsPath = System.getProperty(P + "questions", "");
        Assume.assumeTrue("candidate stock test needs -D" + P + "questions", !questionsPath.isEmpty());
        File out = new File(System.getProperty(P + "out", "build/candidates-stock"));
        Files.createDirectories(out.toPath());

        String vectorsPath = System.getProperty(P + "vectors", "");
        ByteBuffer raw = vectorsPath.isEmpty() ? null : ByteBuffer.wrap(Files.readAllBytes(new File(vectorsPath)
                .toPath())).order(ByteOrder.LITTLE_ENDIAN);
        CandidateStock stock = CandidateStock.read(read(corpusPath), raw);
        List<CandidateStock.Question> questions = CandidateStock.questions(read(questionsPath));
        System.out.println("[candidates] stock " + stock.passages.size() + " passages, dim " + stock.dim + ", "
                + questions.size() + " questions");

        String questionVectorsPath = System.getProperty(P + "questionVectors", "");
        Map<String, float[]> vectors = questionVectorsPath.isEmpty()
                ? embed(questions, stock.dim) : CandidateStock.questionVectors(read(questionVectorsPath));
        if (questionVectorsPath.isEmpty()) {
            Files.write(new File(out, "question-vectors.json").toPath(),
                    CandidateStock.questionVectorsJson(vectors).getBytes(StandardCharsets.UTF_8));
        }
        CandidateSearchSettings defaults = CandidateSearchSettings.defaults();
        CandidateSearchSettings settings = new CandidateSearchSettings(CandidateSearchSettings.Mode.UNION,
                Integer.getInteger(P + "perList", defaults.getPerList()),
                Integer.getInteger(P + "perSource", defaults.getPerSource()),
                Integer.getInteger(P + "depth", defaults.getDepth()));
        String verdictsPath = System.getProperty(P + "verdicts", "");
        Run run = run(stock, questions, vectors, verdictsPath.isEmpty() ? null : read(verdictsPath), settings,
                scratch.newFolder("index"), out);
        System.out.println(run.union.summary(run.verdicts != null));
        System.out.println(run.fusion.summary(run.verdicts != null));
        if (run.verdicts != null) {
            System.out.println("[candidates] judged pairs " + run.verdicts.pairs + ", passages not in the stock "
                    + run.verdicts.unknownPassages + ", conflicting verdicts on the same text "
                    + run.verdicts.conflicts);
        }
        System.out.println("[candidates] written to " + out.getAbsolutePath());
        assertTrue("failed lists (union): " + run.union.failed, run.union.failed.isEmpty());
        assertTrue("failed lists (fusion): " + run.fusion.failed, run.fusion.failed.isEmpty());
    }

    /** Both modes over the same index, scored, checked against the list rules and written to {@code out}. */
    static Run run(CandidateStock stock, List<CandidateStock.Question> questions, Map<String, float[]> vectors,
                   String verdictsJsonl, CandidateSearchSettings settings, File indexFolder, File out)
            throws IOException {
        CompositeSemanticKnowledgeIndex index = stock.index(indexFolder);
        EmbeddingPort port = CandidateStock.storedVectors(questions, vectors);
        List<String> texts = CandidateStock.texts(questions);
        CandidateSearchSettings unionSettings = settings.withMode(CandidateSearchSettings.Mode.UNION);
        CandidateSearchSettings fusionSettings = settings.withMode(CandidateSearchSettings.Mode.FUSION);
        List<CandidateList> union = new CandidateSearch(index, port, unionSettings).searchAll(CandidateStock.PROJECT,
                CandidateStock.WORLD, texts);
        List<CandidateList> fusion = new CandidateSearch(index, port, fusionSettings).searchAll(
                CandidateStock.PROJECT, CandidateStock.WORLD, texts);
        for (CandidateList list : union) {
            checkRules(list, 2 * settings.getPerList());
        }
        for (CandidateList list : fusion) {
            checkRules(list, settings.getPerList());
        }
        Map<String, String> groups = stock.groups(Arrays.asList(union, fusion));
        Run run = new Run();
        run.verdicts = verdictsJsonl == null ? null : stock.verdicts(verdictsJsonl);
        run.union = stock.new Scored(unionSettings, questions, union, run.verdicts, groups);
        run.fusion = stock.new Scored(fusionSettings, questions, fusion, run.verdicts, groups);

        JsonObject result = new JsonObject();
        result.addProperty("written", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        result.addProperty("passages", stock.passages.size());
        result.addProperty("questions", questions.size());
        result.addProperty("servedRule", "at least " + CandidateStock.SERVED_HITS + " BELEGT from "
                + CandidateStock.SERVED_GROUPS + " source groups");
        if (run.verdicts != null) {
            JsonObject judged = new JsonObject();
            judged.addProperty("pairs", run.verdicts.pairs);
            judged.addProperty("passagesNotInStock", run.verdicts.unknownPassages);
            judged.addProperty("conflictsOnSameText", run.verdicts.conflicts);
            result.add("verdicts", judged);
        }
        JsonArray modes = new JsonArray();
        modes.add(run.union.toJson());
        modes.add(run.fusion.toJson());
        result.add("modes", modes);
        CandidateStock.write(new File(out, "result.json"), result);
        CandidateStock.write(new File(out, "lists-union.json"), run.union.perQuestion);
        CandidateStock.write(new File(out, "lists-fusion.json"), run.fusion.perQuestion);
        return run;
    }

    /** The list rules seen from outside: a text once, the list size, and every candidate taken from a ranking. */
    private static void checkRules(CandidateList list, int maxSize) {
        assertTrue(list.getQuestion() + ": " + list.size() + " candidates", list.size() <= maxSize);
        Set<String> keys = new HashSet<String>();
        for (Candidate candidate : list.getCandidates()) {
            assertTrue(list.getQuestion() + ": text twice " + candidate, keys.add(candidate.getTextKey()));
            assertTrue(list.getQuestion() + ": no rank " + candidate,
                    candidate.getSimilarityRank() > 0 || candidate.getKeywordRank() > 0);
        }
    }

    /** One batch call to Ollama in the query role, with the product's adapter and prompt scheme. */
    private static Map<String, float[]> embed(List<CandidateStock.Question> questions, int dim) {
        String url = System.getProperty(P + "url", "http://localhost:11434");
        String model = System.getProperty(P + "embedModel", "bge-m3");
        EmbeddingEndpointDescriptor descriptor = new EmbeddingEndpointDescriptor(model, url, "/api/embed", dim, "",
                "", 120000L);
        HttpEmbeddingPortAdapter adapter = new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport(120000));
        List<EmbeddingPort.EmbeddingVector> embedded = adapter.embedQueries(CandidateStock.texts(questions));
        Map<String, float[]> vectors = new LinkedHashMap<String, float[]>();
        for (int i = 0; i < questions.size(); i++) {
            vectors.put(questions.get(i).id, embedded.get(i).getValues());
        }
        System.out.println("[candidates] embedded " + vectors.size() + " questions with " + model + " ("
                + adapter.scheme() + ")");
        return vectors;
    }

    private static String resource(String name) throws IOException {
        InputStream in = CandidateSearchStockTest.class.getResourceAsStream("/candidates-stock/" + name);
        if (in == null) {
            throw new IOException("missing test resource candidates-stock/" + name);
        }
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                bytes.write(buffer, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    static final class Run {
        CandidateStock.Verdicts verdicts;
        CandidateStock.Scored union;
        CandidateStock.Scored fusion;
    }
}

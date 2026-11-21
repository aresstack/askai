package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicProfile;
import com.aresstack.askai.research.outline.synthesis.ReplayOutlineLiveAcceptanceTest.StoredCorpus;
import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The small self-written sample of the replay harness ({@link ReplayOutlineLiveAcceptanceTest}), run through the
 * topic analysis without a model. It keeps the harness and its file format alive in the normal test run.
 * <p>The sample ({@code src/test/resources/outline-replay/sample-corpus.json}, written by {@code make-sample.py}
 * next to it) holds 24 passages on three themes of a made-up book and six passages on a foreign theme. Its vectors
 * are synthetic (a shared subject axis, one axis per theme, a little noise), so the test says nothing about topic
 * discovery on real text. It shows in small what the replay of a real book showed in large: a foreign theme with
 * enough sources becomes a significant, novel topic of its own unless a keep list takes it out.
 */
public class ReplayOutlineSampleTest {

    private static final String[] THEMES = {"bau", "pflanzen", "pflege"};
    private static final String FOREIGN = "fahrrad";

    @Test
    public void theSampleCorpusRunsThroughTheTopicAnalysis() throws Exception {
        StoredCorpus stored = StoredCorpus.read(sample(), null, null);
        assertEquals(30, stored.total);
        assertEquals(30, stored.passages.size());
        assertEquals(3, stored.anchors.size());

        TopicAnalysis analysis = stored.analyse();

        Map<String, TopicProfile> topicOf = topicOfEveryPassage(analysis);
        assertEquals("every passage lies in one topic", 30, topicOf.size());
        for (String theme : THEMES) {
            TopicProfile topic = topicOf.get(theme + "-p1");
            assertEquals("the eight passages of '" + theme + "' lie together", 8, topic.getPassageCount());
            for (String id : topic.getPassageIds()) {
                assertTrue(id + " in the topic of " + theme, id.startsWith(theme + "-"));
            }
            assertEquals("node-" + theme, topic.getConceptAnchorId());
            assertTrue(theme + " matches its concept node", topic.isConceptAligned());
            assertTrue(theme + " has enough passages, sources and domains", topic.isSignificant());
            assertFalse(theme + " is a theme of the concept, not a new one", topic.isNovel());
        }
        TopicProfile foreign = topicOf.get(FOREIGN + "-p1");
        assertEquals(6, foreign.getPassageCount());
        assertFalse("the foreign theme matches no concept node", foreign.isConceptAligned());
        assertTrue("three sources on two domains make it significant", foreign.isSignificant());
        assertTrue("and the analysis reports it as a new topic", foreign.isNovel());
    }

    @Test
    public void aKeepListNarrowsTheCorpus() throws Exception {
        JsonObject corpus = sample();
        Set<String> keep = new HashSet<String>();
        for (JsonElement element : corpus.getAsJsonArray("passages")) {
            String id = element.getAsJsonObject().get("id").getAsString();
            if (!id.startsWith(FOREIGN + "-")) {
                keep.add(id);
            }
        }

        StoredCorpus stored = StoredCorpus.read(corpus, null, keep);
        assertEquals(30, stored.total);
        assertEquals(24, stored.passages.size());

        TopicAnalysis analysis = stored.analyse();
        Map<String, TopicProfile> topicOf = topicOfEveryPassage(analysis);
        assertEquals(24, topicOf.size());
        for (TopicProfile topic : analysis.getTopics()) {
            assertFalse("no new topic is left: " + topic.getTitle(), topic.isNovel());
        }
    }

    @Test
    public void aVectorFileIsReadLikeTheVectorsInTheRows() throws Exception {
        JsonObject corpus = sample();
        int dim = corpus.get("dim").getAsInt();
        JsonArray rows = corpus.getAsJsonArray("passages");
        ByteBuffer raw = ByteBuffer.allocate(rows.size() * dim * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (JsonElement element : rows) {
            for (JsonElement value : element.getAsJsonObject().getAsJsonArray("vector")) {
                raw.putFloat(value.getAsFloat());
            }
        }

        StoredCorpus fromRows = StoredCorpus.read(corpus, null, null);
        StoredCorpus fromFile = StoredCorpus.read(corpus, raw, null);

        assertEquals(fromRows.vectors.keySet(), fromFile.vectors.keySet());
        for (Map.Entry<String, EmbeddingPort.EmbeddingVector> entry : fromRows.vectors.entrySet()) {
            assertArrayEquals(entry.getKey(), entry.getValue().getValues(),
                    fromFile.vectors.get(entry.getKey()).getValues(), 0f);
        }
    }

    @Test
    public void theProseRuleTakesBoilerplateOutOfARawExport() throws Exception {
        // One article page and one listing page, both out of page order in the file.
        JsonObject corpus = sample();
        int dim = corpus.get("dim").getAsInt();
        JsonArray rows = new JsonArray();
        rows.add(row(dim, "artikel#p4@v", "artikel", "Die Sohle misst den Druck unter dem Fuß und meldet eine "
                + "Fehlbelastung, bevor der Träger sie selbst bemerkt."));
        rows.add(row(dim, "artikel#p0@v", "artikel", "Startseite Produkte Kontakt"));
        rows.add(row(dim, "artikel#p2@v", "artikel", "Messung im Schuh"));
        StringBuilder menu = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            rows.add(row(dim, "liste#p" + (i < 20 ? i : i + 1) + "@v", "liste", "Kalender " + (2000 + i)));
        }
        rows.add(row(dim, "liste#p20@v", "liste", "Wer regelmäßig übt, bleibt im Notfall ruhig und hilft sofort."));
        corpus.add("passages", rows);

        ProsePassageFilter prose = new ProsePassageFilter(ProsePassageFilter.Criteria.defaults());
        StoredCorpus stored = StoredCorpus.read(corpus, null, null, prose);

        assertEquals(44, stored.total);
        assertEquals("only the statement of the article page stays", 1, stored.passages.size());
        assertEquals("artikel#p4@v", stored.passages.get(0).getPassageId());
        assertEquals("the menu, the heading and the 40 product lines are no prose", 42, stored.noProse);
        assertEquals("the lone sentence between the product lines is a caption", 1, stored.captions);
        assertEquals("without the rule every row is taken as analysed", 44,
                StoredCorpus.read(corpus, null, null).passages.size());
    }

    private static JsonObject row(int dim, String id, String capture, String text) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("captureId", capture);
        row.addProperty("sourceId", "s-" + capture);
        row.addProperty("domain", capture + ".example");
        row.addProperty("heading", "");
        row.addProperty("text", text);
        JsonArray vector = new JsonArray();
        for (int d = 0; d < dim; d++) {
            vector.add(d == 0 ? 1f : 0f);
        }
        row.add("vector", vector);
        return row;
    }

    private static Map<String, TopicProfile> topicOfEveryPassage(TopicAnalysis analysis) {
        Map<String, TopicProfile> topicOf = new LinkedHashMap<String, TopicProfile>();
        for (TopicProfile topic : analysis.getTopics()) {
            for (String id : topic.getPassageIds()) {
                assertFalse(id + " lies in two topics", topicOf.containsKey(id));
                topicOf.put(id, topic);
            }
        }
        return topicOf;
    }

    private static JsonObject sample() throws Exception {
        InputStream stream = ReplayOutlineSampleTest.class.getResourceAsStream("/outline-replay/sample-corpus.json");
        assertNotNull("sample corpus on the test classpath", stream);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return JsonParser.parseString(new String(buffer.toByteArray(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } finally {
            stream.close();
        }
    }
}

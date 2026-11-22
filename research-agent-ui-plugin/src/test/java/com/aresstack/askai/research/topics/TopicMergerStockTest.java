package com.aresstack.askai.research.topics;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchMemoryStore;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.topics.TopicFinder.WindowResult;
import com.aresstack.askai.research.topics.TopicFinderStockTest.Expected;
import com.aresstack.askai.research.topics.TopicMerger.Comparison;
import com.aresstack.askai.research.topics.TopicMerger.Mapping;
import com.aresstack.askai.research.topics.TopicMerger.MergedQuestion;
import com.aresstack.askai.research.topics.TopicMerger.MergedTopic;
import com.aresstack.askai.research.topics.TopicMerger.Options;
import com.aresstack.askai.research.topics.TopicMerger.Result;
import com.aresstack.askai.research.topics.TopicProposal.FindingCheck;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Component 15 on stored data: the topic merge over the proposals the topic finder (13) made from the stock of
 * 17.11.2026, with the four cards of its concept and their twenty questions as the known topics and questions, the
 * vectors from bge-m3 and the doubtful cases from gemma4:12b. Nothing is placed in the app; the merge writes into a
 * fresh research memory and its own log under {@code <out>/projekt}.
 *
 * <p>What it measures, with the 17 expected topics and their key words of the topic finder's test as silver labels
 * (a proposal carries a label when a key word stands in its name or description, and is "clear" with exactly one):
 * Z1 one topic instead of many (the clear proposals of an expected topic lie mostly in one family: a topic with its
 * subtopics), Z2 no mixed topics (a merged topic holds clear proposals of one expected topic), Z3 every origin kept
 * (each proposal in exactly one topic or held back, every finding kept), Z4 no foreign topic swallowed by a card
 * (clear proposals of AUSSEN or UNKLAR topics are not merged into a concept card, which is INNEN by definition), Z5 a
 * known topic is recognized (each proposal fed in again under a new id lands in the topic of its twin, without new
 * model calls). The thresholds are proposals; the architect fixes them before the run counts. {@code result.json}
 * carries names, counts and similarities, no passage text and no quote.</p>
 *
 * <pre>
 *   ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*TopicMergerStockTest*'
 *     -Daskai.stock.merge.vorschlaege=&lt;topic-proposals/log.jsonl of the finder's run, or its project folder&gt;
 *     -Daskai.stock.merge.korpus=&lt;replay-corpus.json, only for the mission&gt;  (or .auftrag=&lt;text file&gt;)
 *     -Daskai.stock.merge.chatUrl=http://127.0.0.1:11434 -Daskai.stock.merge.chatModel=gemma4:12b
 *     -Daskai.stock.merge.embedModel=bge-m3:latest -Daskai.stock.merge.out=&lt;folder&gt;
 *   optional: gleich, zweifel, frageGleich, frageZweifel (bands), kandidaten (3), kartenschritt (true), parallel,
 *   nurNamen, zwilling (true), numCtx, think, timeoutSeconds, erwartet, stichworte, bekannteThemen (fragen file),
 *   neu (true: forget earlier verdicts)
 * </pre>
 */
public class TopicMergerStockTest {

    private static final String P = "askai.stock.merge.";
    private static final List<String> FOREIGN = Arrays.asList("AUSSEN", "UNKLAR");
    private static final double[] BINS = {0.70, 0.75, 0.80, 0.85, 0.90, 0.95};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The known topics (the concept's cards) and their questions, from a file in the form of fragen-1711.json. */
    static final class Known {
        final Map<Integer, String> themes = new TreeMap<Integer, String>();
        final List<String[]> questions = new ArrayList<String[]>();

        static Known of(String questionsJson) {
            JsonObject json = JsonParser.parseString(questionsJson).getAsJsonObject();
            Known known = new Known();
            for (Map.Entry<String, JsonElement> theme : json.getAsJsonObject("themen").entrySet()) {
                known.themes.put(Integer.valueOf(theme.getKey()), theme.getValue().getAsString());
            }
            JsonArray questions = json.getAsJsonArray("fragen");
            for (JsonElement element : questions == null ? new JsonArray() : questions) {
                JsonObject q = element.getAsJsonObject();
                known.questions.add(new String[]{q.get("id").getAsString(), q.get("thema").getAsString(),
                        q.get("text").getAsString()});
            }
            return known;
        }

        static String topicId(int theme) {
            return "konzept-" + theme;
        }
    }

    /** The bands and the proposed thresholds. */
    static final class Settings {
        Options options = Options.defaults();
        boolean twins = true;
        boolean forget;
        String language = "de";
        int minClear = 3;
        double z1Share = 0.6;
        double z1Topics = 0.7;
        double z2Mixed = 0.10;
        double z4Swallowed = 0.05;
        double z5Twins = 0.95;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    public void sampleStock() throws Exception {
        File out = tmp.newFolder("out");
        Known known = Known.of("{\"themen\":{\"1\":\"Radwege in Städten\",\"2\":\"Parken\"},\"fragen\":["
                + "{\"id\":\"F01\",\"thema\":1,\"text\":\"Wie sicher sind Radwege?\"},"
                + "{\"id\":\"F02\",\"thema\":2,\"text\":\"Was kostet Parken?\"}]}");
        List<Expected> expected = TopicFinderStockTest.readExpected(
                "{\"themen\":[{\"name\":\"Tempo 30\",\"blaetter\":[],\"erwartet\":\"INNEN\"},"
                        + "{\"name\":\"Bahnlärm\",\"blaetter\":[],\"erwartet\":\"AUSSEN\"}]}",
                "{\"themen\":{\"Tempo 30\":[\"tempo\"],\"Bahnlärm\":[\"bahn\"]}}");
        List<TopicProposal> proposals = Arrays.asList(
                proposal("v1", "s1", "Tempo 30 in Wohngebieten", "Wie Tempo 30 Unfälle senkt.",
                        "Wie stark senkt Tempo 30 die Unfälle?"),
                proposal("v2", "s2", "Tempo 30 in Wohngebieten", "Was Städte zu Tempo 30 sagen.",
                        "Wie stark senkt Tempo 30 die Unfälle?"),
                proposal("v3", "s3", "Tempo 30 vor Schulen", "Wo Schulen Tempo 30 bekommen."),
                proposal("v4", "s4", "Bahnlärm an Strecken", "Wie laut Güterzüge sind."),
                proposal("v5", "s1", "Radwege in Städten", "Wie sicher Radwege sind.", "Wie sicher sind Radwege?"),
                proposal("v6", "s5", "Parken und Gebühren", "Parken kostet."));
        TopicProposalLog finderLog = new TopicProposalLog(new File(out, "finder"));
        finderLog.open();
        Map<String, List<TopicProposal>> bySource = new LinkedHashMap<String, List<TopicProposal>>();
        for (TopicProposal proposal : proposals) {
            if (!bySource.containsKey(proposal.getSourceId())) {
                bySource.put(proposal.getSourceId(), new ArrayList<TopicProposal>());
            }
            bySource.get(proposal.getSourceId()).add(proposal);
        }
        for (Map.Entry<String, List<TopicProposal>> source : bySource.entrySet()) {
            finderLog.append(new WindowResult(source.getKey(), source.getValue().get(0).getSourceUrl(), 1,
                    Collections.singletonList(source.getKey() + "-p1"), WindowResult.Status.OK, "", source.getValue(),
                    null, null, "gemma4:e2b", 1, 1L, 10L));
        }
        File copied = copyProposals(finderLog.logFile(), out);
        List<TopicProposal> read = TopicProposalLog.proposals(new TopicProposalLog(copied).open());
        assertEquals(6, read.size());

        TopicFinderTest.Scripted chat = new TopicFinderTest.Scripted(m -> MainModelChatResult.ok(
                !TopicMerger.CARD_SYSTEM_DE.equals(m.get(0).getContent())
                        ? "{\"beziehung\":\"verschieden\",\"grund\":\"Anderes Thema.\"}"
                        : m.get(1).getContent().contains("Thema:\nParken und Gebühren")
                        ? "{\"grund\":\"Gebühren gehören zum Parken.\",\"zuordnung\":\"teil_von\","
                        + "\"karte\":\"konzept-2\"}"
                        : "{\"grund\":\"Keine Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\"}"));
        JsonObject sources = new JsonObject();
        sources.addProperty("vorschlaege", "Beispiel");
        JsonObject result = run(read, "Ein Buch über Verkehr in Städten.", known, expected, chat, new Words(),
                new Settings(), out, sources);

        assertEquals(6, result.getAsJsonObject("eingabe").get("vorschlaege").getAsInt());
        JsonObject topics = result.getAsJsonObject("themen");
        assertEquals("Radwege joins its card; Tempo 30 (twice) and Bahnlärm are new", 1,
                topics.get("bekannteMitVorschlaegen").getAsInt());
        assertEquals(3, topics.get("neu").getAsInt());
        assertEquals("the fees under the card Parken", 1, topics.get("unterthema").getAsInt());
        JsonObject asked = result.getAsJsonObject("vergleiche").getAsJsonObject("themen");
        assertEquals("no doubtful pair: the fees meet the card Parken in the card step", 0,
                asked.get("gefragt").getAsInt());
        JsonObject cards = result.getAsJsonObject("vergleiche").getAsJsonObject("karten");
        assertEquals("four heads against the cards", 4, cards.get("gefragt").getAsInt());
        assertEquals(1, cards.getAsJsonObject("urteile").get("A_TEIL_VON_B").getAsInt());
        assertEquals(3, cards.getAsJsonObject("urteile").get("VERSCHIEDEN").getAsInt());
        assertEquals(4, chat.calls.get());
        JsonObject structure = result.getAsJsonObject("herkuenfte");
        assertTrue(structure.get("jederVorschlagEinmal").getAsBoolean());
        assertTrue(structure.get("fundstellenErhalten").getAsBoolean());
        assertTrue(result.getAsJsonObject("gedaechtnis").get("wiedergeoeffnetGleich").getAsBoolean());
        JsonObject twins = result.getAsJsonObject("zwilling");
        assertEquals(6, twins.get("wiedererkannt").getAsInt());
        assertEquals(0, twins.get("neueThemen").getAsInt());
        assertEquals(0, twins.get("modellaufrufe").getAsInt());
        JsonObject questions = result.getAsJsonObject("fragen");
        assertEquals(3, questions.get("gesamt").getAsInt());
        assertEquals("v2's into v1's, v5's into the card's", 2, questions.get("WORTLAUT").getAsInt());
        assertEquals("v5's question is the card's question", 1, questions.get("inBekannteFragen").getAsInt());
        JsonObject tempo = result.getAsJsonArray("erwartet").get(0).getAsJsonObject();
        assertEquals("Tempo 30", tempo.get("name").getAsString());
        assertEquals(3, tempo.get("eindeutig").getAsInt());
        JsonObject criteria = result.getAsJsonObject("kriterien");
        for (String name : new String[]{"Z2", "Z3", "Z4", "Z5"}) {
            assertTrue(name + " " + criteria.get(name), criteria.getAsJsonObject(name).get("erfuellt").getAsBoolean());
        }
        String written = new String(Files.readAllBytes(new File(out, "result.json").toPath()), StandardCharsets.UTF_8);
        assertFalse("no quote in result.json", written.contains("steht etwas zu"));
        assertTrue(new File(out, "projekt/topic-merge").isDirectory());
        assertTrue(new File(out, "projekt/research-memory").isDirectory());
    }

    @Test
    public void storedStock() throws Exception {
        String proposalsPath = System.getProperty(P + "vorschlaege", "");
        Assume.assumeTrue("topic merge stock test skipped (no -D" + P + "vorschlaege)", !proposalsPath.isEmpty());
        String chatUrl = System.getProperty(P + "chatUrl", "http://127.0.0.1:11434");
        String chatModel = System.getProperty(P + "chatModel", "gemma4:12b");
        String embedUrl = System.getProperty(P + "embedUrl", chatUrl);
        String embedModel = System.getProperty(P + "embedModel", "bge-m3:latest");
        int dimension = Integer.getInteger(P + "dimension", 1024);
        String numCtx = System.getProperty(P + "numCtx", "");
        boolean think = !"omit".equalsIgnoreCase(System.getProperty(P + "think", "false"));
        int timeout = 1000 * Integer.getInteger(P + "timeoutSeconds", 180);
        File out = new File(System.getProperty(P + "out", "build/component-stock/topic-merger"),
                chatModel.replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.createDirectories(out.toPath());

        Settings settings = new Settings();
        Options options = Options.defaults()
                .withTopicBand(number("gleich", 0.85), number("zweifel", 0.65))
                .withQuestionBand(number("frageGleich", 0.92), number("frageZweifel", 0.85))
                .withParallel(Integer.getInteger(P + "parallel", 4))
                .withNamesOnly("true".equalsIgnoreCase(System.getProperty(P + "nurNamen", "false")))
                .withCandidates(Integer.getInteger(P + "kandidaten", 3))
                .withCardStep(!"false".equalsIgnoreCase(System.getProperty(P + "kartenschritt", "true")));
        settings.options = options;
        settings.twins = !"false".equalsIgnoreCase(System.getProperty(P + "zwilling", "true"));
        settings.forget = "true".equalsIgnoreCase(System.getProperty(P + "neu", "false"));

        File source = new File(proposalsPath);
        File logFile = source.isDirectory() ? new TopicProposalLog(source).logFile() : source;
        File copied = copyProposals(logFile, out);
        List<TopicProposal> proposals = TopicProposalLog.proposals(new TopicProposalLog(copied).open());
        String[] task = mission();
        String mission = task[0];
        String expectedPath = System.getProperty(P + "erwartet", "");
        String keywordsPath = System.getProperty(P + "stichworte", "");
        String knownPath = System.getProperty(P + "bekannteThemen", "");
        List<Expected> expected = TopicFinderStockTest.readExpected(
                expectedPath.isEmpty() ? resource("erwartete-themen-1711.json") : read(expectedPath),
                keywordsPath.isEmpty() ? resource("stichworte-1711.json") : read(keywordsPath));
        Known known = Known.of(knownPath.isEmpty() ? resource("fragen-1711.json") : read(knownPath));

        JsonObject sources = new JsonObject();
        sources.addProperty("vorschlaege", logFile.getPath());
        sources.addProperty("auftragZeichen", mission.length());
        sources.addProperty("sprache", task[1]);
        if (TopicFinderStockTest.MOJIBAKE.matcher(mission).find()) {
            sources.addProperty("warnung", "Der Auftrag sieht beschädigt aus (UTF-8 als Latin-1 gelesen).");
        }
        sources.addProperty("erwartet", expectedPath.isEmpty() ? "Repo-Kopie erwartete-themen-1711.json"
                : expectedPath);
        sources.addProperty("stichworte", keywordsPath.isEmpty() ? "Repo-Kopie stichworte-1711.json" : keywordsPath);
        sources.addProperty("bekannteThemen", knownPath.isEmpty() ? "Repo-Kopie fragen-1711.json (4 Karten, 20 Fragen)"
                : knownPath);
        sources.addProperty("chat", chatModel + " at " + chatUrl + (numCtx.isEmpty() ? "" : ", num_ctx " + numCtx)
                + (think ? ", think:false" : ", think weggelassen") + ", Zeitlimit " + timeout / 1000 + " s");
        sources.addProperty("einbettung", embedModel + " at " + embedUrl + ", " + dimension + " Dimensionen");
        System.out.println("[topic-merge] " + proposals.size() + " proposals, chat " + chatModel + ", embeddings "
                + embedModel + ", " + options.getParallel() + " at a time");

        MainModelChat chat = new OllamaStockChat(chatUrl, chatModel, numCtx.isEmpty() ? null : Integer.valueOf(numCtx),
                think, timeout);
        EmbeddingPort embeddings = new HttpEmbeddingPortAdapter(new EmbeddingEndpointDescriptor(embedModel, embedUrl,
                "/api/embed", dimension, "", "", 120000L), new UrlConnectionEmbeddingHttpTransport(120000));
        settings.language = task[1];
        JsonObject result = run(proposals, mission, known, expected, chat, embeddings, settings, out, sources);
        System.out.println("[topic-merge] result " + new File(out, "result.json").getAbsolutePath());
        assertTrue(result.get("befund").getAsString(), result.get("bestanden").getAsBoolean());
    }

    // ------------------------------------------------------------------------------------------------- run

    /** One merge into a fresh memory, then the twins; writes {@code result.json} and returns it. */
    static JsonObject run(List<TopicProposal> proposals, String mission, Known known, List<Expected> expected,
                          MainModelChat chat, EmbeddingPort embeddings, Settings settings, File out,
                          JsonObject sources) throws Exception {
        File project = new File(out, "projekt");
        deleteTree(new File(project, "research-memory"));
        if (settings.forget) {
            deleteTree(new File(project, "topic-merge"));
        }
        ResearchMemoryStore store = new ResearchMemoryStore(project);
        ResearchMemory memory = store.open();
        for (Map.Entry<Integer, String> theme : known.themes.entrySet()) {
            memory.addTopic(ResearchTopic.ofCard(Known.topicId(theme.getKey()), theme.getValue(),
                    "karte-" + theme.getKey(), 0, 1L));
        }
        Map<String, String> knownQuestionText = new HashMap<String, String>();
        for (String[] question : known.questions) {
            memory.addQuestion(ResearchQuestion.open(question[0], question[2],
                    Known.topicId(Integer.parseInt(question[1])), ResearchQuestion.Origin.KONZEPT, 1L));
            knownQuestionText.put(question[0], question[2]);
        }
        TopicMergeLog log = new TopicMergeLog(project);
        List<Comparison> earlier = new ArrayList<Comparison>(log.open());
        TopicMerger merger = new TopicMerger(chat, embeddings, mission, settings.language, settings.options,
                line -> System.out.println("[topic-merge] " + line));

        long start = System.currentTimeMillis();
        Result result = merger.merge(proposals, memory, earlier);
        long mergeMillis = System.currentTimeMillis() - start;
        log.appendAll(result.getAsked());
        result.applyTo(memory);

        JsonObject json = new JsonObject();
        json.addProperty("geschrieben", java.time.LocalDateTime.now().withNano(0).toString());
        json.add("quellen", sources);
        json.add("einstellungen", settingsJson(settings));
        json.add("eingabe", input(proposals));
        json.addProperty("gruppenSchritt1", result.getGroups());
        json.add("vergleiche", comparisons(result));
        Map<String, MergedTopic> topicOfProposal = new HashMap<String, MergedTopic>();
        for (MergedTopic topic : result.getTopics()) {
            for (String id : topic.getProposalIds()) {
                topicOfProposal.put(id, topic);
            }
        }
        json.add("themen", topics(result, proposals));
        json.add("bekannte", knownTopics(result, known, proposals));
        Labels labels = new Labels(expected, proposals);
        JsonObject criteria = new JsonObject();
        List<String> missed = new ArrayList<String>();
        json.add("erwartet", silver(labels, result, topicOfProposal, criteria, missed, settings));
        json.add("fragen", questions(result, knownQuestionText));

        JsonObject structure = new JsonObject();
        Set<String> seen = new LinkedHashSet<String>();
        boolean once = true;
        for (MergedTopic topic : result.getTopics()) {
            for (String id : topic.getProposalIds()) {
                once &= seen.add(id);
            }
        }
        once &= Collections.disjoint(seen, result.getHeldBack().keySet());
        int placedAndHeld = seen.size() + result.getHeldBack().size();
        once &= placedAndHeld == proposals.size();
        int withFinding = 0;
        int lost = 0;
        for (TopicProposal proposal : result.getPlaced()) {
            MergedTopic topic = topicOfProposal.get(proposal.getId());
            if (!proposal.hasFinding() || topic == null) {
                continue;
            }
            withFinding++;
            Set<String> places = new LinkedHashSet<String>();
            for (ResearchTopic.Finding finding : topic.getFindings()) {
                places.add(place(finding.getSourceId(), finding.getPassageId(), finding.getQuote()));
            }
            lost += places.contains(place(proposal.getSourceId(), proposal.getPassageId(), proposal.getQuote()))
                    ? 0 : 1;
        }
        structure.addProperty("jederVorschlagEinmal", once);
        structure.addProperty("platziertPlusZurueckgehalten", placedAndHeld);
        structure.addProperty("vorschlaegeMitFundstelle", withFinding);
        structure.addProperty("fundstellenVerloren", lost);
        structure.addProperty("fundstellenErhalten", lost == 0);
        json.add("herkuenfte", structure);
        criterion(criteria, missed, "Z3", "jeder Vorschlag in genau einem Thema oder zurückgehalten, jede Fundstelle "
                + "im Thema ihres Vorschlags", once && lost == 0 ? 1 : 0, 1, true);

        ResearchMemory reopened = new ResearchMemoryStore(project).open();
        boolean same = reopened.topics().size() == memory.topics().size()
                && reopened.questions().size() == memory.questions().size();
        for (ResearchTopic topic : memory.topics()) {
            same &= reopened.findingsOfTopic(topic.getId()).size() == memory.findingsOfTopic(topic.getId()).size();
        }
        JsonObject stored = new JsonObject();
        stored.addProperty("themen", memory.topics().size());
        stored.addProperty("fragen", memory.questions().size());
        stored.addProperty("wiedergeoeffnetGleich", same);
        json.add("gedaechtnis", stored);

        long twinMillis = 0;
        if (settings.twins) {
            List<Comparison> known2 = new ArrayList<Comparison>(earlier);
            known2.addAll(result.getAsked());
            List<TopicProposal> twins = new ArrayList<TopicProposal>();
            for (TopicProposal proposal : result.getPlaced()) {
                twins.add(new TopicProposal(proposal.getId() + "-z", proposal.getSourceId(), proposal.getSourceUrl(),
                        proposal.getPassageId(), proposal.getQuote(), proposal.getFindingCheck(), proposal.getName(),
                        proposal.getDescription(), proposal.getQuestions(), proposal.getModel(), proposal.getRound(),
                        proposal.getTimeMillis()));
            }
            int standingBefore = memory.questions().size();
            long twinStart = System.currentTimeMillis();
            Result twin = merger.merge(twins, memory, known2);
            twinMillis = System.currentTimeMillis() - twinStart;
            log.appendAll(twin.getAsked());
            int recognized = 0;
            Map<String, String> topicOfTwin = new HashMap<String, String>();
            for (MergedTopic topic : twin.getTopics()) {
                for (String id : topic.getProposalIds()) {
                    topicOfTwin.put(id, topic.getId());
                }
            }
            for (TopicProposal proposal : result.getPlaced()) {
                String original = memory.currentTopic(proposal.getId()).getId();
                if (original.equals(topicOfTwin.get(proposal.getId() + "-z"))) {
                    recognized++;
                }
            }
            int newTopics = 0;
            for (MergedTopic topic : twin.getTopics()) {
                newTopics += topic.getMapping() == Mapping.GLEICH ? 0 : 1;
            }
            int newQuestions = 0;
            for (MergedQuestion question : twin.getQuestions()) {
                newQuestions += question.getInto().isEmpty() ? 1 : 0;
            }
            JsonObject twinJson = new JsonObject();
            twinJson.addProperty("vorschlaege", twins.size());
            twinJson.addProperty("wiedererkannt", recognized);
            twinJson.addProperty("anteil", share(recognized, twins.size()));
            twinJson.addProperty("modellaufrufe", twin.getAsked().size());
            twinJson.addProperty("neueThemen", newTopics);
            twinJson.addProperty("neueFragen", newQuestions);
            twinJson.addProperty("zurueckgehalten", twin.getHeldBack().size());
            twinJson.addProperty("fragenVorher", standingBefore);
            json.add("zwilling", twinJson);
            criterion(criteria, missed, "Z5", "Anteil der Zwillinge im Thema ihres Originals, ohne neuen Modellaufruf",
                    twin.getAsked().isEmpty() ? share(recognized, twins.size()) : 0, settings.z5Twins, true);
        }
        JsonObject time = new JsonObject();
        time.addProperty("zusammenfuehrungMs", mergeMillis);
        time.addProperty("zwillingMs", twinMillis);
        json.add("zeit", time);
        JsonObject ordered = new JsonObject();
        for (String name : new java.util.TreeSet<String>(criteria.keySet())) {
            ordered.add(name, criteria.get(name));
        }
        json.add("kriterien", ordered);
        json.addProperty("bestanden", missed.isEmpty());
        json.addProperty("befund", missed.isEmpty() ? "alle Kriterien erfüllt" : "nicht erfüllt: "
                + String.join(", ", missed));
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(json).getBytes(StandardCharsets.UTF_8));
        return json;
    }

    // ------------------------------------------------------------------------------------------------- measures

    private static JsonObject input(List<TopicProposal> proposals) {
        int withFinding = 0;
        int questions = 0;
        Set<String> sources = new LinkedHashSet<String>();
        for (TopicProposal proposal : proposals) {
            withFinding += proposal.hasFinding() ? 1 : 0;
            questions += proposal.getQuestions().size();
            sources.add(proposal.getSourceId());
        }
        JsonObject json = new JsonObject();
        json.addProperty("vorschlaege", proposals.size());
        json.addProperty("mitFundstelle", withFinding);
        json.addProperty("fragen", questions);
        json.addProperty("quellen", sources.size());
        return json;
    }

    /** Verdicts per kind and per band of similarity, asked and reused. */
    private static JsonObject comparisons(Result result) {
        JsonObject json = new JsonObject();
        for (Comparison.Kind kind : Comparison.Kind.values()) {
            JsonObject part = new JsonObject();
            int asked = 0;
            int reused = 0;
            Map<String, Integer> verdicts = new LinkedHashMap<String, Integer>();
            Map<String, Map<String, Integer>> byBand = new TreeMap<String, Map<String, Integer>>();
            for (Comparison comparison : result.getComparisons()) {
                if (comparison.getKind() != kind) {
                    continue;
                }
                boolean now = result.getAsked().contains(comparison);
                asked += now ? 1 : 0;
                reused += now ? 0 : 1;
                String verdict = comparison.isOk() ? comparison.getRelation().name() : "FEHLER";
                increment(verdicts, verdict);
                String band = band(comparison.getSimilarity());
                if (!byBand.containsKey(band)) {
                    byBand.put(band, new LinkedHashMap<String, Integer>());
                }
                increment(byBand.get(band), verdict);
            }
            part.addProperty("gefragt", asked);
            part.addProperty("wiederverwendet", reused);
            part.add("urteile", counts(verdicts));
            JsonObject bands = new JsonObject();
            for (Map.Entry<String, Map<String, Integer>> band : byBand.entrySet()) {
                bands.add(band.getKey(), counts(band.getValue()));
            }
            part.add("nachAehnlichkeit", bands);
            json.add(kind == Comparison.Kind.THEMA ? "themen" : kind == Comparison.Kind.FRAGE ? "fragen" : "karten",
                    part);
        }
        List<String> problems = new ArrayList<String>();
        for (Comparison failed : result.getFailed()) {
            if (problems.size() < 10) {
                problems.add(failed.getProblem());
            }
        }
        json.add("fehlerBeispiele", strings(problems));
        return json;
    }

    private static JsonObject topics(Result result, List<TopicProposal> proposals) {
        Map<String, TopicProposal> byId = new HashMap<String, TopicProposal>();
        for (TopicProposal proposal : proposals) {
            byId.put(proposal.getId(), proposal);
        }
        int fresh = 0;
        int sub = 0;
        int known = 0;
        int manySources = 0;
        Map<String, Integer> sizes = new LinkedHashMap<String, Integer>();
        for (String size : new String[]{"1", "2-3", "4-9", "10+"}) {
            sizes.put(size, 0);
        }
        for (MergedTopic topic : result.getTopics()) {
            if (topic.getMapping() == Mapping.GLEICH) {
                known++;
                continue;
            }
            fresh += topic.getMapping() == Mapping.NEU ? 1 : 0;
            sub += topic.getMapping() == Mapping.UNTERTHEMA ? 1 : 0;
            manySources += topic.getSourceIds().size() >= 2 ? 1 : 0;
            int n = topic.getProposalIds().size();
            increment(sizes, n == 1 ? "1" : n <= 3 ? "2-3" : n <= 9 ? "4-9" : "10+");
        }
        List<MergedTopic> largest = new ArrayList<MergedTopic>(result.getTopics());
        Collections.sort(largest, (x, y) -> Integer.compare(y.getProposalIds().size(), x.getProposalIds().size()));
        JsonArray top = new JsonArray();
        for (MergedTopic topic : largest.subList(0, Math.min(15, largest.size()))) {
            JsonObject t = new JsonObject();
            t.addProperty("id", topic.getId());
            t.addProperty("name", topic.getName());
            t.addProperty("zuordnung", topic.getMapping().name());
            t.addProperty("unterthemaVon", topic.getParentId());
            t.addProperty("vorschlaege", topic.getProposalIds().size());
            t.addProperty("quellen", topic.getSourceIds().size());
            t.addProperty("fundstellen", topic.getFindings().size());
            t.addProperty("fragen", topic.getQuestionIds().size());
            Set<String> names = new LinkedHashSet<String>();
            for (String id : topic.getProposalIds()) {
                if (names.size() < 8) {
                    names.add(byId.get(id).getName());
                }
            }
            t.add("namen", strings(new ArrayList<String>(names)));
            top.add(t);
        }
        JsonObject json = new JsonObject();
        json.addProperty("anzahl", result.getTopics().size());
        json.addProperty("neu", fresh);
        json.addProperty("unterthema", sub);
        json.addProperty("bekannteMitVorschlaegen", known);
        json.addProperty("zurueckgehalten", result.getHeldBack().size());
        json.addProperty("mehrereQuellen", manySources);
        json.addProperty("vorschlaegeJeNeuesThema", share(result.getPlaced().size() - knownProposals(result),
                fresh + sub));
        json.add("vorschlaegeJeThema", counts(sizes));
        json.add("groesste", top);
        return json;
    }

    private static int knownProposals(Result result) {
        int n = 0;
        for (MergedTopic topic : result.getTopics()) {
            n += topic.getMapping() == Mapping.GLEICH ? topic.getProposalIds().size() : 0;
        }
        return n;
    }

    private static JsonArray knownTopics(Result result, Known known, List<TopicProposal> proposals) {
        Map<String, TopicProposal> byId = new HashMap<String, TopicProposal>();
        for (TopicProposal proposal : proposals) {
            byId.put(proposal.getId(), proposal);
        }
        JsonArray json = new JsonArray();
        for (Map.Entry<Integer, String> theme : known.themes.entrySet()) {
            String id = Known.topicId(theme.getKey());
            JsonObject k = new JsonObject();
            k.addProperty("id", id);
            k.addProperty("name", theme.getValue());
            MergedTopic same = result.topic(id);
            List<String> sameNames = new ArrayList<String>();
            if (same != null) {
                for (String proposalId : same.getProposalIds()) {
                    if (sameNames.size() < 10) {
                        sameNames.add(byId.get(proposalId).getName());
                    }
                }
            }
            List<String> subNames = new ArrayList<String>();
            int subtopics = 0;
            for (MergedTopic topic : result.getTopics()) {
                if (topic.getMapping() == Mapping.UNTERTHEMA && id.equals(topic.getKnownId())) {
                    subtopics++;
                    if (subNames.size() < 10) {
                        subNames.add(topic.getName());
                    }
                }
            }
            k.addProperty("gleich", same == null ? 0 : same.getProposalIds().size());
            k.add("gleichBeispiele", strings(sameNames));
            k.addProperty("unterthemen", subtopics);
            k.add("unterthemenBeispiele", strings(subNames));
            json.add(k);
        }
        return json;
    }

    /** The silver labels of the proposals: the expected topics whose key words stand in name or description. */
    static final class Labels {
        final List<Expected> expected;
        final Map<String, List<Integer>> labelsOf = new HashMap<String, List<Integer>>();
        final Map<String, TopicProposal> proposals = new LinkedHashMap<String, TopicProposal>();

        Labels(List<Expected> expected, List<TopicProposal> all) {
            this.expected = expected;
            for (TopicProposal proposal : all) {
                proposals.put(proposal.getId(), proposal);
                List<Integer> labels = new ArrayList<Integer>();
                for (int e = 0; e < expected.size(); e++) {
                    if (TopicFinderStockTest.hitsAny(proposal.getName() + " " + proposal.getDescription(),
                            expected.get(e).keywords)) {
                        labels.add(e);
                    }
                }
                labelsOf.put(proposal.getId(), labels);
            }
        }

        /** The one expected topic of a clear proposal, -1 for none or several. */
        int clear(String proposalId) {
            List<Integer> labels = labelsOf.get(proposalId);
            return labels != null && labels.size() == 1 ? labels.get(0) : -1;
        }
    }

    private static JsonArray silver(Labels labels, Result result, Map<String, MergedTopic> topicOfProposal,
                                    JsonObject criteria, List<String> missed, Settings settings) {
        Map<String, MergedTopic> byId = new HashMap<String, MergedTopic>();
        for (MergedTopic topic : result.getTopics()) {
            byId.put(topic.getId(), topic);
        }
        JsonArray json = new JsonArray();
        int qualifying = 0;
        int concentrated = 0;
        int foreignClear = 0;
        int swallowed = 0;
        List<String> swallowedNames = new ArrayList<String>();
        for (int e = 0; e < labels.expected.size(); e++) {
            Expected expected = labels.expected.get(e);
            int hits = 0;
            int clear = 0;
            int inCard = 0;
            int underCard = 0;
            Map<String, Integer> families = new LinkedHashMap<String, Integer>();
            for (TopicProposal proposal : labels.proposals.values()) {
                if (labels.labelsOf.get(proposal.getId()).contains(e)) {
                    hits++;
                }
                if (labels.clear(proposal.getId()) != e) {
                    continue;
                }
                MergedTopic topic = topicOfProposal.get(proposal.getId());
                if (topic == null) {
                    continue; // held back
                }
                clear++;
                if (topic.getMapping() != Mapping.GLEICH && !topic.getKnownId().isEmpty()) {
                    underCard++;
                }
                if (topic.getMapping() == Mapping.GLEICH) {
                    inCard++;
                    if (FOREIGN.contains(expected.verdict)) {
                        swallowed++;
                        if (swallowedNames.size() < 20) {
                            swallowedNames.add(proposal.getName() + " -> " + topic.getName());
                        }
                    }
                }
                increment(families, family(topic, byId));
            }
            if (FOREIGN.contains(expected.verdict)) {
                foreignClear += clear;
            }
            int largest = 0;
            for (int n : families.values()) {
                largest = Math.max(largest, n);
            }
            double largestShare = share(largest, clear);
            if (clear >= settings.minClear) {
                qualifying++;
                concentrated += largestShare >= settings.z1Share ? 1 : 0;
            }
            List<Map.Entry<String, Integer>> ranked = new ArrayList<Map.Entry<String, Integer>>(families.entrySet());
            Collections.sort(ranked, (x, y) -> Integer.compare(y.getValue(), x.getValue()));
            JsonArray names = new JsonArray();
            for (Map.Entry<String, Integer> entry : ranked.subList(0, Math.min(5, ranked.size()))) {
                MergedTopic head = byId.get(entry.getKey());
                names.add((head == null ? entry.getKey() : head.getName()) + " (" + entry.getValue() + ")");
            }
            JsonObject t = new JsonObject();
            t.addProperty("name", expected.name);
            t.addProperty("erwartet", expected.verdict);
            t.addProperty("treffer", hits);
            t.addProperty("eindeutig", clear);
            t.addProperty("familien", families.size());
            t.addProperty("groessterAnteil", largestShare);
            t.addProperty("gleichMitKarte", inCard);
            t.addProperty("unterEinerKarte", underCard);
            t.add("familienNamen", names);
            json.add(t);
        }
        criterion(criteria, missed, "Z1", "Anteil der erwarteten Themen mit mindestens " + settings.minClear
                + " eindeutigen Vorschlägen, deren größte Familie mindestens " + percent(settings.z1Share)
                + " davon hält (" + concentrated + " von " + qualifying + ")", share(concentrated, qualifying),
                settings.z1Topics, true);

        int judged = 0;
        int mixed = 0;
        JsonArray mixedTopics = new JsonArray();
        for (MergedTopic topic : result.getTopics()) {
            Map<Integer, Integer> counts = new TreeMap<Integer, Integer>();
            for (String id : topic.getProposalIds()) {
                int label = labels.clear(id);
                if (label >= 0) {
                    counts.put(label, counts.containsKey(label) ? counts.get(label) + 1 : 1);
                }
            }
            int n = 0;
            for (int c : counts.values()) {
                n += c;
            }
            if (n < 2) {
                continue;
            }
            judged++;
            if (counts.size() > 1) {
                mixed++;
                if (mixedTopics.size() < 15) {
                    StringBuilder text = new StringBuilder(topic.getName()).append(":");
                    for (Map.Entry<Integer, Integer> count : counts.entrySet()) {
                        text.append(' ').append(labels.expected.get(count.getKey()).name).append(" (")
                                .append(count.getValue()).append(')');
                    }
                    mixedTopics.add(text.toString());
                }
            }
        }
        criterion(criteria, missed, "Z2", "Anteil gemischter Themen unter denen mit mindestens zwei eindeutigen "
                + "Vorschlägen (" + mixed + " von " + judged + ")", share(mixed, judged), settings.z2Mixed, false);
        criteria.getAsJsonObject("Z2").add("gemischt", mixedTopics);
        criterion(criteria, missed, "Z4", "Anteil eindeutiger Vorschläge von AUSSEN- und UNKLAR-Themen, die in eine "
                + "Karte des Konzepts zusammengeführt wurden (" + swallowed + " von " + foreignClear + ")",
                share(swallowed, foreignClear), settings.z4Swallowed, false);
        criteria.getAsJsonObject("Z4").add("geschluckt", strings(swallowedNames));
        return json;
    }

    /** The top of a topic's family: its highest ancestor that is not a known topic. */
    private static String family(MergedTopic topic, Map<String, MergedTopic> byId) {
        MergedTopic at = topic;
        Set<String> seen = new LinkedHashSet<String>();
        while (at.getMapping() != Mapping.GLEICH && seen.add(at.getId())) {
            MergedTopic parent = byId.get(at.getParentId());
            if (parent == null || parent.getMapping() == Mapping.GLEICH) {
                break;
            }
            at = parent;
        }
        return at.getId();
    }

    private static JsonObject questions(Result result, Map<String, String> knownText) {
        Map<String, Integer> how = new LinkedHashMap<String, Integer>();
        for (MergedQuestion.How h : MergedQuestion.How.values()) {
            how.put(h.name(), 0);
        }
        int standing = 0;
        int intoKnown = 0;
        JsonArray examples = new JsonArray();
        for (MergedQuestion question : result.getQuestions()) {
            if (question.getInto().isEmpty()) {
                standing++;
                continue;
            }
            increment(how, question.getHow().name());
            if (knownText.containsKey(question.getInto())) {
                intoKnown++;
                if (examples.size() < 15) {
                    examples.add(question.getText() + " -> " + question.getInto() + " "
                            + knownText.get(question.getInto()));
                }
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("gesamt", result.getQuestions().size());
        json.addProperty("eigenstaendig", standing);
        for (Map.Entry<String, Integer> entry : how.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        json.addProperty("inBekannteFragen", intoKnown);
        json.add("beispieleBekannt", examples);
        return json;
    }

    private static void criterion(JsonObject criteria, List<String> missed, String name, String what, double value,
                                  double threshold, boolean atLeast) {
        boolean met = atLeast ? value >= threshold : value <= threshold;
        JsonObject c = new JsonObject();
        c.addProperty("was", what);
        c.addProperty("wert", round(value));
        c.addProperty("schwelle", (atLeast ? ">= " : "<= ") + threshold);
        c.addProperty("erfuellt", met);
        criteria.add(name, c);
        if (!met) {
            missed.add(name);
        }
    }

    private static JsonObject settingsJson(Settings settings) {
        JsonObject json = new JsonObject();
        Options o = settings.options;
        json.addProperty("themenGleich", o.getSame());
        json.addProperty("themenZweifel", o.getDoubt());
        json.addProperty("fragenGleich", o.getQuestionSame());
        json.addProperty("fragenZweifel", o.getQuestionDoubt());
        json.addProperty("parallel", o.getParallel());
        json.addProperty("nurNamen", o.isNamesOnly());
        json.addProperty("kandidaten", o.getCandidates());
        json.addProperty("kartenschritt", o.isCardStep());
        json.addProperty("zwilling", settings.twins);
        json.addProperty("fruehereUrteileVergessen", settings.forget);
        return json;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** The topic finder's log copied next to the result, so the original is only read. */
    static File copyProposals(File logFile, File out) throws IOException {
        if (!logFile.isFile()) {
            throw new IOException("no topic proposals at " + logFile);
        }
        File project = new File(out, "eingabe");
        File copy = new TopicProposalLog(project).logFile();
        Files.createDirectories(copy.getParentFile().toPath());
        Files.copy(logFile.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return project;
    }

    /** The mission and its language: from a text file, else from the replay corpus as the topic finder reads it. */
    private static String[] mission() throws IOException {
        String language = System.getProperty(P + "sprache", "de");
        String missionPath = System.getProperty(P + "auftrag", "");
        if (!missionPath.isEmpty()) {
            return new String[]{read(missionPath).trim(), language};
        }
        String corpusPath = System.getProperty(P + "korpus", "");
        if (corpusPath.isEmpty()) {
            throw new IOException("the merge needs the mission: -D" + P + "korpus=<replay-corpus.json> or -D" + P
                    + "auftrag=<text file>");
        }
        try (java.io.Reader in = new java.io.InputStreamReader(new java.io.FileInputStream(corpusPath),
                StandardCharsets.UTF_8)) {
            TopicFinderStockTest.Stock stock = TopicFinderStockTest.readStock(in);
            return new String[]{stock.mission, stock.language};
        }
    }

    private static String place(String sourceId, String passageId, String quote) {
        return sourceId + "\u0000" + passageId + "\u0000" + TopicMerger.wording(quote);
    }

    private static String band(double similarity) {
        for (int i = BINS.length - 1; i >= 0; i--) {
            if (similarity >= BINS[i]) {
                return String.format(Locale.ROOT, "%.2f", BINS[i]);
            }
        }
        return "unter " + String.format(Locale.ROOT, "%.2f", BINS[0]);
    }

    private static void increment(Map<String, Integer> counts, String key) {
        counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
    }

    private static JsonObject counts(Map<String, Integer> counts) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            json.addProperty(count.getKey(), count.getValue());
        }
        return json;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static double share(int part, int whole) {
        return whole == 0 ? 0 : round((double) part / whole);
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    private static String percent(double value) {
        return Math.round(value * 100) + " %";
    }

    private static double number(String name, double fallback) {
        String value = System.getProperty(P + name, "");
        return value.isEmpty() ? fallback : Double.parseDouble(value);
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        java.io.InputStream in = TopicMergerStockTest.class.getResourceAsStream("/topic-finder-stock/" + name);
        if (in == null) {
            throw new IOException("resource " + name + " missing");
        }
        try (java.io.InputStream stream = in) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        Files.delete(file.toPath());
    }

    private static TopicProposal proposal(String id, String source, String name, String description,
                                          String... questions) {
        return new TopicProposal(id, source, "https://" + source + ".example/verkehr", source + "-p1",
                "In " + source + " steht etwas zu " + name, FindingCheck.GEPRUEFT, name, description,
                Arrays.asList(questions), "gemma4:e2b", 1, 5L);
    }

    /**
     * A stand-in for bge-m3 in the sample: one dimension per word (lower case, letters and digits), so texts that
     * share words are near; the same words give the same vector.
     */
    static final class Words implements EmbeddingPort {
        private final Map<String, Integer> dimensions = new LinkedHashMap<String, Integer>();

        @Override
        public synchronized List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (String text : texts) {
                float[] vector = new float[256];
                for (String word : TopicMerger.wording(text).split(" ")) {
                    if (!word.isEmpty()) {
                        if (!dimensions.containsKey(word)) {
                            dimensions.put(word, dimensions.size());
                        }
                        vector[dimensions.get(word) % vector.length] += 1;
                    }
                }
                out.add(new EmbeddingVector("words", "words", vector));
            }
            return out;
        }
    }
}

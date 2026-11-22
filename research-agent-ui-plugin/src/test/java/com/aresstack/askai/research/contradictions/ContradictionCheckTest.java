package com.aresstack.askai.research.contradictions;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Candidates;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Judgement;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Options;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Pair;
import com.aresstack.askai.research.contradictions.ContradictionCheck.Result;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.memory.Contradiction;
import com.aresstack.askai.research.memory.Contradiction.Difference;
import com.aresstack.askai.research.memory.Contradiction.Scope;
import com.aresstack.askai.research.memory.Contradiction.Status;
import com.aresstack.askai.research.memory.Contradiction.Verdict;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.EvidenceVerdict.Kind;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Component 17 without a model: which pairs are put forward (different source groups only, the most similar first,
 * a cap per question or topic, no pair twice, no verdict twice), one repair, FEHLER is never VEREINBAR, every verdict
 * open in the memory and in the log, the prompt. The texts are written for this test.
 */
public class ContradictionCheckTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final String MISSION = "Ein Sachbuch über Tempolimits im Straßenverkehr für Eltern und Gemeinderäte.";
    static final String TEMPO_A = "In Tempo-30-Zonen gilt für alle Fahrzeuge eine Höchstgeschwindigkeit von 30 km/h.";
    static final String TEMPO_B = "In Tempo-30-Zonen dürfen Lastwagen bis zu 50 km/h fahren.";
    static final String TEMPO_C = "In Österreich verordnen Gemeinden Tempo 30 auf Gemeindestraßen selbst.";
    static final String TEMPO_X = "Vor Schulen ordnen die Behörden oft Tempo 30 zu den Schulzeiten an.";
    static final String TEMPO_Y = "Tempo 30 senkt die Zahl schwerer Unfälle mit Kindern deutlich.";
    static final String HELM_A = "Für Radfahrer gibt es in Deutschland keine Helmpflicht.";

    /** Vectors that put the pairs in a known order of similarity. */
    static final Map<String, float[]> VECTORS = new HashMap<String, float[]>();

    static {
        VECTORS.put(TEMPO_A, new float[]{1f, 0f, 0f});
        VECTORS.put(TEMPO_B, new float[]{0.9f, 0.1f, 0f});
        VECTORS.put(TEMPO_C, new float[]{0.5f, 0.5f, 0f});
        VECTORS.put(TEMPO_X, new float[]{0.2f, 0.9f, 0.1f});
        VECTORS.put(TEMPO_Y, new float[]{0f, 0.3f, 1f});
        VECTORS.put(HELM_A, new float[]{0f, 0f, 1f});
    }

    // ------------------------------------------------------------------------------------------------- fakes

    /** The embedding model: a vector per known text, counted. */
    static final class FakeEmbeddings implements EmbeddingPort {
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<Integer>());
        boolean fail;

        @Override
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            calls.incrementAndGet();
            batchSizes.add(texts.size());
            assertEquals(Role.DOCUMENT, role);
            if (fail) {
                throw new IllegalStateException("bge-m3 is not loaded");
            }
            List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>();
            for (String text : texts) {
                float[] values = VECTORS.get(text);
                vectors.add(new EmbeddingVector("bge-m3", "bge-m3/doc", values == null ? new float[]{0.3f, 0.3f, 0.3f}
                        : values));
            }
            return vectors;
        }
    }

    /** A chat model that answers each prompt through a function; {@code null} is a failed call. */
    static final class ScriptedChat implements MainModelChat {
        final Function<String, String> answer;
        final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());
        final List<String> schemas = Collections.synchronizedList(new ArrayList<String>());

        ScriptedChat(Function<String, String> answer) {
            this.answer = answer;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            String prompt = messages.get(1).getContent();
            prompts.add(prompt);
            schemas.add(schemaJson);
            String text = answer.apply(messages.size() > 2 ? "REPAIR\n" + prompt : prompt);
            return text == null ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "no answer in time")
                    : MainModelChatResult.ok(text);
        }

        @Override
        public String modelName() {
            return "gemma4:12b";
        }
    }

    static String answer(String verdict, String difference) {
        JsonObject json = new JsonObject();
        json.addProperty("grund", "Beide Aussagen sprechen von derselben Zone und derselben Zeit.");
        json.addProperty("urteil", verdict);
        json.addProperty("unterschied", difference);
        return json.toString();
    }

    /** WIDERSPRUCH for the pair of A and B, VEREINBAR (another jurisdiction) for everything else. */
    static String tempo(String prompt) {
        return prompt.contains(TEMPO_A) && prompt.contains(TEMPO_B) ? answer("WIDERSPRUCH", "KEINER")
                : answer("VEREINBAR", "RECHTSRAUM");
    }

    static ContradictionCheck check(MainModelChat chat, EmbeddingPort embeddings, Options options,
                                    ContradictionCheck.Passages passages, ContradictionLog log) {
        return new ContradictionCheck(chat, embeddings, "de", options, passages, log, null,
                Clock.fixed(Instant.ofEpochMilli(5000L), ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------------------------------------- memory

    static EvidenceVerdict direct(String question, String passage, String source, String url, String text) {
        return EvidenceVerdict.judged(question, passage, source, url, text, Kind.BELEGT, text, "nennt die Regel",
                "gemma4:12b", 1, 10L);
    }

    static ResearchMemory topicMemory() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-tempo", "Tempo im Ort", "anchor-tempo", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("F01", "Wo gilt Tempo 30?", "T-tempo",
                ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F02", "Wer ordnet Tempo 30 an?", "T-tempo",
                ResearchQuestion.Origin.KONZEPT, 1L));
        return memory;
    }

    // ------------------------------------------------------------------------------------------------- candidates

    @Test
    public void onlyPairsFromDifferentSourceGroupsAreCandidates() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p5", "s1", "https://www.stadt.example/schule", TEMPO_X));
        memory.record(direct("F01", "p4", "s4", "https://kopie.example.net/tempo", TEMPO_A));
        memory.record(direct("F01", "p6", "s4", "https://kopie.example.net/unfaelle", TEMPO_Y));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));

        Candidates candidates = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults(), null, null).candidates(memory);

        // s1 and s4 carry the same text: one source group, so A, X and Y never meet; each of them meets B
        assertEquals(6, candidates.getPairs());
        assertEquals(3, candidates.getSameGroup());
        assertEquals(3, candidates.getToJudge().size());
        for (Pair pair : candidates.getToJudge()) {
            assertEquals(Scope.FRAGE, pair.getScope());
            assertEquals("F01", pair.getScopeId());
            assertTrue(pair.getA().getSourceId() + " " + pair.getB().getSourceId(),
                    "s2".equals(pair.getA().getSourceId()) || "s2".equals(pair.getB().getSourceId()));
        }
        assertEquals("", candidates.getProblem());
    }

    @Test
    public void theMostSimilarPairsOfAQuestionComeFirstUpToTheCap() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F01", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C));

        Candidates candidates = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults().withPairsPerScope(2), null, null).candidates(memory);

        assertEquals(3, candidates.getPairs());
        assertEquals(1, candidates.getOverCap());
        assertEquals(2, candidates.getToJudge().size());
        Pair first = candidates.getToJudge().get(0);
        assertEquals(new HashSetOf(TEMPO_A, TEMPO_B), new HashSetOf(first.getQuoteA(), first.getQuoteB()));
        Pair second = candidates.getToJudge().get(1);
        assertEquals(new HashSetOf(TEMPO_B, TEMPO_C), new HashSetOf(second.getQuoteA(), second.getQuoteB()));
        assertTrue(first.getSimilarity() > second.getSimilarity());
        assertEquals(ContradictionCheck.cosine(VECTORS.get(TEMPO_A), VECTORS.get(TEMPO_B)), first.getSimilarity(),
                1e-9);
    }

    @Test
    public void aLowerBoundLeavesDissimilarPairsOut() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F01", "p9", "s9", "https://rad.example.org/helm", HELM_A));

        Candidates candidates = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults().withMinSimilarity(0.5), null, null).candidates(memory);

        assertEquals(3, candidates.getPairs());
        assertEquals(2, candidates.getBelowSimilarity());
        assertEquals(1, candidates.getToJudge().size());
    }

    @Test
    public void aTopicPairsTheEvidenceOfTwoOfItsQuestions() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F02", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C));

        Candidates candidates = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults(), null, null).candidates(memory);

        assertEquals(3, candidates.getToJudge().size());
        assertEquals(Scope.FRAGE, candidates.getToJudge().get(0).getScope());
        Pair topic = candidates.getToJudge().get(1);
        assertEquals(Scope.THEMA, topic.getScope());
        assertEquals("T-tempo", topic.getScopeId());
        assertEquals("Tempo im Ort", topic.getScopeName());
        assertEquals("Wo gilt Tempo 30?", topic.getQuestionA());
        assertEquals("Wer ordnet Tempo 30 an?", topic.getQuestionB());
        assertEquals(Scope.THEMA, candidates.getToJudge().get(2).getScope());
        assertTrue(topic.getSimilarity() >= candidates.getToJudge().get(2).getSimilarity());
    }

    @Test
    public void twoTextsMeetOnceWhereverTheyMeet() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F02", "p4", "s4", "https://kopie.example.net/tempo", TEMPO_A));
        memory.record(direct("F02", "p5", "s5", "https://kopie.example.net/lkw", TEMPO_B));

        Candidates candidates = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults(), null, null).candidates(memory);

        // F01 puts A and B forward; F02 has the same two texts, and so has the topic (twice, crosswise)
        assertEquals(4, candidates.getPairs());
        assertEquals(3, candidates.getTwice());
        assertEquals(1, candidates.getToJudge().size());
        assertEquals("F01", candidates.getToJudge().get(0).getScopeId());
    }

    @Test
    public void leadsAndFailedVerdictsAreNoEvidenceToCompare() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(EvidenceVerdict.judged("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B,
                Kind.HINWEIS, TEMPO_B, "nur ein Hinweis", "gemma4:12b", 1, 10L));
        memory.record(EvidenceVerdict.failed("F01", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C, "timeout",
                "gemma4:12b", 1, 10L));

        Candidates candidates = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults(), null, null).candidates(memory);

        assertEquals(0, candidates.getPairs());
        assertTrue(candidates.getToJudge().isEmpty());
    }

    @Test
    public void quotesAreEmbeddedOncePerTextInBatchesOf32() throws Exception {
        ResearchMemory memory = topicMemory();
        for (int i = 0; i < 40; i++) {
            memory.record(direct("F01", "p" + i, "s" + i, "https://quelle" + i + ".example/x",
                    "Regel Nummer " + i + " gilt für Tempo 30 in der Zone " + i + "."));
        }
        memory.record(direct("F02", "q1", "s1", "https://quelle1.example/x", "Regel Nummer 1 gilt für Tempo 30 in der "
                + "Zone 1."));
        FakeEmbeddings embeddings = new FakeEmbeddings();

        check(new ScriptedChat(ContradictionCheckTest::tempo), embeddings, Options.defaults(), null, null)
                .candidates(memory);

        assertEquals(Arrays.asList(32, 8), embeddings.batchSizes);
    }

    @Test
    public void aFailedEmbeddingJudgesNothing() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        FakeEmbeddings embeddings = new FakeEmbeddings();
        embeddings.fail = true;
        ScriptedChat chat = new ScriptedChat(ContradictionCheckTest::tempo);

        Result result = check(chat, embeddings, Options.defaults(), null, null).check(memory, MISSION);

        assertTrue(result.getCandidates().getProblem(), result.getCandidates().getProblem().contains("bge-m3"));
        assertTrue(result.getJudgements().isEmpty());
        assertTrue(chat.prompts.isEmpty());
        assertTrue(memory.contradictions().isEmpty());
    }

    // ------------------------------------------------------------------------------------------------- judging

    @Test
    public void everyVerdictGoesToTheMemoryOpenInTheOrderOfTheCandidates() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F02", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C));

        Result result = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults(), null, null).check(memory, MISSION);

        assertEquals(3, result.getJudgements().size());
        assertEquals(1, result.count(Verdict.WIDERSPRUCH));
        assertEquals(2, result.count(Verdict.VEREINBAR));
        assertEquals(0, result.count(null));
        List<Contradiction> stored = memory.contradictions();
        assertEquals(3, stored.size());
        for (int i = 0; i < stored.size(); i++) {
            assertEquals(result.getCandidates().getToJudge().get(i).id(), stored.get(i).getId());
            assertEquals(Contradiction.State.OFFEN, stored.get(i).getState());
            assertEquals("gemma4:12b", stored.get(i).getModel());
            assertEquals(ContradictionCheck.PROMPT_VERSION, stored.get(i).getVersion());
        }
        assertEquals(1, memory.openContradictions().size());
        Contradiction open = memory.openContradictions().get(0);
        assertEquals(Scope.FRAGE, open.getScope());
        assertEquals(Difference.KEINER, open.getDifference());
        assertEquals(Difference.RECHTSRAUM, stored.get(1).getDifference());
        assertEquals(1, memory.openContradictionsOf("F01").size());
        assertEquals(1, memory.openContradictionsOf("T-tempo").size());
    }

    @Test
    public void aJudgedPairIsNotAskedAgainAFailedOneIs() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F02", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C));
        ScriptedChat failingOnC = new ScriptedChat(prompt -> prompt.contains(TEMPO_C) && prompt.contains(TEMPO_A)
                ? null : tempo(prompt));

        Result first = check(failingOnC, new FakeEmbeddings(), Options.defaults(), null, null).check(memory, MISSION);
        assertEquals(1, first.count(null));
        Contradiction failed = null;
        for (Judgement judgement : first.getJudgements()) {
            if (judgement.getContradiction().getStatus() == Status.FEHLER) {
                failed = judgement.getContradiction();
            }
        }
        assertNull(failed.getVerdict());
        assertTrue(failed.getProblem(), failed.getProblem().contains("TIMEOUT"));
        assertEquals(2, ContradictionCheck.ATTEMPTS);

        ScriptedChat second = new ScriptedChat(ContradictionCheckTest::tempo);
        Result again = check(second, new FakeEmbeddings(), Options.defaults(), null, null).check(memory, MISSION);

        assertEquals(1, again.getJudgements().size());
        assertEquals(failed.getId(), again.getJudgements().get(0).getContradiction().getId());
        assertEquals(2, again.getCandidates().getJudgedBefore());
        assertEquals(1, second.prompts.size());
        assertEquals(Status.OK, memory.contradiction(failed.getId()).getStatus());
    }

    @Test
    public void aDecidedPairIsNeverAskedAgain() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        Result first = check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(),
                Options.defaults(), null, null).check(memory, MISSION);
        Contradiction judged = first.getJudgements().get(0).getContradiction();
        memory.record(judged.withState(Contradiction.State.VOM_NUTZER_ENTSCHIEDEN, "Lastwagen gelten mit", 9L));

        ScriptedChat again = new ScriptedChat(ContradictionCheckTest::tempo);
        Result second = check(again, new FakeEmbeddings(), Options.defaults(), null, null).check(memory, MISSION);

        assertTrue(second.getJudgements().isEmpty());
        assertEquals(1, second.getCandidates().getJudgedBefore());
        assertTrue(again.prompts.isEmpty());
        assertEquals(Contradiction.State.VOM_NUTZER_ENTSCHIEDEN, memory.contradiction(judged.getId()).getState());
        assertTrue(memory.openContradictions().isEmpty());
    }

    @Test
    public void anInvalidAnswerGetsOneRepair() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        ScriptedChat repaired = new ScriptedChat(prompt -> prompt.startsWith("REPAIR") ? answer("WIDERSPRUCH", "KEINER")
                : "{\"urteil\": \"VIELLEICHT\"}");

        Result result = check(repaired, new FakeEmbeddings(), Options.defaults(), null, null).check(memory, MISSION);

        Judgement judgement = result.getJudgements().get(0);
        assertEquals(Verdict.WIDERSPRUCH, judgement.getContradiction().getVerdict());
        assertEquals(2, judgement.getAnswers().size());

        ScriptedChat never = new ScriptedChat(prompt -> "kein JSON");
        ResearchMemory other = topicMemory();
        other.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        other.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        Judgement failed = check(never, new FakeEmbeddings(), Options.defaults(), null, null).check(other, MISSION)
                .getJudgements().get(0);
        assertEquals(Status.FEHLER, failed.getContradiction().getStatus());
        assertNull(failed.getContradiction().getVerdict());
        assertEquals(Difference.KEINER, failed.getContradiction().getDifference());
        assertTrue(failed.getContradiction().getProblem(), failed.getContradiction().getProblem()
                .startsWith("invalid answer"));
        assertEquals(2, never.prompts.size());
        assertFalse(failed.getContradiction().isOpen());
    }

    @Test
    public void answersAreReadLeniently() {
        ContradictionCheck.Answer lower = ContradictionCheck.parse("{\"grund\": \"Andere Länder, andere Regeln.\", "
                + "\"urteil\": \" vereinbar \", \"unterschied\": \"rechtsraum\"}");
        assertEquals("", lower.problem);
        assertEquals(Verdict.VEREINBAR, lower.verdict);
        assertEquals(Difference.RECHTSRAUM, lower.difference);

        ContradictionCheck.Answer noDifference = ContradictionCheck.parse("{\"grund\": \"Beide sagen das Gegenteil.\","
                + " \"urteil\": \"WIDERSPRUCH\"}");
        assertEquals("", noDifference.problem);
        assertEquals(Difference.KEINER, noDifference.difference);

        assertTrue(ContradictionCheck.parse("{\"grund\": \"kurz\", \"urteil\": \"UNKLAR\"}").problem
                .contains("grund"));
        assertTrue(ContradictionCheck.parse("{\"grund\": \"Ein ganzer Satz als Grund.\"}").problem.contains("urteil"));
        assertTrue(ContradictionCheck.parse("[1, 2]").problem.contains("JSON object"));
        assertTrue(ContradictionCheck.parse("").problem.contains("JSON"));
    }

    @Test
    public void aContradictionKeepsNoDifference() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));

        Result result = check(new ScriptedChat(prompt -> answer("WIDERSPRUCH", "JAHR")), new FakeEmbeddings(),
                Options.defaults(), null, null).check(memory, MISSION);

        assertEquals(Difference.KEINER, result.getJudgements().get(0).getContradiction().getDifference());
    }

    @Test
    public void pairsAreJudgedInParallelAndKeepTheirOrder() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F01", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C));
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger();
        ScriptedChat waiting = new ScriptedChat(prompt -> {
            if (prompt.contains(TEMPO_A) && prompt.contains(TEMPO_B)) {
                try {
                    assertTrue("the second pair did not start while the first waited",
                            secondStarted.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else if (order.getAndIncrement() == 0) {
                secondStarted.countDown();
            }
            return tempo(prompt);
        });

        Result result = check(waiting, new FakeEmbeddings(), Options.defaults().withParallel(2), null, null)
                .check(memory, MISSION);

        assertEquals(0, result.count(null));
        assertEquals(3, result.getJudgements().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(result.getCandidates().getToJudge().get(i).id(),
                    result.getJudgements().get(i).getContradiction().getId());
        }
        assertEquals(Verdict.WIDERSPRUCH, result.getJudgements().get(0).getContradiction().getVerdict());
    }

    // ------------------------------------------------------------------------------------------------- prompt

    @Test
    public void thePromptShowsMissionQuestionQuotesSourcesAndContextButNoIds() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        Map<String, String> passages = new HashMap<String, String>();
        passages.put("p1", "Die Stadt informiert. " + TEMPO_A + " Ausnahmen gibt es nicht.");
        ScriptedChat chat = new ScriptedChat(ContradictionCheckTest::tempo);

        check(chat, new FakeEmbeddings(), Options.defaults(), passages::get, null).check(memory, MISSION);

        String prompt = chat.prompts.get(0);
        assertTrue(prompt, prompt.contains(MISSION));
        assertTrue(prompt, prompt.contains("Frage: Wo gilt Tempo 30?"));
        assertTrue(prompt, prompt.contains("„" + TEMPO_A + "“"));
        assertTrue(prompt, prompt.contains("„" + TEMPO_B + "“"));
        assertTrue(prompt, prompt.contains("aus stadt.example"));
        assertTrue(prompt, prompt.contains("aus example.org"));
        assertTrue(prompt, prompt.contains("Umfeld: „Die Stadt informiert."));
        assertEquals(1, count(prompt, "Umfeld:"));
        assertTrue(prompt, prompt.contains("Entscheide nicht, welche Aussage stimmt."));
        assertTrue(prompt, prompt.contains("Verschiedene Zahlen widersprechen sich nur, wenn sie dasselbe messen, "));
        assertTrue("numbers of different years are compatible (architect, 22.11.)",
                prompt.contains("Zahlen mit verschiedenen Bezugsgrößen oder Zeitpunkten sind VEREINBAR, etwa "
                        + "Unfallzahlen aus 2015 und aus 2022"));
        for (String id : new String[]{"F01", "p1", "p2", "s1", "s2", "T-tempo"}) {
            assertFalse(id + " in " + prompt, prompt.matches("(?s).*\\b" + id + "\\b.*"));
        }
        assertEquals(ContradictionCheck.SCHEMA, chat.schemas.get(0));
    }

    @Test
    public void anEnglishBookGetsEnglishPrompts() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F02", "p3", "s3", "", TEMPO_C));
        ScriptedChat chat = new ScriptedChat(ContradictionCheckTest::tempo);

        new ContradictionCheck(chat, new FakeEmbeddings(), "en", Options.defaults(), null, null, null)
                .check(memory, "");

        String prompt = chat.prompts.get(0);
        assertTrue(prompt, prompt.contains("Book mission:\n(none given)"));
        assertTrue(prompt, prompt.contains("Topic: Tempo im Ort"));
        assertTrue(prompt, prompt.contains("Statement A answers the question: "));
        assertTrue(prompt, prompt.contains("from a source without address"));
        assertTrue(prompt, prompt.contains("Do not decide which statement is right."));
        assertTrue(prompt, prompt.contains("accident numbers from 2015 and from 2022 (\"unterschied\" \"JAHR\")"));
    }

    @Test
    public void theContextIsCutAroundTheQuote() {
        StringBuilder before = new StringBuilder();
        while (before.length() < 3000) {
            before.append("Allgemeine Hinweise zum Verkehr ohne Bezug. ");
        }
        String passage = before + TEMPO_B + " " + before;
        ContradictionCheck check = new ContradictionCheck(new ScriptedChat(ContradictionCheckTest::tempo), null, "de",
                Options.defaults().withContextChars(400), id -> passage, null, null);

        String around = check.around("p2", TEMPO_B);

        assertTrue(around, around.contains(TEMPO_B));
        assertTrue(around, around.startsWith("… ") && around.endsWith(" …"));
        assertTrue(around.length() + "", around.length() <= 400 + 4);
        assertEquals("", new ContradictionCheck(new ScriptedChat(ContradictionCheckTest::tempo), null, "de",
                Options.defaults().withContextChars(0), id -> passage, null, null).around("p2", TEMPO_B));
        assertEquals("", new ContradictionCheck(new ScriptedChat(ContradictionCheckTest::tempo), null, "de",
                Options.defaults(), id -> { throw new IllegalStateException("gone"); }, null, null)
                .around("p2", TEMPO_B));
    }

    @Test
    public void withoutAnEmbeddingModelThePairsKeepTheirOrder() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));

        Candidates candidates = check(new ScriptedChat(ContradictionCheckTest::tempo), null, Options.defaults(), null,
                null).candidates(memory);

        assertEquals(1, candidates.getToJudge().size());
        assertEquals(0.0, candidates.getToJudge().get(0).getSimilarity(), 0.0);
    }

    // ------------------------------------------------------------------------------------------------- role, log

    @Test
    public void theRoleThinksOnItsOwnSlot() {
        assertEquals(ResearchModelSlots.PHASE_RESEARCH_CONTRADICTIONS, ContradictionCheck.ROLE.getSlot());
        assertEquals("phase.research.contradictions", ContradictionCheck.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.ON, ContradictionCheck.ROLE.getThinking());
        assertEquals(16384, ContradictionCheck.ROLE.getContextTokens());
        JsonObject schema = JsonParser.parseString(ContradictionCheck.SCHEMA).getAsJsonObject();
        assertEquals("grund", schema.getAsJsonObject("properties").keySet().iterator().next());
        assertEquals(3, schema.getAsJsonArray("required").size());
        assertEquals(3, schema.getAsJsonObject("properties").getAsJsonObject("urteil").getAsJsonArray("enum").size());
        assertEquals(6, schema.getAsJsonObject("properties").getAsJsonObject("unterschied").getAsJsonArray("enum")
                .size());
    }

    @Test
    public void everyPairIsALineOfTheLogWithoutTheQuotes() throws Exception {
        File project = tmp.newFolder("project");
        ContradictionLog log = new ContradictionLog(project);
        assertTrue(log.open().isEmpty());
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        memory.record(direct("F02", "p3", "s3", "https://www.ris.bka.gv.at/x", TEMPO_C));

        check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(), Options.defaults(), null, log)
                .check(memory, MISSION);

        String content = new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8);
        assertFalse(content.contains(TEMPO_A.substring(0, 30)));
        List<JsonObject> lines = new ContradictionLog(project).open();
        assertEquals(3, lines.size());
        JsonObject first = lines.get(0);
        assertEquals("paar", first.get("art").getAsString());
        assertEquals(1, first.get("v").getAsInt());
        assertEquals("FRAGE", first.get("bereich").getAsString());
        assertEquals("F01", first.get("bereichId").getAsString());
        assertEquals("OK", first.get("status").getAsString());
        assertEquals("WIDERSPRUCH", first.get("urteil").getAsString());
        assertEquals("KEINER", first.get("unterschied").getAsString());
        assertEquals("p1", first.getAsJsonObject("a").get("passage").getAsString());
        assertEquals(1, first.getAsJsonArray("rohantworten").size());
        assertEquals(memory.contradictions().get(0).getId(), first.get("id").getAsString());
        assertEquals("THEMA", lines.get(1).get("bereich").getAsString());
    }

    @Test
    public void theLogFailsClosedAndCutsOnlyAnUnfinishedLastLine() throws Exception {
        File project = tmp.newFolder("project");
        File folder = new File(project, "contradictions");
        assertTrue(folder.mkdirs());
        File file = new File(folder, "log.jsonl");
        Files.write(file.toPath(), "{\"v\":1,\"art\":\"paar\",\"id\":\"x\"}\n{\"v\":1,\"art\":\"pa".getBytes(
                StandardCharsets.UTF_8));
        assertEquals(1, new ContradictionLog(project).open().size());
        assertEquals("{\"v\":1,\"art\":\"paar\",\"id\":\"x\"}\n", new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8));

        Files.write(file.toPath(), "kaputt\n".getBytes(StandardCharsets.UTF_8));
        try {
            new ContradictionLog(project).open();
            throw new AssertionError("an unreadable line must stop the opening");
        } catch (com.aresstack.askai.research.transition.CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 1"));
        }
        Files.write(file.toPath(), "{\"v\":1,\"art\":\"anders\"}\n".getBytes(StandardCharsets.UTF_8));
        try {
            new ContradictionLog(project).open();
            throw new AssertionError("an unknown kind must stop the opening");
        } catch (com.aresstack.askai.research.transition.CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unknown record kind"));
        }
        Files.write(file.toPath(), "{\"v\":2,\"art\":\"paar\"}\n".getBytes(StandardCharsets.UTF_8));
        try {
            new ContradictionLog(project).open();
            throw new AssertionError("a newer version must stop the opening");
        } catch (ContradictionLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("version 2"));
        }
        Files.write(file.toPath(), new byte[0]);
        Files.write(new File(folder, "current").toPath(), "log-v2.jsonl\n".getBytes(StandardCharsets.UTF_8));
        try {
            new ContradictionLog(project).open();
            throw new AssertionError("another generation must stop the opening");
        } catch (ContradictionLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("log-v2.jsonl"));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void theLogIsWrittenOnlyAfterOpening() throws Exception {
        ResearchMemory memory = topicMemory();
        memory.record(direct("F01", "p1", "s1", "https://www.stadt.example/tempo", TEMPO_A));
        memory.record(direct("F01", "p2", "s2", "https://verkehr.example.org/lkw", TEMPO_B));
        check(new ScriptedChat(ContradictionCheckTest::tempo), new FakeEmbeddings(), Options.defaults(), null,
                new ContradictionLog(tmp.newFolder("project"))).check(memory, MISSION);
    }

    @Test
    public void optionsRejectNonsense() {
        for (Runnable bad : new Runnable[]{
                () -> Options.defaults().withPairsPerScope(0),
                () -> Options.defaults().withParallel(0),
                () -> Options.defaults().withAnswerTokens(100),
                () -> Options.defaults().withMinSimilarity(1.5),
                () -> Options.defaults().withContextChars(-1)}) {
            try {
                bad.run();
                throw new AssertionError("accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("options"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }

    /** A set of strings that compares by content, for pairs in either order. */
    static final class HashSetOf extends java.util.HashSet<String> {
        HashSetOf(String... values) {
            super(Arrays.asList(values));
        }
    }
}

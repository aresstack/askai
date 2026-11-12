package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.FakeModelPort;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.team.ModelCallMetrics;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SectionWriterTest {

    /** A scripted transport that records every request. */
    static final class ScriptedChat implements MainModelChat {
        final Deque<MainModelChatResult> answers = new ArrayDeque<MainModelChatResult>();
        final List<List<ChatMessage>> requests = new ArrayList<List<ChatMessage>>();
        final List<Integer> budgets = new ArrayList<Integer>();

        ScriptedChat answer(String text) {
            answers.add(MainModelChatResult.ok(text, ModelCallMetrics.builder().modelLabel("gemma4:e2b")
                    .doneReason("stop").build()));
            return this;
        }

        ScriptedChat cutOff(String text) {
            answers.add(MainModelChatResult.ok(text, ModelCallMetrics.builder().modelLabel("gemma4:e2b")
                    .doneReason("length").build()));
            return this;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            requests.add(new ArrayList<ChatMessage>(messages));
            budgets.add(maxOutputTokens);
            return answers.isEmpty() ? MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "no scripted answer") : answers.poll();
        }

        public String modelName() {
            return "slot phase.draft.writer";
        }
    }

    static List<EvidencePassage> pack(int k) {
        List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
        for (int i = 1; i <= k; i++) {
            passages.add(new EvidencePassage(i, "src-" + i, "sha-" + i, "p-" + i,
                    "Passage " + i + " sagt: Die Rate stieg auf 15,5 % im Jahr 2020 weiter, danach kam ein "
                            + "Rückgang (Teil eins und Teil zwei)."));
        }
        return passages;
    }

    static SectionAssignment assignment(int k) {
        return SectionAssignment.builder("s1").title("Rates").brief("How the rate developed")
                .keyPoints(Arrays.asList("growth")).passages(pack(k)).bookLanguage("German")
                .wordsPerCall(450).answerTokens(1200).build();
    }

    @Test
    public void unknownMarkerIsStrippedAndLogged() {
        ScriptedChat chat = new ScriptedChat().answer("Die Rate stieg auf 15,5 % [1][9].");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(assignment(6));
        assertTrue(draft.isOk());
        assertEquals(Arrays.asList("[9]"), draft.getStrippedMarkers());
        assertFalse(draft.getText().contains("[9]"));
        assertTrue(draft.getText().contains("[1]"));
        assertTrue(log.toString(), log.toString().contains("stripped unknown marker [9]"));
        assertEquals(1, chat.requests.size());
        assertEquals("sha-1", draft.getCitations().get(1).getSourceChecksum());
    }

    @Test
    public void replyWithoutCitationsGetsOneRepairWithoutTheFailedAnswerAndIsStoredFlagged() {
        String failed = "Die Rate stieg deutlich an. Das war wichtig.";
        ScriptedChat chat = new ScriptedChat().answer(failed).answer("Die Rate stieg weiter an. Ohne Beleg.");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertEquals(2, chat.requests.size());
        List<ChatMessage> repair = chat.requests.get(1);
        for (ChatMessage m : repair) {
            assertFalse("the failed answer is never sent back", m.getRole() == ChatMessage.Role.ASSISTANT);
        }
        String last = repair.get(repair.size() - 1).getContent();
        assertTrue(last.contains("uncited sentence"));
        assertTrue(draft.isOk());
        assertEquals(2, draft.flagsOf(ClaimFlag.Kind.UNCITED_SENTENCE).size());
        assertEquals(1, draft.flagsOf(ClaimFlag.Kind.UNCITED_PARAGRAPH).size());
        assertTrue(draft.getText().contains("weiter"));
    }

    @Test
    public void cutOffAnswerGetsExactlyOneContinuationWithoutDuplicatedSentence() {
        ScriptedChat chat = new ScriptedChat()
                .cutOff("Die Rate stieg auf 15,5 % [1]. Im Jahr 2020 war das so [2]. Danach kam ein")
                .answer("Im Jahr 2020 war das so [2]. Danach kam ein Rückgang [3].");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertEquals(2, chat.requests.size());
        List<ChatMessage> continuation = chat.requests.get(1);
        List<ChatMessage> first = chat.requests.get(0);
        assertEquals(first.size() + 2, continuation.size());
        assertEquals("Die Rate stieg auf 15,5 % [1]. Im Jahr 2020 war das so [2].",
                continuation.get(first.size()).getContent());
        assertEquals("Die Rate stieg auf 15,5 % [1]. Im Jahr 2020 war das so [2]. Danach kam ein Rückgang [3].",
                draft.getText());
        assertTrue(draft.getFlags().toString(), draft.getFlags().isEmpty());
    }

    @Test
    public void cutOffContinuationIsNotRepeated() {
        ScriptedChat chat = new ScriptedChat().cutOff("Die Rate stieg [1]. Und").cutOff("Weiter [2]. Und")
                .answer("never asked");
        new SectionWriter(chat, null).write(assignment(6));
        assertEquals(2, chat.requests.size());
    }

    @Test
    public void promptNamesBookLanguageAtStartAndEndAndFitsSmallPreset() {
        SectionAssignment a = SectionAssignment.builder("s1").title("Titel").brief(repeat("Brief ", 40))
                .keyPoints(Arrays.asList("a", "b", "c")).passages(longPack(6, 250))
                .synopsis(repeat("Synopsis wort ", 200)).previousDigest(repeat("Digest wort ", 200))
                .glossary(Arrays.asList(repeat("Begriff ", 100).split(" "))).bookLanguage("German")
                .wordsPerCall(450).answerTokens(1200).build();
        List<ChatMessage> first = SectionWriterPrompt.first(a);
        String user = first.get(1).getContent();
        assertTrue(user.startsWith("Book language: German."));
        assertTrue(user.endsWith("Write in German."));
        assertTrue("small preset: " + SectionWriterPrompt.estimatedTokens(first),
                SectionWriterPrompt.estimatedTokens(first) <= 3400);
        SectionAssignment large = SectionAssignment.builder("s1").title("Titel").brief(repeat("Brief ", 40))
                .keyPoints(Arrays.asList("a", "b", "c")).passages(longPack(8, 300))
                .synopsis(repeat("Synopsis wort ", 200)).previousDigest(repeat("Digest wort ", 200))
                .bookLanguage("German").wordsPerCall(1100).answerTokens(3072).build();
        assertTrue("large preset: " + SectionWriterPrompt.estimatedTokens(SectionWriterPrompt.first(large)),
                SectionWriterPrompt.estimatedTokens(SectionWriterPrompt.first(large)) <= 4300);
    }

    @Test
    public void continuationAddsOnlyTheCutOffAnswer() {
        SectionAssignment a = assignment(6);
        List<ChatMessage> first = SectionWriterPrompt.first(a);
        List<ChatMessage> next = SectionWriterPrompt.continuation(a, "Abc [1].");
        assertEquals(first.get(1).getContent(), next.get(1).getContent());
        assertEquals("Abc [1].", next.get(2).getContent());
        assertTrue(SectionWriterPrompt.estimatedTokens(next) - SectionWriterPrompt.estimatedTokens(first) < 100);
    }

    @Test
    public void rewriteSendsCommentAndCurrentTextButContinuationOmitsTheOldText() {
        SectionAssignment a = SectionAssignment.builder("s1").title("T").passages(pack(6))
                .rewriteComment("kürzer").currentText("Alter Text des Teils [1].").bookLanguage("German").build();
        ScriptedChat chat = new ScriptedChat().cutOff("Neu [1]. Abgeschn").answer("Weiter [2].");
        SectionDraft draft = new SectionWriter(chat, null).write(a);
        assertTrue(chat.requests.get(0).get(1).getContent().contains("Alter Text des Teils"));
        assertTrue(chat.requests.get(0).get(1).getContent().contains("kürzer"));
        assertFalse(chat.requests.get(1).get(1).getContent().contains("Alter Text des Teils"));
        assertTrue(chat.requests.get(1).get(1).getContent().contains("kürzer"));
        assertEquals("Neu [1]. Weiter [2].", draft.getText());
    }

    @Test
    public void longSectionIsSplitByKeyPoints() {
        SectionAssignment a = SectionAssignment.builder("s1").title("T")
                .keyPoints(Arrays.asList("k1", "k2", "k3", "k4")).passages(pack(6)).wordsPerCall(450)
                .sectionWords(900).answerTokens(1200).build();
        ScriptedChat chat = new ScriptedChat().answer("Teil eins [1].").answer("Teil zwei [2].");
        SectionDraft draft = new SectionWriter(chat, null).write(a);
        assertEquals(2, chat.requests.size());
        assertTrue(chat.requests.get(0).get(1).getContent().contains("- k2"));
        assertFalse(chat.requests.get(0).get(1).getContent().contains("- k3"));
        assertTrue(chat.requests.get(1).get(1).getContent().contains("- k3"));
        assertEquals("Teil eins [1].\n\nTeil zwei [2].", draft.getText());
        assertEquals(2, draft.getCitations().size());
    }

    /** Live run 4, book 3, section 2.2: the second half repeated sentences the first half had written. */
    @Test
    public void aLaterPartLeavesOutWhatAnEarlierPartSaid() {
        SectionAssignment a = SectionAssignment.builder("s1").title("T")
                .keyPoints(Arrays.asList("k1", "k2", "k3", "k4")).passages(pack(6)).wordsPerCall(450)
                .sectionWords(900).answerTokens(1200).build();
        ScriptedChat chat = new ScriptedChat().answer("Die Rate stieg weiter [1].")
                .answer("Die Rate stieg weiter [2]. Danach kam ein Rückgang [3].");
        List<String> log = new ArrayList<String>();
        SectionDraft draft = new SectionWriter(chat, log::add).write(a);
        assertEquals("Die Rate stieg weiter [1].\n\nDanach kam ein Rückgang [3].", draft.getText());
        assertTrue(log.toString(), log.toString().contains("removed 0 label lines and 1 repeated sentences"));
        assertFalse("the log names counts, never text", log.toString().contains("Rate stieg"));
    }

    /** Live run 4, books 2 and 3: echoed key points and a closing list of questions stood in the book as prose. */
    @Test
    public void labelLinesAndOpenQuestionsLeaveTheProse() {
        ScriptedChat chat = new ScriptedChat()
                .answer("Die Rate stieg weiter [1].\n\n- growth\n- Rückgang\n\nWie hoch war die Rate 2021?");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertEquals("Die Rate stieg weiter [1].", draft.getText());
        assertEquals(Arrays.asList("Wie hoch war die Rate 2021?"), draft.getGaps());
        assertEquals("nothing is left to repair", 1, chat.requests.size());
        assertTrue(draft.getFlags().toString(), draft.getFlags().isEmpty());
    }

    /** Live run 4, books 2 and 3: "unsere tragbaren Geräte", "implementieren wir" (a manufacturer's voice). */
    @Test
    public void aSourcesWeGetsTheOneRepair() {
        ScriptedChat chat = new ScriptedChat().answer("Unsere Rate stieg weiter [1].")
                .answer("Die Rate stieg weiter [1].");
        SectionDraft draft = new SectionWriter(chat, null).write(assignment(6));
        assertTrue(chat.requests.get(0).get(0).getContent().contains("neutral third person"));
        assertEquals(2, chat.requests.size());
        List<ChatMessage> repair = chat.requests.get(1);
        String last = repair.get(repair.size() - 1).getContent();
        assertTrue(last, last.contains("first-person voice of a source \"Unsere\": Unsere Rate stieg weiter [1]."));
        assertEquals("Die Rate stieg weiter [1].", draft.getText());
        assertTrue(draft.getFlags().toString(), draft.getFlags().isEmpty());
    }

    @Test
    public void continuationStartingWithGapKeepsItOnItsOwnLine() {
        assertEquals("The rate rose [1].\nGAP: Later figures", SectionWriter.join("The rate rose [1].", "GAP: Later figures"));
        CitationValidator.Result r = new CitationValidator().validate(
                SectionWriter.join("The rate rose [1].", "GAP: Later figures"), 3);
        assertEquals(Arrays.asList("Later figures"), r.getGaps());
    }

    @Test
    public void cutKeepsSentenceEndingInsideQuotes() {
        assertEquals("She said \u201CThe rate rose.\u201D [1]",
                SectionWriter.upToLastFullSentence("She said \u201CThe rate rose.\u201D [1] Then an unfinished"));
    }

    @Test
    public void failedCallGivesFailedDraft() {
        SectionDraft draft = new SectionWriter(new ScriptedChat(), null).write(assignment(6));
        assertFalse(draft.isOk());
        assertEquals("no scripted answer", draft.getFailure());
    }

    @Test
    public void writerOnPortCallsThePhaseDraftWriterSlotWithTheAnswerBudget() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueRaw("Die Rate stieg auf 15,5 % [1].\nGAP: Zahlen nach 2021");
        SectionDraft draft = SectionWriter.onPort(port, Duration.ofSeconds(10), null).write(assignment(6));
        assertTrue(draft.getFailure(), draft.isOk());
        ChatRequest request = port.requests().get(0);
        assertEquals(ResearchModelSlots.PHASE_DRAFT_WRITER, request.getSlot());
        assertEquals(1200, request.getMaxOutputTokens());
        assertEquals(Arrays.asList("Zahlen nach 2021"), draft.getGaps());
        port.close();
    }

    static List<EvidencePassage> longPack(int k, int tokens) {
        List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
        for (int i = 1; i <= k; i++) {
            passages.add(new EvidencePassage(i, "s" + i, "c" + i, "p" + i, repeat("x", tokens * 3)));
        }
        return passages;
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}

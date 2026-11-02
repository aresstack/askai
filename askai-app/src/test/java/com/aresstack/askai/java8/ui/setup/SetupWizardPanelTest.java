package com.aresstack.askai.java8.ui.setup;

import com.aresstack.comiccontrols.theme.ComicPalette;
import org.junit.Test;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The wizard's flow against a synchronous fake of {@link SetupActions}: steps, checks, Merlin, finish, cancel. */
public class SetupWizardPanelTest {

    @Test
    public void startsOnTheNetworkStepAndOnlyGoesOnAfterATestOrASkip() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        assertEquals(SetupWizardPanel.STEP_NETWORK, wizard.step());
        assertFalse(wizard.nextButton().isEnabled());
        assertTrue("skipping is always possible", wizard.skipButton().isEnabled());

        wizard.networkTestButton().doClick();

        assertTrue(wizard.isDone(SetupWizardPanel.STEP_NETWORK));
        assertTrue(wizard.nextButton().isEnabled());
    }

    @Test
    public void theNetworkInputsAreLockedWhileATestRunsSoItsAnswerAlwaysCounts() {
        Fake fake = new Fake();
        fake.holdNetwork = true;
        SetupWizardPanel wizard = wizard(fake);
        wizard.networkTestButton().doClick();
        assertFalse(wizard.trustWindowsBox().isEnabled());
        assertFalse(wizard.proxyModeChoice().isEnabled());

        fake.pendingNetwork.accept("OK", null);

        assertTrue(wizard.isDone(SetupWizardPanel.STEP_NETWORK));
        assertTrue(wizard.nextButton().isEnabled());
        assertTrue(wizard.trustWindowsBox().isEnabled());
    }

    @Test
    public void changingACheckedSettingAfterTheFinalCheckRequiresANewCheck() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        walkToVoice(wizard);
        assertTrue(wizard.finishButton().isEnabled());

        wizard.jumpTo(SetupWizardPanel.STEP_CHAT);
        wizard.chatModelChoice().setSelectedItem("qwen3");
        wizard.jumpTo(SetupWizardPanel.STEP_VOICE);

        assertFalse(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        assertFalse(wizard.finishButton().isEnabled());
        assertFalse(wizard.skipButton().isEnabled());
        assertEquals(SetupWizardPanel.CHECK_OUTDATED, wizard.checkStatusText());

        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertEquals("qwen3", fake.finished.chatModel());
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_CHECK));
    }

    @Test
    public void manualProxyFieldsOnlyShowForTheManualProxy() {
        SetupWizardPanel wizard = wizard(new Fake());
        wizard.proxyModeChoice().setSelectedItem(SetupWizardPanel.LABEL_PROXY_NONE);
        assertFalse(wizard.manualProxyShown());
        wizard.proxyModeChoice().setSelectedItem(SetupWizardPanel.LABEL_PROXY_MANUAL);
        assertTrue(wizard.manualProxyShown());
        assertEquals(SetupWizardPanel.PROXY_MANUAL, wizard.draft().proxyMode());
    }

    @Test
    public void aFailedNetworkTestShowsTheErrorAndKeepsTheStepOpen() {
        Fake fake = new Fake();
        fake.networkError = "proxy refused";
        SetupWizardPanel wizard = wizard(fake);
        wizard.networkTestButton().doClick();
        assertFalse(wizard.isDone(SetupWizardPanel.STEP_NETWORK));
        assertEquals("proxy refused", wizard.errorOf(SetupWizardPanel.STEP_NETWORK));
    }

    @Test
    public void theRuntimeStepChecksRightAwayWakesMerlinAndPreselectsTheModels() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        assertFalse("Merlin is silent until a runtime answers", wizard.isMerlinAwake());
        assertEquals(SetupWizardPanel.RUNTIME_INSTALL_MASCOT,
                wizard.mascotOf(SetupWizardPanel.STEP_RUNTIME).art().resource());

        wizard.skipButton().doClick();

        assertEquals(SetupWizardPanel.STEP_RUNTIME, wizard.step());
        assertEquals(1, fake.runtimeChecks);
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_RUNTIME));
        assertTrue(wizard.isMerlinAwake());
        assertEquals(WizardGuide.FIRST_GREETING, wizard.mascotOf(SetupWizardPanel.STEP_RUNTIME).spokenText());
        assertEquals("the connecting picture once a runtime answers", SetupWizardPanel.mascotResource(
                SetupWizardPanel.STEP_RUNTIME), wizard.mascotOf(SetupWizardPanel.STEP_RUNTIME).art().resource());
        assertTrue(wizard.ollamaStatusText().contains("0.9.0"));
        assertEquals("llama3.2", wizard.draft().chatModel());
        assertEquals("nomic-embed-text", wizard.draft().embeddingModel());
        assertEquals("a reranker stays optional", "", wizard.draft().rerankerModel());
    }

    @Test
    public void anUnreachableRuntimeShowsTheInstallSceneAndAnError() {
        Fake fake = new Fake();
        fake.ollamaVersion = null;
        SetupWizardPanel wizard = wizard(fake);
        wizard.skipButton().doClick();
        assertFalse(wizard.isDone(SetupWizardPanel.STEP_RUNTIME));
        assertNotNull(wizard.errorOf(SetupWizardPanel.STEP_RUNTIME));
        assertFalse(wizard.isMerlinAwake());
        assertEquals(SetupWizardPanel.RUNTIME_INSTALL_MASCOT,
                wizard.mascotOf(SetupWizardPanel.STEP_RUNTIME).art().resource());
    }

    @Test
    public void theLocalRuntimeAloneIsEnough() {
        Fake fake = new Fake();
        fake.ollamaVersion = null;
        fake.localRuntime = true;
        SetupWizardPanel wizard = wizard(fake);
        wizard.skipButton().doClick();
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_RUNTIME));
    }

    @Test
    public void theKnowledgeStepIsDoneOnceTheEmbeddingModelAnswers() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        wizard.jumpTo(SetupWizardPanel.STEP_KNOWLEDGE);
        assertEquals("768", wizard.embeddingDimensionField().getText());
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_KNOWLEDGE));

        wizard.sentenceInstallButton().doClick();
        assertEquals(1, fake.sentenceInstalls);
        assertEquals("opennlp-de-sent", wizard.draft().nlpSentenceGerman());
    }

    @Test
    public void theFinalCheckSavesTheDraftAndBlocksOnAFailure() {
        Fake fake = new Fake();
        fake.checks = Arrays.asList(SetupCheck.ok("AI runtime", "Ollama 0.9.0"),
                SetupCheck.failed("Chat model", "llama3.2 is not installed"));
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertNotNull("the draft was handed over for saving", fake.finished);
        assertEquals("llama3.2", fake.finished.chatModel());
        assertFalse(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        assertFalse(wizard.nextButton().isEnabled());
        assertTrue(wizard.errorOf(SetupWizardPanel.STEP_CHECK).contains("not installed"));
    }

    @Test
    public void finishingSavesTheVoicesAndClosesWithoutHandOffWhenCalledAgain() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        walkToVoice(wizard);
        assertTrue(wizard.finishButton().isVisible());
        assertTrue("the Windows voice needs no install", wizard.finishButton().isEnabled());

        wizard.finishButton().doClick();

        assertTrue(finished[0]);
        assertNotNull(fake.savedVoices);
        assertEquals("", fake.savedVoices.voiceGerman());
    }

    @Test
    public void aChosenVoiceMustBeInstalledBeforeFinishing() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        walkToVoice(wizard);
        wizard.voiceGermanChoice().setSelectedItem("Thorsten (German)");
        assertFalse(wizard.finishButton().isEnabled());
        assertTrue("finishing without voices stays possible", wizard.skipButton().isEnabled());
    }

    @Test
    public void theFirstFinishPlaysTheHandOverBeforeClosing() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        wizard.setHandoff(true);
        wizard.setReadingPace(0);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        walkToVoice(wizard);
        wizard.skipButton().doClick();
        assertTrue(finished[0]);
        assertEquals(WizardGuide.handoff(WizardGuide.handoffLines() - 1).text,
                wizard.mascotOf(SetupWizardPanel.STEP_VOICE).spokenText());
    }

    @Test
    public void aLaterCallGreetsAsAReunion() {
        SetupWizardPanel wizard = wizard(new Fake());
        wizard.welcomeBack();
        assertTrue(wizard.isMerlinAwake());
        assertEquals(WizardGuide.WELCOME_BACK, wizard.mascotOf(SetupWizardPanel.STEP_NETWORK).spokenText());
    }

    @Test
    public void merlinsMenuOffersTheFixedAnswersAndAFreeQuestionOnlyWithAChatModel() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        assertFalse(wizard.canAskMerlin());
        assertEquals(4, wizard.merlinMenu().getComponentCount());
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        assertTrue(wizard.canAskMerlin());
        wizard.askMerlin("What is an embedding?");
        assertEquals("Fake answer", wizard.mascotOf(SetupWizardPanel.STEP_RUNTIME).spokenText());
    }

    @Test
    public void cancellingARunReportsItOnceAndLetsTheActionsUndoTheirTests() {
        Fake fake = new Fake();
        final List<Boolean> ends = new ArrayList<Boolean>();
        SetupWizardRun run = new SetupWizardRun(SetupDraft.builder().build(), fake, ComicPalette.defaultPalette(),
                ends::add);
        run.wizard().cancel();
        run.wizard().cancel();
        assertEquals(Collections.singletonList(Boolean.FALSE), ends);
        assertTrue(fake.cancelled);
        assertFalse(run.isCompleted());
    }

    private static void walkToVoice(SetupWizardPanel wizard) {
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        wizard.nextButton().doClick();
        assertEquals(SetupWizardPanel.STEP_VOICE, wizard.step());
    }

    private static SetupWizardPanel wizard(Fake fake) {
        return new SetupWizardPanel(SetupDraft.builder().ollamaBaseUrl("http://localhost:11434").build(), fake,
                ComicPalette.defaultPalette());
    }

    /** Answers every call at once, on the calling thread. */
    static final class Fake implements SetupActions {
        String networkError;
        boolean holdNetwork;
        BiConsumer<String, String> pendingNetwork;
        String ollamaVersion = "0.9.0";
        boolean localRuntime;
        int runtimeChecks;
        int sentenceInstalls;
        boolean sentenceInstalled;
        List<SetupCheck> checks = Collections.singletonList(SetupCheck.ok("AI runtime", "Ollama 0.9.0"));
        SetupDraft finished;
        SetupDraft savedVoices;
        boolean cancelled;

        @Override
        public void testNetwork(SetupDraft draft, BiConsumer<String, String> done) {
            if (holdNetwork) {
                pendingNetwork = done;
                return;
            }
            done.accept(networkError == null ? "OK" : null, networkError);
        }

        @Override
        public SetupDraft editNetwork(Component parent, SetupDraft draft) {
            return null;
        }

        @Override
        public void checkRuntime(SetupDraft draft, Consumer<RuntimeStatus> done) {
            runtimeChecks++;
            done.accept(new RuntimeStatus(ollamaVersion, ollamaVersion == null ? "connection refused" : "",
                    localRuntime));
        }

        @Override
        public void openRuntimeDownload() {
        }

        @Override
        public void loadModels(SetupDraft draft, Consumer<SetupModels> done) {
            done.accept(new SetupModels(Arrays.asList("llama3.2", "qwen3"), Collections.singletonList(
                    "nomic-embed-text"), Collections.<String>emptyList(), sentenceInstalled
                    ? Collections.singletonList("opennlp-de-sent") : Collections.<String>emptyList(),
                    Collections.<String>emptyList(), ""));
        }

        @Override
        public void embeddingDimension(SetupDraft draft, BiConsumer<Integer, String> done) {
            done.accept(768, null);
        }

        @Override
        public void installSentenceModels(Consumer<String> progress, BiConsumer<String, String> done) {
            sentenceInstalls++;
            sentenceInstalled = true;
            progress.accept("Downloading opennlp-de-sent …");
            done.accept("1 sentence model installed.", null);
        }

        @Override
        public void loadVoices(Consumer<List<VoiceOffer>> done) {
            done.accept(Arrays.asList(new VoiceOffer("de_DE-thorsten-medium", "Thorsten (German)", "de", false),
                    new VoiceOffer("en_US-amy-medium", "Amy (English)", "en", true)));
        }

        @Override
        public void installVoice(String voiceId, Consumer<String> progress, BiConsumer<String, String> done) {
            done.accept("installed", null);
        }

        @Override
        public void saveVoices(SetupDraft draft, Consumer<String> done) {
            savedVoices = draft;
            done.accept(null);
        }

        @Override
        public void finish(SetupDraft draft, Consumer<List<SetupCheck>> done) {
            finished = draft;
            done.accept(checks);
        }

        @Override
        public void ask(SetupDraft draft, String system, String question, BiConsumer<String, String> done) {
            done.accept("Fake answer", null);
        }

        @Override
        public void cancelled() {
            cancelled = true;
        }
    }
}

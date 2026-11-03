package com.aresstack.askai.ui.setup;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The wizard's flow against a synchronous fake of {@link SetupActions}: steps, checks, Witch, finish, cancel. */
public class SetupWizardPanelTest {

    @Test
    public void startsOnTheNetworkStepAndNextIsOpenEvenWithoutATest() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        assertEquals(SetupWizardPanel.STEP_NETWORK, wizard.step());
        assertTrue("an unchecked step never blocks Next", wizard.nextButton().isEnabled());
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
        walkToEnd(wizard);
        assertTrue(wizard.finishButton().isEnabled());

        wizard.jumpTo(SetupWizardPanel.STEP_CHAT);
        wizard.chatModelChoice().setSelectedItem("qwen3");

        assertFalse(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        assertTrue("an outdated check is a hint, not a lock", wizard.nextButton().isEnabled());
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
    public void theRuntimeStepChecksRightAwayWakesWitchAndPreselectsTheModels() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        assertFalse("Witch is silent until a runtime answers", wizard.isWitchAwake());
        assertEquals(SetupWizardPanel.RUNTIME_INSTALL_MASCOT,
                wizard.mascotOf(SetupWizardPanel.STEP_RUNTIME).art().resource());

        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);

        assertEquals(SetupWizardPanel.STEP_RUNTIME, wizard.step());
        assertEquals(1, fake.runtimeChecks);
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_RUNTIME));
        assertTrue(wizard.isWitchAwake());
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
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        assertFalse(wizard.isDone(SetupWizardPanel.STEP_RUNTIME));
        assertNotNull(wizard.errorOf(SetupWizardPanel.STEP_RUNTIME));
        assertFalse(wizard.isWitchAwake());
        assertEquals(SetupWizardPanel.RUNTIME_INSTALL_MASCOT,
                wizard.mascotOf(SetupWizardPanel.STEP_RUNTIME).art().resource());
    }

    @Test
    public void theLocalRuntimeAloneIsEnough() {
        Fake fake = new Fake();
        fake.ollamaVersion = null;
        fake.localRuntime = true;
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
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
    public void theFinalCheckSavesTheDraftAndReportsAFailureWithoutBlocking() {
        Fake fake = new Fake();
        fake.checks = Arrays.asList(SetupCheck.ok("AI runtime", "Ollama 0.9.0"),
                SetupCheck.failed("Chat model", "llama3.2 is not installed"));
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertNotNull("the draft was handed over for saving", fake.finished);
        assertEquals("llama3.2", fake.finished.chatModel());
        assertFalse(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        assertTrue("a failed check is a hint, Finish stays open", wizard.finishButton().isEnabled());
        assertTrue(wizard.errorOf(SetupWizardPanel.STEP_CHECK).contains("not installed"));
    }

    @Test
    public void finishingSavesTheVoicesAndClosesWithoutHandOffWhenCalledAgain() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        walkToEnd(wizard);
        assertTrue(wizard.finishButton().isVisible());
        assertTrue("the Windows voice needs no install", wizard.finishButton().isEnabled());

        wizard.finishButton().doClick();

        assertTrue(finished[0]);
        assertNotNull(fake.savedVoices);
        assertEquals("", fake.savedVoices.voiceGerman());
    }

    @Test
    public void aChosenButMissingVoiceDoesNotBlockFinishing() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        wizard.jumpTo(SetupWizardPanel.STEP_VOICE);
        wizard.voiceGermanChoice().setSelectedItem("Thorsten (German)");
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertTrue(wizard.finishButton().isEnabled());
        assertTrue("finishing without voices stays possible", wizard.skipButton().isEnabled());

        wizard.finishButton().doClick();

        assertTrue(finished[0]);
        assertTrue("a voice that is not installed is not stored",
                fake.savedVoices == null || fake.savedVoices.voiceGerman().isEmpty());
    }

    @Test
    public void everyStepCanBePassedWithNextEvenWhenAllChecksFail() {
        Fake fake = new Fake();
        fake.networkError = "proxy unreachable";
        fake.ollamaVersion = null;
        fake.checks = Collections.singletonList(SetupCheck.failed("AI runtime", "connection refused"));
        SetupWizardPanel wizard = wizard(fake);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        wizard.networkTestButton().doClick();
        assertFalse(wizard.isDone(SetupWizardPanel.STEP_NETWORK));

        for (int i = SetupWizardPanel.STEP_NETWORK; i < SetupWizardPanel.STEP_CHECK; i++) {
            assertTrue("Next is open in step " + i, wizard.nextButton().isEnabled());
            wizard.nextButton().doClick();
            assertEquals(i + 1, wizard.step());
        }
        assertFalse(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        assertTrue(wizard.finishButton().isEnabled());
        wizard.finishButton().doClick();

        assertTrue("the wizard closes despite the failed check", finished[0]);
        assertNotNull("the entries were saved all the same", fake.finished);
    }

    @Test
    public void aFailedSaveKeepsTheWizardOpen() {
        Fake fake = new Fake();
        fake.checks = Collections.singletonList(SetupCheck.failed(SetupWizardPanel.SAVING_CHECK, "disk full"));
        SetupWizardPanel wizard = wizard(fake);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);

        wizard.finishButton().doClick();

        assertFalse("unsaved entries must not close the wizard", finished[0]);
        assertTrue(wizard.errorOf(SetupWizardPanel.STEP_CHECK).contains("disk full"));
    }

    @Test
    public void theVoiceStepComesRightAfterTheNetworkAndStoresTheVoicesOnLeaving() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        assertEquals("network and voice share the first step", SetupWizardPanel.STEP_VOICE, wizard.step());
        assertEquals(SetupWizardPanel.STEP_NETWORK, wizard.step());
        assertNull(fake.savedVoices);

        wizard.nextButton().doClick();

        assertEquals(SetupWizardPanel.STEP_RUNTIME, wizard.step());
        assertNotNull("the voices are stored before the next step, so Witch can use them", fake.savedVoices);
    }

    @Test
    public void withoutAVoiceTheWizardOnlyThinks() {
        SetupWizardPanel wizard = wizard(new Fake());
        assertFalse(wizard.isWitchAwake());
        assertEquals(WizardGuide.THOUGHT_SILENT, wizard.mascotOf(SetupWizardPanel.STEP_VOICE).spokenText());
    }

    @Test
    public void aDownloadedVoiceLetsHimSpeakWithoutARuntimeAndProvesTheNetwork() {
        Fake fake = new Fake();
        fake.ollamaVersion = null;
        SetupWizardPanel wizard = wizard(fake);
        final List<String> spoken = new ArrayList<String>();
        wizard.setVoice(new WizardVoice() {
            @Override
            public boolean isAvailable() {
                return fake.savedVoices != null;
            }

            @Override
            public void speak(String text) {
                spoken.add(text);
            }

            @Override
            public void stop() {
            }
        });
        wizard.nextButton().doClick();
        wizard.voiceGermanChoice().setSelectedItem("Thorsten (German)");
        wizard.voiceInstallButton().doClick();
        assertTrue("the voice download counts as network test", wizard.isDone(SetupWizardPanel.STEP_NETWORK));

        wizard.nextButton().doClick();

        assertTrue("awake without any AI runtime", wizard.isWitchAwake());
        assertFalse(spoken.isEmpty());
    }

    @Test
    public void theVoiceDownloadUsesTheProxyOfTheStepAndShowsItsProgress() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        wizard.proxyModeChoice().setSelectedItem(SetupWizardPanel.LABEL_PROXY_MANUAL);
        wizard.voiceGermanChoice().setSelectedItem("Thorsten (German)");

        wizard.voiceInstallButton().doClick();

        assertEquals("the download is the network test", SetupWizardPanel.PROXY_MANUAL, fake.voiceDraft.proxyMode());
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_NETWORK));
    }

    @Test
    public void aMissingChatModelCanBeDownloadedRightInTheStep() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_CHAT);
        wizard.chatModelChoice().setSelectedItem("mistral");
        assertTrue("offered for a model that is not installed", wizard.chatDownloadButton().isVisible());

        wizard.chatDownloadButton().doClick();

        assertEquals(Collections.singletonList("mistral"), fake.pulled);
        assertEquals("the name Ollama lists it under", "mistral:latest", wizard.draft().chatModel());
    }

    @Test
    public void pulledModelsKeepTheNameOllamaListsThemUnder() {
        assertEquals("nomic-embed-text:latest", AskAiSetupActions.canonicalOllamaName("nomic-embed-text"));
        assertEquals("qwen3:8b", AskAiSetupActions.canonicalOllamaName("qwen3:8b"));
        assertEquals("hf.co/user/model:latest", AskAiSetupActions.canonicalOllamaName("hf.co/user/model"));
        assertEquals("registry:5000/model:latest", AskAiSetupActions.canonicalOllamaName("registry:5000/model"));
    }

    @Test
    public void everyStepShowsItsOwnPicture() {
        SetupWizardPanel wizard = wizard(new Fake());
        String[] expected = {"voice", "ai-runtime", "chat-model", "knowledge", "voice", "check"};
        int[] steps = {SetupWizardPanel.STEP_VOICE, SetupWizardPanel.STEP_RUNTIME, SetupWizardPanel.STEP_CHAT,
                SetupWizardPanel.STEP_KNOWLEDGE, SetupWizardPanel.STEP_DICTATION, SetupWizardPanel.STEP_CHECK};
        for (int i = 0; i < steps.length; i++) {
            String resource = wizard.mascotOf(steps[i]).art().resource();
            assertTrue(SetupWizardPanel.STEP_NAMES[steps[i]] + " shows " + resource,
                    resource.contains(expected[i]) || resource.equals(SetupWizardPanel.CHECK_SUCCESS_MASCOT));
        }
    }

    @Test
    public void dictationPicksAndTestsTheMicrophoneAndDownloadsAnAudioModel() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_DICTATION);
        assertEquals(3, wizard.microphoneChoice().getItemCount()); // system default + two devices
        assertTrue("offered while no model understands audio", wizard.audioDownloadButton().isVisible());

        wizard.microphoneChoice().setSelectedItem("USB Headset");
        wizard.micTestButton().doClick();
        assertEquals("USB Headset", fake.testedMicrophone);
        assertTrue(wizard.micStatusLabel().getText().contains("works"));

        wizard.audioDownloadButton().doClick();
        assertEquals(Collections.singletonList(SetupActions.RECOMMENDED_AUDIO_MODEL), fake.pulled);
        assertFalse(wizard.audioDownloadButton().isVisible());
        assertEquals("USB Headset", wizard.draft().microphone());
        assertEquals(SetupActions.RECOMMENDED_AUDIO_MODEL, wizard.draft().audioModel());
    }

    @Test
    public void dictationKeepsTheSavedChoiceUntilTheStepIsOpenedAndSkippingKeepsIt() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = new SetupWizardPanel(SetupDraft.builder().ollamaBaseUrl("http://localhost:11434")
                .dictation("Laptop microphone", "").build(), fake, ComicPalette.defaultPalette());
        assertEquals("Laptop microphone", wizard.draft().microphone());
        wizard.jumpTo(SetupWizardPanel.STEP_DICTATION);
        assertEquals("Laptop microphone", wizard.microphoneChoice().getSelectedItem());
        assertEquals(SetupWizardPanel.AUDIO_AUTOMATIC, wizard.audioModelChoice().getSelectedItem());
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertEquals("Laptop microphone", fake.finished.microphone());
        assertEquals("", fake.finished.audioModel());
    }

    @Test
    public void theSavedAudioModelStaysWhileTheAudioModelsAreStillLoading() {
        Fake fake = new Fake();
        fake.holdAudio = true;
        SetupWizardPanel wizard = new SetupWizardPanel(SetupDraft.builder().ollamaBaseUrl("http://localhost:11434")
                .dictation("", SetupActions.RECOMMENDED_AUDIO_MODEL).build(), fake, ComicPalette.defaultPalette());
        wizard.jumpTo(SetupWizardPanel.STEP_DICTATION);
        assertNotNull("the audio models are still loading", fake.pendingAudio);

        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);

        assertEquals(SetupActions.RECOMMENDED_AUDIO_MODEL, fake.finished.audioModel());
    }

    @Test
    public void anotherRuntimeReloadsTheAudioModels() {
        Fake fake = new Fake();
        fake.audioModels.add(SetupActions.RECOMMENDED_AUDIO_MODEL);
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_DICTATION);
        assertFalse(wizard.audioDownloadButton().isVisible());

        fake.audioModels.clear(); // the other server has no audio model
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        wizard.runtimeTestButton().doClick();
        wizard.jumpTo(SetupWizardPanel.STEP_DICTATION);

        assertTrue("the download is offered for the new runtime", wizard.audioDownloadButton().isVisible());
    }

    @Test
    public void aChosenButNotInstalledRerankerCanBeInstalled() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = new SetupWizardPanel(SetupDraft.builder().ollamaBaseUrl("http://localhost:11434")
                .rerankerModel("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest").build(), fake,
                ComicPalette.defaultPalette());
        wizard.jumpTo(SetupWizardPanel.STEP_KNOWLEDGE);
        assertTrue("a local reranker that is only chosen must be installable",
                wizard.rerankerInstallButton().isVisible());
    }

    @Test
    public void theCheckPageInstallsMissingLocalModelsAndChecksAgain() {
        Fake fake = new Fake();
        fake.checks = Arrays.asList(SetupCheck.ok("AI runtime", "Ollama 0.9.0"),
                SetupCheck.failed("Reranker", "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest is not installed"),
                SetupCheck.failed("Sentence model (German)", "apache-opennlp/sentence-de is not installed"));
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertTrue(wizard.checkInstallMissingButton().isVisible());
        int checksBefore = fake.finishes;
        fake.checks = Collections.singletonList(SetupCheck.ok("AI runtime", "Ollama 0.9.0"));

        wizard.checkInstallMissingButton().doClick();

        assertEquals(1, fake.rerankerInstalls);
        assertEquals(1, fake.sentenceInstalls);
        assertEquals("checked again after the installation", checksBefore + 1, fake.finishes);
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        assertFalse(wizard.checkInstallMissingButton().isVisible());
    }

    @Test
    public void theRequiredRerankerIsInstalledAndSelectedInTheKnowledgeStep() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        wizard.jumpTo(SetupWizardPanel.STEP_KNOWLEDGE);
        assertTrue(wizard.rerankerInstallButton().isVisible());

        wizard.rerankerInstallButton().doClick();

        assertEquals(1, fake.rerankerInstalls);
        assertEquals("ms-marco-MiniLM-L6-v2", wizard.draft().rerankerModel());
    }

    @Test
    public void pluginSectionsAppearInTheResearchStepAndAreSavedWithTheSetup() {
        Fake fake = new Fake();
        final int[] saved = {0};
        final javax.swing.JLabel section = new javax.swing.JLabel("search browser");
        fake.contributions.add(new com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution() {
            @Override
            public String getDisplayName() {
                return "Web search";
            }

            @Override
            public com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage createSetupPage(
                    com.aresstack.askai.plugin.api.agent.setup.AgentSetupContext context) {
                return new com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage() {
                    @Override
                    public javax.swing.JComponent getComponent() {
                        return section;
                    }

                    @Override
                    public void save() {
                        saved[0]++;
                    }
                };
            }
        });
        SetupWizardPanel wizard = wizard(fake);
        assertNull("built when the research step opens", section.getParent());

        wizard.jumpTo(SetupWizardPanel.STEP_KNOWLEDGE);
        assertNotNull(section.getParent());

        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertTrue("saved with the final check", saved[0] > 0);
    }

    @Test
    public void skipInTheLastStepFinishes() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);

        wizard.skipButton().doClick();

        assertTrue(finished[0]);
        assertNotNull(fake.finished);
    }

    @Test
    public void theFirstFinishPlaysTheHandOverBeforeClosing() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        wizard.setHandoff(true);
        wizard.setReadingPace(0);
        final boolean[] finished = {false};
        wizard.setOnFinished(() -> finished[0] = true);
        walkToEnd(wizard);
        wizard.skipButton().doClick();
        assertTrue(finished[0]);
        assertEquals(WizardGuide.handoff(WizardGuide.handoffLines() - 1).text,
                wizard.mascotOf(SetupWizardPanel.STEP_CHECK).spokenText());
    }

    @Test
    public void aLaterCallGreetsAsAReunion() {
        SetupWizardPanel wizard = wizard(new Fake());
        wizard.welcomeBack();
        assertTrue(wizard.isWitchAwake());
        assertEquals(WizardGuide.WELCOME_BACK, wizard.mascotOf(SetupWizardPanel.STEP_NETWORK).spokenText());
    }

    @Test
    public void witchsMenuOffersTheFixedAnswersAndAFreeQuestionOnlyWithAChatModel() {
        Fake fake = new Fake();
        SetupWizardPanel wizard = wizard(fake);
        assertFalse(wizard.canAskWitch());
        assertEquals(4, wizard.witchMenu().getComponentCount());
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        assertTrue(wizard.canAskWitch());
        wizard.askWitch("What is an embedding?");
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

    private static void walkToEnd(SetupWizardPanel wizard) {
        wizard.jumpTo(SetupWizardPanel.STEP_RUNTIME);
        wizard.jumpTo(SetupWizardPanel.STEP_VOICE);
        wizard.jumpTo(SetupWizardPanel.STEP_CHECK);
        assertTrue(wizard.isDone(SetupWizardPanel.STEP_CHECK));
        assertEquals(SetupWizardPanel.STEP_CHECK, wizard.step());
        assertEquals("the success picture once every check passed", SetupWizardPanel.CHECK_SUCCESS_MASCOT,
                wizard.mascotOf(SetupWizardPanel.STEP_CHECK).art().resource());
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
        boolean thorstenInstalled;
        SetupDraft voiceDraft;
        List<String> pulled = new ArrayList<String>();
        int rerankerInstalls;
        int finishes;
        List<String> audioModels = new ArrayList<String>();
        Boolean micHeard = Boolean.TRUE;
        String testedMicrophone;
        boolean holdAudio;
        Consumer<List<String>> pendingAudio;
        List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> contributions =
                new ArrayList<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution>();
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
                    "nomic-embed-text"), rerankerInstalls > 0 ? Collections.singletonList("ms-marco-MiniLM-L6-v2")
                    : Collections.<String>emptyList(), sentenceInstalled
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
            done.accept(Arrays.asList(new VoiceOffer("de_DE-thorsten-medium", "Thorsten (German)", "de", thorstenInstalled),
                    new VoiceOffer("en_US-amy-medium", "Amy (English)", "en", true)));
        }

        @Override
        public void installVoice(SetupDraft draft, String voiceId, Progress progress,
                                 BiConsumer<String, String> done) {
            voiceDraft = draft;
            progress.update("Downloading", 50, 100);
            thorstenInstalled = true;
            done.accept("installed", null);
        }

        @Override
        public void pullModel(SetupDraft draft, String model, Progress progress, BiConsumer<String, String> done) {
            pulled.add(model);
            if (model.equals(SetupActions.RECOMMENDED_AUDIO_MODEL)) {
                audioModels.add(model);
            }
            progress.update("pulling", 1, 2);
            done.accept(AskAiSetupActions.canonicalOllamaName(model), null);
        }

        @Override
        public void installReranker(SetupDraft draft, Progress progress, BiConsumer<String, String> done) {
            rerankerInstalls++;
            done.accept("ms-marco-MiniLM-L6-v2", null);
        }

        @Override
        public List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> pluginSetup() {
            return contributions;
        }

        @Override
        public com.aresstack.askai.plugin.api.agent.setup.AgentSetupContext pluginSetupContext() {
            return () -> null;
        }

        @Override
        public void saveVoices(SetupDraft draft, Consumer<String> done) {
            savedVoices = draft;
            done.accept(null);
        }

        @Override
        public List<String> microphones() {
            return Arrays.asList("USB Headset", "Laptop microphone");
        }

        @Override
        public void testMicrophone(String device, java.util.function.IntConsumer level,
                                   BiConsumer<Boolean, String> done) {
            testedMicrophone = device;
            level.accept(42);
            done.accept(micHeard, null);
        }

        @Override
        public void loadAudioModels(SetupDraft draft, Consumer<List<String>> done) {
            if (holdAudio) {
                pendingAudio = done;
                return;
            }
            done.accept(new ArrayList<String>(audioModels));
        }

        @Override
        public void finish(SetupDraft draft, Consumer<List<SetupCheck>> done) {
            finished = draft;
            finishes++;
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

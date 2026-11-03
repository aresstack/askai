package com.aresstack.askai.ui.setup;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.reranker.RerankerModelCatalog;
import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.client.OllamaPullProgress;
import com.aresstack.askai.config.AiModelSelections;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.hf.HuggingFaceClient;
import com.aresstack.askai.hf.HuggingFaceConnectionTestResult;
import com.aresstack.askai.localmodels.ApacheOpenNlpModelCatalogProvider;
import com.aresstack.askai.localmodels.HttpNlpDownloadClient;
import com.aresstack.askai.localmodels.LocalModelInstaller;
import com.aresstack.askai.localmodels.LocalModelRuntimeManager;
import com.aresstack.askai.localmodels.LocalNlpModelCatalog;
import com.aresstack.askai.localmodels.LocalNlpModelStore;
import com.aresstack.askai.localmodels.NlpModelCatalogEntry;
import com.aresstack.askai.localmodels.NlpModelInstaller;
import com.aresstack.askai.net.ConfiguredConnections;
import com.aresstack.askai.net.CertificateTrustConfiguration;
import com.aresstack.askai.net.ProxyConfiguration;
import com.aresstack.askai.service.OllamaService;
import com.aresstack.askai.service.VirtualOllamaContainerService;
import com.aresstack.askai.tts.PiperInstaller;
import com.aresstack.askai.tts.PiperReadAloudService;
import com.aresstack.askai.tts.PiperTtsStore;
import com.aresstack.askai.tts.PiperVoice;
import com.aresstack.askai.tts.PiperVoiceCatalog;
import com.aresstack.askai.tts.TextToSpeechSettings;
import com.aresstack.askai.tts.TtsSettingsStore;
import com.aresstack.askai.ui.BrowserLauncher;
import com.aresstack.askai.ui.ProxyPanel;
import com.aresstack.comiccontrols.control.ComicButton;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The production adapter of {@link SetupActions}: maps the wizard onto AskAI's existing use cases – the
 * configuration repository and {@link AskAiModel} (Ollama address, central model selections), the virtual container
 * service (Ollama plus AskAI's local runtime), the reranker and OpenNLP catalogs, the Piper voice store and the
 * network settings. It adds no logic of its own beyond the wizard's checks; every write goes through the same
 * methods the settings pages use. Slow work runs on one background thread, every result is posted to the EDT.
 */
public final class AskAiSetupActions implements SetupActions {

    /** Where the wizard's “Download Ollama…” leads. */
    static final String OLLAMA_DOWNLOAD_URL = "https://ollama.com/download";
    /** How long the microphone test listens. */
    private static final long MIC_TEST_MILLIS = 4000;
    /** The language Witch speaks in (his lines are English). */
    private static final String WITCH_LANGUAGE = "en";
    private static final String[] SENTENCE_LANGUAGES = {"de", "en"};

    private final AskAiModel model;
    private final AppConfigurationRepository configuration;
    private final VirtualOllamaContainerService ollama;
    private final LocalModelRuntimeManager localRuntime;
    private final RerankerModelCatalog rerankers;
    private final LocalNlpModelStore nlpStore;
    private final Runnable onSaved;
    private final TtsSettingsStore ttsSettings = new TtsSettingsStore();
    private final PiperTtsStore piperStore = new PiperTtsStore();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "askai-setup-wizard");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * Long downloads (voice, sentence models, reranker) run here, next to {@link #worker}: a slow or stalled
     * download must never queue the readiness checks behind it, or Finish would wait for it.
     */
    private final ExecutorService installs = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "askai-setup-wizard-install");
        thread.setDaemon(true);
        return thread;
    });

    /** The Ollama address before the wizard tried another one (restored on cancel); {@code null}: untouched. */
    private String originalOllamaUrl;
    /** The saved network settings before the model browser needed the draft's; restored on cancel. */
    private AppConfiguration originalNetwork;
    /** Counts wizard runs ended by a cancel; a runtime result from an earlier run must not touch the model. */
    private int cancellations;
    private PiperReadAloudService witchService;
    /** Speaks Witch's lines: Piper synthesis and playback block until the line is over, never on the EDT. */
    private final ExecutorService speaker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "askai-setup-wizard-voice");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param localRuntime nullable: no local runtime staged
     * @param rerankers    nullable: no reranker catalog (no local runtime)
     * @param onSaved      after the wizard wrote the settings (e.g. refresh the model catalogs); nullable
     */
    public AskAiSetupActions(AskAiModel model, AppConfigurationRepository configuration,
                             VirtualOllamaContainerService ollama, LocalModelRuntimeManager localRuntime,
                             RerankerModelCatalog rerankers, LocalNlpModelStore nlpStore, Runnable onSaved) {
        if (model == null || configuration == null || ollama == null || nlpStore == null) {
            throw new IllegalArgumentException("model, configuration, ollama and nlpStore must not be null");
        }
        this.model = model;
        this.configuration = configuration;
        this.ollama = ollama;
        this.localRuntime = localRuntime;
        this.rerankers = rerankers;
        this.nlpStore = nlpStore;
        this.onSaved = onSaved;
    }

    private java.util.function.Supplier<List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution>>
            pluginSetup;
    private com.aresstack.askai.plugin.api.service.WorkspaceStateStore hostState;

    private java.util.function.Supplier<? extends Component> modelBrowser;

    /** The model browser the wizard opens for "Install…" (a fresh one each time, so it never leaves the app). */
    public void setModelBrowser(java.util.function.Supplier<? extends Component> modelBrowser) {
        this.modelBrowser = modelBrowser;
    }

    @Override
    public void openModelBrowser(Component parent, SetupDraft draft) {
        if (modelBrowser == null) {
            return;
        }
        useDraftNetworkForBrowser(draft);
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(owner, "Install a model", JDialog.ModalityType.APPLICATION_MODAL);
        ComicButton close = new ComicButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(close);
        dialog.getContentPane().add(modelBrowser.get(), BorderLayout.CENTER);
        dialog.getContentPane().add(south, BorderLayout.SOUTH);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setSize(new Dimension(1100, 760));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true); // blocks until closed; downloads started there keep their own progress
    }

    /** Connects the plugins' setup sections (search browser, search provider) and the host state they write to. */
    public void setPluginSetup(
            java.util.function.Supplier<List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution>>
                    contributions,
            com.aresstack.askai.plugin.api.service.WorkspaceStateStore hostState) {
        this.pluginSetup = contributions;
        this.hostState = hostState;
    }

    @Override
    public List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> pluginSetup() {
        if (pluginSetup == null || hostState == null) {
            return Collections.emptyList();
        }
        try {
            List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> all = pluginSetup.get();
            return all == null ? Collections.<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution>
                    emptyList() : all;
        } catch (RuntimeException unavailable) {
            return Collections.emptyList();
        }
    }

    @Override
    public com.aresstack.askai.plugin.api.agent.setup.AgentSetupContext pluginSetupContext() {
        final com.aresstack.askai.plugin.api.service.WorkspaceStateStore store = hostState;
        return store == null ? null : () -> store;
    }

    /** The wizard's starting point: AskAI's settings as they are now. */
    public SetupDraft currentDraft() {
        AppConfiguration stored = configuration.load();
        AiModelSelections selections = model.getAiModelSelections();
        TextToSpeechSettings tts = ttsSettings.load();
        return withNetwork(SetupDraft.builder(), stored)
                .ollamaBaseUrl(model.getOllamaBaseUrl())
                .chatModel(selections.getMainModel())
                .embeddingModel(selections.getEmbeddingsModel())
                .rerankerModel(selections.getRerankerModel())
                .nlpSentence(selections.getNlp().getModelId(NlpCapability.SENTENCE_DETECTION, "de"),
                        selections.getNlp().getModelId(NlpCapability.SENTENCE_DETECTION, "en"))
                .voices(piperVoiceOf(tts, "de"), piperVoiceOf(tts, "en"))
                .dictation(stt().getMicrophoneDeviceId(), stt().isAudioModelAutomatic() ? "" : stt().getModelName())
                .build();
    }

    private com.aresstack.askai.stt.SpeechToTextConfiguration stt() {
        com.aresstack.askai.stt.SpeechToTextConfiguration stt = model.getSpeechToTextConfiguration();
        return stt == null ? com.aresstack.askai.stt.SpeechToTextConfiguration.defaults() : stt;
    }

    private static SetupDraft.Builder withNetwork(SetupDraft.Builder builder, AppConfiguration stored) {
        ProxyConfiguration proxy = stored.getProxyConfiguration();
        CertificateTrustConfiguration trust = stored.getCertificateTrustConfiguration();
        return builder.proxy(proxy.getModeName(), proxy.getManualProxyHost(), proxy.getManualProxyPort())
                .trustWindowsStores(trust.isUseWindowsRoot() || trust.isUseWindowsCaStores());
    }

    private static String piperVoiceOf(TextToSpeechSettings settings, String language) {
        TextToSpeechSettings.Selection selection = settings.selectionFor(language);
        return selection.getEngine() == TextToSpeechSettings.Engine.PIPER ? selection.getVoiceId() : "";
    }

    // ------------------------------------------------------------------ network & security

    @Override
    public void testNetwork(final SetupDraft draft, final BiConsumer<String, String> done) {
        final AppConfiguration stored = configuration.load();
        background(() -> {
            try {
                ProxyConfiguration proxy = proxyOf(draft, stored);
                proxy.validateForUse();
                HuggingFaceConnectionTestResult result = new HuggingFaceClient(proxy, trustOf(draft, stored),
                        stored.getHttpClientConfiguration(), stored.getHuggingFaceToken()).testConnection();
                if (result.isSuccess()) {
                    deliver(() -> done.accept(result.shortSummary(), null));
                } else {
                    deliver(() -> done.accept(null, result.shortSummary()));
                }
            } catch (Exception e) {
                deliver(() -> done.accept(null, message(e)));
            }
        });
    }

    /** The draft's proxy; PAC script and test address stay as stored (they are edited in the network settings). */
    static ProxyConfiguration proxyOf(SetupDraft draft, AppConfiguration stored) {
        ProxyConfiguration current = stored.getProxyConfiguration();
        boolean sameMode = current.getModeName().equals(draft.proxyMode());
        return new ProxyConfiguration(draft.proxyMode().isEmpty() ? current.getModeName() : draft.proxyMode(),
                current.getTestUrl(), sameMode ? current.getPacUrlDiscoveryScript() : "",
                current.getPacUrl(), draft.proxyHost(), draft.proxyPort());
    }

    /** The draft's Windows trust; the JVM default trust stays as stored. */
    static CertificateTrustConfiguration trustOf(SetupDraft draft, AppConfiguration stored) {
        CertificateTrustConfiguration current = stored.getCertificateTrustConfiguration();
        boolean unchanged = draft.trustWindowsStores() == (current.isUseWindowsRoot()
                || current.isUseWindowsCaStores());
        return unchanged ? current : new CertificateTrustConfiguration(current.isUseJvmDefault(),
                draft.trustWindowsStores(), draft.trustWindowsStores());
    }

    @Override
    public SetupDraft editNetwork(Component parent, SetupDraft draft) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(owner, "Network settings", JDialog.ModalityType.APPLICATION_MODAL);
        JScrollPane scroll = new JScrollPane(new ProxyPanel(configuration));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        ComicButton close = new ComicButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(close);
        dialog.getContentPane().add(scroll, BorderLayout.CENTER);
        dialog.getContentPane().add(south, BorderLayout.SOUTH);
        dialog.setSize(new Dimension(760, 620));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true); // blocks until closed; the panel saves with its own “Save”
        return withNetwork(draft.toBuilder(), configuration.load()).build();
    }

    /**
     * The model browser's Hugging Face client and installers read the saved network settings, so the wizard's
     * proxy and certificate choice is saved while it is open; "Cancel" restores what was saved before.
     */
    private void useDraftNetworkForBrowser(SetupDraft draft) {
        if (draft == null) {
            return;
        }
        try {
            AppConfiguration stored = configuration.load();
            if (originalNetwork == null) {
                originalNetwork = stored;
            }
            configuration.save(stored.withNetworkConfiguration(proxyOf(draft, stored), trustOf(draft, stored),
                    stored.getHttpClientConfiguration()));
        } catch (RuntimeException e) {
            // the browser still opens with the saved settings
        }
    }

    // ------------------------------------------------------------------ AI runtime

    @Override
    public void checkRuntime(final SetupDraft draft, final Consumer<RuntimeStatus> done) {
        final String url = draft.ollamaBaseUrl().isEmpty() ? model.getOllamaBaseUrl() : draft.ollamaBaseUrl();
        final int run = cancellations;
        background(() -> {
            String version = null;
            String problem = "";
            try {
                version = new AskAiOllamaClient(url).getVersion();
            } catch (Exception e) {
                problem = message(e);
            }
            boolean local = localRuntime != null && localRuntime.isAvailable() && localRuntime.hasInstalledModels();
            final RuntimeStatus status = new RuntimeStatus(version, problem, local);
            final boolean apply = version != null;
            deliver(() -> {
                if (run != cancellations) {
                    return; // cancelled meanwhile: neither the model nor the closed wizard may change
                }
                if (apply) {
                    useOllamaUrl(url); // the model lists of the next steps come from the address that answered
                }
                done.accept(status);
            });
        });
    }

    /** Points the in-memory model at the tested address; restored by {@link #cancelled()} unless saved. */
    private void useOllamaUrl(String url) {
        if (url.equals(model.getOllamaBaseUrl())) {
            return;
        }
        if (originalOllamaUrl == null) {
            originalOllamaUrl = model.getOllamaBaseUrl();
        }
        model.setOllamaBaseUrl(url);
    }

    @Override
    public void openRuntimeDownload() {
        BrowserLauncher.open(OLLAMA_DOWNLOAD_URL);
    }

    @Override
    public void cancelled() {
        cancellations++;
        if (originalOllamaUrl != null) {
            model.setOllamaBaseUrl(originalOllamaUrl);
            originalOllamaUrl = null;
        }
        if (originalNetwork != null) {
            try {
                configuration.save(configuration.load().withNetworkConfiguration(
                        originalNetwork.getProxyConfiguration(), originalNetwork.getCertificateTrustConfiguration(),
                        originalNetwork.getHttpClientConfiguration()));
            } catch (RuntimeException e) {
                // nothing more to restore
            }
            originalNetwork = null;
        }
        stopWitch();
    }

    // ------------------------------------------------------------------ models

    @Override
    public void loadModels(SetupDraft draft, final Consumer<SetupModels> done) {
        background(() -> {
            List<String> problems = new ArrayList<String>();
            List<String> chat = list(problems, "chat models", ollama::loadChatModelNamesNow);
            List<String> embeddings = list(problems, "embedding models", ollama::loadEmbeddingModelNamesNow);
            List<String> rerank = rerankers == null ? Collections.<String>emptyList()
                    : list(problems, "rerankers", rerankers::listInstalledRerankModels);
            LocalNlpModelCatalog nlp = new LocalNlpModelCatalog(nlpStore);
            List<String> german = list(problems, "sentence models",
                    () -> nlp.listInstalledModels(NlpCapability.SENTENCE_DETECTION, "de"));
            List<String> english = list(problems, "sentence models",
                    () -> nlp.listInstalledModels(NlpCapability.SENTENCE_DETECTION, "en"));
            final SetupModels models = new SetupModels(chat, embeddings, rerank, german, english,
                    problems.isEmpty() ? "" : String.join("; ", problems));
            deliver(() -> done.accept(models));
        });
    }

    private interface Lister {
        List<String> list() throws Exception;
    }

    private static List<String> list(List<String> problems, String what, Lister lister) {
        try {
            List<String> names = lister.list();
            return names == null ? Collections.<String>emptyList() : names;
        } catch (Exception e) {
            problems.add("could not read the " + what + " (" + message(e) + ")");
            return Collections.emptyList();
        }
    }

    @Override
    public void pullModel(SetupDraft draft, final String model, final Progress progress,
                          final BiConsumer<String, String> done) {
        if (model == null || model.trim().isEmpty()) {
            done.accept(null, "no model name");
            return;
        }
        final String name = canonicalOllamaName(model.trim());
        ollama.pullModel(name, new OllamaService.PullListener() {
            @Override
            public void onProgress(final OllamaPullProgress p) {
                deliver(() -> progress.update(p.getStatus(), p.getCompleted(), p.getTotal()));
            }

            @Override
            public void onComplete(String message) {
                deliver(() -> done.accept(name, null));
            }

            @Override
            public void onError(final Exception ex) {
                deliver(() -> done.accept(null, message(ex)));
            }
        });
    }

    /**
     * The name Ollama lists a pulled model under: without a tag it is stored as {@code :latest}, and AskAI compares
     * configured names literally against {@code /api/tags} (embedding digest lookup), so the wizard keeps that name.
     */
    static String canonicalOllamaName(String model) {
        int slash = model.lastIndexOf('/');
        return model.indexOf(':', slash + 1) >= 0 ? model : model + ":latest";
    }

    @Override
    public void installReranker(final SetupDraft draft, final Progress progress,
                                final BiConsumer<String, String> done) {
        if (localRuntime == null) {
            done.accept(null, "AskAI's local model runtime is not available in this installation");
            return;
        }
        final AppConfiguration stored = configuration.load();
        install(() -> {
            try {
                HuggingFaceClient client = new HuggingFaceClient(proxyOf(draft, stored), trustOf(draft, stored),
                        stored.getHttpClientConfiguration(), stored.getHuggingFaceToken());
                final String name = new LocalModelInstaller(client, localRuntime).install(RECOMMENDED_RERANKER,
                        new LocalModelInstaller.Listener() {
                            @Override
                            public void onStep(final String step) {
                                deliver(() -> progress.update(step, 0, 0));
                            }

                            @Override
                            public void onDownloadProgress(final String file, final long completed,
                                                           final long total) {
                                deliver(() -> progress.update(file, completed, total));
                            }
                        });
                deliver(() -> done.accept(name, null));
            } catch (Exception e) {
                deliver(() -> done.accept(null, message(e)));
            }
        });
    }

    @Override
    public void embeddingDimension(SetupDraft draft, final BiConsumer<Integer, String> done) {
        if (draft.embeddingModel().isEmpty()) {
            done.accept(null, "no embedding model chosen");
            return;
        }
        ollama.embed(draft.embeddingModel(), "AskAI setup: dimension probe", new OllamaService.EmbedListener() {
            @Override
            public void onEmbedding(int vectorCount, final int dimensions) {
                deliver(() -> done.accept(dimensions > 0 ? Integer.valueOf(dimensions) : null,
                        dimensions > 0 ? null : "empty vector"));
            }

            @Override
            public void onError(final Exception ex) {
                deliver(() -> done.accept(null, message(ex)));
            }
        });
    }

    @Override
    public void installSentenceModels(final String language, final boolean reinstall,
                                      final Consumer<String> progress, final BiConsumer<String, String> done) {
        install(() -> {
            NlpModelInstaller installer = new NlpModelInstaller(new HttpNlpDownloadClient(), nlpStore);
            int installed = 0;
            try {
                for (NlpModelCatalogEntry entry : sentenceModels(language, reinstall)) {
                    final String name = entry.getModelId();
                    deliver(() -> progress.accept("Downloading " + name + " …"));
                    if (installer.install(entry, reinstall) == NlpModelInstaller.Outcome.INSTALLED) {
                        installed++;
                    }
                }
            } catch (Exception e) {
                deliver(() -> done.accept(null, message(e)));
                return;
            }
            final String message = installed == 0 ? "All sentence models were installed already."
                    : installed == 1 ? "1 sentence model installed." : installed + " sentence models installed.";
            deliver(() -> done.accept(message, null));
        });
    }

    /** The curated sentence models for German and English that are not installed yet. */
    List<NlpModelCatalogEntry> missingSentenceModels() {
        return sentenceModels(null, false);
    }

    /** The curated sentence models of {@code language} (both if {@code null}); all of them, or the missing ones. */
    List<NlpModelCatalogEntry> sentenceModels(String language, boolean all) {
        List<NlpModelCatalogEntry> missing = new ArrayList<NlpModelCatalogEntry>();
        for (NlpModelCatalogEntry entry : new ApacheOpenNlpModelCatalogProvider().availableModels()) {
            if (entry.getCapability() == NlpCapability.SENTENCE_DETECTION
                    && java.util.Arrays.asList(SENTENCE_LANGUAGES).contains(entry.getLanguageCode())
                    && (language == null || language.equals(entry.getLanguageCode()))
                    && (all || nlpStore.find(entry.getModelId()) == null)) {
                missing.add(entry);
            }
        }
        return missing;
    }

    // ------------------------------------------------------------------ voice

    @Override
    public void loadVoices(final Consumer<List<VoiceOffer>> done) {
        background(() -> {
            List<VoiceOffer> offers = new ArrayList<VoiceOffer>();
            try {
                for (PiperVoice voice : PiperVoiceCatalog.curated()) {
                    offers.add(new VoiceOffer(voice.getId(), voice.getDisplayName(), voice.getLanguageCode(),
                            piperStore.isVoiceInstalled(voice)));
                }
            } catch (RuntimeException e) {
                deliver(() -> done.accept(null));
                return;
            }
            deliver(() -> done.accept(offers));
        });
    }

    @Override
    public void installVoice(final SetupDraft draft, final String voiceId, final boolean reinstall,
                             final Progress progress,
                             final BiConsumer<String, String> done) {
        final PiperVoice voice = PiperVoiceCatalog.findById(voiceId);
        if (voice == null) {
            done.accept(null, "unknown voice " + voiceId);
            return;
        }
        install(() -> {
            try {
                AppConfiguration stored = configuration.load();
                ConfiguredConnections network = new ConfiguredConnections(proxyOf(draft, stored),
                        trustOf(draft, stored), stored.getHttpClientConfiguration());
                PiperInstaller installer = new PiperInstaller(piperStore, ttsSettings, network::open);
                PiperInstaller.Progress report =
                        (stage, bytesDone, bytesTotal) -> deliver(() -> progress.update(stage, bytesDone, bytesTotal));
                if (reinstall) {
                    installer.reinstall(voice, report);
                } else {
                    installer.install(voice, report);
                }
                deliver(() -> done.accept(voice.getDisplayName() + " installed.", null));
            } catch (Exception e) {
                deliver(() -> done.accept(null, message(e)));
            }
        });
    }

    @Override
    public void saveVoices(SetupDraft draft, Consumer<String> done) {
        try {
            TextToSpeechSettings settings = ttsSettings.load();
            settings = withVoice(settings, "de", draft.voiceGerman());
            settings = withVoice(settings, "en", draft.voiceEnglish());
            ttsSettings.save(settings);
            done.accept(null);
        } catch (Exception e) {
            done.accept(message(e));
        }
    }

    /**
     * Writes a picked Piper voice. "Windows voice" only replaces a Piper voice that was chosen before; otherwise the
     * language keeps its stored (default) selection, so the wizard never overrides the default engine by itself.
     */
    private static TextToSpeechSettings withVoice(TextToSpeechSettings settings, String language, String voiceId) {
        if (voiceId.isEmpty()) {
            return piperVoiceOf(settings, language).isEmpty()
                    ? settings
                    : settings.withSelection(language, TextToSpeechSettings.Engine.WINDOWS, "");
        }
        return settings.withSelection(language, TextToSpeechSettings.Engine.PIPER, voiceId);
    }

    // ------------------------------------------------------------------ finish

    @Override
    public void finish(final SetupDraft draft, final Consumer<List<SetupCheck>> done) {
        try {
            save(draft);
        } catch (RuntimeException e) {
            done.accept(Collections.singletonList(SetupCheck.failed(SetupWizardPanel.SAVING_CHECK, message(e))));
            return;
        }
        background(() -> {
            final List<SetupCheck> checks = check(draft);
            deliver(() -> {
                if (onSaved != null) {
                    onSaved.run();
                }
                done.accept(checks);
            });
        });
    }

    /** Writes the draft through the same paths the settings pages use. */
    private void save(SetupDraft draft) {
        originalNetwork = null; // the draft's network settings are the saved ones from now on
        AppConfiguration stored = configuration.load();
        configuration.save(stored.withNetworkConfiguration(proxyOf(draft, stored), trustOf(draft, stored),
                stored.getHttpClientConfiguration()));
        if (!draft.ollamaBaseUrl().isEmpty()) {
            model.setOllamaBaseUrl(draft.ollamaBaseUrl());
        }
        com.aresstack.askai.stt.SpeechToTextConfiguration stt = stt().withMicrophoneDeviceId(draft.microphone());
        model.setSpeechToTextConfiguration(draft.audioModel().isEmpty()
                ? stt.withAudioModelAutomatic(true).withModelName("")
                : stt.withAudioModelAutomatic(false).withModelName(draft.audioModel()));
        model.saveSettings(); // the Ollama address and the dictation; every other stored value is kept
        originalOllamaUrl = null; // saved: a later cancel no longer restores the old address
        model.persistMainModel(draft.chatModel());
        model.persistRerankerAndEmbeddingsModels(draft.rerankerModel(), draft.embeddingModel());
        model.persistNlpSentenceModels(draft.nlpSentenceGerman(), draft.nlpSentenceEnglish());
    }

    /** Checks every role once for real (off the EDT). Optional roles left empty count as fine. */
    private List<SetupCheck> check(SetupDraft draft) {
        List<SetupCheck> checks = new ArrayList<SetupCheck>();
        String version = null;
        String problem = "";
        try {
            version = new AskAiOllamaClient(model.getOllamaBaseUrl()).getVersion();
        } catch (Exception e) {
            problem = message(e);
        }
        boolean local = localRuntime != null && localRuntime.isAvailable() && localRuntime.hasInstalledModels();
        if (version != null || local) {
            checks.add(SetupCheck.ok("AI runtime", (version != null ? "Ollama " + version : "")
                    + (version != null && local ? " and " : "") + (local ? "AskAI local runtime" : "")));
        } else {
            checks.add(SetupCheck.failed("AI runtime", problem.isEmpty() ? "no runtime answers" : problem));
        }

        List<String> chat = quietly(ollama::loadChatModelNamesNow);
        if (draft.chatModel().isEmpty()) {
            checks.add(SetupCheck.failed("Chat model", "no chat model chosen"));
        } else if (chat.contains(draft.chatModel())) {
            checks.add(SetupCheck.ok("Chat model", draft.chatModel()));
        } else {
            checks.add(SetupCheck.failed("Chat model", draft.chatModel() + " is not installed"));
        }

        if (draft.embeddingModel().isEmpty()) {
            checks.add(SetupCheck.ok("Embedding model", "none chosen (optional; the research agent falls back "
                    + "to simpler matching)"));
        } else {
            checks.add(embeddingCheck(draft.embeddingModel()));
        }

        if (draft.rerankerModel().isEmpty()) {
            checks.add(SetupCheck.failed("Reranker", "none chosen – the web research needs one; install the "
                    + "recommended reranker in the Knowledge step"));
        } else if (rerankers != null && quietly(rerankers::listInstalledRerankModels)
                .contains(draft.rerankerModel())) {
            checks.add(SetupCheck.ok("Reranker", draft.rerankerModel()));
        } else {
            checks.add(SetupCheck.failed("Reranker", draft.rerankerModel() + " is not installed"));
        }

        checks.add(sentenceCheck("Sentence model (German)", draft.nlpSentenceGerman()));
        checks.add(sentenceCheck("Sentence model (English)", draft.nlpSentenceEnglish()));
        checks.add(dictationCheck(draft));
        return checks;
    }

    /** Dictation is optional: only a chosen audio model that is missing counts as a problem. */
    private SetupCheck dictationCheck(SetupDraft draft) {
        List<String> audio = audioModelsNow();
        String microphone = draft.microphone().isEmpty() ? "system microphone" : draft.microphone();
        if (draft.audioModel().isEmpty()) {
            return SetupCheck.ok("Dictation", audio.isEmpty()
                    ? "no audio model yet (optional; download one in the Dictation step)"
                    : microphone + ", " + audio.get(0) + " (automatic)");
        }
        return audio.contains(draft.audioModel())
                ? SetupCheck.ok("Dictation", microphone + ", " + draft.audioModel())
                : SetupCheck.failed("Dictation", draft.audioModel() + " is not installed or does not understand audio");
    }

    /** The installed models whose capabilities include audio (asked from the runtime, one by one). */
    private List<String> audioModelsNow() {
        List<String> audio = new ArrayList<String>();
        for (String name : quietly(ollama::loadChatModelNamesNow)) {
            try {
                if (com.aresstack.askai.stt.AudioCapability.isAudioCapable(
                        ollama.loadModelInfoNow(name).getCapabilities())) {
                    audio.add(name);
                }
            } catch (Exception unreadable) {
                // a model whose details cannot be read is not offered for dictation
            }
        }
        return audio;
    }

    @Override
    public void useNetwork(SetupDraft draft) {
        if (draft == null) {
            com.aresstack.askai.plugin.api.service.HostNetwork.override(null);
            return;
        }
        final AppConfiguration stored = configuration.load();
        final ConfiguredConnections connections = new ConfiguredConnections(proxyOf(draft, stored),
                trustOf(draft, stored), stored.getHttpClientConfiguration());
        com.aresstack.askai.plugin.api.service.HostNetwork.override(connections::open);
    }

    // ------------------------------------------------------------------ dictation

    @Override
    public List<String> microphones() {
        try {
            return com.aresstack.audio.infrastructure.AvailableAudioDevices.listCaptureDeviceNames();
        } catch (RuntimeException | LinkageError e) {
            return Collections.emptyList();
        }
    }

    @Override
    public void testMicrophone(final String device, final java.util.function.IntConsumer level,
                               final BiConsumer<Boolean, String> done) {
        install(() -> {
            java.io.File workDir = null;
            com.aresstack.askai.speech.MicrophoneRecorder.Session session = null;
            try {
                workDir = java.nio.file.Files.createTempDirectory("askai-mic-test").toFile();
                session = new com.aresstack.askai.speech.JavaSoundMicrophoneRecorder().start(device, workDir);
                long endAt = System.currentTimeMillis() + MIC_TEST_MILLIS;
                boolean signal = false;
                while (System.currentTimeMillis() < endAt) {
                    Thread.sleep(100);
                    com.aresstack.audio.dsp.AudioLevelMeter meter = session.getMeter();
                    final int percent = com.aresstack.askai.stt.DecibelLevelScale.percentFromPeak(
                            meter.consumeRecentPeak());
                    signal |= meter.getOverallRms() > 30 || meter.getPeak() > 500;
                    deliver(() -> level.accept(percent));
                }
                final boolean heard = signal;
                deliver(() -> done.accept(heard, null));
            } catch (Exception e) {
                deliver(() -> done.accept(null, message(e)));
            } finally {
                if (session != null) {
                    session.discard();
                }
                if (workDir != null) {
                    java.io.File[] left = workDir.listFiles();
                    if (left != null) {
                        for (java.io.File file : left) {
                            file.delete();
                        }
                    }
                    workDir.delete();
                }
            }
        });
    }

    @Override
    public void loadAudioModels(SetupDraft draft, final Consumer<List<String>> done) {
        background(() -> {
            final List<String> audio = audioModelsNow();
            deliver(() -> done.accept(audio));
        });
    }

    private SetupCheck embeddingCheck(String embeddingModel) {
        final int[] dimensions = {0};
        final String[] error = {null};
        final java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
        ollama.embed(embeddingModel, "AskAI setup: final check", new OllamaService.EmbedListener() {
            @Override
            public void onEmbedding(int vectorCount, int size) {
                dimensions[0] = size;
                answered.countDown();
            }

            @Override
            public void onError(Exception ex) {
                error[0] = message(ex);
                answered.countDown();
            }
        });
        try {
            if (!answered.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                return SetupCheck.failed("Embedding model", embeddingModel + " did not answer in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SetupCheck.failed("Embedding model", "check interrupted");
        }
        return dimensions[0] > 0
                ? SetupCheck.ok("Embedding model", embeddingModel + " (" + dimensions[0] + " dimensions)")
                : SetupCheck.failed("Embedding model", embeddingModel + ": "
                + (error[0] == null ? "empty vector" : error[0]));
    }

    private SetupCheck sentenceCheck(String label, String modelId) {
        if (modelId.isEmpty()) {
            return SetupCheck.ok(label, "none chosen (optional; sentences are split by rules)");
        }
        return nlpStore.find(modelId) != null ? SetupCheck.ok(label, modelId)
                : SetupCheck.failed(label, modelId + " is not installed");
    }

    private static List<String> quietly(Lister lister) {
        try {
            List<String> names = lister.list();
            return names == null ? Collections.<String>emptyList() : names;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------ Witch

    @Override
    public void ask(SetupDraft draft, String system, String question, final BiConsumer<String, String> done) {
        if (draft.chatModel().isEmpty()) {
            done.accept(null, "no chat model");
            return;
        }
        ollama.generate(draft.chatModel(), system + "\n\nQuestion: " + question,
                new OllamaService.ActionListener() {
                    @Override
                    public void onComplete(final String message) {
                        deliver(() -> done.accept(message, null));
                    }

                    @Override
                    public void onError(final Exception ex) {
                        deliver(() -> done.accept(null, message(ex)));
                    }
                });
    }

    /**
     * Witch speaks only with a local model voice chosen for English (like the Enterprise wizard, which only speaks
     * with a chosen TTS model); with the Windows default he stays in his speech bubbles.
     */
    @Override
    public WizardVoice witchVoice() {
        if (witchService == null) {
            witchService = new PiperReadAloudService(ttsSettings, piperStore);
        }
        final PiperReadAloudService service = witchService;
        return new WizardVoice() {
            @Override
            public boolean isAvailable() {
                return service.isModelVoiceActive(WITCH_LANGUAGE);
            }

            @Override
            public void speak(final String text) {
                service.stop(); // a new line replaces the one still being spoken
                speaker.execute(() -> service.speak(text, WITCH_LANGUAGE));
            }

            @Override
            public void stop() {
                service.stop();
            }
        };
    }

    @Override
    public void voices(SetupDraft draft, Consumer<SetupVoices> done) {
        WizardVoice voice = witchVoice();
        done.accept(new SetupVoices(voice, voice, SetupVoices.DEFAULT_ASSISTANT_NAME, null));
    }

    private void stopWitch() {
        if (witchService != null) {
            witchService.stop();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private void background(Runnable task) {
        worker.execute(task);
    }

    private void install(Runnable task) {
        installs.execute(task);
    }

    private static void deliver(Runnable result) {
        SwingUtilities.invokeLater(result);
    }

    private static String message(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage();
        return text == null || text.trim().isEmpty() ? root.getClass().getSimpleName() : text.trim();
    }
}

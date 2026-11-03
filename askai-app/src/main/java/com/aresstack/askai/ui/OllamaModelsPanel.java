package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.client.OllamaModelInfo;
import com.aresstack.askai.client.OllamaModelInfoView;
import com.aresstack.askai.client.OllamaRunningModelInfo;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.config.HuggingFaceSearchSuggestion.Modality;
import com.aresstack.askai.hf.HuggingFaceFile;
import com.aresstack.askai.service.AskAiService;
import com.aresstack.askai.service.OllamaService;
import com.aresstack.askai.service.VerificationResult;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows installed and currently loaded Ollama models as ONE list of rich object cards (taken over from
 * ollama-manager): running models always come first, washed in the progress-bar accent and carrying a
 * RUNNING badge; the remaining installed models follow. Both lists ({@code /api/tags} and {@code /api/ps})
 * are read on every refresh.
 */
public final class OllamaModelsPanel extends JPanel {

    /** Opens a model in the chat view (wired by the frame that owns both panels). */
    public interface UseInChatHandler {
        void useInChat(String modelName);
    }

    /** Routes the two add-on entry points for a model (wired by the owning frame). */
    public interface FindAddOnsHandler {
        /** Enter add-on mode and search Hugging Face for an encoder to attach to {@code modelName}. */
        void findAddOns(String modelName);

        /** Enter add-on mode and pick a local projector GGUF to attach to {@code modelName}. */
        void selectLocalAddOn(String modelName);
    }


    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final AskAiService askAiService;
    private final AppConfigurationRepository configurationRepository;
    private final JPanel installedCardsPanel;
    private final JLabel installedStatusLabel;
    /** Incremented on every refresh so a slow answer of an older refresh never overwrites a newer one. */
    private int refreshGeneration;
    private final JLabel informationLabel;
    private boolean serverInformationLoaded;
    private UseInChatHandler useInChatHandler;
    private FindAddOnsHandler findAddOnsHandler;

    public OllamaModelsPanel(AskAiModel model, OllamaService ollamaService,
                             AskAiService askAiService, AppConfigurationRepository configurationRepository) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.askAiService = askAiService;
        this.configurationRepository = configurationRepository;
        this.installedCardsPanel = createCardsPanel();
        this.installedStatusLabel = new JLabel("Models are not loaded yet.");
        this.informationLabel = new JLabel("Ollama server information is not loaded yet.");
        buildUserInterface();
    }

    /** Wires the "Use in chat" primary action shown on each installed model card. */
    public void setUseInChatHandler(UseInChatHandler handler) {
        this.useInChatHandler = handler;
    }

    public void setFindAddOnsHandler(FindAddOnsHandler handler) {
        this.findAddOnsHandler = handler;
    }

    /** Show the models list (installed and running in one list) and refresh it. */
    public void showModels() {
        ensureServerInformation();
        refreshModels();
    }

    private void ensureServerInformation() {
        if (!serverInformationLoaded) {
            serverInformationLoaded = true;
            refreshServerInformation();
        }
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(createModelsCard(), BorderLayout.CENTER);
        informationLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        add(informationLabel, BorderLayout.SOUTH);
    }

    private JPanel createModelsCard() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new ComicButton("Refresh");
        refreshButton.addActionListener(event -> refreshModels());
        toolbar.add(refreshButton);
        toolbar.add(installedStatusLabel);
        panel.add(toolbar, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(installedCardsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);
        showInstalledPlaceholder("Open Models or click Refresh to load the models.");
        return panel;
    }

    private static JPanel createCardsPanel() {
        // Tracks the viewport width: cards are always as wide as the visible list (long text lines are
        // clipped with "..." inside the card) and the list never scrolls horizontally.
        JPanel panel = new WidthTrackingPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return panel;
    }

    /**
     * Read installed ({@code /api/tags}) and running ({@code /api/ps}) models together and show them as one
     * list once both answers are in. A failing running list still shows the installed models.
     */
    private void refreshModels() {
        final int generation = ++refreshGeneration;
        installedStatusLabel.setText("Loading models from " + model.getOllamaBaseUrl() + " ...");
        showInstalledPlaceholder("Loading models ...");
        final ModelsResult result = new ModelsResult();
        ollamaService.listInstalledModels(new OllamaService.InstalledModelsListener() {
            @Override
            public void onInstalledModels(final List<OllamaModelInfo> models) {
                onUi(() -> {
                    result.installed = models;
                    result.installedDone = true;
                    maybeShowModels(generation, result);
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(() -> {
                    result.installedError = ex;
                    result.installedDone = true;
                    maybeShowModels(generation, result);
                });
            }
        });
        ollamaService.listRunningModels(new OllamaService.RunningModelsListener() {
            @Override
            public void onRunningModels(final List<OllamaRunningModelInfo> models) {
                onUi(() -> {
                    result.running = models;
                    result.runningDone = true;
                    maybeShowModels(generation, result);
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(() -> {
                    result.runningError = ex;
                    result.runningDone = true;
                    maybeShowModels(generation, result);
                });
            }
        });
    }

    /** The two answers of one refresh; read and written on the EDT only. */
    private static final class ModelsResult {
        List<OllamaModelInfo> installed = new ArrayList<OllamaModelInfo>();
        List<OllamaRunningModelInfo> running = new ArrayList<OllamaRunningModelInfo>();
        Exception installedError;
        Exception runningError;
        boolean installedDone;
        boolean runningDone;
    }

    private void maybeShowModels(int generation, ModelsResult result) {
        if (generation != refreshGeneration || !result.installedDone || !result.runningDone) {
            return;
        }
        if (result.installedError != null && result.running.isEmpty()) {
            showInstalledPlaceholder("Could not load installed models: " + result.installedError.getMessage());
            installedStatusLabel.setText("Error while loading installed models.");
            return;
        }
        showModels(result.installed, result.running);
        installedStatusLabel.setText(result.installedError != null
                ? result.running.size() + " running; installed models could not be read: "
                        + result.installedError.getMessage()
                : result.runningError != null
                ? result.installed.size() + " installed; running models could not be read: "
                        + result.runningError.getMessage()
                : result.installed.size() + " installed, " + result.running.size() + " running");
    }

    private void refreshServerInformation() {
        informationLabel.setText("Loading Ollama server information ...");
        ollamaService.getServerVersion(new OllamaService.ServerVersionListener() {
            @Override
            public void onServerVersion(final String version) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        informationLabel.setText(version == null || version.isEmpty()
                                ? "Ollama server: " + model.getOllamaBaseUrl()
                                : "Ollama server: " + model.getOllamaBaseUrl() + " | version " + version);
                    }
                });
            }

            @Override
            public void onError(Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        informationLabel.setText("Ollama server: " + model.getOllamaBaseUrl());
                    }
                });
            }
        });
    }

    /**
     * One list: running models first (with the running wash and badge), then the other installed models.
     * A running model that is also installed keeps all its installed actions; a running model without an
     * installed entry gets the plain running card.
     */
    void showModels(List<OllamaModelInfo> models, List<OllamaRunningModelInfo> runningModels) {
        installedCardsPanel.removeAll();
        java.util.Map<String, OllamaRunningModelInfo> runningByKey =
                new java.util.LinkedHashMap<String, OllamaRunningModelInfo>();
        for (OllamaRunningModelInfo running : runningModels) {
            runningByKey.put(modelKey(running.getContainerId(), running.getDisplayName()), running);
        }
        List<OllamaModelInfo> ordered = new ArrayList<OllamaModelInfo>();
        List<OllamaModelInfo> idle = new ArrayList<OllamaModelInfo>();
        java.util.Set<String> installedRunningKeys = new java.util.HashSet<String>();
        for (OllamaModelInfo modelInfo : models) {
            String key = modelKey(modelInfo.getContainerId(), modelInfo.getDisplayName());
            if (runningByKey.containsKey(key)) {
                ordered.add(modelInfo);
                installedRunningKeys.add(key);
            } else {
                idle.add(modelInfo);
            }
        }
        ordered.addAll(idle);
        if (models.isEmpty() && runningModels.isEmpty()) {
            addPlaceholder(installedCardsPanel, "No installed models returned by Ollama.");
        } else {
            String lastContainer = null;
            // A loaded model without an installed entry is never hidden: plain running card on top.
            for (java.util.Map.Entry<String, OllamaRunningModelInfo> entry : runningByKey.entrySet()) {
                if (installedRunningKeys.contains(entry.getKey())) {
                    continue;
                }
                String container = containerLabel(entry.getValue().getContainerDisplayName());
                if (!container.equals(lastContainer)) {
                    addSectionHeader(installedCardsPanel, container);
                    lastContainer = container;
                }
                installedCardsPanel.add(OllamaModelCard.running(entry.getValue())
                        .withLoadToggle(loadToggle(entry.getValue().getDisplayName(), true)));
                installedCardsPanel.add(Box.createVerticalStrut(6));
            }
            for (final OllamaModelInfo modelInfo : ordered) {
                final OllamaRunningModelInfo running =
                        runningByKey.get(modelKey(modelInfo.getContainerId(), modelInfo.getDisplayName()));
                String container = containerLabel(modelInfo.getContainerDisplayName());
                if (!container.equals(lastContainer)) {
                    addSectionHeader(installedCardsPanel, container);
                    lastContainer = container;
                }
                if (modelInfo.isLocal()) {
                    // The card is built from the model's catalog-validated on-disk manifest (fail-closed:
                    // an unknown/invalid manifest shows "metadata unavailable" and offers no actions). The
                    // "Test reranker" action appears ONLY when the manifest advertises rerank.
                    java.io.File modelRoot = askAiService.localRuntimeManager().getModelRoot();
                    com.aresstack.windirectml.catalog.InstalledModelManifest manifest =
                            com.aresstack.askai.localmodels.LocalInstalledModels.readByVirtualName(
                                    modelRoot, modelInfo.getDisplayName());
                    String detailLine = com.aresstack.askai.localmodels.LocalEngineModelView
                            .installedDetailLine(manifest, false);
                    boolean canRerank = manifest != null && manifest.hasCapability(
                            com.aresstack.windirectml.catalog.ModelCapability.RERANK);
                    Runnable rerankerAction = canRerank
                            ? new Runnable() {
                                @Override
                                public void run() {
                                    openRerankerTestDialog(modelInfo.getDisplayName());
                                }
                            }
                            : null;
                    OllamaModelCard localCard = OllamaModelCard.installedLocal(modelInfo, running,
                            detailLine, rerankerAction,
                            new Runnable() {
                                @Override
                                public void run() {
                                    confirmAndDelete(modelInfo.getDisplayName());
                                }
                            });
                    if (canRerank) {
                        localCard.withUseMenu(useMenu(modelInfo.getDisplayName(),
                                () -> java.util.EnumSet.of(ModelUsePurpose.RERANKER)));
                    }
                    // The local runtime has no empty-prompt warm-up (/api/generate rejects it), so a local model
                    // only offers Unload while it runs; it is loaded by its first real request.
                    if (running != null) {
                        localCard.withLoadToggle(loadToggle(modelInfo.getDisplayName(), true));
                    }
                    installedCardsPanel.add(localCard);
                    installedCardsPanel.add(Box.createVerticalStrut(6));
                    loadCapabilities(modelInfo.getDisplayName(), localCard);
                    continue;
                }
                // Stateless: route to HF search or a local projector file — never guess/store encoder state.
                Runnable searchAddOns = new Runnable() {
                    @Override
                    public void run() {
                        if (findAddOnsHandler != null) {
                            findAddOnsHandler.findAddOns(modelInfo.getDisplayName());
                        }
                    }
                };
                Runnable localAddOn = new Runnable() {
                    @Override
                    public void run() {
                        if (findAddOnsHandler != null) {
                            findAddOnsHandler.selectLocalAddOn(modelInfo.getDisplayName());
                        }
                    }
                };
                OllamaModelCard card = OllamaModelCard.installed(modelInfo, running, searchAddOns, localAddOn, null,
                        new Runnable() {
                    @Override
                    public void run() {
                        confirmAndDelete(modelInfo.getDisplayName());
                    }
                });
                // Embeddings is offered only once /api/show reports the embedding capability (checked at click
                // time, so it appears as soon as the capabilities have loaded).
                final OllamaModelCard useCard = card;
                card.withUseMenu(useMenu(modelInfo.getDisplayName(), () -> useCard.shownCapabilities()
                        .contains(ModelCapability.EMBEDDING)
                        || ModelUsePurpose.EMBEDDINGS.isSelected(model.getAiModelSelections(),
                                modelInfo.getDisplayName())
                        ? java.util.EnumSet.of(ModelUsePurpose.CHAT, ModelUsePurpose.EMBEDDINGS)
                        : java.util.EnumSet.of(ModelUsePurpose.CHAT)));
                card.withLoadToggle(loadToggle(modelInfo.getDisplayName(), running != null));
                installedCardsPanel.add(card);
                installedCardsPanel.add(Box.createVerticalStrut(6));
                loadCapabilities(modelInfo.getDisplayName(), card);
            }
        }
        refreshCards(installedCardsPanel);
    }

    /** Query /api/show for the model's capability tags and render them on the card. */
    private void loadCapabilities(String modelName, final OllamaModelCard card) {
        ollamaService.getModelInfo(modelName, new OllamaService.ModelInfoListener() {
            @Override
            public void onModelInfo(final OllamaModelInfoView info) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        card.setCapabilities(info.getCapabilities());
                    }
                });
            }

            @Override
            public void onError(Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        // Older Ollama without capabilities: enable the buttons without icons.
                        card.setCapabilities(new ArrayList<String>());
                    }
                });
            }
        });
    }


    /** Container section label; models without an origin tag are the plain remote Ollama list. */
    private static String containerLabel(String containerDisplayName) {
        return containerDisplayName == null || containerDisplayName.trim().isEmpty()
                ? "Ollama" : containerDisplayName.trim();
    }

    private static void addSectionHeader(JPanel target, String title) {
        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        header.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 2, 2, 2));
        header.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        target.add(header);
        javax.swing.JSeparator separator = new javax.swing.JSeparator();
        separator.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 2));
        target.add(separator);
        target.add(Box.createVerticalStrut(4));
    }

    /**
     * R0.4: the immediately testable reranker. Sends one sample query with three documents to the
     * LOCAL runtime's /api/rerank (loading the model on first use) and shows the ordered raw
     * scores — the relevant document must come out on top. Afterwards the model is genuinely
     * loaded, so the Running list is refreshed.
     */
    private void openRerankerTestDialog(final String modelName) {
        final javax.swing.JDialog dialog = new javax.swing.JDialog(
                javax.swing.SwingUtilities.getWindowAncestor(this) instanceof java.awt.Frame
                        ? (java.awt.Frame) javax.swing.SwingUtilities.getWindowAncestor(this)
                        : null,
                "Test reranker - " + modelName, true);
        final javax.swing.JTextField queryField =
                ComicControls.textField("What is DirectML?");
        final javax.swing.JTextArea document1 = ComicControls.textArea(
                "DirectML is a Windows API for hardware-accelerated machine learning.", 2, 48);
        final javax.swing.JTextArea document2 = ComicControls.textArea(
                "Paris is the capital of France.", 2, 48);
        final javax.swing.JTextArea document3 = ComicControls.textArea(
                "Shoes are available in many sizes.", 2, 48);
        final javax.swing.JTextArea resultArea = ComicControls.textArea(6, 48);
        resultArea.setEditable(false);
        final javax.swing.JButton runButton = new ComicButton("Run rerank");

        JPanel form = new JPanel();
        form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));
        form.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        form.add(new JLabel("Query"));
        form.add(queryField);
        form.add(new JLabel("Document 1"));
        form.add(new javax.swing.JScrollPane(document1));
        form.add(new JLabel("Document 2"));
        form.add(new javax.swing.JScrollPane(document2));
        form.add(new JLabel("Document 3"));
        form.add(new javax.swing.JScrollPane(document3));
        form.add(runButton);
        form.add(new JLabel("Result (raw model scores, best first)"));
        form.add(new javax.swing.JScrollPane(resultArea));
        dialog.setContentPane(form);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        runButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                runButton.setEnabled(false);
                resultArea.setText("Running (the first call loads the model) ...");
                final String query = queryField.getText();
                final java.util.List<String> documents = java.util.Arrays.asList(
                        document1.getText(), document2.getText(), document3.getText());
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String baseUrl = askAiService.localRuntimeManager().ensureStarted();
                            java.util.Map<String, Object> request =
                                    new java.util.LinkedHashMap<String, Object>();
                            request.put("model", modelName);
                            request.put("query", query);
                            request.put("documents", documents);
                            request.put("top_n", documents.size());
                            final java.util.Map<String, Object> response =
                                    com.aresstack.askai.localmodels.LocalRuntimeHttp
                                            .postJson(baseUrl, "/api/rerank", request);
                            onUi(new Runnable() {
                                @Override
                                public void run() {
                                    resultArea.setText(renderRerankResponse(response, documents));
                                    runButton.setEnabled(true);
                                    refreshModels(); // the model is genuinely loaded now
                                }
                            });
                        } catch (final Exception ex) {
                            onUi(new Runnable() {
                                @Override
                                public void run() {
                                    resultArea.setText("Rerank failed: " + ex.getMessage());
                                    runButton.setEnabled(true);
                                }
                            });
                        }
                    }
                }, "askai-reranker-test").start();
            }
        });
        dialog.setVisible(true);
    }

    /** Renders the /api/rerank response as ordered "N. score=... - <document>" lines. */
    static String renderRerankResponse(java.util.Map<String, Object> response,
                                       java.util.List<String> documents) {
        Object error = response.get("error");
        if (error != null) {
            return "Rerank failed: " + error;
        }
        Object results = response.get("results");
        if (!(results instanceof java.util.List)) {
            return "Unexpected response: " + response;
        }
        StringBuilder text = new StringBuilder();
        int rank = 0;
        for (Object entry : (java.util.List<?>) results) {
            if (!(entry instanceof java.util.Map)) {
                continue;
            }
            java.util.Map<?, ?> result = (java.util.Map<?, ?>) entry;
            int index = result.get("index") instanceof Number
                    ? ((Number) result.get("index")).intValue() : -1;
            double score = result.get("score") instanceof Number
                    ? ((Number) result.get("score")).doubleValue() : Double.NaN;
            String document = index >= 0 && index < documents.size()
                    ? documents.get(index) : "?";
            String preview = document.length() > 60 ? document.substring(0, 57) + "..." : document;
            rank++;
            text.append(rank).append(". score=").append(String.format("%.2f", score))
                    .append(" - ").append(preview).append('\n');
        }
        return text.length() == 0 ? "No results returned." : text.toString();
    }

    private void showInstalledPlaceholder(String message) {
        installedCardsPanel.removeAll();
        addPlaceholder(installedCardsPanel, message);
        refreshCards(installedCardsPanel);
    }

    /**
     * The "Use" popup of a model card: one check item per purpose, checked when this model currently holds it.
     * Checking assigns the model (the previous holder is replaced, so it is un-checked everywhere); un-checking
     * clears the purpose. Chat goes through the chat window so its model selector follows.
     */
    private java.util.function.Supplier<javax.swing.JPopupMenu> useMenu(final String modelName,
                                                                         final java.util.function.Supplier<java.util.Set<ModelUsePurpose>> offered) {
        return () -> {
            java.util.Set<ModelUsePurpose> purposes = offered.get();
            javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
            com.aresstack.askai.config.AiModelSelections selections = model.getAiModelSelections();
            for (final ModelUsePurpose purpose : purposes) {
                final javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(purpose.label(),
                        purpose.isSelected(selections, modelName));
                item.addActionListener(event -> use(modelName, purpose, item.isSelected()));
                menu.add(item);
            }
            return menu;
        };
    }

    private void use(String modelName, ModelUsePurpose purpose, boolean use) {
        if (purpose == ModelUsePurpose.CHAT) {
            if (use && useInChatHandler != null) {
                useInChatHandler.useInChat(modelName);
            } else {
                model.persistMainModel(purpose.apply(model.getAiModelSelections(), modelName, use).getMainModel());
            }
        } else {
            com.aresstack.askai.config.AiModelSelections next =
                    purpose.apply(model.getAiModelSelections(), modelName, use);
            model.persistRerankerAndEmbeddingsModels(next.getRerankerModel(), next.getEmbeddingsModel());
        }
        installedStatusLabel.setText((use ? "Using " : "No longer using ") + modelName + " for "
                + purpose.label().toLowerCase(java.util.Locale.ROOT) + ".");
    }

    /**
     * The Load/Unload action of a model card, as in ollama-manager: load = empty /api/generate with the
     * configured chat keep_alive, unload = keep_alive 0. Afterwards the list is refreshed from /api/ps, so the
     * card moves to/from the top and its button flips; on failure the button is re-enabled.
     */
    private java.util.function.Consumer<OllamaModelCard> loadToggle(final String modelName, final boolean running) {
        return card -> {
            installedStatusLabel.setText((running ? "Unloading " : "Loading ") + modelName + " ...");
            OllamaService.ActionListener listener = new OllamaService.ActionListener() {
                @Override
                public void onComplete(final String message) {
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            installedStatusLabel.setText(message);
                            refreshModels();
                        }
                    });
                }

                @Override
                public void onError(final Exception ex) {
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            installedStatusLabel.setText("Could not " + (running ? "unload " : "load ")
                                    + modelName + ": " + ex.getMessage());
                            card.resetLoadToggle();
                        }
                    });
                }
            };
            if (running) {
                ollamaService.unloadModel(modelName, listener);
            } else {
                ollamaService.loadModel(modelName, model.getDefaultKeepAlive(), listener);
            }
        };
    }

    private void confirmAndDelete(final String modelName) {
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete model '" + modelName + "' from Ollama?",
                "Delete model",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        installedStatusLabel.setText("Deleting " + modelName + " ...");
        ollamaService.deleteModel(modelName, new OllamaService.ActionListener() {
            @Override
            public void onComplete(final String message) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        installedStatusLabel.setText(message);
                        refreshModels();
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        installedStatusLabel.setText("Could not delete " + modelName + ": " + ex.getMessage());
                    }
                });
            }
        });
    }

    private static String modelKey(String containerId, String displayName) {
        return (containerId == null ? "" : containerId) + '\u0000' + displayName;
    }

    private static void addPlaceholder(JPanel target, String message) {
        JLabel label = new JLabel(message);
        label.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        target.add(label);
    }

    private static void refreshCards(JPanel panel) {
        panel.revalidate();
        panel.repaint();
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }

    /** Card list column that always fits the viewport width and scrolls only vertically. */
    static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, (orientation == javax.swing.SwingConstants.VERTICAL
                    ? visibleRect.height : visibleRect.width) - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}

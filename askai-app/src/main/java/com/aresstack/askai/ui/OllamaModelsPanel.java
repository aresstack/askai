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
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows installed and currently loaded Ollama models as rich object cards. Installed and running
 * are two separate views selected from the "Models" menu (no in-panel tabs).
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

    private static final String INSTALLED_CARD = "installed";
    private static final String RUNNING_CARD = "running";

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final AskAiService askAiService;
    private final AppConfigurationRepository configurationRepository;
    private final CardLayout cardLayout;
    private final JPanel cards;
    private final JPanel installedCardsPanel;
    private final JPanel runningCardsPanel;
    private final JLabel installedStatusLabel;
    private final JLabel runningStatusLabel;
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
        this.cardLayout = new CardLayout();
        this.cards = new JPanel(cardLayout);
        this.installedCardsPanel = createCardsPanel();
        this.runningCardsPanel = createCardsPanel();
        this.installedStatusLabel = new JLabel("Installed models are not loaded yet.");
        this.runningStatusLabel = new JLabel("Running models are not loaded yet.");
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

    /** Show the installed-models view and refresh it (the "Models > Installed" entry). */
    public void showInstalled() {
        cardLayout.show(cards, INSTALLED_CARD);
        ensureServerInformation();
        refreshInstalledModels();
    }

    /** Show the running-models view and refresh it (the "Models > Running Models" entry). */
    public void showRunning() {
        cardLayout.show(cards, RUNNING_CARD);
        ensureServerInformation();
        refreshRunningModels();
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
        cards.add(createInstalledModelsCard(), INSTALLED_CARD);
        cards.add(createRunningModelsCard(), RUNNING_CARD);
        add(cards, BorderLayout.CENTER);
        informationLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        add(informationLabel, BorderLayout.SOUTH);
    }

    private JPanel createInstalledModelsCard() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(createInstalledToolbar(), BorderLayout.NORTH);
        panel.add(new JScrollPane(installedCardsPanel), BorderLayout.CENTER);
        showInstalledPlaceholder("Open Models > Installed or click Refresh to load installed models.");
        return panel;
    }

    private JPanel createRunningModelsCard() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(createRunningToolbar(), BorderLayout.NORTH);
        panel.add(new JScrollPane(runningCardsPanel), BorderLayout.CENTER);
        showRunningPlaceholder("Open Models > Running Models or click Refresh to load running models.");
        return panel;
    }

    private JPanel createInstalledToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new ComicButton("Refresh");
        refreshButton.addActionListener(event -> refreshInstalledModels());
        toolbar.add(refreshButton);
        toolbar.add(installedStatusLabel);
        return toolbar;
    }

    private JPanel createRunningToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new ComicButton("Refresh");
        refreshButton.addActionListener(event -> refreshRunningModels());
        toolbar.add(refreshButton);
        toolbar.add(runningStatusLabel);
        return toolbar;
    }

    private static JPanel createCardsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return panel;
    }

    private void refreshInstalledModels() {
        installedStatusLabel.setText("Loading installed models from " + model.getOllamaBaseUrl() + " ...");
        showInstalledPlaceholder("Loading installed models ...");
        ollamaService.listInstalledModels(new OllamaService.InstalledModelsListener() {
            @Override
            public void onInstalledModels(final List<OllamaModelInfo> models) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showInstalledModels(models);
                        installedStatusLabel.setText("Loaded " + models.size() + " installed models.");
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showInstalledPlaceholder("Could not load installed models: " + ex.getMessage());
                        installedStatusLabel.setText("Error while loading installed models.");
                    }
                });
            }
        });
    }

    private void refreshRunningModels() {
        runningStatusLabel.setText("Loading running models from " + model.getOllamaBaseUrl() + " ...");
        showRunningPlaceholder("Loading running models ...");
        ollamaService.listRunningModels(new OllamaService.RunningModelsListener() {
            @Override
            public void onRunningModels(final List<OllamaRunningModelInfo> models) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showRunningModels(models);
                        runningStatusLabel.setText("Loaded " + models.size() + " running models.");
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        showRunningPlaceholder("Could not load running models: " + ex.getMessage());
                        runningStatusLabel.setText("Error while loading running models.");
                    }
                });
            }
        });
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

    private void showInstalledModels(List<OllamaModelInfo> models) {
        installedCardsPanel.removeAll();
        if (models.isEmpty()) {
            addPlaceholder(installedCardsPanel, "No installed models returned by Ollama.");
        } else {
            String lastContainer = null;
            for (final OllamaModelInfo modelInfo : models) {
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
                    OllamaModelCard localCard = OllamaModelCard.installedLocal(modelInfo,
                            detailLine, rerankerAction,
                            new Runnable() {
                                @Override
                                public void run() {
                                    confirmAndDelete(modelInfo.getDisplayName());
                                }
                            });
                    installedCardsPanel.add(localCard);
                    installedCardsPanel.add(Box.createVerticalStrut(6));
                    loadCapabilities(modelInfo.getDisplayName(), localCard);
                    continue;
                }
                Runnable useInChat = new Runnable() {
                    @Override
                    public void run() {
                        if (useInChatHandler != null) {
                            useInChatHandler.useInChat(modelInfo.getDisplayName());
                        }
                    }
                };
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
                OllamaModelCard card = OllamaModelCard.installed(modelInfo, searchAddOns, localAddOn, useInChat,
                        new Runnable() {
                    @Override
                    public void run() {
                        confirmAndDelete(modelInfo.getDisplayName());
                    }
                });
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
                                    refreshRunningModels(); // the model is genuinely loaded now
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

    private void showRunningModels(List<OllamaRunningModelInfo> models) {
        runningCardsPanel.removeAll();
        if (models.isEmpty()) {
            addPlaceholder(runningCardsPanel, "No running models returned by Ollama.");
        } else {
            String lastContainer = null;
            for (OllamaRunningModelInfo modelInfo : models) {
                String container = containerLabel(modelInfo.getContainerDisplayName());
                if (!container.equals(lastContainer)) {
                    addSectionHeader(runningCardsPanel, container);
                    lastContainer = container;
                }
                runningCardsPanel.add(OllamaModelCard.running(modelInfo));
                runningCardsPanel.add(Box.createVerticalStrut(6));
            }
        }
        refreshCards(runningCardsPanel);
    }

    private void showInstalledPlaceholder(String message) {
        installedCardsPanel.removeAll();
        addPlaceholder(installedCardsPanel, message);
        refreshCards(installedCardsPanel);
    }

    private void showRunningPlaceholder(String message) {
        runningCardsPanel.removeAll();
        addPlaceholder(runningCardsPanel, message);
        refreshCards(runningCardsPanel);
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
                        refreshInstalledModels();
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
}

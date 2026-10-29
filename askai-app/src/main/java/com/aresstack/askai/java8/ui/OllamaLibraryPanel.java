package com.aresstack.askai.java8.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.java8.ollamalib.OllamaLibraryModel;
import com.aresstack.askai.java8.ollamalib.OllamaModelVariant;
import com.aresstack.askai.java8.service.AskAiService;
import com.aresstack.askai.java8.client.OllamaPullProgress;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;

/**
 * The "Ollama Library" search tab: scrape ollama.com, list models and their tag variants, and pull a
 * chosen tag on the connected remote Ollama via {@code /api/pull}. Unlike the HuggingFace tab there is
 * no download/convert/import — the remote server fetches the ready-made registry model itself.
 */
public final class OllamaLibraryPanel extends JPanel {

    private final AskAiService askAiService;
    private final JTextField searchField = ComicControls.textField(28);
    private final JButton searchButton = new ComicButton("Search Ollama Library");
    private final DefaultListModel<OllamaLibraryModel> resultsModel = new DefaultListModel<OllamaLibraryModel>();
    private final JList<OllamaLibraryModel> resultsList = ComicControls.<OllamaLibraryModel>list(resultsModel);
    private final DefaultListModel<OllamaModelVariant> variantsModel = new DefaultListModel<OllamaModelVariant>();
    private final JList<OllamaModelVariant> variantsList = ComicControls.<OllamaModelVariant>list(variantsModel);
    private final JButton installButton = new ComicButton("Install (pull) selected tag");
    private final JProgressBar progressBar = ComicControls.progressBar(0, 100);
    private final JTextArea logArea = ComicControls.textArea(10, 80);

    public OllamaLibraryPanel(AskAiService askAiService) {
        this.askAiService = askAiService;
        buildUserInterface();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(buildSearchBar(), BorderLayout.NORTH);

        javax.swing.JSplitPane lists = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, buildResultsArea(), buildVariantsArea());
        lists.setResizeWeight(0.55d);
        lists.setContinuousLayout(true);
        lists.setBorder(null);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(buildActionRow(), BorderLayout.NORTH);
        bottom.add(buildLog(), BorderLayout.CENTER);

        javax.swing.JSplitPane main = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, lists, bottom);
        main.setResizeWeight(0.5d);
        main.setContinuousLayout(true);
        main.setBorder(null);
        add(main, BorderLayout.CENTER);

        progressBar.setStringPainted(true);
        add(progressBar, BorderLayout.SOUTH);
    }

    private JComponent buildSearchBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(new JLabel("Search"));
        bar.add(searchField);
        bar.add(searchButton);
        searchButton.addActionListener(event -> search());
        searchField.addActionListener(event -> search());
        JLabel hint = new JLabel("Models from ollama.com — installed directly via ollama pull on the remote server.");
        hint.setForeground(ThemeColor.of("models.secondaryText"));
        bar.add(hint);
        return bar;
    }

    private JComponent buildResultsArea() {
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.setCellRenderer(new ModelRenderer());
        resultsList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                onModelSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(resultsList);
        scroll.setBorder(ComicControls.titledBorder("Ollama models"));
        return scroll;
    }

    private JComponent buildVariantsArea() {
        variantsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        variantsList.setCellRenderer(new VariantRenderer());
        variantsList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateInstallButton();
            }
        });
        JScrollPane scroll = new JScrollPane(variantsList);
        scroll.setBorder(ComicControls.titledBorder("Tags / variants"));
        return scroll;
    }

    private JComponent buildActionRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        installButton.setEnabled(false);
        installButton.addActionListener(event -> installSelected());
        row.add(installButton);
        return row;
    }

    private JComponent buildLog() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new java.awt.Dimension(scroll.getPreferredSize().width, 160));
        // Technical pull output collapsed by default (progress bar above stays visible).
        return new CollapsiblePanel("Technical details", scroll, false);
    }

    // ------------------------------------------------------------------ actions

    private void search() {
        final String query = searchField.getText().trim();
        if (query.length() == 0) {
            append("Enter a search term, e.g. devstral.");
            return;
        }
        searchButton.setEnabled(false);
        resultsModel.clear();
        variantsModel.clear();
        updateInstallButton();
        append("Searching Ollama Library for \"" + query + "\" ...");
        askAiService.searchOllamaLibrary(query, new AskAiService.OllamaLibraryListener() {
            public void onModels(final List<OllamaLibraryModel> models) {
                onUi(new Runnable() {
                    public void run() {
                        searchButton.setEnabled(true);
                        for (OllamaLibraryModel model : models) {
                            resultsModel.addElement(model);
                        }
                        append("Found " + models.size() + " model(s). Select one to see its tags.");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        searchButton.setEnabled(true);
                        append("Ollama search failed: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void onModelSelected() {
        final OllamaLibraryModel selected = resultsList.getSelectedValue();
        if (selected == null) {
            return;
        }
        variantsModel.clear();
        updateInstallButton();
        append("Loading tags for " + selected.getBaseName() + " ...");
        askAiService.loadOllamaVariants(selected.getBaseName(), new AskAiService.OllamaVariantsListener() {
            public void onVariants(final List<OllamaModelVariant> variants) {
                onUi(new Runnable() {
                    public void run() {
                        for (OllamaModelVariant variant : variants) {
                            variantsModel.addElement(variant);
                        }
                        append("Found " + variants.size() + " tag(s). Select one and install.");
                        if (!variantsModel.isEmpty()) {
                            variantsList.setSelectedIndex(0);
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("Could not load tags: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void updateInstallButton() {
        OllamaModelVariant variant = variantsList.getSelectedValue();
        installButton.setEnabled(variant != null);
        if (variant != null && variant.isCloud()) {
            installButton.setText("Install (pull) — cloud tag runs on Ollama cloud");
        } else {
            installButton.setText("Install (pull) selected tag");
        }
    }

    private void installSelected() {
        final OllamaModelVariant variant = variantsList.getSelectedValue();
        if (variant == null) {
            return;
        }
        installButton.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("Pulling " + variant.getTag() + " ...");
        append("Pulling " + variant.getTag() + " on the remote Ollama server ...");
        if (variant.isCloud()) {
            append("Note: this is a cloud tag — it runs on Ollama's cloud, not as a local model.");
        }
        askAiService.pullOllamaModel(variant.getTag(), new AskAiService.PullListener() {
            public void onProgress(final OllamaPullProgress progress) {
                onUi(new Runnable() {
                    public void run() {
                        long total = progress.getTotal();
                        long completed = progress.getCompleted();
                        if (total > 0) {
                            int percent = (int) (completed * 100L / total);
                            progressBar.setValue(Math.max(0, Math.min(100, percent)));
                            progressBar.setString(progress.getStatus() + "  " + percent + "%");
                        } else {
                            progressBar.setString(progress.getStatus());
                        }
                    }
                });
            }

            public void onComplete(final String message) {
                onUi(new Runnable() {
                    public void run() {
                        progressBar.setValue(100);
                        progressBar.setString("Done");
                        append("Installed " + variant.getTag() + " on remote Ollama.");
                        installButton.setEnabled(true);
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        progressBar.setString("Failed");
                        append("Pull failed: " + ex.getMessage());
                        installButton.setEnabled(true);
                    }
                });
            }
        });
    }

    private void append(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }

    // ------------------------------------------------------------------ renderers

    private static final class ModelRenderer extends JPanel implements ListCellRenderer<OllamaLibraryModel> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel iconLabel = new JLabel();
        private final JLabel descLabel = new JLabel();
        private final JLabel statsLabel = new JLabel();

        ModelRenderer() {
            super(new BorderLayout(8, 1));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeColor.of("models.resultRowDivider")),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)));
            setOpaque(true);
            add(nameLabel, BorderLayout.NORTH);
            add(descLabel, BorderLayout.CENTER);
            add(statsLabel, BorderLayout.SOUTH);
            // Capability icons, right-aligned, matching the HuggingFace results list.
            iconLabel.setToolTipText("Model capabilities (vision, tools, thinking, embedding, cloud, ...)");
            add(iconLabel, BorderLayout.EAST);
        }

        public Component getListCellRendererComponent(JList<? extends OllamaLibraryModel> list,
                OllamaLibraryModel model, int index, boolean isSelected, boolean cellHasFocus) {
            Font base = list.getFont();
            nameLabel.setFont(base);
            // Name in bold + parameter-size badges (grey). Capabilities are shown as the shared icon
            // strip on the right (same visual language as the HuggingFace search).
            StringBuilder html = new StringBuilder("<html><b>").append(escape(model.getBaseName())).append("</b>");
            for (String param : model.getParameterSizes()) {
                html.append(badge(param, ThemeColor.toHtmlHex(ThemeColor.of("models.parameterBadgeBackground")),
                        ThemeColor.toHtmlHex(ThemeColor.of("models.parameterBadgeText"))));
            }
            html.append("</html>");
            nameLabel.setText(html.toString());
            iconLabel.setIcon(CapabilityIcons.forCapabilities(
                    ModelCapability.fromOllamaTags(model.getCapabilities())));
            String desc = model.getDescription();
            descLabel.setText(desc.length() > 90 ? desc.substring(0, 88) + "…" : desc);
            descLabel.setFont(base.deriveFont(base.getSize2D() - 1f));
            statsLabel.setText("↓ " + model.getPullsText() + " pulls · " + model.getTagCount() + " tags · "
                    + model.getUpdatedText());
            statsLabel.setFont(base.deriveFont(base.getSize2D() - 2f));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
            nameLabel.setForeground(fg);
            descLabel.setForeground(isSelected ? fg : ThemeColor.of("models.descriptionText"));
            statsLabel.setForeground(isSelected ? fg : ThemeColor.of("models.mutedText"));
            return this;
        }

        private static String badge(String text, String background, String foreground) {
            return "&nbsp;<span style='background:" + background + ";color:" + foreground + "'>&nbsp;"
                    + escape(text) + "&nbsp;</span>";
        }

        private static String escape(String text) {
            return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static final class VariantRenderer extends JPanel implements ListCellRenderer<OllamaModelVariant> {
        private final JLabel tagLabel = new JLabel();
        private final JLabel detailLabel = new JLabel();

        VariantRenderer() {
            super(new BorderLayout(0, 1));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeColor.of("models.resultRowDivider")),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)));
            setOpaque(true);
            add(tagLabel, BorderLayout.NORTH);
            add(detailLabel, BorderLayout.SOUTH);
        }

        public Component getListCellRendererComponent(JList<? extends OllamaModelVariant> list,
                OllamaModelVariant variant, int index, boolean isSelected, boolean cellHasFocus) {
            Font base = list.getFont();
            tagLabel.setFont(base.deriveFont(Font.BOLD));
            tagLabel.setText(variant.getTag()
                    + (variant.isLatest() ? "   · latest" : "")
                    + (variant.isCloud() ? "   · cloud" : ""));
            StringBuilder detail = new StringBuilder();
            // Cloud tags have no local size — show "Cloud" in that slot instead of a blank.
            append(detail, variant.isCloud() && variant.getSize().length() == 0 ? "Cloud" : variant.getSize());
            append(detail, variant.getContextWindow());
            append(detail, variant.getInputTypesText());
            append(detail, variant.getUpdatedText());
            detailLabel.setText(detail.toString());
            detailLabel.setFont(base.deriveFont(base.getSize2D() - 1f));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            Color fg = isSelected ? list.getSelectionForeground() : list.getForeground();
            tagLabel.setForeground(fg);
            detailLabel.setForeground(isSelected ? fg : ThemeColor.of("models.variantDetailText"));
            return this;
        }

        private static void append(StringBuilder builder, String value) {
            if (value != null && value.length() > 0) {
                if (builder.length() > 0) {
                    builder.append("  ·  ");
                }
                builder.append(value);
            }
        }
    }
}

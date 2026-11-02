package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.hf.HuggingFaceModel;
import com.aresstack.askai.hf.convert.RepositoryAnalysis;
import com.aresstack.askai.hf.convert.SupportDecision;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Set;

/**
 * A repository detail view that opens for any hit — including greyed/unsupported ones — showing the
 * files, detected formats, architecture, base-model relation, modalities, and the compatibility
 * verdict with its concrete reason. Model-derived facts render immediately; the file/architecture/
 * decision sections fill in when the (re-run) analysis returns, so a greyed hit shows the same
 * authoritative reason it carries in the list.
 */
public final class RepositoryDetailDialog extends JDialog {

    /** Runs the authoritative analysis; the callback is invoked on the EDT by the host. */
    public interface Analyzer {
        void analyze(String modelId, AnalysisCallback callback);
    }

    public interface AnalysisCallback {
        void onResult(SupportDecision decision, RepositoryAnalysis analysis);

        void onError(String message);
    }

    private final HuggingFaceModel model;
    private final JLabel statusValue = new JLabel();
    private final JLabel reasonValue = new JLabel();
    private final JLabel strategyValue = new JLabel();
    private final JLabel formatsValue = new JLabel();
    private final JLabel architectureValue = new JLabel();
    private final JLabel configValue = new JLabel();
    private final DefaultListModel<String> filesModel = new DefaultListModel<String>();
    private final JLabel filesHeader = new JLabel("Dateien");

    public RepositoryDetailDialog(Frame owner, HuggingFaceModel model,
                                  SupportDecision initialDecision, RepositoryAnalysis initialAnalysis,
                                  Analyzer analyzer) {
        super(owner, "Repository: " + model.getId(), true);
        this.model = model;
        buildUserInterface();
        if (initialAnalysis != null && initialDecision != null) {
            applyAnalysis(initialDecision, initialAnalysis);
        } else {
            showPending();
        }
        if (analyzer != null) {
            analyzer.analyze(model.getId(), new AnalysisCallback() {
                public void onResult(SupportDecision decision, RepositoryAnalysis analysis) {
                    applyAnalysis(decision, analysis);
                }

                public void onError(String message) {
                    statusValue.setText("Analyse fehlgeschlagen");
                    statusValue.setForeground(ThemeColor.of("models.statusErrorText"));
                    reasonValue.setText(message == null ? "" : message);
                }
            });
        }
        setSize(640, 640);
        setLocationRelativeTo(owner);
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(0, 0));
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        content.add(buildHeader());
        content.add(Box.createVerticalStrut(8));
        content.add(buildOverview());
        content.add(Box.createVerticalStrut(8));
        content.add(buildBaseModel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildCompatibility());
        content.add(Box.createVerticalStrut(8));
        content.add(buildDetection());
        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);

        add(scroll, BorderLayout.CENTER);
        add(buildFilesArea(), BorderLayout.SOUTH);
    }

    // ------------------------------------------------------------------ sections

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel id = new JLabel(model.getId());
        id.setFont(id.getFont().deriveFont(Font.BOLD, id.getFont().getSize2D() + 2f));
        JButton open = new ComicButton("Auf HuggingFace öffnen");
        final String url = "https://huggingface.co/" + model.getId();
        open.setToolTipText(url);
        open.addActionListener(event -> {
            if (!BrowserLauncher.open(url)) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
                open.setText("URL kopiert");
            }
        });
        header.add(id, BorderLayout.CENTER);
        header.add(open, BorderLayout.EAST);
        return header;
    }

    private JComponent buildOverview() {
        JPanel panel = section("Übersicht");
        panel.add(row("Besitzer / Name", model.getOwner() + " / " + model.getRepoName()));
        panel.add(row("Downloads / Likes", format(model.getDownloads()) + " / " + format(model.getLikes())));
        if (model.getLibraryName().length() > 0) {
            panel.add(row("library_name", model.getLibraryName()));
        }
        panel.add(row("Herkunft", HuggingFaceModelClassifier.provenanceOf(model).name()));
        Set<ModelCapability> modalities = HuggingFaceModelClassifier.modalitiesOf(model);
        JPanel modRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        modRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel modLabel = new JLabel(fixedLabel("Modalitäten") + describeModalities(modalities));
        modRow.add(new JLabel(CapabilityIcons.forCapabilities(modalities)));
        modRow.add(modLabel);
        panel.add(modRow);
        return panel;
    }

    private JComponent buildBaseModel() {
        JPanel panel = section("Basismodell");
        if (model.hasBaseModelRelation()) {
            panel.add(row("Beziehung", baseRelation()));
            panel.add(row("Basismodell", model.getBaseModelId().length() > 0 ? model.getBaseModelId() : "(unbekannt)"));
        } else {
            panel.add(row("Beziehung", "kein Basismodell (Original)"));
        }
        return panel;
    }

    private JComponent buildCompatibility() {
        JPanel panel = section("Kompatibilität");
        statusValue.setFont(statusValue.getFont().deriveFont(Font.BOLD));
        panel.add(labeledValue("Status", statusValue));
        panel.add(labeledValue("Grund", reasonValue));
        panel.add(labeledValue("Strategie", strategyValue));
        return panel;
    }

    private JComponent buildDetection() {
        JPanel panel = section("Erkennung (aus Dateien + config.json)");
        panel.add(labeledValue("Formate", formatsValue));
        panel.add(labeledValue("Architektur", architectureValue));
        panel.add(labeledValue("config / tokenizer / mmproj", configValue));
        return panel;
    }

    private JComponent buildFilesArea() {
        JList<String> files = ComicControls.<String>list(filesModel);
        JScrollPane scroll = new JScrollPane(files);
        filesHeader.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
        filesHeader.setFont(filesHeader.getFont().deriveFont(Font.BOLD));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        wrap.add(filesHeader, BorderLayout.NORTH);
        wrap.add(scroll, BorderLayout.CENTER);
        wrap.setPreferredSize(new Dimension(640, 180));
        return wrap;
    }

    // ------------------------------------------------------------------ dynamic fill

    private void showPending() {
        statusValue.setText("wird geprüft …");
        statusValue.setForeground(ThemeColor.of("comic.hintText"));
        reasonValue.setText("Analyse läuft …");
        strategyValue.setText("—");
        formatsValue.setText("Analyse läuft …");
        architectureValue.setText("Analyse läuft …");
        configValue.setText("Analyse läuft …");
    }

    private void applyAnalysis(SupportDecision decision, RepositoryAnalysis analysis) {
        String statusWord;
        Color color;
        if (decision.isChecking()) {
            statusWord = "wird geprüft";
            color = ThemeColor.of("comic.hintText");
        } else if (decision.isExecutable()) {
            statusWord = "importierbar";
            color = ThemeColor.of("models.statusSuccessText");
        } else if (decision.isSupported()) {
            statusWord = "erkannt — Import folgt";
            color = ThemeColor.of("models.statusWarningText");
        } else {
            statusWord = "kein Importweg";
            color = ThemeColor.of("models.statusErrorText");
        }
        statusValue.setText(statusWord + (decision.isVerified() ? "" : " (vorläufig)"));
        statusValue.setForeground(color);
        reasonValue.setText("<html><body style='width:520px'>" + escape(decision.getReason()) + "</body></html>");
        strategyValue.setText(decision.getStrategyName().length() > 0 ? decision.getStrategyName() : "—");

        formatsValue.setText(analysis.describeFormats());
        String arch = analysis.getArchitectures().isEmpty() ? "—" : String.join(", ", analysis.getArchitectures());
        String modelType = analysis.getModelType().length() > 0 ? "  (" + analysis.getModelType() + ")" : "";
        architectureValue.setText(arch + modelType);
        configValue.setText(yesNo(analysis.hasConfigJson())
                + (analysis.hasConfigJson() && !analysis.isConfigReadable() ? " (nicht lesbar/gated)" : "")
                + "  /  " + yesNo(analysis.hasTokenizer()) + "  /  " + yesNo(analysis.hasMmproj()));

        filesModel.clear();
        List<String> files = analysis.getFiles();
        for (int i = 0; i < files.size(); i++) {
            filesModel.addElement(files.get(i));
        }
        filesHeader.setText("Dateien (" + files.size() + ")");
    }

    // ------------------------------------------------------------------ helpers

    private String baseRelation() {
        List<String> tags = model.getTags();
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            if (tag.startsWith("base_model:") && tag.indexOf(':', "base_model:".length()) > 0) {
                String rest = tag.substring("base_model:".length());
                int colon = rest.indexOf(':');
                return colon > 0 ? rest.substring(0, colon) : "abgeleitet";
            }
        }
        return "abgeleitet";
    }

    private static String describeModalities(Set<ModelCapability> modalities) {
        StringBuilder builder = new StringBuilder();
        for (ModelCapability modality : modalities) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(modality.name().toLowerCase(java.util.Locale.ROOT));
        }
        return builder.length() == 0 ? "—" : builder.toString();
    }

    private JPanel section(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(ComicControls.titledBorder(title));
        return panel;
    }

    private JComponent row(String label, String value) {
        JLabel line = new JLabel(fixedLabel(label) + value);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        return line;
    }

    /** A label + a live value component (updated later), on one left-aligned line. */
    private JComponent labeledValue(String label, JLabel valueLabel) {
        JPanel line = new JPanel(new BorderLayout(6, 0));
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel key = new JLabel(fixedLabel(label));
        line.add(key, BorderLayout.WEST);
        line.add(valueLabel, BorderLayout.CENTER);
        return line;
    }

    private static String fixedLabel(String label) {
        return label + ":  ";
    }

    private static String format(long value) {
        if (value >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000.0d);
        }
        if (value >= 1_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fK", value / 1_000.0d);
        }
        return String.valueOf(value);
    }

    private static String yesNo(boolean value) {
        return value ? "ja" : "nein";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

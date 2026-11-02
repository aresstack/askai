package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.client.OllamaModelInfo;
import com.aresstack.askai.client.OllamaRunningModelInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class OllamaModelCard extends JPanel {

    private final JLabel capabilityIconLabel = new JLabel();
    // The full set of capabilities /api/show reported (completion, tools, thinking, vision, audio, ...).
    private Set<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);
    private boolean capabilitiesKnown;

    private OllamaModelCard(String title, String line1, String line2, boolean running,
                            final OllamaModelInfo installedModel, final Runnable searchAddOnsAction,
                            final Runnable localAddOnAction,
                            final Runnable useInChatAction, Runnable deleteAction) {
        this(title, line1, line2, running, installedModel, searchAddOnsAction, localAddOnAction,
                useInChatAction, deleteAction, null);
    }

    private OllamaModelCard(String title, String line1, String line2, boolean running,
                            final OllamaModelInfo installedModel, final Runnable searchAddOnsAction,
                            final Runnable localAddOnAction,
                            final Runnable useInChatAction, Runnable deleteAction,
                            final Runnable testRerankerAction) {
        setLayout(new BorderLayout(12, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColor.of("models.cardBorder")),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        setBackground(ThemeColor.of("models.cardBackground"));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));

        JPanel text = new JPanel(new GridLayout(0, 1, 0, 3));
        text.setOpaque(false);
        text.add(new JLabel(title));
        if (!line1.isEmpty()) {
            text.add(new JLabel(line1));
        }
        if (!line2.isEmpty()) {
            text.add(new JLabel(line2));
        }
        add(text, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        capabilityIconLabel.setToolTipText("Model capabilities (from /api/show)");
        right.add(capabilityIconLabel);
        if (deleteAction != null) {
            // Local reranker models are directly testable from their card (R0.4).
            if (testRerankerAction != null) {
                JButton testReranker = new ComicButton("Test reranker");
                testReranker.setToolTipText(
                        "Run a sample query against this local reranker (loads the model)");
                testReranker.setFont(testReranker.getFont().deriveFont(Font.BOLD));
                testReranker.setForeground(ThemeColor.of("models.primaryActionText"));
                testReranker.addActionListener(event -> testRerankerAction.run());
                right.add(testReranker);
            }
            // Primary action first and highlighted: open this model in the chat with one click.
            if (useInChatAction != null) {
                JButton useInChat = new ComicButton("Use in chat");
                useInChat.setToolTipText("Open the Chat and select this model (keeps the current conversation)");
                useInChat.setFont(useInChat.getFont().deriveFont(Font.BOLD));
                useInChat.setForeground(ThemeColor.of("models.primaryActionText"));
                useInChat.addActionListener(event -> useInChatAction.run());
                right.add(useInChat);
            }
            if (searchAddOnsAction != null || localAddOnAction != null) {
                // AskAI stores no model state, so it cannot know whether a text model is secretly a
                // multimodal model missing its encoder. Offer two ways to add an encoder (mmproj): search
                // Hugging Face, or pick a projector GGUF already on disk. Both take the same attach path.
                final JButton addOns = new ComicButton("Add-ons…");
                addOns.setToolTipText("Add an audio/vision encoder (mmproj) to this model");
                final javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                if (searchAddOnsAction != null) {
                    javax.swing.JMenuItem search = new javax.swing.JMenuItem("Search on Hugging Face");
                    search.addActionListener(event -> searchAddOnsAction.run());
                    menu.add(search);
                }
                if (localAddOnAction != null) {
                    javax.swing.JMenuItem local = new javax.swing.JMenuItem("Select local projector file…");
                    local.addActionListener(event -> localAddOnAction.run());
                    menu.add(local);
                }
                addOns.addActionListener(event -> menu.show(addOns, 0, addOns.getHeight()));
                right.add(addOns);
            }
            // Destructive action last and clearly marked.
            JButton deleteButton = new ComicButton("Delete");
            deleteButton.setToolTipText("Delete this model from the Ollama server");
            deleteButton.setForeground(ThemeColor.of("models.destructiveActionText"));
            deleteButton.addActionListener(event -> deleteAction.run());
            right.add(deleteButton);
        } else {
            JLabel status = new JLabel(running ? "RUNNING" : "INSTALLED");
            status.setOpaque(true);
            status.setForeground(ThemeColor.of("bubble.userForeground"));
            status.setBackground(running ? ThemeColor.of("models.runningBadge") : ThemeColor.of("models.installedBadge"));
            status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            right.add(status);
        }
        add(right, BorderLayout.EAST);
    }

    /** @return the capabilities currently shown on the card (test hook). */
    Set<ModelCapability> shownCapabilities() {
        return capabilities;
    }

    /**
     * Apply the capability tags reported by {@code /api/show}: render the capability icons. AskAI keeps no
     * capability state of its own — this is display only, straight from {@code /api/show}.
     */
    void setCapabilities(List<String> capabilityTags) {
        // Show every capability /api/show reports (TEXT, TOOLS, THINKING, VISION, AUDIO, EMBEDDING, ...),
        // not just the input modalities — the previous modality-only filter dropped tools/thinking.
        capabilities = ModelCapability.fromOllamaTags(capabilityTags);
        capabilitiesKnown = true;
        capabilityIconLabel.setIcon(capabilities.isEmpty() ? null : CapabilityIcons.forCapabilities(capabilities));
        capabilityIconLabel.setToolTipText(capabilities.isEmpty()
                ? "Model capabilities (from /api/show)" : ModelCapability.tooltipHtml(capabilities));
        revalidate();
        repaint();
    }

    static OllamaModelCard installed(OllamaModelInfo model, Runnable searchAddOnsAction,
                                     Runnable localAddOnAction, Runnable useInChatAction, Runnable deleteAction) {
        String details = join(model.getDetails().getFamily(), model.getDetails().getParameterSize(),
                model.getDetails().getQuantizationLevel(), model.getDetails().getFormat());
        String meta = join(formatBytes(model.getSize()), shortDate(model.getModifiedAt()), shortDigest(model.getDigest()));
        return new OllamaModelCard(model.getDisplayName(), details, meta, false, model, searchAddOnsAction,
                localAddOnAction, useInChatAction, deleteAction);
    }

    static OllamaModelCard running(OllamaRunningModelInfo model) {
        if (model.isLocal()) {
            // Family + backends come from the catalog (never a hardcoded "CPU"); a local runtime handle
            // is RAM-resident and never occupies VRAM.
            return new OllamaModelCard(model.getDisplayName(),
                    com.aresstack.askai.localmodels.LocalEngineModelView.runningDetailLine(
                            model.getDisplayName()),
                    join("RAM " + formatBytes(model.getSize()), "VRAM 0"), true, null, null, null,
                    null, null);
        }
        String details = join(model.getDetails().getFamily(), model.getDetails().getParameterSize(),
                model.getDetails().getQuantizationLevel(), model.getDetails().getFormat());
        String meta = join("RAM " + formatBytes(model.getSize()), "VRAM " + formatBytes(model.getSizeVram()),
                "expires " + shortDate(model.getExpiresAt()));
        return new OllamaModelCard(model.getDisplayName(), details, meta, true, null, null, null, null, null);
    }

    /** A LOCALLY installed model card: capability/runtime line, test action, delete (R0.4). */
    /**
     * A locally installed model card. {@code detailLine} is the manifest-backed family/capability/backend
     * line computed by the caller (fail-closed to "metadata unavailable"); {@code testRerankerAction} is
     * {@code null} unless the model can rerank.
     */
    static OllamaModelCard installedLocal(OllamaModelInfo model, String detailLine,
                                          Runnable testRerankerAction, Runnable deleteAction) {
        String meta = join(formatBytes(model.getSize()), shortDate(model.getModifiedAt()));
        return new OllamaModelCard(model.getDisplayName(), detailLine, meta, false, model, null, null,
                null, deleteAction, testRerankerAction);
    }

    private static String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    private static String shortDate(String value) {
        return value == null || value.isEmpty() ? "" : value.substring(0, Math.min(19, value.length())).replace('T', ' ');
    }

    private static String shortDigest(String digest) {
        return digest == null || digest.isEmpty() ? "" : "digest " + (digest.length() <= 18 ? digest : digest.substring(0, 18) + "...");
    }

    private static String formatBytes(long value) {
        if (value <= 0L) {
            return "size unknown";
        }
        double size = value;
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (size >= 1024.0d && unit < units.length - 1) {
            size = size / 1024.0d;
            unit++;
        }
        return String.format("%.1f %s", size, units[unit]);
    }
}

package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.hf.HuggingFaceModel;
import com.aresstack.askai.hf.convert.SupportDecision;
import com.aresstack.askai.ui.HuggingFaceModelClassifier.Provenance;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.Icon;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ToolTipManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A richer results list for HuggingFace model search: two lines per entry with the owner
 * highlighted by provenance, download/like counts, the base-model origin for community quants,
 * and the modality icons derived from the repo's pipeline tag. Provenance and variant detection
 * live in {@link HuggingFaceModelClassifier}; this class only renders them.
 *
 * <p>Each row can carry an import-{@link SupportDecision} (by model id): unsupported hits are greyed
 * with a short status line, "checking" hits show a pending note, supported-but-not-yet-executable
 * hits (Safetensors) show their note without greying. Statuses are set as they resolve; unknown ids
 * render normally.</p>
 */
public final class HuggingFaceResultsList extends JList<HuggingFaceModel> {

    private static final Color OFFICIAL_COLOR = ThemeColor.of("models.provenanceOfficial");
    private static final Color KNOWN_COLOR = ThemeColor.of("models.provenanceKnown");
    private static final Color COMMUNITY_COLOR = ThemeColor.of("models.provenanceCommunity");
    private static final Color MUTED_COLOR = ThemeColor.of("models.unsupportedText");

    private final Map<String, SupportDecision> statusById = new HashMap<String, SupportDecision>();

    /** Right inset of a row (matches the renderer's empty border), so the icon strip's left edge is known. */
    private static final int ROW_RIGHT_INSET = 8;

    public HuggingFaceResultsList(DefaultListModel<HuggingFaceModel> model) {
        super(model);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setCellRenderer(new ResultRenderer());
        setVisibleRowCount(8);
        // Renderer components can't own tooltips reliably; the list resolves them per mouse position.
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    /**
     * Per-icon-slot tooltips for the capability strip: figure out the hovered row, whether the mouse is
     * over the right-aligned icon strip, and which capability slot it is — then return that capability's
     * central description. Hovering the strip but between slots shows all of the row's capabilities.
     */
    @Override
    public String getToolTipText(MouseEvent event) {
        int index = locationToIndex(event.getPoint());
        if (index < 0 || index >= getModel().getSize()) {
            return null;
        }
        Rectangle cell = getCellBounds(index, index);
        if (cell == null || !cell.contains(event.getPoint())) {
            return null;
        }
        HuggingFaceModel model = getModel().getElementAt(index);
        Set<ModelCapability> capabilities = HuggingFaceModelClassifier.modalitiesOf(model);
        Icon icon = CapabilityIcons.forCapabilities(capabilities);
        int iconWidth = icon.getIconWidth();
        if (iconWidth <= 0) {
            return null;
        }
        int iconLeft = cell.x + cell.width - ROW_RIGHT_INSET - iconWidth;
        int offsetX = event.getX() - iconLeft;
        if (offsetX < 0 || offsetX >= iconWidth) {
            return null; // not over the icon strip
        }
        ModelCapability capability = CapabilityIcons.capabilityAt(capabilities, offsetX);
        return capability != null ? capability.tooltipLine() : ModelCapability.tooltipHtml(capabilities);
    }

    /** Sets (or replaces) a row's import-support decision and repaints. */
    public void setStatus(String modelId, SupportDecision decision) {
        if (modelId != null && decision != null) {
            statusById.put(modelId, decision);
            repaint();
        }
    }

    public SupportDecision getStatus(String modelId) {
        return modelId == null ? null : statusById.get(modelId);
    }

    /** Forgets all statuses (call on a fresh search). */
    public void clearStatuses() {
        statusById.clear();
        repaint();
    }

    private static String formatCount(long value) {
        if (value >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0d);
        }
        if (value >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fK", value / 1_000.0d);
        }
        return String.valueOf(value);
    }

    /** Two-line card: owner/name + icons on top, stats and provenance below, plus an import-status line. */
    private final class ResultRenderer extends JPanel implements ListCellRenderer<HuggingFaceModel> {

        private final JLabel ownerLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel iconLabel = new JLabel();
        private final JLabel statsLabel = new JLabel();
        private final JLabel badgeLabel = new JLabel();
        private final JLabel statusLabel = new JLabel();

        ResultRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeColor.of("models.resultRowDivider")),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)));
            setOpaque(true);

            JPanel firstLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            firstLine.setOpaque(false);
            firstLine.add(ownerLabel);
            firstLine.add(nameLabel);

            JPanel secondLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            secondLine.setOpaque(false);
            secondLine.add(statsLabel);
            secondLine.add(badgeLabel);

            JPanel lines = new JPanel(new BorderLayout(0, 1));
            lines.setOpaque(false);
            lines.add(firstLine, BorderLayout.NORTH);
            lines.add(secondLine, BorderLayout.CENTER);
            lines.add(statusLabel, BorderLayout.SOUTH);

            add(lines, BorderLayout.CENTER);
            add(iconLabel, BorderLayout.EAST);

            badgeLabel.setOpaque(true);
            badgeLabel.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            // Icon tooltips are resolved per mouse position by the enclosing list (getToolTipText).
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends HuggingFaceModel> list,
                                                      HuggingFaceModel model, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            Provenance provenance = HuggingFaceModelClassifier.provenanceOf(model);
            Font base = list.getFont();
            SupportDecision decision = statusById.get(model.getId());
            boolean greyed = decision != null && decision.getSupport() == SupportDecision.Support.UNSUPPORTED;

            ownerLabel.setText(model.getOwner() + " / ");
            ownerLabel.setFont(base);
            nameLabel.setText(model.getRepoName());
            nameLabel.setFont(base.deriveFont(Font.BOLD));
            iconLabel.setIcon(CapabilityIcons.forCapabilities(HuggingFaceModelClassifier.modalitiesOf(model)));

            statsLabel.setText("↓ " + formatCount(model.getDownloads())
                    + "   ♥ " + formatCount(model.getLikes())
                    + (model.getLibraryName().length() > 0 ? "   " + model.getLibraryName() : ""));
            statsLabel.setFont(base.deriveFont(base.getSize2D() - 1f));

            configureBadge(provenance, model, base);
            configureStatus(decision, base);

            Color foreground = isSelected ? list.getSelectionForeground()
                    : (greyed ? MUTED_COLOR : list.getForeground());
            Color ownerColor = isSelected ? list.getSelectionForeground()
                    : (greyed ? MUTED_COLOR : ownerColorFor(provenance));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            ownerLabel.setForeground(ownerColor);
            nameLabel.setForeground(foreground);
            statsLabel.setForeground(isSelected ? list.getSelectionForeground()
                    : (greyed ? MUTED_COLOR : ThemeColor.of("models.secondaryText")));
            return this;
        }

        /** Shows a short import-status line, and a "verified" cue (no "~" prefix once checked). */
        private void configureStatus(SupportDecision decision, Font base) {
            if (decision == null) {
                statusLabel.setText(" ");
                return;
            }
            statusLabel.setFont(base.deriveFont(base.getSize2D() - 2f));
            String prefix = decision.isVerified() ? "" : "~ ";
            if (decision.isChecking()) {
                statusLabel.setForeground(ThemeColor.of("comic.hintText"));
                statusLabel.setText("⧗ Kompatibilität wird geprüft …");
            } else if (decision.getSupport() == SupportDecision.Support.UNSUPPORTED) {
                statusLabel.setForeground(ThemeColor.of("models.statusErrorText"));
                statusLabel.setText(prefix + "kein Importweg — " + shorten(decision.getReason()));
            } else if (decision.isExecutable()) {
                statusLabel.setForeground(ThemeColor.of("models.statusSuccessText"));
                statusLabel.setText(prefix + "importierbar");
            } else {
                statusLabel.setForeground(ThemeColor.of("models.statusWarningText"));
                statusLabel.setText(prefix + "erkannt — Import folgt");
            }
        }

        private String shorten(String text) {
            if (text == null) {
                return "";
            }
            return text.length() > 60 ? text.substring(0, 57) + "…" : text;
        }

        private void configureBadge(Provenance provenance, HuggingFaceModel model, Font base) {
            badgeLabel.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 2f));
            badgeLabel.setForeground(ThemeColor.of("bubble.userForeground"));
            if (provenance == Provenance.OFFICIAL) {
                badgeLabel.setText("OFFICIAL");
                badgeLabel.setBackground(OFFICIAL_COLOR);
                badgeLabel.setToolTipText("Published by the original model vendor");
                return;
            }
            if (provenance == Provenance.KNOWN_QUANTIZER) {
                badgeLabel.setBackground(KNOWN_COLOR);
                String baseOwner = model.getBaseModelOwner();
                badgeLabel.setText(baseOwner.length() > 0 ? "QUANT OF " + baseOwner.toUpperCase(Locale.ROOT) : "KNOWN");
                badgeLabel.setToolTipText("Well-known quantizer organization"
                        + (baseOwner.length() > 0 ? "; original model by " + baseOwner : ""));
                return;
            }
            String baseOwner = model.getBaseModelOwner();
            badgeLabel.setBackground(COMMUNITY_COLOR);
            badgeLabel.setText(baseOwner.length() > 0
                    ? "community — quant of " + baseOwner : "community");
            badgeLabel.setToolTipText("Community repository"
                    + (baseOwner.length() > 0 ? "; original model by " + baseOwner : ""));
        }

        private Color ownerColorFor(Provenance provenance) {
            if (provenance == Provenance.OFFICIAL) {
                return OFFICIAL_COLOR;
            }
            if (provenance == Provenance.KNOWN_QUANTIZER) {
                return KNOWN_COLOR;
            }
            return COMMUNITY_COLOR;
        }
    }
}

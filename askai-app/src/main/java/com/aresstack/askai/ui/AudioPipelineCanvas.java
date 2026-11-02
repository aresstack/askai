package com.aresstack.askai.ui;

import com.aresstack.audio.pipeline.AudioValidationSeverity;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Render and reorder a linear DSP pipeline as interactive Java2D blocks.
 *
 * <p>Extends {@link JPanel} (not a bare {@code JComponent}) so its UI delegate clears the full opaque
 * background on every {@link #paintComponent(Graphics)} via {@code super.paintComponent(...)}. A bare
 * opaque {@code JComponent} does not reliably erase its area, which left stale double-buffer pixels
 * (ghosting of the menu/toolbar/blocks) visible on a selection-triggered repaint, especially maximized.</p>
 */
public final class AudioPipelineCanvas extends JPanel {

    static final int BLOCK_WIDTH = 168;
    private static final int BLOCK_HEIGHT = 92;
    private static final int BLOCK_GAP = 54;
    private static final int MARGIN = 20;
    /** Vertical gap between a block's bottom edge and the top of its settings card (leaves room for the caret). */
    private static final int CARD_GAP = 4;

    /** The settings card that drops out of the selected block; hosted inside the canvas so it scrolls with it. */
    private AudioInspectorCard inspectorCard;

    public interface Listener {
        void selectionChanged(int selectedIndex);

        void orderChanged(List<AudioBlockDefinition> blocks, int selectedIndex);
    }

    private List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
    private int selectedIndex = -1;
    private int dragIndex = -1;
    private Listener listener;
    private Map<Integer, AudioValidationSeverity> blockSeverities = new HashMap<Integer, AudioValidationSeverity>();

    public AudioPipelineCanvas() {
        setOpaque(true);
        setLayout(null); // the settings card is positioned manually under the selected block
        ToolTipManager.sharedInstance().registerComponent(this);
        installMouseInteraction();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Mark blocks (by index) with a validation severity so the canvas shows a subtle status badge. */
    public void setBlockSeverities(Map<Integer, AudioValidationSeverity> severities) {
        this.blockSeverities = severities == null
                ? new HashMap<Integer, AudioValidationSeverity>()
                : new HashMap<Integer, AudioValidationSeverity>(severities);
        repaint();
    }

    /** Hosts the settings card inside the canvas so it appears directly under the selected block. */
    public void setInspectorCard(AudioInspectorCard card) {
        this.inspectorCard = card;
        if (card != null) {
            add(card);
        }
        revalidate();
    }

    public void setBlocks(List<AudioBlockDefinition> blocks) {
        this.blocks = blocks == null
                ? new ArrayList<AudioBlockDefinition>()
                : new ArrayList<AudioBlockDefinition>(blocks);
        if (selectedIndex >= this.blocks.size()) {
            selectedIndex = this.blocks.isEmpty() ? -1 : this.blocks.size() - 1;
        }
        updatePreferredSize();
        repaint();
    }

    public List<AudioBlockDefinition> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public void setSelectedIndex(int index) {
        selectedIndex = index >= 0 && index < blocks.size() ? index : -1;
        revalidate();
        repaint();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /** @return the horizontal center of the selected block in canvas coordinates, or -1 when none. */
    public int selectedBlockCenterX() {
        if (selectedIndex < 0 || selectedIndex >= blocks.size()) {
            return -1;
        }
        return blockX(selectedIndex) + BLOCK_WIDTH / 2;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintPipeline(g);
        } finally {
            g.dispose();
        }
    }

    private void paintPipeline(Graphics2D g) {
        int y = MARGIN; // blocks sit at the TOP; the settings card drops down beneath the selected one
        for (int i = 0; i < blocks.size(); i++) {
            int x = blockX(i);
            if (i > 0) {
                paintConnector(g, blockX(i - 1) + BLOCK_WIDTH, y + BLOCK_HEIGHT / 2,
                        x, y + BLOCK_HEIGHT / 2);
            }
            paintBlock(g, blocks.get(i), i, x, y);
        }
        if (blocks.isEmpty()) {
            g.setColor(uiColor("Label.disabledForeground", ThemeColor.of("audio.disabledForeground")));
            g.drawString("Add a block to build the processing pipeline.", MARGIN, MARGIN + 20);
        }
    }

    private void paintConnector(Graphics2D g, int x1, int y1, int x2, int y2) {
        Color foreground = uiColor("Label.disabledForeground", ThemeColor.of("audio.disabledForeground"));
        g.setColor(foreground);
        g.setStroke(new BasicStroke(2.0f));
        int arrowX = x2 - 10;
        g.drawLine(x1 + 7, y1, arrowX, y2);
        g.drawLine(arrowX, y2, arrowX - 8, y2 - 6);
        g.drawLine(arrowX, y2, arrowX - 8, y2 + 6);
    }

    private void paintBlock(Graphics2D g, AudioBlockDefinition block, int index, int x, int y) {
        Color background = uiColor("Panel.background", ThemeColor.of("audio.blockBackground"));
        Color foreground = uiColor("Label.foreground", ThemeColor.of("audio.blockForeground"));
        Color accent = uiColor("Component.accentColor", ThemeColor.of("audio.blockAccent"));
        Color disabled = uiColor("Label.disabledForeground", ThemeColor.of("audio.disabledForeground"));

        AudioValidationSeverity severity = blockSeverities.get(index);

        g.setColor(background.brighter());
        g.fillRoundRect(x, y, BLOCK_WIDTH, BLOCK_HEIGHT, 18, 18);
        g.setStroke(new BasicStroke(index == selectedIndex ? 3.0f : (severity != null ? 2.0f : 1.5f)));
        Color border = index == selectedIndex ? accent : (block.isEnabled() ? foreground : disabled);
        if (severity != null && index != selectedIndex) {
            border = severityColor(severity);
        }
        g.setColor(border);
        g.drawRoundRect(x, y, BLOCK_WIDTH, BLOCK_HEIGHT, 18, 18);
        if (severity != null) {
            paintSeverityBadge(g, x + BLOCK_WIDTH - 18, y + 10, severity);
        }

        paintWaveIcon(g, x + 14, y + 16, block.isEnabled() ? accent : disabled);
        g.setColor(block.isEnabled() ? foreground : disabled);
        FontMetrics metrics = g.getFontMetrics();
        String title = fit(block.getType().getDisplayName(), metrics, BLOCK_WIDTH - 58);
        g.drawString(title, x + 48, y + 30);

        g.setColor(block.isEnabled() ? foreground : disabled);
        String summary = fit(parameterSummary(block), metrics, BLOCK_WIDTH - 24);
        g.drawString(summary, x + 12, y + 58);
        g.setColor(block.isEnabled() ? accent : disabled);
        g.drawString(block.isEnabled() ? "Enabled" : "Bypassed", x + 12, y + 78);
    }

    private void paintSeverityBadge(Graphics2D g, int x, int y, AudioValidationSeverity severity) {
        g.setColor(severityColor(severity));
        g.fillOval(x, y, 10, 10);
        g.setColor(uiColor("Panel.background", ThemeColor.of("comic.buttonPlate")));
        g.setStroke(new BasicStroke(1.0f));
        g.drawOval(x, y, 10, 10);
    }

    /** Theme-aware status colors (FlatLaf action colors when present), never hardcoded light-only tones. */
    private static Color severityColor(AudioValidationSeverity severity) {
        if (severity == AudioValidationSeverity.ERROR) {
            return uiColor("Actions.Red", ResearchUiPalette.DANGER_RED);
        }
        return uiColor("Actions.Yellow", ThemeColor.of("audio.warningSeverity"));
    }

    private void paintWaveIcon(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2.0f));
        int middle = y + 12;
        g.drawLine(x, middle, x + 5, middle);
        g.drawLine(x + 5, middle, x + 10, y + 4);
        g.drawLine(x + 10, y + 4, x + 16, y + 20);
        g.drawLine(x + 16, y + 20, x + 22, y + 8);
        g.drawLine(x + 22, y + 8, x + 28, middle);
    }

    private String parameterSummary(AudioBlockDefinition block) {
        // The one-line summary comes from the block's descriptor in the registry, not a per-type switch.
        return com.aresstack.audio.pipeline.AudioBlockRegistry.getInstance()
                .descriptor(block.getType()).summarize(block);
    }

    /** Return contextual help for the block under the mouse pointer. */
    @Override
    public String getToolTipText(MouseEvent event) {
        int index = event == null ? -1 : indexAt(event.getX(), event.getY());
        return index < 0 || index >= blocks.size() ? null : AudioTooltipText.block(blocks.get(index));
    }

    private void installMouseInteraction() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragIndex = indexAt(event.getX(), event.getY());
                select(dragIndex);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragIndex < 0) {
                    return;
                }
                int target = indexForX(event.getX());
                if (target != dragIndex && target >= 0 && target < blocks.size()) {
                    AudioBlockDefinition moved = blocks.remove(dragIndex);
                    blocks.add(target, moved);
                    dragIndex = target;
                    selectedIndex = target;
                    updatePreferredSize();
                    repaint();
                    if (listener != null) {
                        listener.orderChanged(new ArrayList<AudioBlockDefinition>(blocks), selectedIndex);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragIndex = -1;
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private void select(int index) {
        selectedIndex = index;
        repaint();
        if (listener != null) {
            listener.selectionChanged(selectedIndex);
        }
    }

    private int indexAt(int x, int y) {
        int blockY = MARGIN;
        if (y < blockY || y > blockY + BLOCK_HEIGHT) {
            return -1;
        }
        for (int i = 0; i < blocks.size(); i++) {
            int blockX = blockX(i);
            if (x >= blockX && x <= blockX + BLOCK_WIDTH) {
                return i;
            }
        }
        return -1;
    }

    private int indexForX(int x) {
        int slot = (x - MARGIN + (BLOCK_WIDTH + BLOCK_GAP) / 2) / (BLOCK_WIDTH + BLOCK_GAP);
        if (slot < 0) {
            return 0;
        }
        if (slot >= blocks.size()) {
            return blocks.size() - 1;
        }
        return slot;
    }

    private int blockX(int index) {
        return MARGIN + index * (BLOCK_WIDTH + BLOCK_GAP);
    }

    private int cardTopY() {
        return MARGIN + BLOCK_HEIGHT + CARD_GAP;
    }

    /** Position the settings card directly under the selected block; the canvas grows to contain it. */
    @Override
    public void doLayout() {
        if (inspectorCard == null) {
            return;
        }
        if (selectedIndex < 0 || selectedIndex >= blocks.size()) {
            inspectorCard.setBounds(0, 0, 0, 0);
            return;
        }
        int cardWidth = inspectorCard.cardWidth();
        int cardHeight = inspectorCard.getPreferredSize().height;
        int center = blockX(selectedIndex) + BLOCK_WIDTH / 2;
        int left = Math.max(0, center - cardWidth / 2);
        inspectorCard.setBounds(left, cardTopY(), cardWidth, cardHeight);
        inspectorCard.setCaretX(center - left);
    }

    @Override
    public Dimension getPreferredSize() {
        int width = Math.max(640, MARGIN * 2 + blocks.size() * BLOCK_WIDTH
                + Math.max(0, blocks.size() - 1) * BLOCK_GAP);
        int height = MARGIN + BLOCK_HEIGHT + MARGIN;
        if (inspectorCard != null && selectedIndex >= 0 && selectedIndex < blocks.size()) {
            int cardHeight = inspectorCard.getPreferredSize().height;
            if (cardHeight > 0) {
                height = Math.max(height, cardTopY() + cardHeight + MARGIN);
            }
        }
        return new Dimension(width, height);
    }

    private void updatePreferredSize() {
        revalidate();
    }

    private static String fit(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > width) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }
}

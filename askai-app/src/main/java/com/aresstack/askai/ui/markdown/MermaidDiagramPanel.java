package com.aresstack.askai.ui.markdown;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;

/** Render Mermaid off the EDT and paint the result with Java2D, with copy-image/copy-code controls. */
public final class MermaidDiagramPanel extends JPanel {

    private static final int PADDING = 12;
    private static final int MIN_RENDER_WIDTH = 640;
    private static final int MAX_RENDER_WIDTH = 2400;

    private final String diagramCode;
    private final MarkdownTheme theme;
    private final MermaidImageRenderer imageRenderer;
    private final DiagramCanvas canvas;
    private final MarkdownActionButton copyImageButton;
    private BufferedImage image;
    private String errorMessage;
    private boolean rendering;

    public MermaidDiagramPanel(String diagramCode, MarkdownTheme theme,
                               MermaidImageRenderer imageRenderer) {
        this.diagramCode = diagramCode == null ? "" : diagramCode;
        this.theme = theme;
        this.imageRenderer = imageRenderer;
        this.canvas = new DiagramCanvas();
        this.copyImageButton = new MarkdownActionButton(new MarkdownActionButton.ImageIcon(),
                "Copy diagram as image", theme.getMutedForeground(), new Runnable() {
                    public void run() {
                        copyImageToClipboard();
                    }
                });
        this.copyImageButton.setEnabled(false);

        setOpaque(false);
        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getSeparatorColor()),
                BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING)));
        add(buildToolbar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        renderAsync();
    }

    /** @return the raw Mermaid source of this diagram. */
    public String getDiagramCode() {
        return diagramCode;
    }

    private JComponent buildToolbar() {
        MarkdownActionButton copyCodeButton = new MarkdownActionButton(new MarkdownActionButton.CopyIcon(),
                "Copy Mermaid code", theme.getMutedForeground(), new Runnable() {
                    public void run() {
                        copyCodeToClipboard();
                    }
                });
        copyCodeButton.setEnabled(diagramCode.trim().length() > 0);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        toolbar.setOpaque(false);
        toolbar.add(copyImageButton);
        toolbar.add(copyCodeButton);
        return toolbar;
    }

    private void renderAsync() {
        if (rendering) {
            return;
        }
        if (diagramCode.trim().isEmpty()) {
            errorMessage = "Mermaid diagram is empty.";
            canvas.repaint();
            return;
        }
        rendering = true;
        int availableWidth = Math.max(1, canvas.getWidth());
        final int targetWidth = Math.max(MIN_RENDER_WIDTH,
                Math.min(MAX_RENDER_WIDTH, availableWidth * 2));
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() {
                return imageRenderer.render(diagramCode, targetWidth);
            }

            @Override
            protected void done() {
                try {
                    image = get();
                    if (image == null) {
                        errorMessage = "Mermaid diagram could not be rendered.";
                    }
                } catch (Exception ex) {
                    errorMessage = "Mermaid error: " + ex.getMessage();
                } finally {
                    rendering = false;
                    copyImageButton.setEnabled(image != null);
                    canvas.setCursor(java.awt.Cursor.getPredefinedCursor(
                            image != null ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
                    canvas.updatePreferredSize();
                    canvas.revalidate();
                    canvas.repaint();
                }
            }
        }.execute();
    }

    /** Open the enlarged, zoomable viewer for this diagram, re-rendered from the Mermaid source. */
    private void openEnlargedViewer() {
        Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
        int baseWidth = image != null ? image.getWidth() : 900;
        MermaidViewerDialog.open(owner, diagramCode, theme, imageRenderer, baseWidth, image);
    }

    private void copyImageToClipboard() {
        if (image == null) {
            return;
        }
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(imageTransferable(image), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not copy the diagram image: " + ex.getMessage(),
                    "Copy failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void copyCodeToClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(diagramCode), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not copy the Mermaid code: " + ex.getMessage(),
                    "Copy failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** A clipboard transferable that exposes a rendered diagram as an image (visible for tests). */
    static Transferable imageTransferable(final BufferedImage image) {
        return new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.imageFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.imageFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (!DataFlavor.imageFlavor.equals(flavor)) {
                    throw new UnsupportedFlavorException(flavor);
                }
                return image;
            }
        };
    }

    /** The area that paints the rendered diagram (or a status line) and drives the height from the image. */
    private final class DiagramCanvas extends JComponent {

        DiagramCanvas() {
            setOpaque(false);
            setPreferredSize(new Dimension(600, 140));
            setToolTipText("Click to open the enlarged viewer");
            addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getButton() == java.awt.event.MouseEvent.BUTTON1 && image != null) {
                        openEnlargedViewer();
                    }
                }
            });
        }

        void updatePreferredSize() {
            if (image == null) {
                setPreferredSize(new Dimension(600, 140));
                return;
            }
            int width = Math.max(1, getWidth());
            if (width <= 1) {
                width = Math.min(900, image.getWidth());
            }
            // Never upscale past the native size, but otherwise fill the available width for a legible diagram.
            double scale = Math.min(1.0d, width / (double) image.getWidth());
            int height = (int) Math.ceil(image.getHeight() * scale);
            setPreferredSize(new Dimension(width, Math.max(80, height)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(80, height)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (image != null) {
                    paintImage(g2);
                    return;
                }
                paintStatus(g2, errorMessage == null ? "Rendering Mermaid…" : errorMessage,
                        errorMessage == null ? theme.getMutedForeground() : theme.getErrorForeground());
            } finally {
                g2.dispose();
            }
        }

        private void paintImage(Graphics2D graphics) {
            double scale = Math.min(1.0d, getWidth() / (double) image.getWidth());
            int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            // Left-align the diagram; it fills the available width so it stays legible.
            graphics.drawImage(image, 0, 0, drawWidth, drawHeight, null);
        }

        private void paintStatus(Graphics2D graphics, String text, Color color) {
            graphics.setFont(theme.getBodyFont());
            graphics.setColor(color);
            FontMetrics metrics = graphics.getFontMetrics();
            int y = Math.max(metrics.getAscent(), (getHeight() + metrics.getAscent()) / 2);
            graphics.drawString(text, 0, y);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }
    }
}

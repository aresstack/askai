package com.aresstack.askai.ui.markdown;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Enlarged Mermaid viewer, styled after the MainframeMate Wiki/Confluence image overlay: a dark,
 * borderless canvas with drag-to-pan, cursor-anchored mouse-wheel zoom, translucent rounded
 * overlay controls (zoom out / in / fit, plus copy and save) and a bottom info bar.
 *
 * <p>The diagram is re-rendered once at a high resolution from its Mermaid source (crisp vector
 * output) and then panned/zoomed as a bitmap, matching the reference viewer's feel while keeping
 * the exported/copied image sharp.</p>
 */
public final class MermaidViewerDialog extends JDialog {

    private static final int HIGH_RES_WIDTH = 2600;

    private final String diagramCode;
    private final ZoomableImagePanel imagePanel = new ZoomableImagePanel();
    private final JLabel infoLabel = new JLabel(" ", SwingConstants.CENTER);
    private int imageWidth;
    private int imageHeight;

    private MermaidViewerDialog(Window owner, String diagramCode, MermaidImageRenderer imageRenderer,
                                int baseWidth, BufferedImage initial) {
        super(owner instanceof Frame ? (Frame) owner : null, "Mermaid diagram", true);
        // An ownerless viewer (no Frame ancestor) would fall back to the Java cup.
        com.aresstack.askai.ui.AppIcon.apply(this);
        this.diagramCode = diagramCode;
        setLayout(new BorderLayout(0, 0));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration());
        int screenMaxW = screen.width - insets.left - insets.right - 80;
        int screenMaxH = screen.height - insets.top - insets.bottom - 120;

        imagePanel.setPreferredSize(new Dimension(600, 400));

        final JLayeredPane layered = new JLayeredPane();
        layered.setLayout(null);

        final int OVL = 40;
        final int ZOOM_BTN = 36;
        final int GAP = 4;
        final OverlayButton zoomOut = new OverlayButton("➖", ZOOM_BTN, "Zoom out (−)");
        final OverlayButton zoomIn = new OverlayButton("➕", ZOOM_BTN, "Zoom in (+)");
        final OverlayButton zoomReset = new OverlayButton("🔄", ZOOM_BTN, "Fit (0)");
        final OverlayButton copyBtn = new OverlayButton("⧉", OVL, "Copy image");
        final OverlayButton saveBtn = new OverlayButton("💾", OVL, "Save as PNG");

        layered.add(imagePanel, JLayeredPane.DEFAULT_LAYER);
        layered.add(zoomOut, JLayeredPane.PALETTE_LAYER);
        layered.add(zoomIn, JLayeredPane.PALETTE_LAYER);
        layered.add(zoomReset, JLayeredPane.PALETTE_LAYER);
        layered.add(copyBtn, JLayeredPane.PALETTE_LAYER);
        layered.add(saveBtn, JLayeredPane.PALETTE_LAYER);
        setHidden(zoomOut, zoomIn, zoomReset, copyBtn, saveBtn);

        layered.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                int w = layered.getWidth();
                int h = layered.getHeight();
                imagePanel.setBounds(0, 0, w, h);
                saveBtn.setBounds(w - OVL - 8, 8, OVL, OVL);
                copyBtn.setBounds(w - 2 * OVL - 16, 8, OVL, OVL);
                int zoomY = h - ZOOM_BTN - 10;
                int zoomX = w - (3 * ZOOM_BTN + 2 * GAP) - 10;
                zoomOut.setBounds(zoomX, zoomY, ZOOM_BTN, ZOOM_BTN);
                zoomIn.setBounds(zoomX + ZOOM_BTN + GAP, zoomY, ZOOM_BTN, ZOOM_BTN);
                zoomReset.setBounds(zoomX + 2 * (ZOOM_BTN + GAP), zoomY, ZOOM_BTN, ZOOM_BTN);
            }
        });
        add(layered, BorderLayout.CENTER);

        infoLabel.setFont(infoLabel.getFont().deriveFont(Font.ITALIC, 11f));
        infoLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(infoLabel, BorderLayout.SOUTH);

        // Glass pane: hover reveals the overlay controls; wheel zooms at the cursor; drag pans.
        final JPanel glass = new JPanel(null);
        glass.setOpaque(false);
        setGlassPane(glass);
        glass.setVisible(true);
        glass.addMouseMotionListener(new MouseAdapter() {
            public void mouseMoved(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(glass, e.getPoint(), layered);
                int w = layered.getWidth();
                int h = layered.getHeight();
                boolean inImage = p.x >= 0 && p.x < w && p.y >= 0 && p.y < h;
                boolean showTop = inImage && p.x >= w - 2 * OVL - 24 && p.y <= OVL + 16;
                boolean showZoom = inImage && p.x >= w - (3 * ZOOM_BTN + 2 * GAP) - 20
                        && p.y >= h - ZOOM_BTN - 20;
                copyBtn.setVisible(showTop);
                saveBtn.setVisible(showTop);
                zoomOut.setVisible(showZoom);
                zoomIn.setVisible(showZoom);
                zoomReset.setVisible(showZoom);
            }
        });
        glass.addMouseListener(new MouseAdapter() {
            public void mouseExited(MouseEvent e) {
                setHidden(zoomOut, zoomIn, zoomReset, copyBtn, saveBtn);
            }

            public void mousePressed(MouseEvent e) {
                imagePanel.dragStart = SwingUtilities.convertPoint(glass, e.getPoint(), imagePanel);
                imagePanel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            public void mouseReleased(MouseEvent e) {
                imagePanel.dragStart = null;
                imagePanel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }

            public void mouseClicked(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(glass, e.getPoint(), layered);
                int w = layered.getWidth();
                int h = layered.getHeight();
                if (p.x >= w - OVL - 20 && p.y <= OVL + 16) {
                    saveImage();
                    return;
                }
                if (p.x >= w - 2 * OVL - 24 && p.x < w - OVL - 16 && p.y <= OVL + 16) {
                    copyImage();
                    return;
                }
                int zoomY = h - ZOOM_BTN - 10;
                int zoomX = w - (3 * ZOOM_BTN + 2 * GAP) - 10;
                if (p.x >= zoomX - 5 && p.y >= zoomY - 5) {
                    int relX = p.x - zoomX;
                    if (relX < ZOOM_BTN) {
                        imagePanel.zoomAt(1.0 / 1.25, null);
                    } else if (relX < 2 * ZOOM_BTN + GAP) {
                        imagePanel.zoomAt(1.25, null);
                    } else {
                        imagePanel.resetView();
                    }
                    updateInfo();
                }
            }
        });
        glass.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (imagePanel.dragStart != null) {
                    Point p = SwingUtilities.convertPoint(glass, e.getPoint(), imagePanel);
                    imagePanel.offsetX += p.x - imagePanel.dragStart.x;
                    imagePanel.offsetY += p.y - imagePanel.dragStart.y;
                    imagePanel.dragStart = p;
                    imagePanel.repaint();
                }
            }
        });
        glass.addMouseWheelListener(e -> {
            Point p = SwingUtilities.convertPoint(glass, e.getPoint(), imagePanel);
            imagePanel.zoomAt(e.getWheelRotation() < 0 ? 1.15 : 1.0 / 1.15, p);
            updateInfo();
        });

        installKeyBindings();

        int fixedH = Math.min(screen.height * 6 / 10, screenMaxH);
        int fixedW = Math.min(fixedH * 16 / 10, screenMaxW);
        setSize(fixedW, fixedH);
        setLocationRelativeTo(owner);

        if (initial != null) {
            setImage(initial);
        }
        // Re-render crisply at a high resolution off the EDT, then fit it into the viewport.
        if (!diagramCode.trim().isEmpty()) {
            new SwingWorker<BufferedImage, Void>() {
                protected BufferedImage doInBackground() {
                    return imageRenderer.render(diagramCode, HIGH_RES_WIDTH);
                }

                protected void done() {
                    try {
                        BufferedImage rendered = get();
                        if (rendered != null) {
                            setImage(rendered);
                        }
                    } catch (Exception ignored) {
                        // Keep the initial image on a failed hi-res render.
                    }
                }
            }.execute();
        }
    }

    /** Open the enlarged Mermaid viewer for {@code diagramCode}. */
    public static void open(Window owner, String diagramCode, MarkdownTheme theme,
                            MermaidImageRenderer imageRenderer, int baseWidth, BufferedImage initial) {
        new MermaidViewerDialog(owner, diagramCode == null ? "" : diagramCode,
                imageRenderer, baseWidth, initial).setVisible(true);
    }

    private void setImage(BufferedImage image) {
        this.imageWidth = image.getWidth();
        this.imageHeight = image.getHeight();
        imagePanel.setStaticImage(image);
        updateInfo();
    }

    private void updateInfo() {
        String dims = imageWidth > 0 ? "  (" + imageWidth + " × " + imageHeight + ")" : "";
        infoLabel.setText("Mermaid diagram" + dims + "  |  Zoom: " + imagePanel.getZoomPercent() + "%");
    }

    private void installKeyBindings() {
        JComponent root = getRootPane();
        bind(root, "ESCAPE", "close", () -> dispose());
        bind(root, "PLUS", "zoomIn", () -> { imagePanel.zoomAt(1.25, null); updateInfo(); });
        bind(root, "EQUALS", "zoomIn2", () -> { imagePanel.zoomAt(1.25, null); updateInfo(); });
        bind(root, "ADD", "zoomIn3", () -> { imagePanel.zoomAt(1.25, null); updateInfo(); });
        bind(root, "MINUS", "zoomOut", () -> { imagePanel.zoomAt(1.0 / 1.25, null); updateInfo(); });
        bind(root, "SUBTRACT", "zoomOut2", () -> { imagePanel.zoomAt(1.0 / 1.25, null); updateInfo(); });
        bind(root, "0", "fit", () -> { imagePanel.resetView(); updateInfo(); });
        bind(root, "NUMPAD0", "fit2", () -> { imagePanel.resetView(); updateInfo(); });
    }

    private static void bind(JComponent root, String key, String name, Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        root.getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private static void setHidden(JComponent... components) {
        for (JComponent c : components) {
            c.setVisible(false);
        }
    }

    private void copyImage() {
        BufferedImage image = imagePanel.staticImage;
        if (image == null) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageTransferable(image), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not copy the diagram: " + ex.getMessage(),
                    "Copy failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void saveImage() {
        BufferedImage image = imagePanel.staticImage;
        if (image == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save diagram as PNG");
        chooser.setSelectedFile(new File("diagram.png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            target = new File(target.getParentFile(), target.getName() + ".png");
        }
        try {
            javax.imageio.ImageIO.write(image, "png", target);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save the diagram: " + ex.getMessage(),
                    "Save failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Dark canvas that fits the diagram to the viewport and supports cursor-anchored zoom + pan. */
    private static final class ZoomableImagePanel extends JPanel {
        private BufferedImage staticImage;
        private int imgW;
        private int imgH;
        private double zoom = 1.0;
        double offsetX;
        double offsetY;
        Point dragStart;
        private boolean fitted;

        ZoomableImagePanel() {
            setBackground(ThemeColor.of("markdown.viewerCanvasBackground"));
            // The image is often set before the panel has a size; fit it once a size is available.
            addComponentListener(new ComponentAdapter() {
                public void componentResized(ComponentEvent e) {
                    if (!fitted) {
                        resetView();
                    }
                }
            });
        }

        void setStaticImage(BufferedImage img) {
            this.staticImage = img;
            this.imgW = img != null ? img.getWidth() : 0;
            this.imgH = img != null ? img.getHeight() : 0;
            this.fitted = false;
            resetView();
        }

        void resetView() {
            zoom = 1.0;
            if (imgW > 0 && imgH > 0 && getWidth() > 0 && getHeight() > 0) {
                zoom = Math.min(getWidth() / (double) imgW, getHeight() / (double) imgH);
                offsetX = (getWidth() - imgW * zoom) / 2.0;
                offsetY = (getHeight() - imgH * zoom) / 2.0;
                fitted = true;
            } else {
                offsetX = 0;
                offsetY = 0;
            }
            repaint();
        }

        void zoomAt(double factor, Point pivot) {
            if (imgW <= 0) {
                return;
            }
            double oldZoom = zoom;
            zoom = Math.max(0.05, Math.min(zoom * factor, 20.0));
            double px = pivot != null ? pivot.x : getWidth() / 2.0;
            double py = pivot != null ? pivot.y : getHeight() / 2.0;
            offsetX = px - (px - offsetX) * (zoom / oldZoom);
            offsetY = py - (py - offsetY) * (zoom / oldZoom);
            repaint();
        }

        int getZoomPercent() {
            return (int) Math.round(zoom * 100);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (staticImage == null) {
                return;
            }
            int drawW = (int) Math.round(imgW * zoom);
            int drawH = (int) Math.round(imgH * zoom);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(staticImage, (int) Math.round(offsetX), (int) Math.round(offsetY), drawW, drawH, null);
            g2.dispose();
        }
    }

    /** Translucent rounded overlay control, matching the reference viewer's buttons. */
    private static final class OverlayButton extends JPanel {
        OverlayButton(String symbol, int size, String tooltip) {
            super(new BorderLayout());
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tooltip);
            JLabel label = new JLabel(symbol, SwingConstants.CENTER);
            label.setForeground(ThemeColor.withAlpha(ThemeColor.of("markdown.viewerControlGlyph"), 210));
            label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
            add(label, BorderLayout.CENTER);
            setPreferredSize(new Dimension(size, size));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            g2.setColor(ThemeColor.of("markdown.viewerControlPlate"));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Exposes the diagram to the clipboard as an image. */
    private static final class ImageTransferable implements Transferable {
        private final BufferedImage image;

        ImageTransferable(BufferedImage image) {
            this.image = image;
        }

        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}

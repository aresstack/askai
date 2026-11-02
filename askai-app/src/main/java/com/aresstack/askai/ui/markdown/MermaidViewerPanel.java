package com.aresstack.askai.ui.markdown;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The EMBEDDABLE Mermaid viewer: the {@link MermaidViewerDialog} feature set — drag-to-pan,
 * cursor-anchored wheel zoom, ➖/➕/fit controls, copy and save-as-PNG, info bar and
 * the automatic high-resolution re-render — as a plain panel, so overlays (e.g. the research
 * mindmap over the transcript) get the full viewer instead of a static image. Rendering runs
 * through the SAME production pipeline as chat diagrams ({@link CachingMermaidImageRenderer});
 * a render failure shows the Mermaid source honestly instead of a blank canvas.
 *
 * <p>SURFACE: the diagram canvas is WHITE like the surrounding overlay frame by default
 * ({@link #DEFAULT_SURFACE_COLOR}/{@link #DEFAULT_SURFACE_OPACITY_PERCENT} are the one place to
 * retheme). At runtime the info bar carries a color swatch and an opacity slider (bottom right):
 * the swatch recolors canvas AND frame plate TOGETHER (two colors would look broken), the slider
 * fades the canvas from solid to fully transparent over the frame.</p>
 */
public final class MermaidViewerPanel extends JPanel {

    private static final int BASE_WIDTH = 1200;
    private static final int HIGH_RES_WIDTH = 2600;

    /** The diagram surface color — white like the overlay frame. THE retheme constant. */
    public static final Color DEFAULT_SURFACE_COLOR = ThemeColor.of("comic.buttonPlate");
    /** Surface opacity in percent: 100 = solid, 0 = fully transparent (the frame shines through). */
    public static final int DEFAULT_SURFACE_OPACITY_PERCENT = 100;

    private final String diagramCode;
    private final MermaidImageRenderer renderer;
    private final Canvas canvas = new Canvas();
    private final JLabel info = new JLabel(" ", SwingConstants.CENTER);
    private final JLayeredPane layers = new JLayeredPane();
    private final JLabel busy = new JLabel("Rendering diagram…", SwingConstants.CENTER);
    private Color surfaceColor = DEFAULT_SURFACE_COLOR;

    public MermaidViewerPanel(String diagramCode) {
        this(diagramCode, CachingMermaidImageRenderer.shared());
    }

    public MermaidViewerPanel(String diagramCode, MermaidImageRenderer renderer) {
        super(new BorderLayout(0, 0));
        this.diagramCode = diagramCode == null ? "" : diagramCode;
        this.renderer = renderer;

        // legible on the (light) default surface
        busy.setForeground(ThemeColor.withAlpha(ThemeColor.of("markdown.diagramBusyText"), 200));
        busy.setFont(busy.getFont().deriveFont(Font.ITALIC, 12f));

        final ViewerButton zoomOut = new ViewerButton("➖", 36, "Zoom out (−)");
        final ViewerButton zoomIn = new ViewerButton("➕", 36, "Zoom in (+)");
        final ViewerButton zoomFit = new ViewerButton("🔄", 36, "Fit");
        final ViewerButton copy = new ViewerButton("⧉", 36, "Copy image");
        final ViewerButton save = new ViewerButton("💾", 36, "Save as PNG");
        zoomOut.onClick(() -> {
            canvas.zoomAt(1.0 / 1.25, null);
            updateInfo();
        });
        zoomIn.onClick(() -> {
            canvas.zoomAt(1.25, null);
            updateInfo();
        });
        zoomFit.onClick(() -> {
            canvas.resetView();
            updateInfo();
        });
        copy.onClick(this::copyImage);
        save.onClick(this::saveImage);

        layers.setLayout(null);
        layers.add(canvas, JLayeredPane.DEFAULT_LAYER);
        layers.add(busy, JLayeredPane.MODAL_LAYER);
        layers.add(zoomOut, JLayeredPane.PALETTE_LAYER);
        layers.add(zoomIn, JLayeredPane.PALETTE_LAYER);
        layers.add(zoomFit, JLayeredPane.PALETTE_LAYER);
        layers.add(copy, JLayeredPane.PALETTE_LAYER);
        layers.add(save, JLayeredPane.PALETTE_LAYER);
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int w = layers.getWidth();
                int h = layers.getHeight();
                canvas.setBounds(0, 0, w, h);
                busy.setBounds(0, 0, w, h);
                save.setBounds(w - 44, 8, 36, 36);
                copy.setBounds(w - 88, 8, 36, 36);
                int zy = h - 46;
                zoomOut.setBounds(w - 130, zy, 36, 36);
                zoomIn.setBounds(w - 90, zy, 36, 36);
                zoomFit.setBounds(w - 50, zy, 36, 36);
            }
        });
        add(layers, BorderLayout.CENTER);

        info.setFont(info.getFont().deriveFont(Font.ITALIC, 11f));
        info.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(buildInfoBar(), BorderLayout.SOUTH);

        installCanvasInteractions();
        renderAsync();
    }

    /**
     * The bottom strip: the info line in the center, the SURFACE controls bottom right — a color
     * swatch and the opacity slider. The strip itself stays transparent, so it always shows the
     * frame plate it sits on (one color, never two).
     */
    private JComponent buildInfoBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.add(info, BorderLayout.CENTER);

        final javax.swing.JSlider opacity = ComicControls.slider(0, 100,
                DEFAULT_SURFACE_OPACITY_PERCENT);
        opacity.setOpaque(false);
        opacity.setFocusable(false);
        opacity.setToolTipText("Diagram surface opacity");
        opacity.setPreferredSize(new Dimension(90, 18));
        opacity.addChangeListener(e -> canvas.setSurface(surfaceColor, opacity.getValue()));

        final SwatchButton swatch = new SwatchButton();
        swatch.setToolTipText("Diagram surface color (recolors the frame too)");
        swatch.onClick(() -> {
            Color chosen = javax.swing.JColorChooser.showDialog(
                    MermaidViewerPanel.this, "Diagram surface color", surfaceColor);
            if (chosen != null) {
                surfaceColor = chosen;
                swatch.repaint();
                canvas.setSurface(surfaceColor, opacity.getValue());
                applyFrameColor(chosen);
            }
        });

        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);
        controls.add(swatch);
        controls.add(opacity);
        bar.add(controls, BorderLayout.EAST);
        return bar;
    }

    /**
     * Surface and frame must never disagree: the swatch recolors the comic overlay plate this
     * viewer sits on together with the canvas. Outside an overlay (no plate ancestor) the canvas
     * alone changes — there is no frame to keep in sync.
     */
    private void applyFrameColor(Color color) {
        java.awt.Container plate = javax.swing.SwingUtilities.getAncestorOfClass(
                com.aresstack.comiccontrols.control.ComicSectionPanel.class, this);
        if (plate instanceof com.aresstack.comiccontrols.control.ComicSectionPanel) {
            ((com.aresstack.comiccontrols.control.ComicSectionPanel) plate).setPlateFill(color);
        }
    }

    private void installCanvasInteractions() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.dragStart = e.getPoint();
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                canvas.dragStart = null;
                canvas.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (canvas.dragStart != null) {
                    canvas.offsetX += e.getX() - canvas.dragStart.x;
                    canvas.offsetY += e.getY() - canvas.dragStart.y;
                    canvas.dragStart = e.getPoint();
                    canvas.repaint();
                }
            }
        };
        canvas.addMouseListener(mouse);
        canvas.addMouseMotionListener(mouse);
        canvas.addMouseWheelListener(e -> {
            canvas.zoomAt(e.getWheelRotation() < 0 ? 1.15 : 1.0 / 1.15, e.getPoint());
            updateInfo();
        });
    }

    /** Base render first (fast feedback), then the crisp high-resolution pass — like the dialog. */
    private void renderAsync() {
        if (diagramCode.trim().isEmpty()) {
            showFailure();
            return;
        }
        new SwingWorker<BufferedImage, Void>() {
            protected BufferedImage doInBackground() {
                return renderer.render(diagramCode, BASE_WIDTH);
            }

            protected void done() {
                BufferedImage image = null;
                try {
                    image = get();
                } catch (Exception ignored) {
                    // fall through to the failure view
                }
                busy.setVisible(false);
                if (image == null) {
                    showFailure();
                    return;
                }
                canvas.setStaticImage(image);
                updateInfo();
                rerenderHighRes();
            }
        }.execute();
    }

    private void rerenderHighRes() {
        new SwingWorker<BufferedImage, Void>() {
            protected BufferedImage doInBackground() {
                return renderer.render(diagramCode, HIGH_RES_WIDTH);
            }

            protected void done() {
                try {
                    BufferedImage crisp = get();
                    if (crisp != null) {
                        canvas.setStaticImage(crisp);
                        updateInfo();
                    }
                } catch (Exception ignored) {
                    // keep the base render on a failed hi-res pass
                }
            }
        }.execute();
    }

    /** No blank canvas, ever: the failure view names the problem and shows the Mermaid source. */
    private void showFailure() {
        removeAll();
        JTextArea details = ComicControls.textArea(
                "The diagram could not be rendered (details in the terminal log).\n\n"
                        + "Mermaid source:\n" + diagramCode);
        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(details);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void updateInfo() {
        BufferedImage image = canvas.staticImage;
        String dims = image != null ? "  (" + image.getWidth() + " × " + image.getHeight() + ")" : "";
        info.setText("Mermaid diagram" + dims + "  |  Zoom: " + canvas.getZoomPercent() + "%");
    }

    private void copyImage() {
        BufferedImage image = canvas.staticImage;
        if (image == null) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new ImageTransferable(image), null);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not copy the diagram: " + ex.getMessage(),
                    "Copy failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void saveImage() {
        BufferedImage image = canvas.staticImage;
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

    // ------------------------------------------------------------------ inner parts

    /** The canvas: fit-to-view, cursor-anchored zoom, pan, and the configurable surface. */
    private static final class Canvas extends JPanel {
        private BufferedImage staticImage;
        private int imgW;
        private int imgH;
        private double zoom = 1.0;
        double offsetX;
        double offsetY;
        Point dragStart;
        private boolean fitted;
        private Color surface = DEFAULT_SURFACE_COLOR;
        private int opacityPercent = DEFAULT_SURFACE_OPACITY_PERCENT;

        Canvas() {
            // The surface paints itself (color + opacity) — never an opaque Swing background,
            // or the transparency slider would have nothing to reveal.
            setOpaque(false);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    if (!fitted) {
                        resetView();
                    }
                }
            });
        }

        /** Recolor/fade the surface (0 = fully transparent — the frame plate shows through). */
        void setSurface(Color color, int opacityPercent) {
            this.surface = color;
            this.opacityPercent = Math.max(0, Math.min(100, opacityPercent));
            repaint();
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
            Graphics2D g2 = (Graphics2D) g.create();
            if (opacityPercent > 0) {
                g2.setColor(ThemeColor.withAlpha(surface, Math.round(opacityPercent * 255 / 100f)));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            if (staticImage == null) {
                g2.dispose();
                return;
            }
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(staticImage, (int) Math.round(offsetX), (int) Math.round(offsetY),
                    (int) Math.round(imgW * zoom), (int) Math.round(imgH * zoom), null);
            g2.dispose();
        }
    }

    /** A small round swatch showing the CURRENT surface color, ink-outlined like the comic chips. */
    private final class SwatchButton extends JPanel {
        private Runnable action;

        SwatchButton() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(18, 18));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (action != null) {
                        action.run();
                    }
                }
            });
        }

        void onClick(Runnable action) {
            this.action = action;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(surfaceColor);
            g2.fillOval(1, 1, getWidth() - 3, getHeight() - 3);
            g2.setColor(ThemeColor.of("comic.ink"));
            g2.setStroke(new java.awt.BasicStroke(1.4f));
            g2.drawOval(1, 1, getWidth() - 3, getHeight() - 3);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Translucent rounded control (always visible — an overlay has no glass pane for hover). */
    private static final class ViewerButton extends JPanel {
        private Runnable action;

        ViewerButton(String symbol, int size, String tooltip) {
            super(new BorderLayout());
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tooltip);
            JLabel label = new JLabel(symbol, SwingConstants.CENTER);
            label.setForeground(ThemeColor.withAlpha(ThemeColor.of("markdown.viewerControlGlyph"), 210));
            label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
            add(label, BorderLayout.CENTER);
            setPreferredSize(new Dimension(size, size));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (action != null) {
                        action.run();
                    }
                }
            });
        }

        void onClick(Runnable action) {
            this.action = action;
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

    /** Reasonable default when the overlay asks before layout. */
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(700, 460);
    }
}

package com.aresstack.askai.ui;

import com.aresstack.askai.vision.ImageAttachment;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A fixed-size square preview of an {@link ImageAttachment}. It shows a neutral placeholder immediately
 * and decodes + scales the real image off the EDT, keeping the placeholder if the format cannot be
 * decoded (e.g. WebP without an ImageIO plugin). It never holds the full-resolution bytes.
 */
public final class ImageThumbnail extends JLabel {

    private final int box;

    public ImageThumbnail(ImageAttachment attachment, int box) {
        this.box = box;
        setPreferredSize(new Dimension(box, box));
        setIcon(new PlaceholderThumbIcon(box));
        setToolTipText(attachment.getDisplayName());
        loadAsync(attachment);
    }

    private void loadAsync(final ImageAttachment attachment) {
        new SwingWorker<BufferedImage, Void>() {
            private int sourceWidth;
            private int sourceHeight;

            @Override
            protected BufferedImage doInBackground() {
                try {
                    BufferedImage image = javax.imageio.ImageIO.read(attachment.getFile().toFile());
                    if (image == null) {
                        return null;
                    }
                    sourceWidth = image.getWidth();
                    sourceHeight = image.getHeight();
                    return scale(image, box);
                } catch (Exception ex) {
                    return null;
                }
            }

            @Override
            protected void done() {
                try {
                    BufferedImage scaled = get();
                    if (scaled != null) {
                        setIcon(new ImageIcon(scaled));
                        setToolTipText(attachment.getDisplayName() + " (" + sourceWidth + "×" + sourceHeight + ")");
                    }
                } catch (Exception ignored) {
                    // keep the placeholder
                }
            }
        }.execute();
    }

    private static BufferedImage scale(BufferedImage source, int box) {
        int w = source.getWidth();
        int h = source.getHeight();
        double factor = Math.min((double) box / w, (double) box / h);
        int tw = Math.max(1, (int) Math.round(w * factor));
        int th = Math.max(1, (int) Math.round(h * factor));
        BufferedImage target = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = target.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(source.getScaledInstance(tw, th, Image.SCALE_SMOOTH), 0, 0, null);
        g2.dispose();
        return target;
    }

    /** A neutral image glyph shown until (or instead of) a real thumbnail. */
    private static final class PlaceholderThumbIcon implements javax.swing.Icon {
        private final int box;

        private PlaceholderThumbIcon(int box) {
            this.box = box;
        }

        public int getIconWidth() {
            return box;
        }

        public int getIconHeight() {
            return box;
        }

        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(ThemeColor.of("chat.imagePlaceholderFill"));
            g2.fillRoundRect(0, 0, box, box, 8, 8);
            g2.setColor(ThemeColor.of("chat.imagePlaceholderGlyph"));
            g2.drawRoundRect(0, 0, box - 1, box - 1, 8, 8);
            int u = box;
            g2.drawOval(u * 5 / 22, u * 6 / 22, u * 4 / 22, u * 4 / 22);
            int[] xs = {u * 3 / 22, u * 9 / 22, u * 14 / 22, u * 19 / 22};
            int[] ys = {u * 18 / 22, u * 11 / 22, u * 15 / 22, u * 9 / 22};
            g2.drawPolyline(xs, ys, 4);
            g2.dispose();
        }
    }
}

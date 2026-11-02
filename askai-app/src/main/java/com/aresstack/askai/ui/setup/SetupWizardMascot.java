package com.aresstack.askai.ui.setup;

import javax.imageio.ImageIO;
import com.aresstack.askai.ui.bubble.BubblePalette;
import com.aresstack.askai.ui.bubble.BubbleSide;
import com.aresstack.askai.ui.bubble.SpeechBubblePanel;
import com.aresstack.comiccontrols.control.ComicSearchBar;
import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.JComponent;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * The setup wizard's wizard: a filler in the free space below a step's fields. Each step sets its place
 * ({@link Placement}: bottom left, centre or right) and its height; the width follows the aspect ratio. If he does
 * not fit there completely – because the step's fields reach too far down or an error message takes the room – he
 * is not shown at all; he is never clipped. The component claims no room itself ({@link #getPreferredSize()} is
 * empty), it only gets what the step leaves over, and so never lies over fields or texts. The wizard is Merlin: his
 * speech bubble (and on request a question field) appears next to him, a click on him reports to the host
 * ({@link #setOnFigureClicked}).
 *
 * <p>Ported from the Enterprise AI Client. AskAI's pictures are full scenes instead of cut-outs, so the figure is
 * drawn as a card with an ink contour; a step can swap its picture ({@link #setArt}), e.g. the runtime step shows
 * the install scene until a runtime answers.</p>
 */
public final class SetupWizardMascot extends JComponent {

    private static final long serialVersionUID = 1L;

    /** Wo der Zauberer im freien Platz steht; immer auf dem unteren Rand. */
    enum Placement {
        LEFT, CENTER, RIGHT
    }

    /** Das allgemeine Bild neben dieser Klasse; Rückfall, wenn ein Schrittbild fehlt. */
    static final String RESOURCE = "wizard/wizard-1-runtime-install.png";
    /** Vorgabe für den Mindestabstand zum letzten Feld darüber. */
    static final int DEFAULT_GAP = 12;
    /** Vorgabe für den Abstand zum linken bzw. rechten Rand bei {@link Placement#LEFT}/{@link Placement#RIGHT}. */
    static final int DEFAULT_SIDE_INSET = 24;
    /** Vorgabe für die größte Breite von Merlins Sprechblase. */
    static final int DEFAULT_BUBBLE_WIDTH = 340;
    /** Schmaler als das passt keine lesbare Sprechblase neben den Zauberer; dann bleibt sie weg. */
    static final int MIN_BUBBLE_WIDTH = 160;
    /**
     * Kleiner als das wird der Zauberer bei knappem Platz nicht: lieber gar nicht als winzig. Ein Schritt mit mehr
     * Feldern verdrängt ihn so nicht mehr, solange diese Höhe frei bleibt.
     */
    static final int MIN_FIGURE_HEIGHT = 110;
    /** Gap between wizard, speech bubble and question field. */
    static final int BUBBLE_GAP = 6;
    /** Corner radius of the card pictures relative to their height (the copies have 36 px at 640 px). */
    static final double CARD_RADIUS = 36d / 640d;

    private transient Art art;
    private final int height;
    private final java.awt.Color ink;
    private final Placement placement;
    private final int gap;
    private final int sideInset;
    private final SpeechBubblePanel bubble;
    private String speaker = WizardGuide.NAME;
    private final ComicSearchBar question;
    private transient Consumer<Point> onFigureClicked;
    private transient Consumer<String> onQuestion;

    /**
     * @param art       das Bild; {@code null} = kein Zauberer
     * @param height    Höhe in logischen Pixeln
     * @param placement Platz im freien Bereich
     * @param gap       Mindestabstand zum oberen Rand der Fläche (zu den Feldern darüber)
     * @param sideInset Abstand zum Seitenrand (links/rechts)
     * @param palette   Farben von Sprechblase und Fragefeld
     */
    SetupWizardMascot(Art art, int height, Placement placement, int gap, int sideInset, ComicPalette palette) {
        if (height <= 0 || gap < 0 || sideInset < 0 || placement == null) {
            throw new IllegalArgumentException("height must be positive, gaps must not be negative, placement set");
        }
        this.art = art;
        this.height = height;
        this.placement = placement;
        this.gap = gap;
        this.sideInset = sideInset;
        setOpaque(false);
        setFocusable(false);
        setLayout(null);
        ComicPalette colors = palette == null ? ComicPalette.defaultPalette() : palette;
        this.ink = colors.getInk();
        // Merlin spricht wie der Assistent im Chat (dieselben Blasenfarben); die Spitze zeigt zum Zauberer. Die Seite
        // einer Blase ist die Seite ihres Sprechers im Verlauf, die Spitze sitzt dort: steht Merlin rechts, ist es eine
        // „linke“ Blase mit Spitze nach rechts, sonst umgekehrt.
        BubblePalette chat = BubblePalette.themed();
        bubble = new SpeechBubblePanel(placement == Placement.RIGHT ? BubbleSide.LEFT : BubbleSide.RIGHT,
                chat.getAssistantBackground(), chat.getAssistantForeground(), WizardGuide.NAME, "");
        bubble.setHeaderColor(chat.getAssistantForeground());
        bubble.setVisible(false);
        add(bubble);
        question = new ComicSearchBar("Ask Merlin …", "Your own question to Merlin; Enter sends it", colors);
        question.setVisible(false);
        question.addSearchAction(e -> submitQuestion());
        question.getTextField().getAccessibleContext().setAccessibleName("Question to Merlin");
        add(question);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (onFigureClicked != null && onFigure(e.getPoint())) {
                    onFigureClicked.accept(e.getPoint());
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                boolean active = onFigureClicked != null && onFigure(e.getPoint());
                setCursor(active ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                setToolTipText(active ? "Ask Merlin" : null);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Swaps the picture (same place and height); {@code null} hides the wizard. */
    void setArt(Art value) {
        this.art = value;
        relayout();
    }

    /** The picture shown now ({@code null}: none). */
    Art art() {
        return art;
    }

    /** Wer auf einen Klick auf den Zauberer reagiert (Punkt in Koordinaten dieser Komponente). */
    void setOnFigureClicked(Consumer<Point> callback) {
        this.onFigureClicked = callback;
    }

    /** Merlins Satz in der Sprechblase; {@code null} oder leer leert sie. */
    void say(String text) {
        say(WizardGuide.NAME, text);
    }

    /** Ein Satz mit seinem Sprecher im Kopf der Sprechblase (bei der Übergabe auch der Chat-Assistent). */
    void say(String speaker, String text) {
        this.speaker = speaker == null || speaker.isEmpty() ? WizardGuide.NAME : speaker;
        bubble.setHeader(this.speaker);
        boolean empty = text == null || text.trim().isEmpty();
        bubble.setText(empty ? "" : text.trim());
        bubble.setVisible(!empty);
        relayout();
    }

    /** Wer gerade spricht (Kopf der Sprechblase). */
    String speaker() {
        return speaker;
    }

    /** Der aktuelle Satz der Sprechblase (leer ohne). */
    String spokenText() {
        return bubble.getText();
    }

    /** Zeigt das Fragefeld unter der Sprechblase; Enter übergibt die Frage und schließt das Feld. */
    void askQuestion(Consumer<String> onAsk) {
        this.onQuestion = onAsk;
        question.setText("");
        question.setVisible(true);
        relayout();
        question.focusField();
    }

    boolean isAskingQuestion() {
        return question.isVisible();
    }

    ComicSearchBar questionField() {
        return question;
    }

    SpeechBubblePanel bubble() {
        return bubble;
    }

    private void submitQuestion() {
        String text = question.getText().trim();
        if (text.isEmpty() || onQuestion == null) {
            return;
        }
        Consumer<String> target = onQuestion;
        onQuestion = null;
        question.setVisible(false);
        relayout();
        target.accept(text);
    }

    private boolean onFigure(Point point) {
        return fits() && figureBounds().contains(point);
    }

    private void relayout() {
        doLayout();
        repaint();
    }

    /**
     * Sprechblase und Fragefeld stehen neben dem Zauberer zur offenen Seite hin, oben etwa auf Kopfhöhe. Passen sie
     * dort nicht, bleiben sie unsichtbar; wie der Zauberer selbst wird nichts abgeschnitten.
     */
    @Override
    public void doLayout() {
        Rectangle figure = figureBounds();
        int left;
        int right;
        if (placement == Placement.RIGHT) {
            left = sideInset;
            right = figure.x - BUBBLE_GAP;
        } else {
            left = figure.x + figure.width + BUBBLE_GAP;
            right = getWidth() - sideInset;
        }
        int column = Math.min(DEFAULT_BUBBLE_WIDTH, right - left);
        boolean room = fits() && column >= MIN_BUBBLE_WIDTH;
        boolean talking = room && !bubble.getText().isEmpty();
        boolean asking = room && onQuestion != null;
        int bubbleWidth = talking ? bubble.preferredWidthWithin(column) : 0;
        int bubbleHeight = talking ? bubble.preferredHeightForWidth(bubbleWidth) : 0;
        int questionHeight = asking ? question.getPreferredSize().height : 0;
        int block = bubbleHeight + (talking && asking ? BUBBLE_GAP : 0) + questionHeight;
        int top = Math.min(figure.y + figure.height / 8, getHeight() - block);
        if (top < 0) {
            talking = false;
            asking = false;
        }
        if (talking) {
            int x = placement == Placement.RIGHT ? right - bubbleWidth : left;
            bubble.setBounds(x, top, bubbleWidth, bubbleHeight);
            bubble.doLayout();
        }
        bubble.setVisible(talking);
        if (asking) {
            int y = top + (talking ? bubbleHeight + BUBBLE_GAP : 0);
            int x = placement == Placement.RIGHT ? right - column : left;
            question.setBounds(x, y, column, questionHeight);
            question.doLayout();
        }
        question.setVisible(asking);
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : new Dimension(0, 0);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, 0);
    }

    Placement placement() {
        return placement;
    }

    /**
     * Breite und Höhe des Zauberers für die jetzige Fläche: die Vorgabehöhe, bei wenig Platz kleiner bis
     * {@link #MIN_FIGURE_HEIGHT} (nie unter die Vorgabe selbst); leer ohne Bild.
     */
    Dimension figureSize() {
        if (art == null) {
            return new Dimension(0, 0);
        }
        int shown = height;
        if (getHeight() > 0) {
            int floor = Math.min(height, MIN_FIGURE_HEIGHT);
            shown = Math.max(floor, Math.min(height, getHeight() - gap));
        }
        return new Dimension(art.widthFor(shown), shown);
    }

    /** {@code true}, wenn der Zauberer an seinem Platz vollständig in die Fläche passt und gezeichnet wird. */
    boolean fits() {
        if (art == null) {
            return false;
        }
        Dimension figure = figureSize();
        return figure.width + 2 * sideInset <= getWidth() && figure.height + gap <= getHeight();
    }

    /** Wo der Zauberer steht, in Koordinaten dieser Komponente (auch wenn er nicht passt). */
    Rectangle figureBounds() {
        Dimension figure = figureSize();
        int x;
        switch (placement) {
            case LEFT:
                x = sideInset;
                break;
            case RIGHT:
                x = getWidth() - sideInset - figure.width;
                break;
            default:
                x = (getWidth() - figure.width) / 2;
        }
        return new Rectangle(x, getHeight() - figure.height, figure.width, figure.height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!fits()) {
            return;
        }
        Rectangle figure = figureBounds();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            AffineTransform device = g2.getTransform();
            int pixelWidth = (int) Math.round(figure.width * Math.abs(device.getScaleX()));
            int pixelHeight = (int) Math.round(figure.height * Math.abs(device.getScaleY()));
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(art.scaled(Math.max(1, pixelWidth), Math.max(1, pixelHeight)), figure.x, figure.y,
                    figure.width, figure.height, null);
            if (!art.isCutOut()) {
                // A full scene: an ink contour along the transparent rounded corners makes it a comic card.
                double arc = 2 * CARD_RADIUS * figure.height;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.setColor(ink);
                g2.draw(new java.awt.geom.RoundRectangle2D.Double(figure.x + 1, figure.y + 1, figure.width - 2,
                        figure.height - 2, arc, arc));
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Das geladene Bild samt der zuletzt verkleinerten Fassung; ein Exemplar je Assistent, geteilt von allen
     * Schritten (alle zeigen den Zauberer in derselben Größe).
     */
    static final class Art {
        private final BufferedImage original;
        private final boolean cutOut;
        private String resource = "";
        private BufferedImage scaled;

        Art(BufferedImage original) {
            if (original == null) {
                throw new IllegalArgumentException("original must not be null");
            }
            this.original = original;
            this.cutOut = transparentCentreEdge(original);
        }

        /**
         * {@code true} for a cut-out figure (transparent background, like the Enterprise pictures), {@code false} for
         * a full scene (AskAI's pictures): the middle of the left edge is transparent only for a cut-out.
         */
        boolean isCutOut() {
            return cutOut;
        }

        private static boolean transparentCentreEdge(BufferedImage image) {
            if (!image.getColorModel().hasAlpha()) {
                return false;
            }
            int alpha = (image.getRGB(0, image.getHeight() / 2) >>> 24) & 0xFF;
            return alpha < 128;
        }

        /** Lädt das allgemeine Bild; {@code null}, wenn es fehlt oder nicht lesbar ist (dann kein Zauberer). */
        static Art load() {
            return load(RESOURCE);
        }

        /**
         * Lädt ein Bild relativ zu dieser Klasse aus dem Klassenpfad; {@code null}, wenn es fehlt oder nicht lesbar
         * ist.
         */
        static Art load(String resource) {
            InputStream in = SetupWizardMascot.class.getResourceAsStream(resource);
            if (in == null) {
                return null;
            }
            try {
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    return null;
                }
                Art art = new Art(image);
                art.resource = resource;
                return art;
            } catch (IOException e) {
                return null;
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nur gelesen
                }
            }
        }

        /** The class-path resource this picture came from (empty when built from an image). */
        String resource() {
            return resource;
        }

        int widthFor(int height) {
            return (int) Math.round((double) original.getWidth() * height / original.getHeight());
        }

        /** Die Fassung in Gerätepixeln; schrittweise halbiert, damit das Verkleinern nicht pixelig wird. */
        BufferedImage scaled(int width, int height) {
            if (scaled != null && scaled.getWidth() == width && scaled.getHeight() == height) {
                return scaled;
            }
            BufferedImage current = original;
            int w = current.getWidth();
            int h = current.getHeight();
            do {
                w = Math.max(width, w / 2);
                h = Math.max(height, h / 2);
                if (w < width * 2 || h < height * 2) {
                    w = width;
                    h = height;
                }
                current = resize(current, w, h);
            } while (w != width || h != height);
            scaled = current;
            return scaled;
        }

        private static BufferedImage resize(BufferedImage source, int width, int height) {
            BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = target.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(source, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }
            return target;
        }
    }
}

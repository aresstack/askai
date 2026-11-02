package com.aresstack.askai.java8.ui.setup;

import com.aresstack.comiccontrols.control.ComicOverlayPanel;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ComicTheme;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * One run of the setup wizard, the same for the first start and the call from the settings
 * ({@link SetupWizardOverlay}): {@link SetupWizardPanel} in a {@link ComicOverlayPanel} (dimmed background, comic
 * plate, comic ✕ = cancel, Escape = cancel). Steps, checks and saving live in the panel; the host only shows the
 * overlay and learns exactly once how the run ended ({@code true} after “Finish” or “Skip” in the last step,
 * otherwise cancelled). Ported from the Enterprise AI Client.
 */
public final class SetupWizardRun {

    /** Heading of the overlay. */
    public static final String TITLE = "Welcome to AskAI: setup";
    /** Largest plate of the wizard (centred, the dimmed window around it). */
    static final int WIDTH = 860;
    static final int HEIGHT = 700;
    private static final String CANCEL_ACTION = "cancelSetup";

    private final SetupWizardPanel wizard;
    private final ComicOverlayPanel overlay;
    private final Consumer<Boolean> ended;
    private final SetupActions setup;
    private boolean finished;
    private boolean completed;

    /**
     * @param ended gets {@code true} (set up) or {@code false} (cancelled) exactly once, on the EDT
     */
    public SetupWizardRun(SetupDraft initial, SetupActions setup, ComicPalette palette, Consumer<Boolean> ended) {
        if (ended == null) {
            throw new IllegalArgumentException("ended must not be null");
        }
        ComicPalette colors = palette == null ? ComicPalette.defaultPalette() : palette;
        ComicTheme.installMenuDefaults(colors);
        this.ended = ended;
        this.setup = setup;
        this.wizard = new SetupWizardPanel(initial, setup, colors);
        wizard.setOnFinished(() -> end(true));
        wizard.setOnCancel(() -> end(false));
        this.overlay = new ComicOverlayPanel(TITLE, wizard, wizard::cancel, colors);
        overlay.setPlateLimit(new Dimension(WIDTH, HEIGHT));
        AbstractAction cancel = new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                wizard.cancel();
            }
        };
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, CANCEL_ACTION);
        overlay.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escape, CANCEL_ACTION);
        overlay.getActionMap().put(CANCEL_ACTION, cancel);
    }

    private void end(boolean done) {
        if (finished) {
            return;
        }
        finished = true;
        completed = done;
        wizard.stopVoice();
        if (!done) {
            setup.cancelled();
        }
        ended.accept(done);
    }

    public SetupWizardPanel wizard() {
        return wizard;
    }

    /** The overlay the host lays over its surface. */
    public ComicOverlayPanel overlay() {
        return overlay;
    }

    public boolean isFinished() {
        return finished;
    }

    /** {@code true} when the run ended with “Finish”. */
    public boolean isCompleted() {
        return completed;
    }
}

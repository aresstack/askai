package com.aresstack.askai.ui.setup;

import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.util.function.Consumer;

/**
 * The setup wizard inside the running application: the overlay of a {@link SetupWizardRun} on the modal layer of the
 * main window's {@link JLayeredPane}. It covers the whole window, whatever card of the workspace is visible (chat,
 * models, settings). Cancelling leaves the application running unchanged.
 *
 * <p>Used for both entries: the first start ({@code firstStart = true}: Witch introduces himself once a runtime
 * answers, “Finish” plays the hand-over) and the call from the settings ({@code false}: Witch is awake from the
 * start and greets as a reunion). Ported from the Enterprise AI Client, where the first start used a separate
 * window because its main window needs a finished setup; AskAI's window runs with defaults, so the overlay serves
 * both.</p>
 */
public final class SetupWizardOverlay {

    /** Name of the overlay in the layer (tests, diagnostics). */
    public static final String NAME = "setupWizardOverlay";

    private SetupWizardOverlay() {
    }

    /**
     * Lays the wizard over the window (EDT only); a run still open is replaced.
     *
     * @param ended exactly once: {@code true} after “Finish”, {@code false} after cancelling; the overlay has
     *              already been removed then
     */
    public static SetupWizardRun show(JRootPane window, SetupDraft initial, SetupActions setup, ComicPalette palette,
                                      boolean firstStart, final Consumer<Boolean> ended) {
        if (window == null || ended == null) {
            throw new IllegalArgumentException("window and ended must not be null");
        }
        final JLayeredPane layers = window.getLayeredPane();
        Object open = layers.getClientProperty(NAME);
        if (open instanceof SetupWizardRun && !((SetupWizardRun) open).isFinished()) {
            ((SetupWizardRun) open).wizard().cancel(); // a second call replaces the open run
        }
        final ComponentListener[] resize = new ComponentListener[1];
        final SetupWizardRun[] run = new SetupWizardRun[1];
        run[0] = new SetupWizardRun(initial, setup, palette, completed -> {
            layers.removeComponentListener(resize[0]);
            layers.putClientProperty(NAME, null);
            layers.remove(run[0].overlay());
            layers.revalidate();
            layers.repaint();
            ended.accept(completed);
        });
        run[0].overlay().setName(NAME);
        resize[0] = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fit(layers, run[0]);
            }
        };
        layers.addComponentListener(resize[0]);
        layers.putClientProperty(NAME, run[0]);
        layers.add(run[0].overlay(), JLayeredPane.MODAL_LAYER);
        fit(layers, run[0]);
        run[0].wizard().setVoice(setup.witchVoice());
        run[0].wizard().setHandoff(firstStart);
        if (!firstStart) {
            run[0].wizard().welcomeBack();
        }
        run[0].wizard().requestFocusInWindow();
        return run[0];
    }

    private static void fit(JLayeredPane layers, SetupWizardRun run) {
        run.overlay().setBounds(0, 0, layers.getWidth(), layers.getHeight());
        run.overlay().revalidate();
        run.overlay().repaint();
    }
}

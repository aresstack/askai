package com.aresstack.askai.ui;

import com.aresstack.askai.stt.whisper.WhisperInstaller;
import com.aresstack.askai.stt.whisper.WhisperModel;
import com.aresstack.askai.stt.whisper.WhisperModelCatalog;
import com.aresstack.askai.stt.whisper.WhisperStore;
import com.aresstack.askai.tts.PiperInstaller;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicForm;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Models → Setup → "Dictation": installs the local whisper.cpp engine and a curated model
 * with one click (downloads through AskAI's network settings), uninstalls them again. Whisper is the default
 * dictation backend; until a model is installed, the Ollama audio model transcribes. Installs run off the EDT,
 * errors are shown, never swallowed.
 */
public final class WhisperModelsPanel extends JPanel {

    /** The one action the panel performs; {@link WhisperInstaller#install} matches it. */
    public interface InstallAction {
        void install(WhisperModel model, PiperInstaller.Progress progress) throws Exception;
    }

    private final WhisperStore store;
    private final InstallAction installer;
    private final Executor background;
    private final Executor ui;
    private final Map<String, JLabel> statusByModel = new LinkedHashMap<String, JLabel>();
    private final Map<String, JButton> buttonByModel = new LinkedHashMap<String, JButton>();
    private final JLabel error = new JLabel(" ");
    private final java.util.List<Runnable> modelsChangedListeners = new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    /** Productive wiring: real installer (AskAI's proxy) + a background thread + the EDT. */
    public WhisperModelsPanel(WhisperStore store) {
        this(store, installActionOver(new WhisperInstaller(store)),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "whisper-install");
                    thread.setDaemon(true);
                    return thread;
                }),
                SwingUtilities::invokeLater);
    }

    WhisperModelsPanel(WhisperStore store, InstallAction installer, Executor background, Executor ui) {
        super(new BorderLayout());
        this.store = store;
        this.installer = installer;
        this.background = background;
        this.ui = ui;
        setOpaque(false);
        ComicForm rows = ComicForm.titled("Dictation models (Whisper)");
        rows.note("Whisper is the default dictation backend: it transcribes locally on the CPU, without Ollama."
                + " Until a model is installed, the Ollama audio model transcribes.");
        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        grid.setOpaque(false);
        int index = 0;
        for (WhisperModel model : WhisperModelCatalog.curated()) {
            addRow(grid, index++, model);
        }
        rows.component(grid);
        rows.note("Downloads: the whisper.cpp engine once (~8 MB, pinned release on GitHub) and the models from"
                + " HuggingFace “" + WhisperModelCatalog.REPOSITORY + "”, through AskAI's network settings."
                + " Stored under %APPDATA%\\.askai\\stt.");
        rows.component(error);
        add(rows, BorderLayout.CENTER);
        refresh();
    }

    /** Another owner (each chat's dictation model selector) that reloads after installs/removals. */
    public void addModelsChangedListener(Runnable listener) {
        if (listener != null) {
            modelsChangedListeners.add(listener);
        }
    }

    public void removeModelsChangedListener(Runnable listener) {
        modelsChangedListeners.remove(listener);
    }

    /** One grid row: name | size | status | button — the action buttons line up in the right column. */
    private void addRow(JPanel grid, int index, final WhisperModel model) {
        JLabel status = new JLabel();
        JButton action = new ComicButton("Install");
        action.addActionListener(event -> {
            if (store.isModelInstalled(model)) {
                onUninstall(model);
            } else {
                onInstall(model);
            }
        });
        statusByModel.put(model.getId(), status);
        buttonByModel.put(model.getId(), action);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridy = index;
        c.insets = new java.awt.Insets(3, 4, 3, 12);
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.gridx = 0;
        grid.add(new JLabel(model.getDisplayName()), c);
        c.gridx = 1;
        grid.add(new JLabel("~" + model.getApproximateSizeMb() + " MB"), c);
        c.gridx = 2;
        c.weightx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        grid.add(status, c);
        c.gridx = 3;
        c.weightx = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        c.anchor = java.awt.GridBagConstraints.EAST;
        grid.add(action, c);
    }

    /** Recompute the installed state of every row. */
    public void refresh() {
        for (WhisperModel model : WhisperModelCatalog.curated()) {
            boolean installed = store.isModelInstalled(model);
            statusByModel.get(model.getId()).setText(!installed ? "Not installed"
                    : store.isEngineInstalled() ? "Installed" : "Installed (engine missing)");
            JButton button = buttonByModel.get(model.getId());
            button.setText(installed ? "Uninstall" : "Install");
            button.setEnabled(true);
        }
    }

    private void onInstall(final WhisperModel model) {
        final JButton button = buttonByModel.get(model.getId());
        final JLabel status = statusByModel.get(model.getId());
        button.setEnabled(false);
        error.setText(" ");
        background.execute(() -> {
            Exception failure = null;
            try {
                installer.install(model, (stage, done, total) -> ui.execute(() -> status.setText(total > 0
                        ? stage + " " + (100 * done / total) + "%" : stage + " …")));
            } catch (Exception ex) {
                failure = ex;
            }
            final Exception outcome = failure;
            ui.execute(() -> {
                if (outcome != null) {
                    error.setText("Install failed: " + describe(outcome));
                }
                refresh();
                fireModelsChanged();
            });
        });
    }

    private void onUninstall(final WhisperModel model) {
        buttonByModel.get(model.getId()).setEnabled(false);
        statusByModel.get(model.getId()).setText("Removing …");
        error.setText(" ");
        background.execute(() -> {
            Exception failure = null;
            try {
                store.uninstallModel(model);
            } catch (Exception ex) {
                failure = ex;
            }
            final Exception outcome = failure;
            ui.execute(() -> {
                if (outcome != null) {
                    error.setText("Uninstall failed: " + describe(outcome));
                }
                refresh();
                fireModelsChanged();
            });
        });
    }

    private void fireModelsChanged() {
        for (Runnable listener : modelsChangedListeners) {
            listener.run();
        }
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static InstallAction installActionOver(final WhisperInstaller installer) {
        return installer::install;
    }

    // ------------------------------------------------------------------ test hooks

    JButton installButton(String modelId) {
        return buttonByModel.get(modelId);
    }

    String statusText(String modelId) {
        return statusByModel.get(modelId).getText();
    }

    String errorText() {
        return error.getText();
    }
}

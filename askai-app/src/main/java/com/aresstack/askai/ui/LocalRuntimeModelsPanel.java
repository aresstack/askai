package com.aresstack.askai.ui;

import com.aresstack.askai.localmodels.LocalInstalledModels;
import com.aresstack.askai.localmodels.LocalModelRuntimeManager;
import com.aresstack.askai.service.AskAiService;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Model Browser "Local models" tab: every model the built-in local runtime (win-directml-java) can run,
 * straight from its catalog ({@link LocalModelCatalog#runnable()}), with a one-click Install — like the NLP and
 * Voices tabs. The files still come from Hugging Face, but the user never has to search for them. Installation
 * goes through {@link AskAiService#installLocalModel}, the same {@code LocalModelInstaller} the setup wizard uses.
 */
public final class LocalRuntimeModelsPanel extends JPanel {

    public static final String TAB_TITLE = "Local models";

    private final AskAiService service;
    private final LocalModelRuntimeManager runtime;
    private final Runnable onInstalled;
    private final Map<String, JLabel> statusByRepo = new LinkedHashMap<String, JLabel>();
    private final Map<String, JButton> buttonByRepo = new LinkedHashMap<String, JButton>();
    private final JProgressBar progress = ComicControls.progressBar(0, 100);
    private final JLabel message = new JLabel(" ");
    private final List<LocalRuntimeModelDescriptor> models;
    private boolean busy;

    public LocalRuntimeModelsPanel(AskAiService service, Runnable onInstalled) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.runtime = service.localRuntimeManager();
        this.onInstalled = onInstalled == null ? () -> { } : onInstalled;
        this.models = sorted(LocalModelCatalog.runnable());
        buildUserInterface();
        refresh();
        // Installs/removals elsewhere (Installed models, Hugging Face tab, wizard): re-read on every show.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refresh();
            }
        });
    }

    /** Rerankers first (Research needs one), then embeddings, then text generation. */
    private static List<LocalRuntimeModelDescriptor> sorted(List<LocalRuntimeModelDescriptor> all) {
        List<LocalRuntimeModelDescriptor> result = new ArrayList<LocalRuntimeModelDescriptor>();
        for (ModelCapability first : new ModelCapability[] {ModelCapability.RERANK, ModelCapability.EMBEDDING}) {
            for (LocalRuntimeModelDescriptor d : all) {
                if (d.hasCapability(first) && !result.contains(d)) {
                    result.add(d);
                }
            }
        }
        for (LocalRuntimeModelDescriptor d : all) {
            if (!result.contains(d)) {
                result.add(d);
            }
        }
        return result;
    }

    private void buildUserInterface() {
        ComicForm rows = ComicForm.titled("Models for AskAI's built-in local runtime");
        rows.note("These models run inside AskAI (no Ollama needed). Research needs a reranker."
                + " Downloads come from Hugging Face; install with one click.");
        if (runtime == null) {
            rows.note("The local model runtime is not available in this installation.");
        }
        for (LocalRuntimeModelDescriptor model : models) {
            addRow(rows, model);
        }
        progress.setStringPainted(true);
        progress.setVisible(false);
        rows.component(progress);
        rows.component(message);
        // ComicForm.page keeps the form at its preferred height, top-aligned (a plain JScrollPane stretched it).
        add(ComicForm.page(rows), BorderLayout.CENTER);
    }

    /** Label column = purpose, field column = repository, then status + Install aligned in their own columns. */
    private void addRow(ComicForm rows, final LocalRuntimeModelDescriptor model) {
        JLabel name = new JLabel(model.huggingFaceRepositoryId());
        name.setToolTipText(model.notes());
        JLabel status = new JLabel("Not installed");
        status.setPreferredSize(new java.awt.Dimension(110, status.getPreferredSize().height));
        JButton install = new ComicButton("Reinstall");
        install.setPreferredSize(install.getPreferredSize()); // fixed width for Install/Reinstall
        install.addActionListener(event -> onInstall(model));
        statusByRepo.put(model.huggingFaceRepositoryId(), status);
        buttonByRepo.put(model.huggingFaceRepositoryId(), install);
        JPanel line = new JPanel(new BorderLayout(8, 0));
        line.setOpaque(false);
        line.add(name, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(status);
        right.add(install);
        line.add(right, BorderLayout.EAST);
        rows.row(purpose(model), line);
    }

    static String purpose(LocalRuntimeModelDescriptor model) {
        if (model.hasCapability(ModelCapability.RERANK)) {
            return "Reranker";
        }
        if (model.hasCapability(ModelCapability.EMBEDDING)) {
            return "Embeddings";
        }
        String family = String.valueOf(model.runtimeFamily()).toLowerCase(Locale.ROOT);
        return "Text generation (" + family + ")";
    }

    /** Recompute Installed / Not installed for every row from the on-disk manifests. */
    public void refresh() {
        for (LocalRuntimeModelDescriptor model : models) {
            JLabel status = statusByRepo.get(model.huggingFaceRepositoryId());
            JButton button = buttonByRepo.get(model.huggingFaceRepositoryId());
            boolean installed = isInstalled(model);
            status.setText(installed ? "Installed" : "Not installed");
            button.setText(installed ? "Reinstall" : "Install");
            button.setEnabled(runtime != null && !busy);
        }
    }

    private boolean isInstalled(LocalRuntimeModelDescriptor model) {
        return runtime != null
                && LocalInstalledModels.readByVirtualName(runtime.getModelRoot(), model.virtualModelName()) != null;
    }

    private void onInstall(final LocalRuntimeModelDescriptor model) {
        busy = true;
        refresh();
        final JLabel status = statusByRepo.get(model.huggingFaceRepositoryId());
        status.setText("Installing …");
        message.setText(" ");
        progress.setVisible(true);
        progress.setIndeterminate(true);
        progress.setString("Starting …");
        service.installLocalModel(model.huggingFaceRepositoryId(), new AskAiService.LocalInstallListener() {
            public void onStep(final String step) {
                SwingUtilities.invokeLater(() -> {
                    progress.setIndeterminate(true);
                    progress.setString(step);
                });
            }

            public void onDownloadProgress(final String fileName, final long completed, final long total) {
                SwingUtilities.invokeLater(() -> {
                    if (total > 0) {
                        progress.setIndeterminate(false);
                        progress.setValue((int) (completed * 100 / total));
                    }
                    progress.setString(fileName + " " + (completed >> 20) + " / "
                            + (total > 0 ? (total >> 20) + " MB" : "? MB"));
                });
            }

            public void onInstalled(final String virtualModelName) {
                SwingUtilities.invokeLater(() -> finish("Installed " + virtualModelName + "."));
            }

            public void onError(final Exception exception) {
                SwingUtilities.invokeLater(() -> finish("Install failed: " + (exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage())));
            }
        });
    }

    private void finish(String text) {
        busy = false;
        progress.setVisible(false);
        message.setText(text);
        refresh();
        onInstalled.run();
    }

    /** The model repositories offered, in order (for diagnostics/tests). */
    public List<String> offeredRepositories() {
        return new ArrayList<String>(statusByRepo.keySet());
    }
}

package com.aresstack.askai.ui;

import com.aresstack.askai.localruntime.onnx.OnnxModelCatalog;
import com.aresstack.askai.localruntime.onnx.OnnxModelDescriptor;
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
 * The Model Browser "Local models" tab: every model the built-in local runtime can run — embeddings and rerankers
 * through ONNX Runtime ({@link OnnxModelCatalog}), text generation from the win-directml catalog
 * ({@link LocalModelCatalog#runnable()}) — with a one-click Install — like the NLP and
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
    private final Map<String, OnnxModelDescriptor> onnxByRepo = new LinkedHashMap<String, OnnxModelDescriptor>();
    private boolean busy;

    public LocalRuntimeModelsPanel(AskAiService service, Runnable onInstalled) {
        super(new BorderLayout(8, 8));
        this.service = service;
        this.runtime = service.localRuntimeManager();
        this.onInstalled = onInstalled == null ? () -> { } : onInstalled;
        this.models = generationOnly(LocalModelCatalog.runnable());
        buildUserInterface();
        refresh();
        // Installs/removals elsewhere (Installed models, Hugging Face tab, wizard): re-read on every show.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refresh();
            }
        });
    }

    /**
     * Embeddings and rerankers now run through ONNX Runtime ({@link OnnxModelCatalog}); the win-directml catalog only
     * contributes its text-generation models.
     */
    private static List<LocalRuntimeModelDescriptor> generationOnly(List<LocalRuntimeModelDescriptor> all) {
        List<LocalRuntimeModelDescriptor> result = new ArrayList<LocalRuntimeModelDescriptor>();
        for (LocalRuntimeModelDescriptor d : all) {
            if (!d.hasCapability(ModelCapability.RERANK) && !d.hasCapability(ModelCapability.EMBEDDING)) {
                result.add(d);
            }
        }
        return result;
    }

    private void buildUserInterface() {
        ComicForm rows = ComicForm.titled("Models for AskAI's built-in local runtime");
        rows.note("These models run inside AskAI on the CPU through ONNX Runtime (no Ollama, no GPU needed)."
                + " Research needs a reranker. Downloads come from Hugging Face; install with one click.");
        if (runtime == null) {
            rows.note("The local model runtime is not available in this installation.");
        }
        for (OnnxModelDescriptor model : onnxSorted()) {
            addOnnxRow(rows, model);
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

    /** ONNX models, rerankers first; models without an ONNX export are shown disabled with the export note. */
    private static List<OnnxModelDescriptor> onnxSorted() {
        List<OnnxModelDescriptor> result = new ArrayList<OnnxModelDescriptor>();
        for (OnnxModelDescriptor.Capability first : new OnnxModelDescriptor.Capability[] {
                OnnxModelDescriptor.Capability.RERANK, OnnxModelDescriptor.Capability.EMBEDDING}) {
            for (OnnxModelDescriptor d : OnnxModelCatalog.all()) {
                if (d.capability() == first) {
                    result.add(d);
                }
            }
        }
        return result;
    }

    private void addOnnxRow(ComicForm rows, final OnnxModelDescriptor model) {
        JLabel name = new JLabel(model.repositoryId());
        name.setToolTipText(model.isRunnable() ? model.displayName() : model.exportNote());
        JLabel status = new JLabel("Not installed");
        status.setPreferredSize(new java.awt.Dimension(110, status.getPreferredSize().height));
        JButton install = new ComicButton("Reinstall");
        install.setPreferredSize(install.getPreferredSize());
        install.addActionListener(event -> onInstall(model.repositoryId()));
        statusByRepo.put(model.repositoryId(), status);
        buttonByRepo.put(model.repositoryId(), install);
        onnxByRepo.put(model.repositoryId(), model);
        JPanel line = new JPanel(new BorderLayout(8, 0));
        line.setOpaque(false);
        line.add(name, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(status);
        right.add(install);
        line.add(right, BorderLayout.EAST);
        rows.row(model.capability() == OnnxModelDescriptor.Capability.RERANK ? "Reranker" : "Embeddings", line);
    }

    /** Label column = purpose, field column = repository, then status + Install aligned in their own columns. */
    private void addRow(ComicForm rows, final LocalRuntimeModelDescriptor model) {
        JLabel name = new JLabel(model.huggingFaceRepositoryId());
        name.setToolTipText(model.notes());
        JLabel status = new JLabel("Not installed");
        status.setPreferredSize(new java.awt.Dimension(110, status.getPreferredSize().height));
        JButton install = new ComicButton("Reinstall");
        install.setPreferredSize(install.getPreferredSize()); // fixed width for Install/Reinstall
        install.addActionListener(event -> onInstall(model.huggingFaceRepositoryId()));
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
        for (OnnxModelDescriptor model : onnxByRepo.values()) {
            JLabel status = statusByRepo.get(model.repositoryId());
            JButton button = buttonByRepo.get(model.repositoryId());
            if (!model.isRunnable()) {
                status.setText("Needs ONNX export");
                button.setText("Install");
                button.setEnabled(false);
                continue;
            }
            boolean installed = runtime != null && LocalInstalledModels.readByVirtualName(runtime.getModelRoot(),
                    model.virtualModelName()) != null;
            status.setText(installed ? "Installed" : "Not installed");
            button.setText(installed ? "Reinstall" : "Install");
            button.setEnabled(runtime != null && !busy);
        }
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

    private void onInstall(final String repositoryId) {
        busy = true;
        refresh();
        final JLabel status = statusByRepo.get(repositoryId);
        status.setText("Installing …");
        message.setText(" ");
        progress.setVisible(true);
        progress.setIndeterminate(true);
        progress.setString("Starting …");
        service.installLocalModel(repositoryId, new AskAiService.LocalInstallListener() {
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

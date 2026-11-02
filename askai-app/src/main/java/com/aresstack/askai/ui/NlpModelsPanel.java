package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelCatalog;
import com.aresstack.askai.localmodels.LocalNlpModelStore;
import com.aresstack.askai.localmodels.NlpDownloadClient;
import com.aresstack.askai.localmodels.HttpNlpDownloadClient;
import com.aresstack.askai.localmodels.NlpModelCatalogEntry;
import com.aresstack.askai.localmodels.NlpModelCatalogProvider;
import com.aresstack.askai.localmodels.NlpModelInstaller;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The Model Browser "NLP" tab: install the curated OpenNLP sentence-detection models (per language) with one
 * explicit click. PURE UI orchestration — all data comes from {@link NlpModelCatalogProvider} (available),
 * {@link NlpModelCatalog} (installed) and {@link NlpModelInstaller} (install). No research-specific logic, no
 * file paths shown. Installing runs OFF the EDT and refreshes the row + the global catalog on success; a
 * download/hash/HTTP error is shown, never swallowed. Already-installed rows show a clear status and no button.
 */
public final class NlpModelsPanel extends JPanel {

    public static final String TAB_TITLE = "NLP";

    /** The one action the panel performs; {@link NlpModelInstaller#install} matches it. */
    public interface InstallAction {
        NlpModelInstaller.Outcome install(NlpModelCatalogEntry entry) throws Exception;
    }

    private final NlpModelCatalogProvider catalog;
    private final NlpModelCatalog installed;
    private final InstallAction installer;
    private final Runnable onInstalled;
    private final Executor background;
    private final Executor ui;

    private final Map<String, JLabel> statusByModel = new LinkedHashMap<String, JLabel>();
    private final Map<String, JButton> buttonByModel = new LinkedHashMap<String, JButton>();
    private final JLabel error = new JLabel(" ");

    /** Productive wiring: real installer over the curated catalog + a background thread + the EDT. */
    public NlpModelsPanel(NlpModelCatalogProvider catalog, NlpModelCatalog installed,
                          LocalNlpModelStore store, Runnable onInstalled) {
        this(catalog, installed, installActionOver(store), onInstalled,
                Executors.newSingleThreadExecutor(daemon("nlp-install")), edt());
    }

    NlpModelsPanel(NlpModelCatalogProvider catalog, NlpModelCatalog installed, InstallAction installer,
                   Runnable onInstalled, Executor background, Executor ui) {
        super(new BorderLayout(8, 8));
        this.catalog = catalog;
        this.installed = installed;
        this.installer = installer;
        this.onInstalled = onInstalled == null ? new Runnable() { public void run() { } } : onInstalled;
        this.background = background;
        this.ui = ui;
        buildUserInterface();
        refresh();
    }

    private void buildUserInterface() {
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(ComicControls.titledBorder("Apache OpenNLP models"));
        for (NlpModelCatalogEntry entry : catalog.availableModels()) {
            if (entry.getCapability() != NlpCapability.SENTENCE_DETECTION
                    && entry.getCapability() != NlpCapability.LANGUAGE_DETECTION) {
                continue; // curated capabilities only
            }
            rows.add(buildRow(entry));
        }
        add(rows, BorderLayout.NORTH);
        add(error, BorderLayout.SOUTH);
    }

    private JPanel buildRow(final NlpModelCatalogEntry entry) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        row.add(new JLabel(languageName(entry.getLanguageCode()) + " — " + entry.getModelId()));
        JLabel status = new JLabel();
        JButton install = new ComicButton("Install");
        install.addActionListener(event -> onInstall(entry));
        statusByModel.put(entry.getModelId(), status);
        buttonByModel.put(entry.getModelId(), install);
        row.add(Box.createHorizontalStrut(8));
        row.add(status);
        row.add(install);
        return row;
    }

    /** Recompute installed state for every row (no file paths — just Installed / Not installed). */
    void refresh() {
        for (NlpModelCatalogEntry entry : catalog.availableModels()) {
            JLabel status = statusByModel.get(entry.getModelId());
            JButton button = buttonByModel.get(entry.getModelId());
            if (status == null || button == null) {
                continue;
            }
            boolean isInstalled = isInstalled(entry);
            status.setText(isInstalled ? "Installed" : "Not installed");
            button.setVisible(!isInstalled);
            button.setEnabled(!isInstalled);
        }
    }

    private boolean isInstalled(NlpModelCatalogEntry entry) {
        List<String> ids = installed.listInstalledModels(entry.getCapability(), entry.getLanguageCode());
        return ids.contains(entry.getModelId());
    }

    private void onInstall(final NlpModelCatalogEntry entry) {
        final JButton button = buttonByModel.get(entry.getModelId());
        final JLabel status = statusByModel.get(entry.getModelId());
        if (button != null) {
            button.setEnabled(false);
        }
        if (status != null) {
            status.setText("Installing …");
        }
        error.setText(" ");
        background.execute(new Runnable() {
            public void run() {
                Exception failure = null;
                try {
                    installer.install(entry);
                } catch (Exception ex) {
                    failure = ex;
                }
                final Exception outcome = failure;
                ui.execute(new Runnable() {
                    public void run() {
                        if (outcome == null) {
                            refresh();          // now Installed → button hidden
                            onInstalled.run();  // GlobalCatalogRefresh so the settings dropdowns update
                        } else {
                            error.setText("Install failed: " + describe(outcome));
                            if (button != null) {
                                button.setEnabled(true);
                            }
                            if (status != null) {
                                status.setText("Not installed");
                            }
                        }
                    }
                });
            }
        });
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static String languageName(String code) {
        if ("de".equals(code)) {
            return "German";
        }
        if ("en".equals(code)) {
            return "English";
        }
        if ("*".equals(code)) {
            return "All languages"; // the language-neutral detector artifact
        }
        return code;
    }

    // ------------------------------------------------------------------ test hooks

    JButton installButton(String modelId) {
        return buttonByModel.get(modelId);
    }

    String statusText(String modelId) {
        JLabel label = statusByModel.get(modelId);
        return label == null ? null : label.getText();
    }

    String errorText() {
        return error.getText();
    }

    java.util.Set<String> modelIds() {
        return statusByModel.keySet();
    }

    // ------------------------------------------------------------------ productive wiring helpers

    private static InstallAction installActionOver(final LocalNlpModelStore store) {
        final NlpDownloadClient client = new HttpNlpDownloadClient();
        final NlpModelInstaller installer = new NlpModelInstaller(client, store);
        return new InstallAction() {
            public NlpModelInstaller.Outcome install(NlpModelCatalogEntry entry) throws Exception {
                return installer.install(entry);
            }
        };
    }

    private static Executor edt() {
        return new Executor() {
            public void execute(Runnable command) {
                SwingUtilities.invokeLater(command);
            }
        };
    }

    private static java.util.concurrent.ThreadFactory daemon(final String name) {
        return new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}

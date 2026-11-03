package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicToggleButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.config.HuggingFaceSearchSuggestion;
import com.aresstack.askai.hf.GgufFile;
import com.aresstack.askai.hf.HuggingFaceFile;
import com.aresstack.askai.hf.DownloadMetadataRecoveryIndex;
import com.aresstack.askai.hf.HuggingFaceInstallPlan;
import com.aresstack.askai.hf.HuggingFaceModel;
import com.aresstack.askai.hf.HuggingFaceSearchResult;
import com.aresstack.askai.hf.meta.OllamaCreateMetadata;
import com.aresstack.askai.hf.ModelSearchCriteria;
import com.aresstack.askai.hf.SearchFilterState;
import com.aresstack.askai.hf.SortOrder;
import com.aresstack.askai.hf.catalog.CatalogBundle;
import com.aresstack.askai.hf.catalog.CatalogRepository;
import com.aresstack.askai.hf.convert.ConverterService;
import com.aresstack.askai.hf.convert.RepositoryAnalysis;
import com.aresstack.askai.hf.convert.SupportDecision;
import com.aresstack.askai.service.AskAiService;
import com.aresstack.askai.service.VerificationResult;
import com.aresstack.askai.service.VerificationStatus;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class OllamaInstallPanel extends JPanel {

    private final AppConfigurationRepository configurationRepository;
    private final AskAiService askAiService;
    private final JComboBox<HuggingFaceSearchSuggestion> searchCombo;
    private final JButton searchButton;
    private final DefaultListModel<HuggingFaceModel> resultsModel;
    private final HuggingFaceResultsList resultsList;
    private final DefaultListModel<HuggingFaceFile> filesModel;
    private final JList<HuggingFaceFile> filesList;
    private final JTextField repoField;
    private final JPasswordField tokenField;
    private final JToggleButton tokenShowToggle = new ComicToggleButton("Show");
    private final JButton tokenClearButton = new ComicButton("Clear");
    private final JLabel tokenStatusLabel = new JLabel();
    private final JTextField installAsField;
    // Read-only: the quantization derived from the selected GGUF file name (no manual entry).
    private final JLabel quantizationLabel = new JLabel("—");
    private final JProgressBar progressBar;
    private final JButton cancelInstallButton;
    private final JCheckBox deleteAfterInstallCheckbox =
            ComicControls.checkBox("Delete download after install");
    /** R0: install the selection as a LOCAL AskAI runtime model instead of the GGUF/Ollama path. */
    private final JCheckBox installLocallyCheckbox = ComicControls.checkBox("Install locally in AskAI");
    /** True once the user manually changed "Install locally" for the CURRENT selection. */
    private boolean localInstallTouchedByUser = false;
    /** Guards the auto-activation setSelected from being counted as a user decision. */
    private boolean programmaticLocalToggle = false;
    /** Suppresses auto-activation during programmatic dropdown reloads. */
    private boolean suppressLocalAutoActivate = false;
    /** Identity (term + target) of the last handled selection, so a NEW selection resets the override.
     *  Using term+target avoids a collision when a local and a general entry share the same search term. */
    private String lastHandledSuggestionKey = "";
    private final JLabel repoCapabilityLabel = new JLabel(" ");
    private final JLabel importStatusLabel = new JLabel(" ");
    // Always-visible one-line summary of the latest step/result/error; the full history lives in the
    // collapsed "Technical details" log so the panel isn't dominated by technical output.
    private final JLabel statusLine = new JLabel(" ");
    private final JTextArea logArea;
    private final JToggleButton originalsToggle = new ComicToggleButton("Originals", true);
    private final JToggleButton variantsToggle = new ComicToggleButton("Variants");
    private final JToggleButton allToggle = new ComicToggleButton("All");
    private List<HuggingFaceModel> lastOriginalModels = Collections.emptyList();
    private List<HuggingFaceModel> lastVariantModels = Collections.emptyList();
    private File lastDownloadedFile;
    // Non-null while the panel is searching for an ENCODER to add to an already-installed model (add-on
    // mode). Transient UI state only — never persisted; a chosen encoder is attached via from/adapters.
    private String addOnTargetModel;
    // Bumped whenever an add-on operation starts or the mode is left (cancel / manual search / new op).
    // Every add-on download/attach callback captures the value at start and no-ops if it no longer matches,
    // so a cancelled or superseded operation can never finish attaching to the old target.
    private int addOnGeneration;
    private boolean addOnAttachRunning;
    // Fired after a verified encoder attach, so the owner can reload Installed Models from /api/show.
    private Runnable addOnAttachedListener;
    private AskAiService.InstallTask installTask;
    // The install contract frozen at the moment "Download and install" starts, so that re-selecting a
    // different search result during a long download can never mix another model's metadata into this
    // installation. Null for a plain download or a re-install from disk (which reads the sidecar).
    private HuggingFaceInstallPlan pendingInstallPlan;
    // Persistent record of downloads whose sidecar could not be written, so a later install (after a
    // restart, with no sidecar) is not silently degraded to a manual import.
    private final DownloadMetadataRecoveryIndex recoveryIndex = new DownloadMetadataRecoveryIndex(
            new File(com.aresstack.askai.settings.AskAiPaths.appDirectory().toFile(),
                    "download-metadata-recovery.json"));

    // Curated library quick-picks (a preview of the future Main-tab Libraries facet); values are
    // the real HuggingFace tag/filter values, labels are what the chip shows.
    private static final String[] LIBRARY_TAGS = {"gguf", "safetensors", "pytorch", "transformers", "onnx", "mlx"};
    private static final String[] LIBRARY_LABELS = {"GGUF", "Safetensors", "PyTorch", "Transformers", "ONNX", "MLX"};

    private final JComboBox<SortOrder> sortCombo = ComicControls.<SortOrder>comboBox(SortOrder.values());
    private final JToggleButton[] libraryToggles = buildLibraryToggles();
    private final JCheckBox baseOnlyCheckbox = ComicControls.checkBox("Base only");
    private final JButton loadMoreButton = new ComicButton("Load more");
    // Install actions that must be gated by the ConverterService support decision.
    private final JButton downloadButton = new ComicButton("Download");
    private final JButton fullInstallButton = new ComicButton("Download and install");
    private final JLabel activeFiltersLabel = new JLabel(" ");
    // The verified support decision + analysis + model for the currently selected repo (null until
    // analyzed / selected) — kept so the detail dialog can show them without re-fetching.
    private SupportDecision currentDecision;
    private RepositoryAnalysis currentAnalysis;
    private HuggingFaceModel currentModel;
    // Central, shared filter selection (all facet groups + base-only + sort). The library chips,
    // the base-only checkbox, the sort combo and the Filters dialog all read and write this one
    // object, so they never disagree; loaded from and saved to configuration.
    private final SearchFilterState filterState;
    // Filter catalogs holder: seeded synchronously from cache/bundled (instant, no network), then a
    // background live refresh swaps in fresh HuggingFace data. Read when the filter dialog opens.
    private CatalogBundle catalogBundle = new CatalogRepository().loadOffline();
    // Network-free provisional classifier for the initial list render; the authoritative,
    // file+config-based decision comes from AskAiService.analyzeRepository.
    private final ConverterService converterService = new ConverterService();
    // How many top hits the throttled background pass deep-analyzes after a search.
    private static final int BACKGROUND_ANALYSIS_LIMIT = 8;
    // Bumped on every new search so a slow background/selection analysis from a prior search is ignored.
    private int analysisGeneration;
    // The full result set accumulated across "load more" pages, re-classified into
    // originals/variants on every page so the toggle above stays consistent as more load in.
    private final List<HuggingFaceModel> accumulatedModels = new ArrayList<HuggingFaceModel>();
    private ModelSearchCriteria lastCriteria;
    private HuggingFaceSearchResult lastSearchResult;

    /** Which subset of the last search's results the left-hand list currently shows. */
    private enum ResultsFilterMode {
        ORIGINALS, VARIANTS, ALL
    }

    private static JToggleButton[] buildLibraryToggles() {
        JToggleButton[] toggles = new JToggleButton[LIBRARY_LABELS.length];
        for (int i = 0; i < toggles.length; i++) {
            toggles[i] = new ComicToggleButton(LIBRARY_LABELS[i], i == 0); // GGUF pre-selected, not hardcoded-forced
        }
        return toggles;
    }

    public OllamaInstallPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this.configurationRepository = configurationRepository;
        this.askAiService = askAiService;
        this.filterState = SearchFilterState.deserialize(
                configurationRepository.load().getHuggingFaceSearchFilters());
        this.searchCombo = ComicControls.<HuggingFaceSearchSuggestion>comboBox();
        this.searchCombo.setEditable(true);
        this.searchCombo.setRenderer(new SearchSuggestionRenderer());
        this.searchButton = new ComicButton("Search Hugging Face");
        this.resultsModel = new DefaultListModel<HuggingFaceModel>();
        this.resultsList = new HuggingFaceResultsList(resultsModel);
        this.filesModel = new DefaultListModel<HuggingFaceFile>();
        this.filesList = ComicControls.<HuggingFaceFile>list(filesModel);
        this.repoField = ComicControls.textField(30);
        this.tokenField = ComicControls.passwordField(24);
        this.installAsField = ComicControls.textField(24);
        this.progressBar = ComicControls.progressBar(0, 100);
        this.cancelInstallButton = new ComicButton(new CancelIcon(11));
        buildCancelButton();
        this.logArea = ComicControls.textArea(12, 80);
        buildUserInterface();
        // Restore the last-used search text (optional persistence, spec §21).
        if (filterState.getSearchText().length() > 0) {
            searchCombo.getEditor().setItem(filterState.getSearchText());
        }
        installSearchTextPersistence();
        refreshCatalogsInBackground();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(buildAddOnBanner(), BorderLayout.NORTH);
        top.add(buildSearchBar(), BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        // Resizable two-column layout: models on the left (toggled between Originals / Variants /
        // All), GGUF files on the right.
        javax.swing.JSplitPane listsSplit = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, buildResultsArea(), buildFilesArea());
        listsSplit.setResizeWeight(0.6d);
        listsSplit.setContinuousLayout(true);
        listsSplit.setBorder(null);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        bottom.add(buildForm(), BorderLayout.NORTH);
        bottom.add(buildCenter(), BorderLayout.CENTER);

        javax.swing.JSplitPane mainSplit = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, listsSplit, bottom);
        mainSplit.setResizeWeight(0.45d);
        mainSplit.setContinuousLayout(true);
        mainSplit.setBorder(null);
        add(mainSplit, BorderLayout.CENTER);

        progressBar.setStringPainted(true);
        JPanel progressRow = new JPanel(new BorderLayout(6, 0));
        deleteAfterInstallCheckbox.setToolTipText(
                "After a successful Ollama create, delete the local GGUF, its companion/mmproj, the sidecar "
                        + "and the recovery entry. The model created in Ollama is not affected.");
        installLocallyCheckbox.setToolTipText(
                "Install the selected repository into AskAI's local model runtime "
                        + "(win-directml-java) instead of creating an Ollama model. "
                        + "Runtime-compatible models catalogued for the AskAI local engine "
                        + "(embeddings, rerankers and native generation families) can be installed locally.");
        installLocallyCheckbox.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent event) {
                if (!programmaticLocalToggle) {
                    localInstallTouchedByUser = true;
                }
                onInstallLocallyToggled();
            }
        });
        JPanel installOptions = new JPanel();
        installOptions.setLayout(new BoxLayout(installOptions, BoxLayout.Y_AXIS));
        installOptions.add(deleteAfterInstallCheckbox);
        installOptions.add(installLocallyCheckbox);
        progressRow.add(installOptions, BorderLayout.WEST);
        // Bar and cancel button share one vertically centered cell at their natural height: hung
        // straight into the BorderLayout they were stretched to the height of the check box column
        // (a fat pill and a clipped, tall, narrow cancel capsule).
        JPanel progressCell = new JPanel(new java.awt.GridBagLayout());
        progressCell.setOpaque(false);
        java.awt.GridBagConstraints cell = new java.awt.GridBagConstraints();
        cell.gridy = 0;
        cell.fill = java.awt.GridBagConstraints.HORIZONTAL;
        cell.weightx = 1;
        progressCell.add(progressBar, cell);
        cell.fill = java.awt.GridBagConstraints.NONE;
        cell.weightx = 0;
        cell.insets = new Insets(0, 6, 0, 0);
        progressCell.add(cancelInstallButton, cell);
        progressRow.add(progressCell, BorderLayout.CENTER);
        add(progressRow, BorderLayout.SOUTH);
        loadTokenFromConfiguration();
    }

    private JComponent buildSearchBar() {
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchRow.add(new JLabel("Search"));
        searchCombo.setPreferredSize(new java.awt.Dimension(320, searchCombo.getPreferredSize().height));
        reloadSearchSuggestions();
        searchRow.add(searchCombo);
        searchRow.add(new JLabel("Sort"));
        sortCombo.setSelectedItem(filterState.getSortOrder());
        sortCombo.addActionListener(event -> {
            Object selected = sortCombo.getSelectedItem();
            if (selected instanceof SortOrder) {
                filterState.setSortOrder((SortOrder) selected);
            }
        });
        searchRow.add(sortCombo);
        searchRow.add(searchButton);
        JButton editSuggestionsButton = new ComicButton("Edit list...");
        editSuggestionsButton.setToolTipText("Edit the model suggestions shown in the dropdown");
        editSuggestionsButton.addActionListener(event -> editSearchSuggestions());
        searchRow.add(editSuggestionsButton);
        searchButton.addActionListener(event -> manualSearch());
        // Enter in the editable combo editor triggers the search, matching the old text field.
        searchCombo.getEditor().addActionListener(event -> manualSearch());
        // Selecting an AskAI-local-engine suggestion auto-activates "Install locally in AskAI".
        searchCombo.addActionListener(event -> autoActivateLocalInstallForSelection());

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filterRow.add(new JLabel("Libraries:"));
        for (int i = 0; i < libraryToggles.length; i++) {
            final int index = i;
            final String tag = LIBRARY_TAGS[index];
            libraryToggles[index].setSelected(filterState.isSelected(SearchFilterState.Group.LIBRARIES, tag));
            libraryToggles[index].addActionListener(event -> {
                filterState.setSelected(SearchFilterState.Group.LIBRARIES, tag, libraryToggles[index].isSelected());
                refreshActiveFilters();
            });
            filterRow.add(libraryToggles[index]);
        }
        baseOnlyCheckbox.setSelected(filterState.isBaseOnly());
        baseOnlyCheckbox.setToolTipText("<html>Approximation, not an exact Hugging Face server-side filter: "
                + "hides any hit that carries a base_model relation tag (finetune, quantized, adapter, "
                + "merge). Hugging Face's public search API only matches that tag exactly, not as a "
                + "\"has any relation\" filter, so this fetches a few extra pages to backfill toward the "
                + "requested count and reports it in the log if still short.</html>");
        baseOnlyCheckbox.addActionListener(event -> filterState.setBaseOnly(baseOnlyCheckbox.isSelected()));
        filterRow.add(baseOnlyCheckbox);
        JButton filtersButton = new ComicButton("Filters...");
        filtersButton.setToolTipText("Open the full filter dialog (tasks, libraries, languages, licenses, other)");
        filtersButton.addActionListener(event -> openFilterDialog());
        filterRow.add(filtersButton);
        filterRow.add(activeFiltersLabel);
        refreshActiveFilters();

        JPanel container = new JPanel(new BorderLayout(0, 2));
        container.add(searchRow, BorderLayout.NORTH);
        container.add(filterRow, BorderLayout.SOUTH);
        return container;
    }

    /** Opens the shared-state filter dialog; on Apply, re-syncs the quick chips and runs a search. */
    private void openFilterDialog() {
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        java.awt.Frame frame = owner instanceof java.awt.Frame ? (java.awt.Frame) owner : null;
        FilterDialog dialog = new FilterDialog(frame, filterState, catalogBundle, new FilterDialog.CatalogRefresher() {
            public void refresh(final FilterDialog.RefreshCallback callback) {
                askAiService.loadFilterCatalogs(true, new AskAiService.FilterCatalogListener() {
                    public void onLoaded(final CatalogBundle bundle) {
                        onUi(new Runnable() {
                            public void run() {
                                catalogBundle = bundle;
                                callback.done(bundle);
                            }
                        });
                    }

                    public void onError(final Exception ex) {
                        onUi(new Runnable() {
                            public void run() {
                                callback.failed(ex.getMessage());
                            }
                        });
                    }
                });
            }
        });
        dialog.setVisible(true);
        if (dialog.isApplied()) {
            syncFilterControlsFromState();
            refreshActiveFilters();
            searchModels();
        }
    }

    /** Kicks a one-off background live refresh of the catalogs at startup, swapping the holder on success. */
    private void refreshCatalogsInBackground() {
        askAiService.loadFilterCatalogs(false, new AskAiService.FilterCatalogListener() {
            public void onLoaded(final CatalogBundle bundle) {
                onUi(new Runnable() {
                    public void run() {
                        catalogBundle = bundle;
                    }
                });
            }

            public void onError(Exception ex) {
                // Keep the offline bundle; the dialog still works and shows Cache/Fallback origin.
            }
        });
    }

    /** Re-reads the library chips, base-only checkbox and sort combo from the shared filter state. */
    private void syncFilterControlsFromState() {
        for (int i = 0; i < libraryToggles.length; i++) {
            libraryToggles[i].setSelected(filterState.isSelected(SearchFilterState.Group.LIBRARIES, LIBRARY_TAGS[i]));
        }
        baseOnlyCheckbox.setSelected(filterState.isBaseOnly());
        sortCombo.setSelectedItem(filterState.getSortOrder());
    }

    /** Updates the compact active-filters summary shown next to the Filters button. */
    private void refreshActiveFilters() {
        int total = filterState.totalActiveFacets();
        StringBuilder summary = new StringBuilder();
        appendGroupCount(summary, "tasks", filterState.count(SearchFilterState.Group.TASKS));
        appendGroupCount(summary, "libs", filterState.count(SearchFilterState.Group.LIBRARIES));
        appendGroupCount(summary, "langs", filterState.count(SearchFilterState.Group.LANGUAGES));
        appendGroupCount(summary, "licenses", filterState.count(SearchFilterState.Group.LICENSES));
        appendGroupCount(summary, "other", filterState.count(SearchFilterState.Group.OTHER));
        appendGroupCount(summary, "apps", filterState.count(SearchFilterState.Group.APPS));
        activeFiltersLabel.setText(total == 0 ? "no filters" : "Active: " + summary);
    }

    private static void appendGroupCount(StringBuilder summary, String label, int count) {
        if (count > 0) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(label).append(' ').append(count);
        }
    }

    private JComponent buildResultsArea() {
        resultsList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                onResultSelected(resultsList);
            }
        });
        // Double-click a result to open its detail view (works for greyed/unsupported hits too).
        resultsList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    openDetailDialog();
                }
            }
        });
        JScrollPane resultsScroll = new JScrollPane(resultsList);
        resultsScroll.setBorder(ComicControls.titledBorder("Hugging Face models"));
        // Infinite-scroll: trigger "load more" when scrolled near the bottom, in addition to the
        // explicit button below (the spec allows either).
        resultsScroll.getVerticalScrollBar().addAdjustmentListener(event -> {
            javax.swing.JScrollBar bar = resultsScroll.getVerticalScrollBar();
            if (loadMoreButton.isEnabled() && bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 32) {
                loadMoreResults();
            }
        });

        loadMoreButton.setEnabled(false);
        loadMoreButton.addActionListener(event -> loadMoreResults());

        JPanel container = new JPanel(new BorderLayout(0, 4));
        container.add(buildResultsFilterToggle(), BorderLayout.NORTH);
        container.add(resultsScroll, BorderLayout.CENTER);
        container.add(loadMoreButton, BorderLayout.SOUTH);
        return container;
    }

    /** Radio-exclusive toggle switching the left list between originals, variants, and both. */
    private JComponent buildResultsFilterToggle() {
        ButtonGroup group = new ButtonGroup();
        group.add(originalsToggle);
        group.add(variantsToggle);
        group.add(allToggle);
        variantsToggle.setToolTipText("Community finetunes, merges and abliterations derived from an original model");
        allToggle.setToolTipText("Originals followed by variants");
        originalsToggle.addActionListener(event -> applyResultsFilter(ResultsFilterMode.ORIGINALS));
        variantsToggle.addActionListener(event -> applyResultsFilter(ResultsFilterMode.VARIANTS));
        allToggle.addActionListener(event -> applyResultsFilter(ResultsFilterMode.ALL));

        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toggleRow.add(new JLabel("Show:"));
        toggleRow.add(originalsToggle);
        toggleRow.add(variantsToggle);
        toggleRow.add(allToggle);
        return toggleRow;
    }

    /**
     * Switches which subset the results list shows and resets the file/capability selection — used
     * only by the Originals/Variants/All toggle buttons, where switching subsets should drop
     * whatever repo was selected. Loading more pages must NOT reset that selection (the user may be
     * browsing a repo's files while paging for more choices), so it calls
     * {@link #repopulateResultsList(ResultsFilterMode)} directly instead of this method.
     */
    private void applyResultsFilter(ResultsFilterMode mode) {
        filesModel.clear();
        setRepoCapability(" ");
        repopulateResultsList(mode);
    }

    /** @return which mode the Originals/Variants/All toggle is currently on. */
    private ResultsFilterMode currentFilterMode() {
        if (variantsToggle.isSelected()) {
            return ResultsFilterMode.VARIANTS;
        }
        if (allToggle.isSelected()) {
            return ResultsFilterMode.ALL;
        }
        return ResultsFilterMode.ORIGINALS;
    }

    /** Repopulate the results list from the cached, already classified/sorted search hits. */
    private void repopulateResultsList(ResultsFilterMode mode) {
        resultsModel.clear();
        if (mode == ResultsFilterMode.ORIGINALS) {
            for (HuggingFaceModel model : lastOriginalModels) {
                resultsModel.addElement(model);
            }
        } else if (mode == ResultsFilterMode.VARIANTS) {
            for (HuggingFaceModel model : lastVariantModels) {
                resultsModel.addElement(model);
            }
        } else {
            // "All": originals first, variants merged in after — each half is already sorted by
            // DISPLAY_ORDER, so re-sorting the merge would defeat "originals first".
            for (HuggingFaceModel model : lastOriginalModels) {
                resultsModel.addElement(model);
            }
            for (HuggingFaceModel model : lastVariantModels) {
                resultsModel.addElement(model);
            }
        }
    }

    private JComponent buildFilesArea() {
        filesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        filesList.setVisibleRowCount(8);
        filesList.setCellRenderer(new GgufFileRenderer());
        filesList.addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent event) {
                if (!event.getValueIsAdjusting()) {
                    updateQuantizationLabel();
                }
            }
        });
        JScrollPane filesScroll = new JScrollPane(filesList);
        filesScroll.setBorder(ComicControls.titledBorder("GGUF files"));
        return filesScroll;
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(ComicControls.titledBorder("Install"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;

        addRow(form, constraints, 0, "Repository", repoField);
        addRow(form, constraints, 1, "HF token (gated, optional)", buildTokenControls());
        addRow(form, constraints, 2, "Install as", installAsField);
        quantizationLabel.setToolTipText("Derived from the selected GGUF file name; not manually configurable");
        addRow(form, constraints, 3, "Quantization", quantizationLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton filesButton = new ComicButton("Load GGUF files");
        JButton detailsButton = new ComicButton("Details");
        detailsButton.setToolTipText("Show the repository details (files, architecture, formats, compatibility)");
        JButton importLastButton = new ComicButton("Install downloaded file");
        final JButton importMenuButton = new ComicButton("▾");
        importMenuButton.setToolTipText("Install another already-downloaded model");
        filesButton.addActionListener(event -> loadFiles());
        detailsButton.addActionListener(event -> openDetailDialog());
        downloadButton.addActionListener(event -> downloadSelected(false));
        fullInstallButton.addActionListener(event -> downloadSelected(true));
        importLastButton.addActionListener(event -> installDownloadedFile(null));
        importMenuButton.addActionListener(event -> showDownloadedFilesMenu(importMenuButton));
        // A split button: primary installs the current download, the arrow lists all downloads.
        JPanel installSplit = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        installSplit.add(importLastButton);
        installSplit.add(importMenuButton);
        buttons.add(filesButton);
        buttons.add(detailsButton);
        buttons.add(downloadButton);
        buttons.add(fullInstallButton);
        buttons.add(installSplit);

        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.gridx = 0;
        buttonConstraints.gridy = 4;
        buttonConstraints.gridwidth = 2;
        buttonConstraints.anchor = GridBagConstraints.WEST;
        form.add(buttons, buttonConstraints);

        GridBagConstraints capabilityConstraints = new GridBagConstraints();
        capabilityConstraints.gridx = 0;
        capabilityConstraints.gridy = 5;
        capabilityConstraints.gridwidth = 2;
        capabilityConstraints.anchor = GridBagConstraints.WEST;
        form.add(repoCapabilityLabel, capabilityConstraints);

        GridBagConstraints importStatusConstraints = new GridBagConstraints();
        importStatusConstraints.gridx = 0;
        importStatusConstraints.gridy = 6;
        importStatusConstraints.gridwidth = 2;
        importStatusConstraints.anchor = GridBagConstraints.WEST;
        importStatusLabel.setToolTipText("Import support decided by the ConverterService from the "
                + "repository files and config.json");
        form.add(importStatusLabel, importStatusConstraints);
        return form;
    }

    /** Builds the masked HF-token row: password field + Show toggle + Clear + a "token set" hint. */
    private JComponent buildTokenControls() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        final char defaultEcho = tokenField.getEchoChar();
        tokenShowToggle.setToolTipText("Show or hide the token");
        tokenShowToggle.addActionListener(event ->
                tokenField.setEchoChar(tokenShowToggle.isSelected() ? (char) 0 : defaultEcho));
        tokenClearButton.setToolTipText("Remove the stored Hugging Face token");
        tokenClearButton.setForeground(ThemeColor.of("models.destructiveActionText"));
        tokenClearButton.addActionListener(event -> {
            tokenField.setText("");
            saveTokenToConfiguration();
            updateTokenStatus();
        });
        tokenStatusLabel.setForeground(ThemeColor.of("models.secondaryText"));
        tokenField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateTokenStatus(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateTokenStatus(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateTokenStatus(); }
        });
        row.add(tokenField);
        row.add(tokenShowToggle);
        row.add(tokenClearButton);
        row.add(tokenStatusLabel);
        return row;
    }

    /** Reflects whether a token is present without ever revealing it. */
    private void updateTokenStatus() {
        boolean present = tokenField.getPassword().length > 0;
        tokenStatusLabel.setText(present ? "Token set" : "No token");
    }

    private JComponent buildCenter() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new java.awt.Dimension(scroll.getPreferredSize().width, 180));
        // Technical output stays out of the way by default: the concise status line stays visible,
        // the full log lives in a collapsed "Technical details" section.
        JPanel center = new JPanel(new BorderLayout(0, 4));
        statusLine.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        center.add(statusLine, BorderLayout.NORTH);
        center.add(new CollapsiblePanel("Technical details", scroll, false), BorderLayout.CENTER);
        return center;
    }

    /**
     * @return the quantization label (e.g. "Q4_K_M") parsed from a GGUF file name, or "" when the
     *         name carries no recognizable quant token. Upper-cased for display.
     */
    static String quantFromFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase();
        for (int i = 0; i < PREFERRED_QUANTS.length; i++) {
            if (lower.contains(PREFERRED_QUANTS[i])) {
                return PREFERRED_QUANTS[i].toUpperCase();
            }
        }
        return "";
    }

    /** Updates the read-only quantization label from the currently selected GGUF file. */
    private void updateQuantizationLabel() {
        HuggingFaceFile selected = filesList.getSelectedValue();
        String quant = selected == null ? "" : quantFromFileName(selected.getFileName());
        quantizationLabel.setText(quant.isEmpty() ? "—" : quant);
    }

    private void addRow(JPanel form, GridBagConstraints constraints, int row, String label, java.awt.Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0.0d;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        form.add(field, constraints);
    }

    /** Fills the dropdown with the configured suggestions, keeping any text the user typed. */
    private void reloadSearchSuggestions() {
        suppressLocalAutoActivate = true;
        try {
            Object typed = searchCombo.getEditor().getItem();
            searchCombo.removeAllItems();
            for (HuggingFaceSearchSuggestion suggestion
                    : configurationRepository.load().getHuggingFaceSearchSuggestions()) {
                searchCombo.addItem(suggestion);
            }
            // Speech-output DISCOVERY entries (🔊): UI-injected, never persisted — selecting one
            // opens the dedicated Speech Output install flow instead of a HuggingFace search.
            for (HuggingFaceSearchSuggestion discovery : SpeechOutputSuggestions.entries()) {
                searchCombo.addItem(discovery);
            }
            searchCombo.setSelectedItem(typed == null ? "" : typed);
        } finally {
            suppressLocalAutoActivate = false;
        }
    }

    /**
     * Auto-activates "Install locally in AskAI" when an AskAI-local-engine suggestion is chosen. A manual
     * change the user makes afterwards for that same selection is respected (not re-forced on every UI
     * event); selecting a DIFFERENT suggestion is fresh intent and re-evaluates.
     */
    private void autoActivateLocalInstallForSelection() {
        if (suppressLocalAutoActivate) {
            return;
        }
        Object selected = searchCombo.getSelectedItem();
        if (!(selected instanceof HuggingFaceSearchSuggestion)) {
            return;
        }
        HuggingFaceSearchSuggestion suggestion = (HuggingFaceSearchSuggestion) selected;
        if (openSpeechOutputFor(suggestion.getTerm())) {
            return; // 🔊 discovery entry: navigation to Speech Output, not a search term
        }
        String key = suggestion.getTerm() + "\u0000" + suggestion.getTarget().name();
        if (!key.equals(lastHandledSuggestionKey)) {
            lastHandledSuggestionKey = key;
            localInstallTouchedByUser = false; // a new selection is fresh intent
        }
        if (suggestion.isLocalEngine() && !localInstallTouchedByUser
                && !installLocallyCheckbox.isSelected()) {
            programmaticLocalToggle = true;
            try {
                installLocallyCheckbox.setSelected(true);
            } finally {
                programmaticLocalToggle = false;
            }
        }
    }

    /** The owner's hand-over for 🔊 entries (voice id → chat settings, Audio & Dictation), or null. */
    private java.util.function.Consumer<String> speechOutputOpener;

    /** Wired via {@link ModelSearchPanel}: 🔊 discovery entries open the voice-install SECTION. */
    void setSpeechOutputOpener(java.util.function.Consumer<String> opener) {
        this.speechOutputOpener = opener;
    }

    /**
     * @return true when {@code term} is a 🔊 speech-output discovery entry — the Speech Output
     *         flow was opened (if wired) and the editor cleared, so no search must run
     */
    private boolean openSpeechOutputFor(String term) {
        final String voiceId = SpeechOutputSuggestions.voiceIdForTerm(term);
        if (voiceId == null) {
            return false;
        }
        searchCombo.getEditor().setItem(""); // the marker is navigation, never a query
        final java.util.function.Consumer<String> opener = speechOutputOpener;
        if (opener != null) {
            // Later on the EDT: let the combo's selection events settle before the tab switches.
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    opener.accept(voiceId);
                }
            });
        }
        return true;
    }

    /** Opens a small editor for the dropdown suggestions (one per line) and persists the list. */
    private void editSearchSuggestions() {
        AppConfiguration current = configurationRepository.load();
        JTextArea editor = ComicControls.textArea(current.getHuggingFaceSearchSuggestionsRaw(), 14, 40);
        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.add(new JLabel("<html>One suggestion per line: "
                + "<b>&lt;search term&gt; | &lt;input&gt;,&lt;input&gt; | &lt;target&gt;</b>"
                + " &mdash; inputs: text, audio, vision.<br>"
                + "Tag audio/vision only when a GGUF repo for that search ships the model's encoder"
                + " (mmproj); otherwise the model is text-only when installed from HuggingFace.<br>"
                + "The optional third field <b>local</b> marks a model catalogued for AskAI's local "
                + "win-directml engine (shown in the ASKAI LOCAL ENGINE group). Omit it for a normal "
                + "Ollama/GGUF import.</html>"),
                BorderLayout.NORTH);
        content.add(new JScrollPane(editor), BorderLayout.CENTER);
        JButton restoreDefaults = new ComicButton("Restore defaults");
        restoreDefaults.addActionListener(event -> editor.setText(AppConfiguration.DEFAULT_HF_SEARCH_SUGGESTIONS));
        JPanel southRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        southRow.add(restoreDefaults);
        content.add(southRow, BorderLayout.SOUTH);
        int choice = JOptionPane.showConfirmDialog(this, content,
                "Search suggestions", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        configurationRepository.save(current.withHuggingFaceSearchSuggestions(editor.getText()));
        reloadSearchSuggestions();
        append("Search suggestions updated ("
                + configurationRepository.load().getHuggingFaceSearchSuggestions().size() + " entries).");
    }

    /** The group heading shown above the first entry of each target group in the dropdown. */
    static String groupHeader(HuggingFaceSearchSuggestion.Target target) {
        return target == HuggingFaceSearchSuggestion.Target.ASKAI_LOCAL_ENGINE
                ? "ASKAI LOCAL ENGINE" : "GENERAL / OLLAMA IMPORT";
    }

    /** Tooltip shown on every AskAI-local-engine suggestion (independent of colour). */
    static final String LOCAL_ENGINE_TOOLTIP = "Runs in AskAI's local win-directml-java engine";

    /**
     * Renders each suggestion with the term on the left and the fixed modality icon column right-aligned.
     * AskAI-local-engine entries are set apart WITHOUT relying on colour alone: a group heading
     * ({@link #groupHeader}), a textual {@code LOCAL} badge, a theme-derived accent background (FlatLaf
     * {@code Component.accentColor}) and a tooltip. Group headings appear at each target boundary.
     */
    private static final class SearchSuggestionRenderer extends JPanel
            implements javax.swing.ListCellRenderer<Object> {

        private final JLabel headerLabel = new JLabel();
        private final JLabel badgeLabel = new JLabel();
        private final JLabel termLabel = new JLabel();
        private final JLabel iconLabel = new JLabel();
        private final JPanel row = new JPanel(new BorderLayout(8, 0));

        SearchSuggestionRenderer() {
            super(new BorderLayout(0, 0));
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            setOpaque(true);
            row.setOpaque(false);
            iconLabel.setToolTipText("Model modalities (text / audio / vision). Audio/vision also needs "
                    + "the model's encoder (mmproj); the installer fetches it from the repo when present.");
            badgeLabel.setText("LOCAL");
            badgeLabel.setOpaque(true);
            badgeLabel.setVisible(false);
            headerLabel.setVisible(false);
            row.add(badgeLabel, BorderLayout.WEST);
            row.add(termLabel, BorderLayout.CENTER);
            row.add(iconLabel, BorderLayout.EAST);
            add(headerLabel, BorderLayout.NORTH);
            add(row, BorderLayout.CENTER);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean isSelected, boolean cellHasFocus) {
            HuggingFaceSearchSuggestion.Target target = HuggingFaceSearchSuggestion.Target.GENERAL;
            if (value instanceof HuggingFaceSearchSuggestion) {
                HuggingFaceSearchSuggestion suggestion = (HuggingFaceSearchSuggestion) value;
                target = suggestion.getTarget();
                termLabel.setText(suggestion.getTerm());
                iconLabel.setIcon(CapabilityIcons.forCapabilities(
                        ModelCapability.fromModalities(suggestion.getModalities())));
            } else {
                termLabel.setText(value == null ? "" : String.valueOf(value));
                iconLabel.setIcon(null);
            }
            boolean local = target == HuggingFaceSearchSuggestion.Target.ASKAI_LOCAL_ENGINE;

            // Group heading at each target boundary (popup only; index < 0 is the closed-combo display).
            boolean boundary = index == 0;
            if (index > 0 && value instanceof HuggingFaceSearchSuggestion) {
                Object prev = list.getModel().getElementAt(index - 1);
                HuggingFaceSearchSuggestion.Target prevTarget =
                        prev instanceof HuggingFaceSearchSuggestion
                                ? ((HuggingFaceSearchSuggestion) prev).getTarget()
                                : HuggingFaceSearchSuggestion.Target.GENERAL;
                boundary = prevTarget != target;
            }
            if (index >= 0 && boundary) {
                headerLabel.setVisible(true);
                headerLabel.setText(groupHeader(target));
                headerLabel.setFont(list.getFont().deriveFont(java.awt.Font.BOLD,
                        Math.max(10f, list.getFont().getSize2D() - 1f)));
                headerLabel.setForeground(mutedForeground(list));
                headerLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));
            } else {
                headerLabel.setVisible(false);
            }

            java.awt.Color accent = accentColor(list);
            badgeLabel.setVisible(local);
            if (local) {
                badgeLabel.setFont(list.getFont().deriveFont(java.awt.Font.BOLD,
                        Math.max(9f, list.getFont().getSize2D() - 2f)));
                badgeLabel.setBackground(accent);
                badgeLabel.setForeground(readableOn(accent));
                badgeLabel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(accent),
                        BorderFactory.createEmptyBorder(0, 5, 0, 5)));
                setToolTipText(LOCAL_ENGINE_TOOLTIP);
                row.setToolTipText(LOCAL_ENGINE_TOOLTIP);
                termLabel.setToolTipText(LOCAL_ENGINE_TOOLTIP);
            } else {
                setToolTipText(null);
                row.setToolTipText(null);
                termLabel.setToolTipText(null);
            }

            // Background: selection wins; otherwise a subtle theme-derived accent wash for local rows.
            java.awt.Color background;
            if (isSelected) {
                background = list.getSelectionBackground();
                termLabel.setForeground(list.getSelectionForeground());
            } else {
                background = local && index >= 0
                        ? blend(list.getBackground(), accent, 0.12f) : list.getBackground();
                termLabel.setForeground(list.getForeground());
            }
            setBackground(background);
            termLabel.setFont(list.getFont());
            return this;
        }

        private static java.awt.Color accentColor(JList<?> list) {
            java.awt.Color accent = javax.swing.UIManager.getColor("Component.accentColor");
            if (accent == null) {
                accent = javax.swing.UIManager.getColor("List.selectionBackground");
            }
            return accent == null ? list.getSelectionBackground() : accent;
        }

        private static java.awt.Color mutedForeground(JList<?> list) {
            java.awt.Color fg = javax.swing.UIManager.getColor("Label.disabledForeground");
            return fg == null ? blend(list.getForeground(), list.getBackground(), 0.4f) : fg;
        }

        private static java.awt.Color readableOn(java.awt.Color background) {
            double luminance = (0.299 * background.getRed() + 0.587 * background.getGreen()
                    + 0.114 * background.getBlue()) / 255.0;
            return luminance > 0.6 ? ThemeColor.of("models.localBadgeDarkText")
                    : ThemeColor.of("bubble.userForeground");
        }

        /** {@code base} mixed towards {@code other} by {@code ratio}, derived live (same rounding as before). */
        private static java.awt.Color blend(java.awt.Color base, java.awt.Color other, float ratio) {
            return ThemeColor.mix(base, other, ratio);
        }
    }

    /**
     * Enters add-on mode for an already-installed model and searches Hugging Face for an encoder to attach.
     * The chosen GGUF is validated as a projector and attached via {@code from}/{@code adapters} — no new
     * model is created and no capabilities are declared. Transient UI state only; nothing is persisted.
     */
    public void openHuggingFaceAddOnSearch(String existingModelName, String query) {
        enterAddOnMode(existingModelName);
        if (!isAddOnMode()) {
            return;
        }
        // The programmatic search below must NOT go through the manual-search path (which clears the mode).
        searchFor(query);
    }

    /**
     * Enters add-on mode and opens a local file chooser to pick a projector GGUF from disk. The chosen file
     * takes exactly the same path as the Hugging Face flow: inspect → attach (from/adapters) → verify.
     */
    public void openLocalProjectorAddOn(String existingModelName) {
        enterAddOnMode(existingModelName);
        if (!isAddOnMode()) {
            return;
        }
        final String target = addOnTargetModel;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select a projector (mmproj) GGUF to attach to \"" + target + "\"");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("GGUF files (*.gguf)", "gguf"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            append("No file chosen — left add-on mode.");
            clearAddOnMode();
            return;
        }
        File chosen = chooser.getSelectedFile();
        // A fresh operation id for this local attach, so cancel / a new op invalidates its callbacks too.
        final int gen = ++addOnGeneration;
        attachDownloadedEncoder(target, chosen, gen);
    }

    /** Sets the add-on target model and shows the banner + switches the controls into attach-only mode. */
    private void enterAddOnMode(String existingModelName) {
        this.addOnTargetModel = existingModelName == null ? null : existingModelName.trim();
        if (isAddOnMode()) {
            append("Add-on mode: choose a multimodal encoder (mmproj) to attach to \""
                    + this.addOnTargetModel + "\". It will be added to the existing model, not installed as "
                    + "a new one.");
            if (addOnBannerLabel != null) {
                addOnBannerLabel.setText("Add-on mode: attaching an encoder to \"" + this.addOnTargetModel
                        + "\" (Download and attach) — not installing a new model.");
            }
            if (addOnBanner != null) {
                addOnBanner.setVisible(true);
            }
        }
        updateAddOnModeControls();
    }

    /** Wires a callback fired after a verified encoder attach (the owner reloads Installed Models). */
    public void setAddOnAttachedListener(Runnable listener) {
        this.addOnAttachedListener = listener;
    }

    /** Public entry so the owning frame can drop the transient target when leaving the Setup/HF view. */
    public void leaveAddOnMode() {
        if (isAddOnMode() || addOnAttachRunning) {
            clearAddOnMode();
        }
    }

    /** Leaves add-on mode (back to normal install) on any terminal path, and hides the banner. */
    private void clearAddOnMode() {
        // Invalidate any in-flight add-on download/attach callbacks so they cannot finish attaching.
        addOnGeneration++;
        if (addOnAttachRunning && installTask != null) {
            installTask.cancel();
            addOnAttachRunning = false;
        }
        this.addOnTargetModel = null;
        if (addOnBanner != null) {
            addOnBanner.setVisible(false);
        }
        updateAddOnModeControls();
    }

    /**
     * In add-on mode there is exactly ONE unambiguous action: hide the plain Download, relabel the primary
     * button "Download and attach", and disable the install-name field (a normal model install is impossible
     * here). Outside add-on mode everything returns to the normal install controls.
     */
    private void updateAddOnModeControls() {
        boolean addOn = isAddOnMode();
        downloadButton.setVisible(!addOn);
        fullInstallButton.setText(addOn ? "Download and attach" : "Download and install");
        fullInstallButton.setToolTipText(addOn
                ? "Download the chosen encoder and attach it to the existing model"
                : null);
        if (installAsField != null) {
            installAsField.setEnabled(!addOn);
        }
    }

    /** @return true while an encoder is being attached to an existing model. */
    private boolean isAddOnMode() {
        return addOnTargetModel != null && addOnTargetModel.length() > 0;
    }

    private JLabel addOnBannerLabel;

    /** A dismissible banner shown only in add-on mode, so the transient target model is always visible. */
    private JComponent buildAddOnBanner() {
        addOnBannerLabel = new JLabel();
        JButton cancel = new ComicButton("Cancel add-on mode");
        cancel.addActionListener(event -> {
            append("Add-on mode cancelled.");
            clearAddOnMode();
        });
        JPanel banner = new JPanel(new BorderLayout(8, 0));
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColor.of("comic.accentOrange")),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        banner.setBackground(ThemeColor.of("models.addOnBannerBackground"));
        banner.setOpaque(true);
        banner.add(addOnBannerLabel, BorderLayout.CENTER);
        banner.add(cancel, BorderLayout.EAST);
        banner.setVisible(false);
        this.addOnBanner = banner;
        return banner;
    }

    private JComponent addOnBanner;

    /** Prefills the search box with {@code query} and runs the search (used to route from a model card). */
    public void searchFor(String query) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        // Strip an Ollama tag (":latest" etc.) — the repo name is the useful Hugging Face query.
        String cleaned = query.trim();
        int colon = cleaned.indexOf(':');
        if (colon > 0) {
            cleaned = cleaned.substring(0, colon);
        }
        searchCombo.getEditor().setItem(cleaned);
        searchModels();
    }

    /** A user-initiated search: leaves add-on mode first, so its target model is not silently kept. */
    private void manualSearch() {
        if (isAddOnMode()) {
            append("Left add-on mode (started a new search).");
            clearAddOnMode();
        }
        searchModels();
    }

    private void searchModels() {
        final String query = String.valueOf(searchCombo.getEditor().getItem()).trim();
        if (query.length() == 0) {
            append("Enter a search term, e.g. qwen2.5 coder 0.5b.");
            return;
        }
        if (openSpeechOutputFor(query)) {
            return; // a 🔊 discovery entry is navigation, never a HuggingFace search
        }
        saveTokenToConfiguration();
        final ModelSearchCriteria criteria = buildCriteria(query);
        lastCriteria = criteria;
        lastSearchResult = null;
        accumulatedModels.clear();
        lastOriginalModels = Collections.emptyList();
        lastVariantModels = Collections.emptyList();
        resultsList.clearStatuses();
        analysisGeneration++;
        originalsToggle.setSelected(true);
        applyResultsFilter(ResultsFilterMode.ORIGINALS);
        searchButton.setEnabled(false);
        loadMoreButton.setEnabled(false);
        append("Searching Hugging Face for \"" + query + "\" (" + criteria.getSortOrder().getDisplayName()
                + (criteria.isBaseOnly() ? ", base only" : "") + ") ...");
        askAiService.searchHuggingFaceModels(criteria, new AskAiService.HuggingFaceSearchListener() {
            public void onResult(final HuggingFaceSearchResult result) {
                onUi(new Runnable() {
                    public void run() {
                        searchButton.setEnabled(true);
                        lastSearchResult = result;
                        accumulatedModels.addAll(result.getModels());
                        loadMoreButton.setEnabled(result.isLoadMoreSupported());
                        reclassifyAccumulated();
                        seedProvisionalStatuses();
                        scheduleBackgroundAnalysis();
                        append("Found " + result.getModels().size() + " model(s)"
                                + (result.getNote() != null ? " — " + result.getNote() : "")
                                + ". Select one to install.");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        searchButton.setEnabled(true);
                        append("Search failed: " + ex.getMessage());
                    }
                });
            }
        });
    }

    /** Continues pagination from the last search's next-page cursor and appends to the results. */
    private void loadMoreResults() {
        if (lastCriteria == null || lastSearchResult == null || !lastSearchResult.isLoadMoreSupported()) {
            return;
        }
        final ModelSearchCriteria criteria = lastCriteria;
        loadMoreButton.setEnabled(false);
        append("Loading more results ...");
        askAiService.loadMoreHuggingFaceModels(criteria, lastSearchResult, new AskAiService.HuggingFaceSearchListener() {
            public void onResult(final HuggingFaceSearchResult result) {
                onUi(new Runnable() {
                    public void run() {
                        lastSearchResult = result;
                        accumulatedModels.addAll(result.getModels());
                        loadMoreButton.setEnabled(result.isLoadMoreSupported());
                        reclassifyAccumulated();
                        seedProvisionalStatuses();
                        append("Loaded " + result.getModels().size() + " more model(s)"
                                + (result.getNote() != null ? " — " + result.getNote() : "") + ".");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        loadMoreButton.setEnabled(true);
                        append("Load more failed: " + ex.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Re-splits and re-sorts the full accumulated result set (every page loaded so far, not just the
     * newest one) into originals/variants, so the Originals/Variants/All toggle stays complete and
     * correctly ordered as more pages load in, then repaints the currently selected mode.
     */
    private void reclassifyAccumulated() {
        List<HuggingFaceModel> originals = new ArrayList<HuggingFaceModel>();
        List<HuggingFaceModel> variants = new ArrayList<HuggingFaceModel>();
        for (HuggingFaceModel model : accumulatedModels) {
            if (HuggingFaceModelClassifier.isVariant(model)) {
                variants.add(model);
            } else {
                originals.add(model);
            }
        }
        Collections.sort(originals, HuggingFaceModelClassifier.DISPLAY_ORDER);
        Collections.sort(variants, HuggingFaceModelClassifier.DISPLAY_ORDER);
        lastOriginalModels = originals;
        lastVariantModels = variants;
        repopulateResultsList(currentFilterMode());
    }

    /**
     * Builds the search criteria from the shared filter state (text + every facet group + sort +
     * base-only) and persists the current filter selection so it survives restarts.
     */
    private ModelSearchCriteria buildCriteria(String query) {
        filterState.setSearchText(query);
        persistFilterState();
        return filterState.toCriteria(query);
    }

    /**
     * Persists the search text whenever the user edits it (debounced), not only when a search runs.
     * Before, clearing the field was never saved, so the last searched text (e.g. "au") came back
     * after every restart.
     */
    private void installSearchTextPersistence() {
        java.awt.Component editor = searchCombo.getEditor().getEditorComponent();
        if (!(editor instanceof javax.swing.text.JTextComponent)) {
            return;
        }
        final javax.swing.text.JTextComponent text = (javax.swing.text.JTextComponent) editor;
        final javax.swing.Timer saveTimer = new javax.swing.Timer(600, event -> {
            String current = text.getText() == null ? "" : text.getText().trim();
            if (!current.equals(filterState.getSearchText())) {
                filterState.setSearchText(current);
                persistFilterState();
            }
        });
        saveTimer.setRepeats(false);
        text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { saveTimer.restart(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { saveTimer.restart(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveTimer.restart(); }
        });
    }

    /** Saves the current filter selection into configuration (serialized, alongside the token etc.). */
    private void persistFilterState() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(current.withHuggingFaceSearchFilters(filterState.serialize()));
    }

    private void onResultSelected(JList<HuggingFaceModel> source) {
        HuggingFaceModel selected = source.getSelectedValue();
        if (selected == null) {
            return;
        }
        currentModel = selected;
        currentAnalysis = null;
        repoField.setText(selected.getId());
        installAsField.setText(suggestInstallName(selected.getId()));
        analyzeSelectedRepository(selected.getId());
        if (installLocallyCheckbox.isSelected()) {
            analyzeLocalCompatibilityForPanel();
        }
        loadFiles();
    }

    /** Seeds a fast, network-free provisional support status for any hit not yet classified. */
    private void seedProvisionalStatuses() {
        for (HuggingFaceModel model : accumulatedModels) {
            if (resultsList.getStatus(model.getId()) == null) {
                resultsList.setStatus(model.getId(), converterService.provisionalClassify(model));
            }
        }
    }

    /**
     * Deep-analyzes the top hits sequentially (concurrency 1, capped) in the background so the list's
     * greying converges to the authoritative, file+config-based verdict without firing dozens of
     * requests at once on a flaky connection. Stale passes (from a previous search) are ignored via
     * the generation counter.
     */
    private void scheduleBackgroundAnalysis() {
        analyzeNextInBackground(0, analysisGeneration);
    }

    private void analyzeNextInBackground(final int index, final int generation) {
        if (generation != analysisGeneration || index >= Math.min(BACKGROUND_ANALYSIS_LIMIT, lastOriginalModels.size())) {
            return;
        }
        final HuggingFaceModel model = lastOriginalModels.get(index);
        SupportDecision existing = resultsList.getStatus(model.getId());
        if (existing != null && existing.isVerified()) {
            analyzeNextInBackground(index + 1, generation);
            return;
        }
        askAiService.analyzeRepository(model.getId(), new AskAiService.RepositoryAnalysisListener() {
            public void onDecision(final SupportDecision decision, final RepositoryAnalysis analysis) {
                onUi(new Runnable() {
                    public void run() {
                        if (generation == analysisGeneration) {
                            resultsList.setStatus(model.getId(), decision);
                            analyzeNextInBackground(index + 1, generation);
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        // Leave the provisional status; keep the chain going.
                        if (generation == analysisGeneration) {
                            analyzeNextInBackground(index + 1, generation);
                        }
                    }
                });
            }
        });
    }

    /**
     * Runs the authoritative analysis for the selected repository and gates the install actions on
     * its verdict, with a concrete reason. Runs {@code loadFiles()} regardless, so an unsupported
     * (greyed) hit stays accessible — its files and rejection reason are still viewable.
     */
    private void analyzeSelectedRepository(final String repoId) {
        currentDecision = null;
        final int generation = analysisGeneration;
        SupportDecision existing = resultsList.getStatus(repoId);
        if (existing == null || !existing.isVerified()) {
            resultsList.setStatus(repoId, SupportDecision.checking());
        }
        setInstallActionsEnabled(false, "Kompatibilität wird geprüft …");
        askAiService.analyzeRepository(repoId, new AskAiService.RepositoryAnalysisListener() {
            public void onDecision(final SupportDecision decision, final RepositoryAnalysis analysis) {
                onUi(new Runnable() {
                    public void run() {
                        resultsList.setStatus(repoId, decision);
                        // Only gate for the repo that is still selected.
                        if (repoId.equals(repoField.getText().trim())) {
                            currentDecision = decision;
                            currentAnalysis = analysis;
                            // This judgement is about the OLLAMA import path only. Prefix it so
                            // an unsupported GGUF route never reads as a rejection by the LOCAL
                            // runtime (which has its own check when the local checkbox is active).
                            setInstallActionsEnabled(decision.isExecutable(),
                                    "Ollama import: " + decision.getReason());
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        if (repoId.equals(repoField.getText().trim())) {
                            // Analysis failed: don't hard-block install, but say so honestly.
                            currentDecision = null;
                            setInstallActionsEnabled(true, "Kompatibilität nicht prüfbar: " + ex.getMessage());
                        }
                    }
                });
            }
        });
    }

    /**
     * Opens the repository detail view for the current selection (or the typed repo id). Works for
     * greyed/unsupported hits — the dialog re-runs the analysis and shows the same authoritative
     * verdict, files, formats, architecture and rejection reason.
     */
    private void openDetailDialog() {
        final String repoId = repoField.getText().trim();
        if (repoId.length() == 0) {
            append("Select a result or type a repository id first.");
            return;
        }
        HuggingFaceModel forModel = currentModel != null && currentModel.getId().equals(repoId)
                ? currentModel : new HuggingFaceModel(repoId, "", 0L, 0L);
        boolean sameModel = forModel == currentModel;
        SupportDecision initialDecision = sameModel ? currentDecision : null;
        RepositoryAnalysis initialAnalysis = sameModel ? currentAnalysis : null;

        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        java.awt.Frame frame = owner instanceof java.awt.Frame ? (java.awt.Frame) owner : null;
        RepositoryDetailDialog.Analyzer analyzer = new RepositoryDetailDialog.Analyzer() {
            public void analyze(String modelId, final RepositoryDetailDialog.AnalysisCallback callback) {
                askAiService.analyzeRepository(modelId, new AskAiService.RepositoryAnalysisListener() {
                    public void onDecision(final SupportDecision decision, final RepositoryAnalysis analysis) {
                        onUi(new Runnable() {
                            public void run() {
                                callback.onResult(decision, analysis);
                            }
                        });
                    }

                    public void onError(final Exception ex) {
                        onUi(new Runnable() {
                            public void run() {
                                callback.onError(ex.getMessage());
                            }
                        });
                    }
                });
            }
        };
        new RepositoryDetailDialog(frame, forModel, initialDecision, initialAnalysis, analyzer).setVisible(true);
    }

    /** Re-evaluates the install actions when "Install locally in AskAI" is toggled. */
    private void onInstallLocallyToggled() {
        String repoId = repoField.getText().trim();
        if (installLocallyCheckbox.isSelected()) {
            if (repoId.length() == 0) {
                setInstallActionsEnabled(false, "Pick a repository to check local compatibility.");
            } else {
                analyzeLocalCompatibilityForPanel();
            }
        } else if (repoId.length() > 0) {
            analyzeSelectedRepository(repoId);
        }
    }

    /** Checks the selected repository against AskAI's LOCAL runtime and gates the install button. */
    private void analyzeLocalCompatibilityForPanel() {
        final String repoId = repoField.getText().trim();
        if (repoId.length() == 0) {
            return;
        }
        setInstallActionsEnabled(false, "Checking local runtime compatibility of " + repoId + " ...");
        askAiService.analyzeLocalRuntimeCompatibility(repoId,
                new AskAiService.LocalCompatibilityListener() {
                    public void onResult(final com.aresstack.askai.localmodels
                            .LocalModelCompatibilityResult result) {
                        onUi(new Runnable() {
                            public void run() {
                                if (!installLocallyCheckbox.isSelected()
                                        || !repoId.equals(repoField.getText().trim())) {
                                    return; // stale answer for another selection/mode
                                }
                                if (result.isSupported()) {
                                    setInstallActionsEnabled(true,
                                            "Local runtime compatibility: Supported - Runtime: "
                                                    + "win-directml-java, Capability: Rerank, "
                                                    + "Backend: CPU");
                                } else {
                                    setInstallActionsEnabled(false,
                                            "Local runtime compatibility: " + result.getStatus()
                                                    + " - " + result.getReason());
                                }
                            }
                        });
                    }

                    public void onError(final Exception exception) {
                        onUi(new Runnable() {
                            public void run() {
                                if (installLocallyCheckbox.isSelected()) {
                                    setInstallActionsEnabled(false,
                                            "Local compatibility check failed: "
                                                    + exception.getMessage());
                                }
                            }
                        });
                    }
                });
    }

    /** The R0 local installation: staged download + compile + smoke load + atomic activation. */
    private void installLocally() {
        final String repoId = repoField.getText().trim();
        if (repoId.length() == 0) {
            append("Pick a repository to install locally.");
            return;
        }
        saveTokenToConfiguration();
        append("Installing " + repoId + " into the local AskAI runtime ...");
        showProgress(0, "Installing locally: " + repoId);
        setInstallActionsEnabled(false, "Installing locally ...");
        askAiService.installLocalModel(repoId, new AskAiService.LocalInstallListener() {
            public void onStep(final String step) {
                onUi(new Runnable() {
                    public void run() {
                        append(step);
                        statusLine.setText(step);
                    }
                });
            }

            public void onDownloadProgress(final String fileName, final long completed,
                                           final long total) {
                onUi(new Runnable() {
                    public void run() {
                        int percent = total > 0 ? (int) (completed * 100 / total) : 0;
                        showProgress(percent, "Downloading " + fileName);
                    }
                });
            }

            public void onInstalled(final String virtualModelName) {
                onUi(new Runnable() {
                    public void run() {
                        showProgress(100, "Installed");
                        append("Installed local model: " + virtualModelName);
                        statusLine.setText("Installed local model: " + virtualModelName);
                        setInstallActionsEnabled(true,
                                "Installed locally as " + virtualModelName);
                    }
                });
            }

            public void onError(final Exception exception) {
                onUi(new Runnable() {
                    public void run() {
                        showProgress(0, "Local install failed");
                        append("Local install failed: " + exception.getMessage());
                        setInstallActionsEnabled(true,
                                "Local install failed: " + exception.getMessage());
                    }
                });
            }
        });
    }

    /** Enables/disables the download+install actions and shows the reason in the import-status label. */
    private void setInstallActionsEnabled(boolean enabled, String reason) {
        downloadButton.setEnabled(enabled);
        fullInstallButton.setEnabled(enabled);
        String text = reason == null ? "" : reason.trim();
        importStatusLabel.setText(text.length() == 0 ? " "
                : (enabled ? "✓ " : "✕ ") + text);
        importStatusLabel.setForeground(text.length() == 0 ? importStatusLabel.getForeground()
                : (enabled ? ThemeColor.of("models.statusSuccessText") : ThemeColor.of("models.statusErrorText")));
    }

    private void loadFiles() {
        final String repoId = repoField.getText().trim();
        if (repoId.length() == 0) {
            append("Pick a model from the list or type a repository id.");
            return;
        }
        saveTokenToConfiguration();
        setRepoCapability("Checking repository capabilities ...");
        append("Loading GGUF files for " + repoId + " ...");
        askAiService.listHuggingFaceFiles(repoId, new AskAiService.HuggingFaceFileListener() {
            public void onFiles(final List<HuggingFaceFile> files) {
                onUi(new Runnable() {
                    public void run() {
                        filesModel.clear();
                        for (HuggingFaceFile file : files) {
                            filesModel.addElement(file);
                        }
                        append("Found " + files.size() + " GGUF file(s).");
                        updateRepoCapability(repoId, files);
                        preselectRecommendedModel(files);
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setRepoCapability(" ");
                        append("Could not load files: " + ex.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Reports only what can be known from the file listing BEFORE download: whether the repo ships one or
     * more encoder <em>candidates</em> (files named like an mmproj). The real type — vision/audio and the
     * architecture — is proven from the GGUF metadata after download, never guessed from names here.
     */
    private void updateRepoCapability(String repoId, List<HuggingFaceFile> files) {
        int candidates = 0;
        for (int i = 0; i < files.size(); i++) {
            if (isMmprojName(files.get(i).getFileName())) {
                candidates++;
            }
        }
        if (candidates == 0) {
            setRepoCapability("<html>No encoder candidate found in this repository. If it is multimodal, its "
                    + "encoder (mmproj) may live in another repo, or use <tt>ollama pull</tt>.</html>");
            return;
        }
        setRepoCapability("<html>This repository contains " + (candidates == 1 ? "an encoder candidate"
                : candidates + " encoder candidates") + ". The selected file will be verified from its GGUF "
                + "metadata after download.</html>");
    }

    private void setRepoCapability(String text) {
        repoCapabilityLabel.setText(text);
    }

    private void downloadSelected(final boolean installAfterDownload) {
        if (installLocallyCheckbox.isSelected()) {
            // R0 local path: no GGUF, no Ollama create - staged raw-file install into the
            // local model runtime. The GGUF flow below stays untouched when the box is off.
            installLocally();
            return;
        }
        HuggingFaceFile selected = filesList.getSelectedValue();
        if (selected == null) {
            append("Select a GGUF file first.");
            return;
        }
        saveTokenToConfiguration();

        // Add-on mode: the selected GGUF IS the encoder to attach to an existing model. No companion, no
        // fresh create — download it, then attach via from/adapters.
        final String addOnTarget = addOnTargetModel;
        if (addOnTarget != null && addOnTarget.length() > 0) {
            downloadAndAttachEncoder(selected, addOnTarget);
            return;
        }

        // Multimodal repos ship the audio/vision encoder as a separate *mmproj* GGUF. Include it
        // automatically so the install is complete in one step — no extra prompt.
        final HuggingFaceFile companionFile =
                isMmprojName(selected.getFileName()) ? null : chooseCompanionEncoder();
        if (companionFile != null) {
            append("Multimodal repo: the encoder " + companionFile.getFileName()
                    + " will be downloaded and installed with the model.");
        }

        // Freeze the install contract now, from the currently selected model — not later in the
        // install callback. During a long download the user may pick another search result; the file
        // being installed must keep the metadata of the model it was actually downloaded for.
        // Held in a 1-element array so the resolved commit SHA (delivered before the bytes) can pin the
        // plan before it is persisted/installed.
        final HuggingFaceInstallPlan[] frozenPlanRef = { freezeInstallPlan() };
        // Record the EXACT chosen encoder in the plan (path + name + sha256 + size), so a later install
        // uses precisely this file — never "the first projector in the folder".
        if (companionFile != null && frozenPlanRef[0] != null) {
            frozenPlanRef[0] = frozenPlanRef[0].withCompanions(java.util.Collections.singletonList(
                    new HuggingFaceInstallPlan.Companion(companionFile.getPath(), companionFile.getFileName(),
                            companionFile.getSha256(), companionFile.getSize())));
        }

        final String modelFileName = selected.getFileName();
        append("Downloading " + modelFileName + " ...");
        // Always name the file in the bar and reset to 0 so consecutive downloads (model, then
        // encoder) are visibly separate phases instead of looking stuck at 100%.
        showProgress(0, "Downloading " + modelFileName);
        askAiService.downloadHuggingFaceFile(selected, new AskAiService.DownloadListener() {
            public void onResolvedRevision(final String sha) {
                onUi(new Runnable() {
                    public void run() {
                        if (frozenPlanRef[0] != null && sha != null && sha.length() > 0) {
                            frozenPlanRef[0] = frozenPlanRef[0].withResolvedRevisionSha(sha);
                            append("Pinned to commit " + (sha.length() > 10 ? sha.substring(0, 10) : sha) + ".");
                        }
                    }
                });
            }

            public void onProgress(final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        if (total > 0L) {
                            int percent = (int) (completed * 100L / total);
                            showProgress(percent, "Downloading " + modelFileName + "  " + percent + "%");
                        } else {
                            progressBar.setString("Downloading " + modelFileName + "  "
                                    + (completed / (1024L * 1024L)) + " MB");
                        }
                    }
                });
            }

            public void onComplete(final File file) {
                onUi(new Runnable() {
                    public void run() {
                        lastDownloadedFile = file;
                        append("Download complete: " + file.getAbsolutePath());
                        // Persist the frozen (now SHA-pinned) contract — even for a download-only, so a
                        // later install from "Downloaded files" still carries the Hugging Face metadata.
                        persistDownloadSidecar(file, frozenPlanRef[0], selected.getSha256());
                        if (companionFile != null) {
                            downloadCompanion(companionFile, installAfterDownload, frozenPlanRef[0]);
                        } else {
                            showProgress(100, "Files downloaded");
                            if (installAfterDownload) {
                                installDownloadedFile(frozenPlanRef[0]);
                            }
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                        showProgress(0, "Download failed");
                    }
                });
            }
        });
    }

    /** Download the encoder after the model; keeps {@code lastDownloadedFile} on the model file. */
    private void downloadCompanion(HuggingFaceFile companion, final boolean installAfterDownload,
                                   final HuggingFaceInstallPlan frozenPlan) {
        final String encoderFileName = companion.getFileName();
        append("Downloading encoder " + encoderFileName + " ...");
        // Reset the bar for this second download phase — otherwise it lingers at the model's 100%.
        showProgress(0, "Downloading encoder " + encoderFileName);
        // Pin the companion to the SAME commit the main model resolved to, so the branch moving between the
        // two downloads cannot pull the encoder from a different commit than the model it belongs to.
        String pinnedRevision = frozenPlan == null ? null : frozenPlan.getPinnedRevision();
        askAiService.downloadHuggingFaceFile(companion, pinnedRevision, new AskAiService.DownloadListener() {
            public void onProgress(final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        if (total > 0L) {
                            int percent = (int) (completed * 100L / total);
                            showProgress(percent, "Downloading encoder " + encoderFileName + "  " + percent + "%");
                        } else {
                            progressBar.setString("Downloading encoder " + encoderFileName + "  "
                                    + (completed / (1024L * 1024L)) + " MB");
                        }
                    }
                });
            }

            public void onComplete(final File file) {
                onUi(new Runnable() {
                    public void run() {
                        append("Encoder downloaded: " + file.getAbsolutePath());
                        showProgress(100, "Files downloaded");
                        if (installAfterDownload) {
                            installDownloadedFile(frozenPlan);
                        }
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        append("ERROR: encoder download failed: " + ex.getMessage()
                                + " — the model will work for text, but not for audio/vision.");
                        showProgress(0, "Encoder download failed");
                        if (installAfterDownload) {
                            installDownloadedFile(frozenPlan);
                        }
                    }
                });
            }
        });
    }

    /**
     * Add-on flow: download the selected encoder GGUF and attach it to {@code existingModel} via
     * {@code from}/{@code adapters}. The file is proven to be a projector (GGUF content) before attaching,
     * and {@code /api/show} is re-read afterwards so the model card reflects the new capability.
     */
    private void downloadAndAttachEncoder(HuggingFaceFile encoder, final String existingModel) {
        final String encoderName = encoder.getFileName();
        // A fresh operation id: any earlier add-on download/attach is now stale and its callbacks no-op.
        final int gen = ++addOnGeneration;
        append("Downloading encoder " + encoderName + " to attach to \"" + existingModel + "\" ...");
        showProgress(0, "Downloading encoder " + encoderName);
        askAiService.downloadHuggingFaceFile(encoder, new AskAiService.DownloadListener() {
            public void onProgress(final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return;
                        }
                        if (total > 0L) {
                            int percent = (int) (completed * 100L / total);
                            showProgress(percent, "Downloading encoder " + encoderName + "  " + percent + "%");
                        } else {
                            progressBar.setString("Downloading encoder " + encoderName + "  "
                                    + (completed / (1024L * 1024L)) + " MB");
                        }
                    }
                });
            }

            public void onComplete(final File file) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return; // cancelled or superseded during the download — do not attach
                        }
                        append("Encoder downloaded: " + file.getAbsolutePath());
                        attachDownloadedEncoder(existingModel, file, gen);
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return;
                        }
                        append("ERROR: encoder download failed: " + ex.getMessage());
                        showProgress(0, "Encoder download failed");
                        clearAddOnMode();
                    }
                });
            }
        });
    }

    /** Validate the downloaded file as a projector and attach it to the existing model, then re-verify. */
    private void attachDownloadedEncoder(final String existingModel, File encoder, final int gen) {
        if (gen != addOnGeneration) {
            return; // cancelled or superseded before the attach could start
        }
        try {
            if (!com.aresstack.askai.hf.GgufFile.inspect(encoder).isProjector()) {
                append("ERROR: " + encoder.getName() + " is not a multimodal encoder (projector) GGUF — "
                        + "nothing was attached. Choose an mmproj file.");
                showProgress(0, "Not an encoder");
                clearAddOnMode();
                return;
            }
        } catch (java.io.IOException ex) {
            append("ERROR: could not read " + encoder.getName() + " as a GGUF: " + ex.getMessage());
            showProgress(0, "Not a GGUF");
            clearAddOnMode();
            return;
        }
        append("Attaching " + describeProjector(encoder) + " to \"" + existingModel + "\" ...");
        showProgress(0, "Attaching encoder");
        setInstallInProgress(true);
        addOnAttachRunning = true;
        installTask = askAiService.attachEncoder(existingModel, encoder, new AskAiService.InstallListener() {
            public void onProgress(final String phase, final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return;
                        }
                        updateInstallProgress(phase, completed, total);
                    }
                });
            }

            public void onVerified(final VerificationResult result) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return;
                        }
                        reportVerification(result, true);
                    }
                });
            }

            public void onComplete(final String message) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return;
                        }
                        addOnAttachRunning = false;
                        setInstallInProgress(false);
                        append(message);
                        progressBar.setIndeterminate(false);
                        showProgress(100, "Encoder attached");
                        clearAddOnMode();
                        // Re-read the truth from /api/show into Installed Models (no local state kept).
                        if (addOnAttachedListener != null) {
                            addOnAttachedListener.run();
                        }
                    }
                });
            }

            public void onIncomplete(final VerificationResult result) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return;
                        }
                        addOnAttachRunning = false;
                        setInstallInProgress(false);
                        progressBar.setIndeterminate(false);
                        showProgress(0, "Attached but not verified");
                        clearAddOnMode();
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        if (gen != addOnGeneration) {
                            return;
                        }
                        addOnAttachRunning = false;
                        setInstallInProgress(false);
                        progressBar.setIndeterminate(false);
                        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                        append("ERROR: " + message);
                        showProgress(0, "Attach failed");
                        clearAddOnMode();
                    }
                });
            }
        });
    }

    private static final String[] PREFERRED_QUANTS = {"q4_k_m", "q4_0", "q5_k_m", "q8_0"};

    /**
     * Auto-select a sensible model quant (never the encoder), so the user can just press
     * "Download and install". Prefer a balanced quant; fall back to the first non-encoder file.
     */
    private void preselectRecommendedModel(List<HuggingFaceFile> files) {
        int fallback = -1;
        for (int q = 0; q < PREFERRED_QUANTS.length; q++) {
            for (int i = 0; i < files.size(); i++) {
                String name = files.get(i).getFileName().toLowerCase();
                if (isMmprojName(name)) {
                    continue;
                }
                if (fallback < 0) {
                    fallback = i;
                }
                if (name.contains(PREFERRED_QUANTS[q])) {
                    filesList.setSelectedIndex(i);
                    filesList.ensureIndexIsVisible(i);
                    return;
                }
            }
        }
        if (fallback >= 0) {
            filesList.setSelectedIndex(fallback);
            filesList.ensureIndexIsVisible(fallback);
        }
    }

    /** @return the first *mmproj* GGUF in the currently listed repository files, or null. */
    /**
     * @return the mmproj encoder to bundle with the model: none → {@code null}; exactly one → it
     *         automatically; several → let the user choose which one to include.
     */
    private HuggingFaceFile chooseCompanionEncoder() {
        List<HuggingFaceFile> encoders = new ArrayList<HuggingFaceFile>();
        for (int i = 0; i < filesModel.getSize(); i++) {
            HuggingFaceFile file = filesModel.getElementAt(i);
            if (isMmprojName(file.getFileName())) {
                encoders.add(file);
            }
        }
        if (encoders.isEmpty()) {
            return null;
        }
        if (encoders.size() == 1) {
            return encoders.get(0);
        }
        // Several encoders (e.g. an audio and a vision mmproj, or different quants): the user picks one.
        String[] names = new String[encoders.size()];
        for (int i = 0; i < encoders.size(); i++) {
            names[i] = encoders.get(i).getFileName();
        }
        Object choice = javax.swing.JOptionPane.showInputDialog(this,
                "This repository has several encoder (mmproj) files. Which one should be installed with "
                        + "the model?", "Choose the encoder", javax.swing.JOptionPane.QUESTION_MESSAGE,
                null, names, names[0]);
        if (choice == null) {
            append("No encoder chosen — installing the model without a multimodal encoder.");
            return null;
        }
        for (int i = 0; i < encoders.size(); i++) {
            if (names[i].equals(choice)) {
                return encoders.get(i);
            }
        }
        return null;
    }

    private static boolean isMmprojName(String fileName) {
        return fileName != null && fileName.toLowerCase().contains("mmproj");
    }

    /** Renders GGUF files with a size, and marks the encoder so it is not mistaken for a model. */
    private static final class GgufFileRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof HuggingFaceFile) {
                HuggingFaceFile file = (HuggingFaceFile) value;
                long mb = file.getSize() / (1024L * 1024L);
                String size = mb > 0 ? "  (" + mb + " MB)" : "";
                if (isMmprojName(file.getFileName())) {
                    label.setText(file.getFileName() + size + "  — encoder candidate (verified after download)");
                } else {
                    label.setText(file.getFileName() + size);
                }
            }
            return label;
        }
    }

    /**
     * @return an encoder (projector) GGUF lying next to the model file, or null. A sibling is treated as a
     *         projector only when its GGUF header proves it (a vision/audio encoder), never by its file
     *         name — a plain model named "mmproj" is not mistaken for an encoder, and a projector with no
     *         "mmproj" in its name is still found.
     */
    private File findLocalMmproj(File modelFile) {
        File parent = modelFile.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return null;
        }
        File[] siblings = parent.listFiles();
        if (siblings == null) {
            return null;
        }
        for (int i = 0; i < siblings.length; i++) {
            File sibling = siblings[i];
            if (!sibling.isFile() || sibling.equals(modelFile)
                    || !sibling.getName().toLowerCase().endsWith(".gguf")) {
                continue;
            }
            try {
                if (com.aresstack.askai.hf.GgufFile.inspect(sibling).isProjector()) {
                    return sibling;
                }
            } catch (java.io.IOException ignored) {
                // Not a readable GGUF header → not a usable encoder.
            }
        }
        return null;
    }

    /** @return a one-line description of a projector GGUF's content for the install log, or "" if unreadable. */
    private static String describeProjector(File encoder) {
        try {
            com.aresstack.askai.hf.GgufFile.GgufInfo info =
                    com.aresstack.askai.hf.GgufFile.inspect(encoder);
            long mb = encoder.length() / (1024L * 1024L);
            return encoder.getName() + " (" + mb + " MB) — kind=" + info.projectorKind()
                    + ", vision=" + (info.hasVisionEncoder() ? "yes" : "no")
                    + ", audio=" + (info.hasAudioEncoder() ? "yes" : "no")
                    + ", architecture=" + (info.architecture().length() > 0 ? info.architecture() : "?");
        } catch (java.io.IOException ex) {
            return "";
        }
    }

    /**
     * Shows a popup listing every already-downloaded GGUF file (not just the last one), so a model
     * downloaded earlier but not yet installed remotely can be installed too. Broken/incomplete
     * files are flagged; each row has a right-aligned delete button, and right-click opens a
     * context menu for deleting the download including any leftover partial data.
     */
    private void showDownloadedFilesMenu(final JButton anchor) {
        List<File> files = findDownloadedGgufFiles();
        final JPopupMenu menu = new JPopupMenu();
        if (files.isEmpty()) {
            JMenuItem empty = new JMenuItem("No downloaded GGUF files found");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (int i = 0; i < files.size(); i++) {
                menu.add(buildDownloadRow(menu, anchor, files.get(i)));
            }
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** One popup row: install on click, a right-aligned ✕ button, and a right-click context menu. */
    private JComponent buildDownloadRow(final JPopupMenu menu, final JButton anchor, final File file) {
        boolean valid = isValidGguf(file);

        final JButton installButton = new ComicButton(
                downloadedFileLabel(file) + (valid ? "" : "   [invalid/incomplete]"));
        installButton.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        installButton.setBorderPainted(false);
        installButton.setContentAreaFilled(false);
        installButton.setFocusPainted(false);
        installButton.setToolTipText(valid
                ? "Install this downloaded model"
                : "This file failed GGUF validation (truncated or corrupt) — delete it and re-download");
        if (!valid) {
            installButton.setForeground(ThemeColor.of("models.invalidFileText"));
        }
        installButton.addActionListener(event -> {
            menu.setVisible(false);
            chooseAndInstall(file);
        });

        JButton deleteButton = new ComicButton("✕");
        deleteButton.setMargin(new Insets(0, 6, 0, 6));
        deleteButton.setFocusPainted(false);
        deleteButton.setToolTipText("Delete this download (including partial data)");
        deleteButton.addActionListener(event -> deleteDownloadedFile(menu, anchor, file));

        final JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem deleteItem = new JMenuItem("Delete download (incl. data)");
        deleteItem.addActionListener(event -> deleteDownloadedFile(menu, anchor, file));
        contextMenu.add(deleteItem);
        installButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                maybeShowContext(event);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                maybeShowContext(event);
            }

            private void maybeShowContext(java.awt.event.MouseEvent event) {
                if (event.isPopupTrigger()) {
                    contextMenu.show(event.getComponent(), event.getX(), event.getY());
                }
            }
        });

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(installButton, BorderLayout.CENTER);
        row.add(deleteButton, BorderLayout.EAST);
        return row;
    }

    /** @return whether the file passes the cheap GGUF header/tensor-bounds validation. */
    private boolean isValidGguf(File file) {
        try {
            GgufFile.validate(file);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Deletes a downloaded model file after confirmation, including its {@code .part} leftover and
     * the model directory when that becomes empty, then reopens the refreshed popup.
     */
    private void deleteDownloadedFile(JPopupMenu menu, JButton anchor, File file) {
        menu.setVisible(false);
        long megabytes = file.length() / (1024L * 1024L);
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete " + file.getName() + " (" + megabytes + " MB)?\n"
                        + "This also removes leftover partial download data (.part).",
                "Delete download", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            showDownloadedFilesMenu(anchor);
            return;
        }
        boolean deleted = !file.isFile() || file.delete();
        forgetRecovery(file); // the file is gone, so drop any recovery entry that referenced it
        File partFile = new File(file.getParentFile(), file.getName() + ".part");
        if (partFile.isFile() && partFile.delete()) {
            append("Deleted partial data: " + partFile.getName());
        }
        File parent = file.getParentFile();
        if (parent != null && parent.isDirectory() && parent.delete()) {
            append("Removed empty model directory: " + parent.getName());
        }
        if (deleted) {
            append("Deleted download: " + file.getAbsolutePath());
            if (file.equals(lastDownloadedFile)) {
                lastDownloadedFile = null;
            }
        } else {
            append("ERROR: Could not delete " + file.getAbsolutePath()
                    + " (file may be in use by another process).");
        }
        showDownloadedFilesMenu(anchor);
    }

    private void chooseAndInstall(File file) {
        lastDownloadedFile = file;
        // Match the install name to the chosen file, since installing it under another model's name
        // would be wrong.
        installAsField.setText(suggestInstallNameForFile(file));
        append("Selected downloaded file: " + file.getAbsolutePath());
        // Re-install from disk: the metadata comes from the sidecar written at first download, not from
        // whatever model happens to be selected now.
        installDownloadedFile(null);
    }

    /** @return all downloaded {@code .gguf} files under the model download directory, newest first. */
    private List<File> findDownloadedGgufFiles() {
        List<File> found = new ArrayList<File>();
        collectGgufFiles(configurationRepository.load().getModelDownloadDirectory(), found, 0);
        Collections.sort(found, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return found;
    }

    private void collectGgufFiles(File directory, List<File> out, int depth) {
        if (directory == null || !directory.isDirectory() || depth > 4) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                collectGgufFiles(child, out, depth + 1);
            } else if (child.isFile() && child.getName().toLowerCase().endsWith(".gguf")) {
                out.add(child);
            }
        }
    }

    private String downloadedFileLabel(File file) {
        File parent = file.getParentFile();
        String relative = (parent != null ? parent.getName() + "/" : "") + file.getName();
        long megabytes = file.length() / (1024L * 1024L);
        return relative + "  (" + megabytes + " MB)";
    }

    private String suggestInstallNameForFile(File file) {
        String name = file.getName();
        int dot = name.toLowerCase().lastIndexOf(".gguf");
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        String cleaned = name.toLowerCase().replaceAll("[^a-z0-9._-]", "-");
        return cleaned.length() == 0 ? "model" : cleaned;
    }

    private void installDownloadedFile(HuggingFaceInstallPlan frozenPlan) {
        final String modelName = installAsField.getText().trim();
        if (modelName.length() == 0) {
            append("ERROR: 'Install as' is empty.");
            return;
        }
        if (lastDownloadedFile == null || !lastDownloadedFile.isFile()) {
            append("ERROR: No downloaded GGUF file available.");
            return;
        }
        // Resolve the install plan first (sidecar precedence + any prompts) — it is authoritative for which
        // encoder(s) belong to this model. Heavier metadata enrichment runs off the EDT in the service.
        final PlanResolution resolution = resolvePlan(lastDownloadedFile, modelName, frozenPlan);
        if (resolution.isCancelled()) {
            showProgress(0, "Install cancelled");
            return;
        }
        final HuggingFaceInstallPlan plan = resolution.getPlan(); // null → a plain manual import
        // Companions come from the plan's EXACT recorded encoders, resolved to their local files by name —
        // never by scanning the folder for "the first projector". A plain manual import (no plan) may still
        // use findLocalMmproj as an explicitly-understood convenience.
        final List<File> companions = new ArrayList<File>();
        if (plan != null && !plan.getCompanions().isEmpty()) {
            for (HuggingFaceInstallPlan.Companion companion : plan.getCompanions()) {
                File local = new File(lastDownloadedFile.getParentFile(), companion.getFileName());
                if (local.isFile()) {
                    companions.add(local);
                    String described = describeProjector(local);
                    append("Including encoder: " + (described.length() > 0 ? described : local.getName()));
                } else {
                    append("WARNING: the chosen encoder " + companion.getFileName()
                            + " is not present next to the model — it will not be installed. Re-download it, "
                            + "or add it later via the model card's add-on button.");
                }
            }
        } else if (plan == null) {
            File mmproj = findLocalMmproj(lastDownloadedFile);
            if (mmproj != null) {
                companions.add(mmproj);
                String described = describeProjector(mmproj);
                append("Including encoder: " + (described.length() > 0 ? described : mmproj.getName()));
            }
        }
        final List<String> requiredCapabilities = plan == null
                ? Collections.<String>emptyList() : plan.getRequiredOllamaCapabilities();
        if (!requiredCapabilities.isEmpty()) {
            append("Hugging Face declares: " + join(requiredCapabilities)
                    + " — will verify against /api/show after install.");
        }
        // A model that declares audio/vision needs its mmproj encoder alongside it before /api/create;
        // warn (and let the user decide) when the required encoder is missing, rather than creating a
        // model that will silently lack the capability (post-install /api/show then also won't confirm it).
        if (companions.isEmpty() && declaresEncoderCapability(requiredCapabilities)) {
            String missing = requiredCapabilities.contains("audio") ? "audio" : "vision";
            int choice = javax.swing.JOptionPane.showConfirmDialog(this,
                    "This model declares " + missing + ", but no mmproj encoder file was found next to it.\n"
                            + missing + " input will not work until the encoder is added.\n\nInstall anyway?",
                    "Missing " + missing + " encoder", javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            if (choice != javax.swing.JOptionPane.YES_OPTION) {
                append("Install cancelled: missing " + missing + " encoder (mmproj).");
                showProgress(0, "Install cancelled");
                return;
            }
            append("WARNING: installing without the " + missing + " encoder — " + missing
                    + " will not work until it is added via the model card's add-on button.");
        }
        append("Installing " + lastDownloadedFile.getAbsolutePath() + " as " + modelName + ".");
        showProgress(0, "Installing");
        setInstallInProgress(true);
        AskAiService.InstallListener installListener = new AskAiService.InstallListener() {
            public void onProgress(final String phase, final long completed, final long total) {
                onUi(new Runnable() {
                    public void run() {
                        updateInstallProgress(phase, completed, total);
                    }
                });
            }

            public void onVerified(final VerificationResult result) {
                onUi(new Runnable() {
                    public void run() {
                        reportVerification(result, false);
                    }
                });
            }

            public void onComplete(final String message) {
                onUi(new Runnable() {
                    public void run() {
                        setInstallInProgress(false);
                        append(message);
                        progressBar.setIndeterminate(false);
                        showProgress(100, "Installed");
                        // Only after a verified create: clean up the local transfer files if requested.
                        if (deleteAfterInstallCheckbox.isSelected()) {
                            deleteDownloadArtifacts(lastDownloadedFile, companions);
                        }
                    }
                });
            }

            public void onIncomplete(final VerificationResult result) {
                onUi(new Runnable() {
                    public void run() {
                        // The model was created but /api/show did not confirm it: never show "Installed".
                        setInstallInProgress(false);
                        progressBar.setIndeterminate(false);
                        boolean failed = result.getStatus() == VerificationStatus.FAILED;
                        showProgress(0, failed ? "Verification failed" : "Installed but not verified");
                    }
                });
            }

            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    public void run() {
                        setInstallInProgress(false);
                        progressBar.setIndeterminate(false);
                        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                        boolean cancelled = ex instanceof java.io.InterruptedIOException
                                || message.toLowerCase().contains("cancel");
                        append((cancelled ? "Install cancelled." : "ERROR: " + message));
                        showProgress(0, cancelled ? "Install cancelled" : "Install failed");
                    }
                });
            }
        };
        if (plan == null) {
            // A plain manual GGUF import: no Hugging Face metadata is invented.
            installTask = askAiService.installGgufFileWithCompanions(modelName, lastDownloadedFile, companions,
                    OllamaCreateMetadata.empty(), installListener);
        } else {
            // The service enriches the plan (config.json / HF model-info) off the EDT and sends the trusted
            // metadata on /api/create.
            installTask = askAiService.installGgufFileWithPlan(modelName, lastDownloadedFile, companions,
                    plan, installListener);
        }
    }

    /**
     * Snapshots the install contract of the currently selected Hugging Face model, so it can be carried
     * unchanged through a long download even if the user re-selects another search result meanwhile.
     *
     * @return the frozen plan, or {@code null} when no model is selected (a plain manual download).
     */
    private HuggingFaceInstallPlan freezeInstallPlan() {
        if (currentModel == null) {
            return null;
        }
        java.util.Set<ModelCapability> declared = HuggingFaceModelClassifier.modalitiesOf(currentModel);
        List<String> declaredNames = new ArrayList<String>();
        for (ModelCapability capability : declared) {
            declaredNames.add(capability.name());
        }
        // Freeze the config.json model_type too (from the verified analysis, if any) so the family can be
        // derived at install time even if the user re-selects another model during the download.
        String modelType = currentAnalysis == null ? "" : currentAnalysis.getModelType();
        return new HuggingFaceInstallPlan(currentModel.getId(), "main", installAsField.getText().trim(),
                declaredNames, ModelCapability.requiredOllamaTags(declared), modelType);
    }

    /** Outcome of resolving which install plan (if any) drives an install. */
    private static final class PlanResolution {
        private static final PlanResolution CANCEL = new PlanResolution(null, true);
        private static final PlanResolution MANUAL = new PlanResolution(null, false);
        private final HuggingFaceInstallPlan plan;
        private final boolean cancelled;

        private PlanResolution(HuggingFaceInstallPlan plan, boolean cancelled) {
            this.plan = plan;
            this.cancelled = cancelled;
        }

        static PlanResolution of(HuggingFaceInstallPlan plan) {
            return new PlanResolution(plan, false);
        }

        boolean isCancelled() {
            return cancelled;
        }

        /** @return the Hugging Face plan, or {@code null} for a plain manual import. */
        HuggingFaceInstallPlan getPlan() {
            return plan;
        }
    }

    /**
     * Resolves which install plan drives this install (EDT only; may prompt). A freshly frozen plan is
     * authoritative and overwrites any stale sidecar; without one (a re-install from the download
     * directory) the persisted sidecar is the only source. The heavy metadata enrichment happens later,
     * off the EDT, in the service.
     *
     * @return {@link PlanResolution#CANCEL} to abort, a manual resolution ({@code getPlan() == null}) for
     *         a plain GGUF import, or a resolution carrying the Hugging Face plan.
     */
    private PlanResolution resolvePlan(File modelFile, String modelName, HuggingFaceInstallPlan frozenPlan) {
        if (frozenPlan != null) {
            // The plan frozen for this download wins over whatever sidecar is on disk, so re-downloading
            // a file can never be shadowed by an older sidecar. Persist it (atomically) as the new truth.
            HuggingFaceInstallPlan plan = frozenPlan.withTargetModelName(modelName);
            Boolean written = persistSidecarOrPrompt(plan, modelFile);
            if (written == null) {
                return PlanResolution.CANCEL; // cancelled after a write failure
            }
            if (!written.booleanValue()) {
                return PlanResolution.MANUAL; // user chose a plain manual import instead
            }
            return PlanResolution.of(plan);
        }
        // Re-install from the download directory: the sidecar written earlier is the only contract.
        HuggingFaceInstallPlan sidecar;
        try {
            sidecar = HuggingFaceInstallPlan.readSidecar(modelFile);
        } catch (java.io.IOException ex) {
            // A present-but-invalid sidecar must be an explicit user decision, not a silent downgrade.
            List<String> decision = confirmManualImportForInvalidSidecar(ex);
            return decision == null ? PlanResolution.CANCEL : PlanResolution.MANUAL;
        }
        if (sidecar != null) {
            return PlanResolution.of(sidecar);
        }
        // No sidecar: before treating this as a manual import, check the recovery index — the metadata may
        // have failed to save at download time (a lost contract must not silently become a manual import).
        return resolveFromRecovery(modelFile, modelName);
    }

    /**
     * No sidecar exists next to {@code modelFile}. If the recovery index remembers a failed metadata write
     * for it, offer to recover; otherwise it is a genuine manual GGUF import.
     */
    private PlanResolution resolveFromRecovery(File modelFile, String modelName) {
        DownloadMetadataRecoveryIndex.Entry entry = recoveryIndex.find(modelFile);
        if (entry == null) {
            return PlanResolution.MANUAL; // genuinely a plain manual GGUF import
        }
        append("This file was downloaded from Hugging Face, but its installation metadata was never saved.");
        Object[] options = {"Retry metadata recovery", "Import as manual GGUF", "Cancel"};
        int choice = javax.swing.JOptionPane.showOptionDialog(this,
                "The Hugging Face installation metadata for this download was never saved.\n"
                        + "Repository: " + entry.toPlan().getRepositoryId()
                        + "\n\nRecover it now, install as a plain GGUF without metadata, or cancel?",
                "Recover install metadata", javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 1) {
            append("Continuing as a manual GGUF import without declared capabilities.");
            return PlanResolution.MANUAL;
        }
        if (choice != 0) {
            append("Install cancelled.");
            return PlanResolution.CANCEL;
        }
        // Retry: re-write the sidecar from the recovered plan; on success the recovery entry is dropped.
        HuggingFaceInstallPlan recovered = entry.toPlan().withTargetModelName(modelName);
        Boolean written = persistSidecarOrPrompt(recovered, modelFile);
        if (written == null) {
            return PlanResolution.CANCEL;
        }
        if (!written.booleanValue()) {
            return PlanResolution.MANUAL;
        }
        return PlanResolution.of(recovered);
    }

    /**
     * Best-effort persistence of the frozen plan right after a download completes, so a plain download
     * (no immediate install) still leaves the Hugging Face metadata next to the GGUF. The authoritative
     * write happens again at install time via {@link #persistSidecarOrPrompt}.
     */
    private void persistDownloadSidecar(File modelFile, HuggingFaceInstallPlan frozenPlan, String sha256) {
        if (frozenPlan == null) {
            return; // a plain download with no selected Hugging Face model
        }
        HuggingFaceInstallPlan plan = frozenPlan.withTargetModelName(installAsField.getText().trim());
        try {
            plan.writeSidecar(modelFile);
            forgetRecovery(modelFile); // a valid sidecar exists now; drop any stale recovery entry
        } catch (java.io.IOException ex) {
            // The loss must survive a restart: remember it in the recovery index so a later install with no
            // sidecar is not silently degraded to a manual import.
            append("Downloaded, but Hugging Face installation metadata could not be saved: " + ex.getMessage());
            showProgress(100, "Downloaded — metadata NOT saved");
            try {
                recoveryIndex.record(modelFile, sha256, plan);
                append("A recovery entry was saved; the later install will offer to recover the metadata.");
            } catch (java.io.IOException recoveryError) {
                append("WARNING: could not save a recovery entry either: " + recoveryError.getMessage());
            }
        }
    }

    /** @return true when the declared capabilities need a separate mmproj encoder (audio or vision). */
    private static boolean declaresEncoderCapability(List<String> requiredCapabilities) {
        return requiredCapabilities.contains("audio") || requiredCapabilities.contains("vision");
    }

    private void forgetRecovery(File modelFile) {
        try {
            recoveryIndex.remove(modelFile);
        } catch (java.io.IOException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Deletes the local transfer artifacts after a verified Ollama create: the GGUF, its companion/mmproj
     * files, the sidecar, any {@code .part} remnant and the recovery entry, plus the now-empty model
     * directory. The model created inside Ollama is untouched.
     */
    private void deleteDownloadArtifacts(File mainFile, List<File> companions) {
        if (mainFile == null) {
            return;
        }
        List<File> targets = new ArrayList<File>();
        targets.add(mainFile);
        targets.add(new File(mainFile.getParentFile(), mainFile.getName() + ".part"));
        targets.add(HuggingFaceInstallPlan.sidecarFile(mainFile));
        targets.add(com.aresstack.askai.hf.meta.HuggingFaceImportProvenance.sidecarFile(mainFile));
        if (companions != null) {
            for (File companion : companions) {
                targets.add(companion);
                targets.add(HuggingFaceInstallPlan.sidecarFile(companion));
            }
        }
        int deleted = 0;
        for (File target : targets) {
            if (target != null && target.isFile() && target.delete()) {
                deleted++;
            }
        }
        forgetRecovery(mainFile);
        if (mainFile.equals(lastDownloadedFile)) {
            lastDownloadedFile = null;
        }
        File parent = mainFile.getParentFile();
        String[] remaining = parent == null ? null : parent.list();
        if (remaining != null && remaining.length == 0 && parent.delete()) {
            append("Removed empty model directory: " + parent.getName());
        }
        append("Cleaned up " + deleted + " local download file(s) after install (the Ollama model is kept).");
    }

    /**
     * Persists {@code plan} next to {@code modelFile}, atomically. On a write failure the user must decide
     * explicitly: retry the metadata recovery, continue as a manual import, or cancel.
     *
     * @return {@code TRUE} when written; {@code FALSE} to continue as a manual import; {@code null} to
     *         cancel the install.
     */
    private Boolean persistSidecarOrPrompt(HuggingFaceInstallPlan plan, File modelFile) {
        while (true) {
            try {
                plan.writeSidecar(modelFile);
                forgetRecovery(modelFile); // the contract is safely persisted now
                return Boolean.TRUE;
            } catch (java.io.IOException ex) {
                append("ERROR: could not save Hugging Face install metadata: " + ex.getMessage());
                Object[] options = {"Retry metadata recovery", "Import as manual GGUF", "Cancel"};
                int choice = javax.swing.JOptionPane.showOptionDialog(this,
                        "The Hugging Face installation metadata could not be saved next to the model:\n"
                                + ex.getMessage()
                                + "\n\nRetry saving it, install this file as a plain GGUF without metadata, "
                                + "or cancel?",
                        "Could not save install metadata", javax.swing.JOptionPane.DEFAULT_OPTION,
                        javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[0]);
                if (choice == 0) {
                    continue; // retry the write
                }
                if (choice == 1) {
                    append("Continuing as a manual GGUF import without declared capabilities.");
                    return Boolean.FALSE;
                }
                append("Install cancelled: could not save install metadata.");
                return null;
            }
        }
    }

    /**
     * A downloaded GGUF has a sidecar that exists but cannot be parsed. Rather than silently installing
     * without the declared capabilities, ask the user to either cancel or continue as a plain manual
     * import.
     *
     * @return an empty list to continue as a manual import, or {@code null} to cancel the install.
     */
    private List<String> confirmManualImportForInvalidSidecar(java.io.IOException ex) {
        append("ERROR: The Hugging Face installation metadata is invalid: " + ex.getMessage());
        Object[] options = {"Cancel", "Import as manual GGUF without Hugging Face metadata"};
        int choice = javax.swing.JOptionPane.showOptionDialog(this,
                "The Hugging Face installation metadata is invalid.\n" + ex.getMessage()
                        + "\n\nInstall this file as a plain GGUF without Hugging Face metadata?",
                "Invalid install metadata", javax.swing.JOptionPane.DEFAULT_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 1) {
            append("Continuing as a manual GGUF import without declared capabilities.");
            return Collections.emptyList();
        }
        append("Install cancelled: invalid Hugging Face metadata.");
        return null;
    }

    /** Reports the post-create /api/show verification: VERIFIED / MISSING_REQUIRED / UNKNOWN / FAILED. */
    /**
     * Reports the {@code /api/show} verification, deciding purely by {@link VerificationStatus} — never by
     * an empty required list (that produced a false "verified" for add-ons). The expected capabilities are
     * reconstructed from the result ({@code confirmedRequired + missingRequired}), and the wording matches
     * whether this was a fresh model install or an add-on encoder attach.
     */
    private void reportVerification(VerificationResult result, boolean addOn) {
        String created = addOn ? "The encoder was attached" : "The model was created";
        switch (result.getStatus()) {
            case FAILED:
                append(created + ", but post-install verification through /api/show failed: "
                        + result.getErrorMessage());
                return;
            case UNKNOWN:
                append(created + ", but the Ollama server did not return a usable capabilities field.");
                return;
            case MISSING_REQUIRED:
                List<String> expected = new ArrayList<String>(result.getConfirmedRequired());
                expected.addAll(result.getMissingRequired());
                append(created + ", but Ollama did not report the expected "
                        + (addOn ? "encoder" : "installed") + " capabilities.\n"
                        + "  Expected: " + join(expected) + "\n"
                        + "  Reported by /api/show: " + result.describeReported() + "\n"
                        + "  Missing: " + join(result.getMissingRequired()));
                return;
            case VERIFIED:
            default:
                append((addOn ? "Encoder attached and verified" : "Model installed and verified")
                        + ". Capabilities reported by Ollama: " + result.describeReported() + ".");
        }
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    /** Cancel a running install; the service aborts the upload/create. */
    private void cancelInstall() {
        AskAiService.InstallTask task = installTask;
        if (task != null) {
            append("Cancelling install ...");
            task.cancel();
        }
    }

    private void setInstallInProgress(boolean inProgress) {
        cancelInstallButton.setEnabled(inProgress);
        if (!inProgress) {
            installTask = null;
        }
    }

    /** Render one install progress update: a percentage bar for byte phases, indeterminate otherwise. */
    private void updateInstallProgress(String phase, long completed, long total) {
        if (total > 0) {
            int percent = (int) Math.max(0, Math.min(100, completed * 100L / total));
            progressBar.setIndeterminate(false);
            progressBar.setValue(percent);
            progressBar.setString(phase + " " + percent + "% (" + humanBytes(completed)
                    + " / " + humanBytes(total) + ")");
        } else {
            progressBar.setIndeterminate(true);
            progressBar.setString(phase);
        }
    }

    /** Smallest square size of the cancel button next to the progress bar (icon 11 px + plate). */
    private static final int CANCEL_BUTTON_MIN_SIZE = 22;

    private void buildCancelButton() {
        cancelInstallButton.setToolTipText("Cancel installation");
        cancelInstallButton.setFocusPainted(false);
        cancelInstallButton.setMargin(new Insets(0, 0, 0, 0));
        int size = Math.max(CANCEL_BUTTON_MIN_SIZE, progressBar.getPreferredSize().height);
        cancelInstallButton.setPreferredSize(new java.awt.Dimension(size, size));
        progressBar.setPreferredSize(new java.awt.Dimension(progressBar.getPreferredSize().width, size));
        cancelInstallButton.setEnabled(false);
        cancelInstallButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                cancelInstall();
            }
        });
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0d;
        if (kb < 1024.0d) {
            return String.format("%.0f KB", kb);
        }
        double mb = kb / 1024.0d;
        if (mb < 1024.0d) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0d);
    }

    /** A small square close/cancel icon (an X) painted with Java2D — no image asset needed. */
    private static final class CancelIcon implements javax.swing.Icon {
        private final int size;

        CancelIcon(int size) {
            this.size = size;
        }

        public int getIconWidth() {
            return size;
        }

        public int getIconHeight() {
            return size;
        }

        public void paintIcon(java.awt.Component component, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(component.isEnabled() ? ThemeColor.of("models.cancelIconGlyph")
                        : ThemeColor.of("app.iconGlyphDisabled"));
                g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                int pad = 2;
                g.drawLine(x + pad, y + pad, x + size - pad, y + size - pad);
                g.drawLine(x + size - pad, y + pad, x + pad, y + size - pad);
            } finally {
                g.dispose();
            }
        }
    }

    private void loadTokenFromConfiguration() {
        AppConfiguration configuration = configurationRepository.load();
        tokenField.setText(configuration.getHuggingFaceToken());
        updateTokenStatus();
    }

    private void saveTokenToConfiguration() {
        AppConfiguration current = configurationRepository.load();
        configurationRepository.save(current.withHuggingFaceToken(new String(tokenField.getPassword())));
    }

    private String suggestInstallName(String repoId) {
        String value = repoId;
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        return value.toLowerCase().replace("_", "-").replace(" ", "-");
    }

    private void append(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        // Mirror the newest line into the always-visible status summary (single line).
        int newline = message.indexOf('\n');
        statusLine.setText(newline < 0 ? message : message.substring(0, newline));
    }

    private void showProgress(int percent, String text) {
        progressBar.setValue(Math.max(0, Math.min(100, percent)));
        progressBar.setString(text);
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }
}

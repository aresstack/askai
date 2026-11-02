package com.aresstack.askai.batch.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.audio.format.SupportedAudioFormats;
import com.aresstack.askai.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.batch.service.BatchTranscriptionEvent;
import com.aresstack.askai.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.batch.service.BatchTranscriptionRequest;
import com.aresstack.askai.catalog.GlobalCatalogSnapshot;
import com.aresstack.askai.ui.MarkdownPreviewTabs;
import com.aresstack.askai.ui.RefreshIcon;
import com.aresstack.askai.ui.ToggleSelectionList;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Swing view for selecting files, models and profiles and observing batch progress. */
public final class BatchTranscriptionPanel extends JPanel {

    private final BatchTranscriptionController controller;
    private final BatchProfileRefresher profileRefresher;
    private final Function<String, JComponent> markdownRenderer;
    private final DefaultListModel<File> audioFiles = new DefaultListModel<File>();
    // Audio files use plain multi-interval selection (standard Ctrl/Shift): the batch set is the whole list
    // model, list selection only drives the Markdown preview and the "remove" action — not what is processed.
    private final JList<File> fileList = ComicControls.<File>list(audioFiles);
    private final DefaultListModel<String> models = new DefaultListModel<String>();
    private final JList<String> modelList = new ToggleSelectionList<String>(models);
    private final DefaultListModel<AudioProcessingProfile> profiles = new DefaultListModel<AudioProcessingProfile>();
    private final JList<AudioProcessingProfile> profileList = new ToggleSelectionList<AudioProcessingProfile>(profiles);
    private final JProgressBar progress = ComicControls.progressBar();
    private final JLabel status = new JLabel("Ready");
    private final JTextArea log = ComicControls.textArea();
    private final JButton startButton = new ComicButton("Start batch");
    private final JButton cancelButton = new ComicButton("Cancel");
    private final JButton refreshButton = new ComicButton();
    private final JPopupMenu fileContextMenu = new JPopupMenu();
    private final JMenuItem removeSelectedItem = new JMenuItem("Remove selected audio files");
    private MarkdownPreviewTabs bottomTabs;
    private File lastPreviewedFile;
    private final BatchTranscriptionEventPublisher.Subscription subscription;

    // Refresh state — touched only on the EDT.
    private boolean refreshing;

    public BatchTranscriptionPanel(BatchTranscriptionController controller,
                                   List<String> availableModels,
                                   List<AudioProcessingProfile> availableProfiles,
                                   BatchProfileRefresher profileRefresher) {
        this(controller, availableModels, availableProfiles, profileRefresher,
                MarkdownPreviewTabs.markdownRenderer());
    }

    BatchTranscriptionPanel(BatchTranscriptionController controller,
                            List<String> availableModels,
                            List<AudioProcessingProfile> availableProfiles,
                            BatchProfileRefresher profileRefresher,
                            Function<String, JComponent> markdownRenderer) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        this.profileRefresher = profileRefresher;
        this.markdownRenderer = markdownRenderer;
        for (String model : availableModels) models.addElement(model);
        for (AudioProcessingProfile profile : availableProfiles) profiles.addElement(profile);
        this.subscription = controller.observe(new Consumer<BatchTranscriptionEvent>() {
            public void accept(final BatchTranscriptionEvent event) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() { apply(event); }
                });
            }
        });
        buildUi();
    }

    private void buildUi() {
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        // Plain multi-select (Ctrl/Shift). A single click selects one file and previews its .md; a double
        // click pins it; right-click offers "Remove selected audio files".
        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileList.setToolTipText("Click to preview a matching .md; double-click to pin it; right-click to remove.");
        removeSelectedItem.addActionListener(event -> removeSelectedAudioFiles());
        fileContextMenu.add(removeSelectedItem);
        fileList.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent event) {
                if (!event.getValueIsAdjusting()) {
                    previewLeadSelection();
                }
            }
        });
        fileList.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent event) { maybeShowFileContextMenu(event); }
            public void mouseReleased(MouseEvent event) { maybeShowFileContextMenu(event); }
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() >= 2) {
                    int index = fileIndexAt(event.getPoint());
                    if (index >= 0) {
                        openMarkdownFor(audioFiles.get(index), true);
                    }
                }
            }
        });
        JPanel selections = new JPanel(new GridLayout(1, 3, 8, 8));
        selections.add(section("Audio files", new JScrollPane(fileList)));
        selections.add(section("Audio AI models", new JScrollPane(modelList)));
        selections.add(section("Audio profiles", new JScrollPane(profileList)));

        JButton addFiles = new ComicButton("Add audio files...");
        addFiles.addActionListener(event -> chooseAudioFiles());
        startButton.addActionListener(event -> startBatch());
        cancelButton.addActionListener(event -> controller.cancel());
        cancelButton.setEnabled(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.add(addFiles);
        actions.add(startButton);
        actions.add(cancelButton);

        // Refresh control, top-right, matching the Chat panel's refresh button.
        int refreshSize = startButton.getPreferredSize().height;
        refreshButton.setIcon(new RefreshIcon(refreshSize - 6));
        refreshButton.setToolTipText("Refresh audio profiles");
        refreshButton.setFocusPainted(false);
        refreshButton.setMargin(new Insets(0, 0, 0, 0));
        refreshButton.setPreferredSize(new Dimension(refreshSize, refreshSize));
        refreshButton.addActionListener(event -> refresh());
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightControls.add(refreshButton);

        JPanel header = new JPanel(new BorderLayout());
        header.add(actions, BorderLayout.WEST);
        header.add(rightControls, BorderLayout.EAST);

        log.setEditable(false);
        bottomTabs = new MarkdownPreviewTabs("Log", new JScrollPane(log), markdownRenderer);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, selections, bottomTabs);
        split.setResizeWeight(0.55d);
        add(header, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.add(status, BorderLayout.WEST);
        footer.add(progress, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
    }

    private JPanel section(String title, java.awt.Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(ComicControls.titledBorder(title));
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Replace the offered audio models. Only audio-capable model names should be passed in. Must be
     * invoked on the EDT (the batch catalog service publishes off the EDT, so callers forward via
     * {@link SwingUtilities#invokeLater}). Retains any still-available selection by model name.
     */
    public void setAvailableModels(List<String> availableModels) {
        List<String> previouslySelected = modelList.getSelectedValuesList();
        models.clear();
        for (String model : availableModels) models.addElement(model);
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < models.size(); i++) {
            if (previouslySelected.contains(models.get(i))) indices.add(i);
        }
        modelList.setSelectedIndices(toIntArray(indices));
    }

    /**
     * Replace the offered audio profiles. Must be invoked on the EDT. Retains any still-available
     * selection by the stable profile <b>id</b> (not the display name), so a renamed profile stays
     * selected and a deleted profile drops out; new profiles are made available but not auto-selected.
     */
    public void setAvailableProfiles(List<AudioProcessingProfile> availableProfiles) {
        Set<String> previouslySelectedIds = new HashSet<String>();
        for (AudioProcessingProfile profile : profileList.getSelectedValuesList()) {
            previouslySelectedIds.add(profile.getId());
        }
        profiles.clear();
        for (AudioProcessingProfile profile : availableProfiles) profiles.addElement(profile);
        List<Integer> indices = new ArrayList<Integer>();
        for (int i = 0; i < profiles.size(); i++) {
            if (previouslySelectedIds.contains(profiles.get(i).getId())) indices.add(i);
        }
        profileList.setSelectedIndices(toIntArray(indices));
    }

    /**
     * Apply a globally-refreshed catalog: audio models and audio profiles, each only when it loaded, keeping
     * this panel's own multi-selection (models by name, profiles by stable id). Must run on the EDT.
     */
    public void applyCatalogSnapshot(GlobalCatalogSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (snapshot.isAudioModelsLoaded()) {
            setAvailableModels(snapshot.getAudioModels());
        }
        if (snapshot.isProfilesLoaded()) {
            setAvailableProfiles(snapshot.getAudioProfiles());
        }
    }

    /**
     * Reload only the audio profiles (a local source) from their live source; models come from the global
     * refresh. A refresh already in progress is ignored (no second parallel run).
     */
    public void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        refreshButton.setEnabled(false);
        status.setText("Refreshing audio profiles...");
        profileRefresher.loadProfiles(new Consumer<BatchProfileCatalogLoadedEvent>() {
            public void accept(final BatchProfileCatalogLoadedEvent event) {
                onUi(new Runnable() {
                    public void run() { applyProfileRefresh(event); }
                });
            }
        });
    }

    private void applyProfileRefresh(BatchProfileCatalogLoadedEvent event) {
        if (event.isSuccessful()) {
            setAvailableProfiles(event.getProfiles());
            status.setText("Audio profiles refreshed.");
        } else {
            status.setText("Profile refresh failed: " + event.getMessage());
        }
        refreshing = false;
        refreshButton.setEnabled(true);
    }

    private void onUi(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private void chooseAudioFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(SupportedAudioFormats.fileChooserDescription(),
                SupportedAudioFormats.extensionArray()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File file : chooser.getSelectedFiles()) audioFiles.addElement(file);
        }
    }

    /** @return the list index under {@code point}, or -1 when it is not on an actual row. */
    private int fileIndexAt(java.awt.Point point) {
        int index = fileList.locationToIndex(point);
        if (index < 0 || index >= audioFiles.size()) {
            return -1;
        }
        Rectangle bounds = fileList.getCellBounds(index, index);
        return bounds != null && bounds.contains(point) ? index : -1;
    }

    /** Preview the active (lead) selected file's Markdown, falling back to the first selected, else clear. */
    void previewLeadSelection() {
        int lead = fileList.getSelectionModel().getLeadSelectionIndex();
        int index = lead >= 0 && lead < audioFiles.size() && fileList.isSelectedIndex(lead)
                ? lead : fileList.getMinSelectionIndex();
        if (index < 0 || index >= audioFiles.size()) {
            lastPreviewedFile = null;
            bottomTabs.clearPreview();
            return;
        }
        openMarkdownFor(audioFiles.get(index), false);
    }

    /** Right-click selection rules: keep a multi-selection if the row is already selected (just move the
     *  lead there), otherwise select only the clicked row. Returns whether "remove" should be offered. */
    boolean selectForContextMenu(int index) {
        if (index < 0 || index >= audioFiles.size()) {
            return false; // empty area: leave the selection untouched, no remove
        }
        if (fileList.isSelectedIndex(index)) {
            fileList.addSelectionInterval(index, index); // keep selection, make this the lead
        } else {
            fileList.setSelectedIndex(index); // replace selection with this row
        }
        previewLeadSelection();
        return true;
    }

    private void maybeShowFileContextMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        removeSelectedItem.setEnabled(selectForContextMenu(fileIndexAt(event.getPoint())));
        fileContextMenu.show(fileList, event.getX(), event.getY());
    }

    /** Remove the selected list entries from the batch input (never touches the file system), then select
     *  a sensible neighbour and refresh the preview. */
    void removeSelectedAudioFiles() {
        int[] selected = fileList.getSelectedIndices();
        if (selected.length == 0) {
            return;
        }
        int firstRemoved = selected[0];
        for (int i = selected.length - 1; i >= 0; i--) {
            audioFiles.remove(selected[i]);
        }
        int size = audioFiles.size();
        if (size == 0) {
            fileList.clearSelection();
            lastPreviewedFile = null;
            bottomTabs.clearPreview();
            return;
        }
        fileList.setSelectedIndex(firstRemoved < size ? firstRemoved : size - 1);
    }

    private void openMarkdownFor(File audio, boolean pinned) {
        if (!pinned) {
            lastPreviewedFile = audio;
        }
        File markdown = markdownSiblingOf(audio);
        if (!markdown.isFile()) {
            if (!pinned) {
                bottomTabs.clearPreview();
            }
            status.setText("No " + markdown.getName() + " found next to " + audio.getName() + ".");
            return;
        }
        String content = readMarkdown(markdown);
        if (pinned) {
            bottomTabs.pin(markdown.getAbsolutePath(), markdown.getName(), content);
        } else {
            bottomTabs.preview(markdown.getName(), content);
        }
    }

    /** The sibling Markdown file with the same base name (e.g. {@code sample-001.wav -> sample-001.md}). */
    static File markdownSiblingOf(File audio) {
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(audio.getParentFile(), base + ".md");
    }

    private static String readMarkdown(File markdown) {
        try {
            return new String(Files.readAllBytes(markdown.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "Could not read `" + markdown.getName() + "`: " + ex.getMessage();
        }
    }

    private void startBatch() {
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < audioFiles.size(); i++) files.add(audioFiles.get(i));
        List<String> models = modelList.getSelectedValuesList();
        List<AudioProcessingProfile> profiles = profileList.getSelectedValuesList();
        try {
            controller.start(new BatchTranscriptionRequest(files, models, profiles, "auto", ""));
        } catch (RuntimeException ex) {
            status.setText(ex.getMessage());
        }
    }

    private void apply(BatchTranscriptionEvent event) {
        progress.setMaximum(Math.max(1, event.getTotalItems()));
        progress.setValue(event.getCompletedItems());
        status.setText(event.getMessage());
        log.append(format(event) + System.lineSeparator());
        boolean running = event.getType() != BatchTranscriptionEvent.Type.BATCH_COMPLETED
                && event.getType() != BatchTranscriptionEvent.Type.BATCH_CANCELLED;
        startButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        if (!running) controller.markFinished();
    }

    private String format(BatchTranscriptionEvent event) {
        StringBuilder text = new StringBuilder("[").append(event.getType()).append("] ");
        if (event.getModelName().length() > 0) text.append(event.getModelName()).append(" | ");
        if (event.getAudioFile() != null) text.append(event.getAudioFile().getName()).append(" | ");
        if (event.getProfileName().length() > 0) text.append(event.getProfileName()).append(" | ");
        return text.append(event.getMessage()).toString();
    }

    public void dispose() { subscription.unsubscribe(); }

    // Package-private accessors for tests in this package (selection/model state verification).
    JList<String> modelListComponent() { return modelList; }

    JList<AudioProcessingProfile> profileListComponent() { return profileList; }

    JList<File> audioFileListComponent() { return fileList; }

    /** The batch input set — the whole list model, independent of Swing selection. */
    List<File> inputFilesForTest() {
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < audioFiles.size(); i++) {
            files.add(audioFiles.get(i));
        }
        return files;
    }

    void addAudioFileForTest(File file) { audioFiles.addElement(file); }

    File lastPreviewTargetForTest() { return lastPreviewedFile; }

    boolean removeMenuEnabledForTest() { return removeSelectedItem.isEnabled(); }

    MarkdownPreviewTabs bottomTabsForTest() { return bottomTabs; }
}

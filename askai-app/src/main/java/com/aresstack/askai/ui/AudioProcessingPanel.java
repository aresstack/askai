package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.audio.AudioProfileRepository;
import com.aresstack.askai.audio.transfer.AudioProfileExportService;
import com.aresstack.askai.audio.transfer.AudioProfileImportPreview;
import com.aresstack.askai.audio.transfer.AudioProfileImportResult;
import com.aresstack.askai.audio.transfer.AudioProfileImportService;
import com.aresstack.askai.audio.transfer.AudioProfileTransferException;
import com.aresstack.askai.audio.transfer.PlannedProfileImport;
import com.aresstack.askai.audio.transfer.RejectedProfileImport;
import com.aresstack.askai.state.ApplicationStateService;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.pipeline.AudioProfileValidationIssue;
import com.aresstack.audio.pipeline.AudioProfileValidationResult;
import com.aresstack.audio.pipeline.AudioProfileValidator;
import com.aresstack.audio.pipeline.AudioValidationSeverity;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Configure reusable audio profiles with a Java2D pipeline canvas and a type-specific inspector. */
public final class AudioProcessingPanel extends JPanel {

    /** Application-state key under which the last selected audio profile id is remembered. */
    private static final String SELECTED_PROFILE_KEY = "audio.selectedProfileId";

    private final AudioProfileRepository repository;
    private final ApplicationStateService applicationState;
    private final JComboBox<AudioProcessingProfile> profileCombo = ComicControls.<AudioProcessingProfile>comboBox();
    private final JButton saveButton = new ComicButton("Save");
    private final JButton saveAsButton = new ComicButton("Save as…");
    private final JButton deleteButton = new ComicButton("Delete");
    private final JButton resetButton = new ComicButton("Reset changes");
    private final JButton importButton = new ComicButton("Import profiles…");
    private final JButton exportSelectedButton = new ComicButton("Export selected profile…");
    private final JButton exportAllButton = new ComicButton("Export all user profiles…");
    private final JButton addButton = new ComicButton("Add block");
    private final JButton removeButton = new ComicButton("Remove block");
    private final AudioProfileExportService exportService = new AudioProfileExportService();
    private final AudioProfileImportService importService;
    private final AudioProfileValidator validator = new AudioProfileValidator();
    private final JLabel validationSummary = new JLabel(" ");
    private final DefaultListModel<AudioProfileValidationIssue> issueModel =
            new DefaultListModel<AudioProfileValidationIssue>();
    private final JList<AudioProfileValidationIssue> issueList =
            ComicControls.<AudioProfileValidationIssue>list(issueModel);
    private AudioProfileValidationResult validation =
            new AudioProfileValidationResult(Collections.<AudioProfileValidationIssue>emptyList());
    private boolean updatingIssueSelection;
    private final JLabel statusLabel = new JLabel(" ");
    private final AudioPipelineCanvas canvas = new AudioPipelineCanvas();
    private final AudioBlockInspectorPanel inspector = new AudioBlockInspectorPanel();
    private final AudioInspectorCard inspectorCard =
            new AudioInspectorCard(inspector, AudioPipelineCanvas.BLOCK_WIDTH);
    private AudioProcessingTestPanel testPanel;

    private AudioProcessingProfile selectedProfile;
    private List<AudioBlockDefinition> workingBlocks = new ArrayList<AudioBlockDefinition>();
    private boolean dirty;
    private boolean updatingProfileCombo;
    private File lastTransferDirectory;

    public AudioProcessingPanel(AudioProfileRepository repository) {
        this(repository, null);
    }

    public AudioProcessingPanel(AudioProfileRepository repository, ApplicationStateService applicationState) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository must not be null.");
        }
        this.repository = repository;
        this.applicationState = applicationState;
        this.importService = new AudioProfileImportService(repository);
        buildUserInterface();
        wireActions();
        reloadProfiles(rememberedProfileId());
    }

    private String rememberedProfileId() {
        return applicationState == null
                ? null
                : applicationState.get(SELECTED_PROFILE_KEY, AudioProcessingProfiles.DEFAULT_PROFILE_ID);
    }

    public void refreshProfiles() {
        String selectedId = selectedProfile == null ? null : selectedProfile.getId();
        reloadProfiles(selectedId);
    }

    /** @return the id of the profile currently loaded into the editor (visible for tests). */
    String selectedProfileId() {
        return selectedProfile == null ? null : selectedProfile.getId();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.add(buildToolbar(), BorderLayout.NORTH);
        north.add(buildValidationStrip(), BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(buildEditor(), BorderLayout.CENTER);
        // Test/preview area: process the CURRENT (possibly unsaved) pipeline snapshot on a test source.
        this.testPanel = new AudioProcessingTestPanel(new java.util.function.Supplier<AudioProcessingProfile>() {
            public AudioProcessingProfile get() {
                return currentPipelineSnapshot();
            }
        });
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(testPanel, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    /** @return an immutable snapshot of the current working pipeline (unsaved edits included), or null. */
    private AudioProcessingProfile currentPipelineSnapshot() {
        if (selectedProfile == null) {
            return null;
        }
        return selectedProfile.withBlocks(new ArrayList<AudioBlockDefinition>(workingBlocks));
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        JPanel profileArea = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        profileArea.add(new JLabel("Profile"));
        profileCombo.setPreferredSize(new Dimension(260, profileCombo.getPreferredSize().height));
        profileArea.add(profileCombo);
        profileArea.add(saveButton);
        profileArea.add(saveAsButton);
        profileArea.add(deleteButton);
        profileArea.add(resetButton);
        profileArea.add(importButton);
        profileArea.add(exportSelectedButton);
        profileArea.add(exportAllButton);

        JPanel blockArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        blockArea.add(addButton);
        blockArea.add(removeButton);
        toolbar.add(profileArea, BorderLayout.CENTER);
        toolbar.add(blockArea, BorderLayout.EAST);
        return toolbar;
    }

    private JPanel buildValidationStrip() {
        JPanel strip = new JPanel(new BorderLayout(0, 2));
        strip.setBorder(ComicControls.titledBorder("Validation"));
        validationSummary.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        strip.add(validationSummary, BorderLayout.NORTH);

        issueList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        issueList.setVisibleRowCount(3);
        issueList.setCellRenderer(new DefaultListCellRenderer() {
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof AudioProfileValidationIssue) {
                    AudioProfileValidationIssue issue = (AudioProfileValidationIssue) value;
                    String tag = issue.getSeverity() == AudioValidationSeverity.ERROR ? "ERROR" : "warning";
                    setText(tag + " · " + issue.getBlockType().getDisplayName() + ": " + issue.getMessage());
                }
                return this;
            }
        });
        issueList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                onIssueSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(issueList);
        scroll.setPreferredSize(new Dimension(100, 74));
        strip.add(scroll, BorderLayout.CENTER);
        return strip;
    }

    private JPanel buildEditor() {
        JPanel editor = new JPanel(new BorderLayout(8, 8));
        // The settings card lives INSIDE the canvas, directly under the selected block, so the pipeline sits
        // at the top and the card scrolls together with it — no scroll bar between the two.
        canvas.setInspectorCard(inspectorCard);
        JScrollPane canvasScroll = new JScrollPane(canvas,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        canvasScroll.setBorder(BorderFactory.createEmptyBorder());
        canvasScroll.getViewport().setBackground(canvas.getBackground());
        editor.add(canvasScroll, BorderLayout.CENTER);
        return editor;
    }

    private void wireActions() {
        profileCombo.addActionListener(event -> profileSelectionChanged());
        saveButton.addActionListener(event -> saveCurrentProfile());
        saveAsButton.addActionListener(event -> saveAsProfile());
        deleteButton.addActionListener(event -> deleteCurrentProfile());
        resetButton.addActionListener(event -> resetWorkingCopy());
        importButton.addActionListener(event -> importProfiles());
        exportSelectedButton.addActionListener(event -> exportSelectedProfile());
        exportAllButton.addActionListener(event -> exportAllUserProfiles());
        addButton.addActionListener(event -> addBlock());
        removeButton.addActionListener(event -> removeSelectedBlock());

        canvas.setListener(new AudioPipelineCanvas.Listener() {
            public void selectionChanged(int selectedIndex) {
                inspect(selectedIndex);
                updateButtons();
                refreshInspectorValidation();
            }

            public void orderChanged(List<AudioBlockDefinition> blocks, int selectedIndex) {
                workingBlocks = new ArrayList<AudioBlockDefinition>(blocks);
                markDirty("Block order changed.");
                inspect(selectedIndex);
            }
        });
        inspector.setListener(new AudioBlockInspectorPanel.Listener() {
            public void blockChanged(AudioBlockDefinition block) {
                replaceSelectedBlock(block);
            }
        });
    }

    private void profileSelectionChanged() {
        if (updatingProfileCombo) {
            return;
        }
        AudioProcessingProfile requested = (AudioProcessingProfile) profileCombo.getSelectedItem();
        if (requested == null || requested == selectedProfile) {
            return;
        }
        if (dirty && !confirmDiscardChanges()) {
            selectProfileInCombo(selectedProfile == null ? null : selectedProfile.getId());
            return;
        }
        loadWorkingCopy(requested);
    }

    private void loadWorkingCopy(AudioProcessingProfile profile) {
        selectedProfile = profile;
        if (applicationState != null && profile.getId() != null) {
            applicationState.putAndSave(SELECTED_PROFILE_KEY, profile.getId());
        }
        workingBlocks = new ArrayList<AudioBlockDefinition>(profile.getBlocks());
        dirty = false;
        canvas.setBlocks(workingBlocks);
        canvas.setSelectedIndex(workingBlocks.isEmpty() ? -1 : 0);
        inspect(canvas.getSelectedIndex());
        setStatus(profile.isBuiltIn()
                ? "The default profile is editable here but can only be stored under a new name."
                : "Profile loaded.");
        updateButtons();
        if (testPanel != null) {
            testPanel.pipelineChanged(); // a switched/loaded profile invalidates any existing preview
        }
        validateNow();
    }

    private void saveCurrentProfile() {
        if (blockedByErrors("save")) {
            return;
        }
        if (selectedProfile == null || selectedProfile.isBuiltIn()) {
            saveAsProfile();
            return;
        }
        AudioProcessingProfile changed = selectedProfile.withBlocks(workingBlocks);
        try {
            repository.save(changed);
            selectedProfile = changed;
            dirty = false;
            reloadProfiles(changed.getId());
            setStatus("Profile saved.");
        } catch (IOException ex) {
            showError("Could not save the profile.", ex);
        }
    }

    private void saveAsProfile() {
        if (selectedProfile == null) {
            return;
        }
        if (blockedByErrors("save")) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Name for the new audio profile:",
                selectedProfile.getName() + " copy");
        if (name == null) {
            return;
        }
        try {
            AudioProcessingProfile working = selectedProfile.withBlocks(workingBlocks);
            AudioProcessingProfile saved = repository.saveAs(working, name);
            dirty = false;
            reloadProfiles(saved.getId());
            setStatus("Profile saved as “" + saved.getName() + "”.");
        } catch (Exception ex) {
            showError("Could not save the new profile.", ex);
        }
    }

    private void deleteCurrentProfile() {
        if (selectedProfile == null || selectedProfile.isBuiltIn()) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete the audio profile “" + selectedProfile.getName() + "”?",
                "Delete audio profile", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            repository.delete(selectedProfile.getId());
            dirty = false;
            reloadProfiles(null);
            setStatus("Profile deleted.");
        } catch (IOException ex) {
            showError("Could not delete the profile.", ex);
        }
    }

    private void resetWorkingCopy() {
        if (selectedProfile == null) {
            return;
        }
        loadWorkingCopy(repository.findById(selectedProfile.getId()));
        setStatus("Unsaved changes discarded.");
    }

    // ------------------------------------------------------------------ JSON import / export

    private void exportSelectedProfile() {
        if (selectedProfile == null || selectedProfile.isBuiltIn()) {
            setStatus("The built-in default profile cannot be exported.");
            return;
        }
        if (blockedByErrors("export")) {
            return;
        }
        File target = chooseJsonToSave(selectedProfile.getName());
        if (target == null) {
            return;
        }
        try {
            exportService.export(java.util.Collections.singletonList(selectedProfile), target);
            setStatus("Exported “" + selectedProfile.getName() + "” to " + target.getName() + ".");
        } catch (AudioProfileTransferException ex) {
            setStatus(ex.getMessage());
        } catch (IOException ex) {
            showError("Could not export the profile.", ex);
        }
    }

    private void exportAllUserProfiles() {
        File target = chooseJsonToSave("askai-audio-profiles");
        if (target == null) {
            return;
        }
        try {
            exportService.export(repository.findAll(), target);
            setStatus("Exported user profiles to " + target.getName() + ".");
        } catch (AudioProfileTransferException ex) {
            setStatus(ex.getMessage());
        } catch (IOException ex) {
            showError("Could not export the profiles.", ex);
        }
    }

    private void importProfiles() {
        if (dirty && !resolveUnsavedBeforeImport()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (lastTransferDirectory != null) {
            chooser.setCurrentDirectory(lastTransferDirectory);
        }
        chooser.setDialogTitle("Import audio profiles");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File source = chooser.getSelectedFile();
        lastTransferDirectory = source.getParentFile();
        try {
            AudioProfileImportPreview preview = importService.preview(source);
            if (!preview.hasImportableProfiles()) {
                JOptionPane.showMessageDialog(this, importPreviewText(preview),
                        "Nothing to import", JOptionPane.WARNING_MESSAGE);
                setStatus("Nothing to import.");
                return;
            }
            int answer = JOptionPane.showConfirmDialog(this, importPreviewText(preview),
                    "Import preview", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                setStatus("Import cancelled.");
                return;
            }
            AudioProfileImportResult result = importService.commit(preview);
            String firstId = result.getImportedIds().isEmpty() ? null : result.getImportedIds().get(0);
            reloadProfiles(firstId);
            setStatus("Imported " + result.getImportedCount() + " profile(s).");
            if (result.hasFailures() || !preview.getWarnings().isEmpty()) {
                JOptionPane.showMessageDialog(this, importResultText(result, preview),
                        "Import finished", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (AudioProfileTransferException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Import failed", JOptionPane.ERROR_MESSAGE);
            setStatus("Import failed.");
        } catch (IOException ex) {
            showError("Could not read the import file.", ex);
        }
    }

    /** Save/Discard/Cancel before an import. @return true to proceed with the import. */
    private boolean resolveUnsavedBeforeImport() {
        Object[] options = {"Save changes", "Discard changes", "Cancel import"};
        int choice = JOptionPane.showOptionDialog(this,
                "The editor has unsaved changes. What should happen before importing?",
                "Unsaved changes", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            saveCurrentProfile(); // built-in profiles are routed to "Save as…"
            return !dirty;        // proceed only if the save actually completed
        }
        if (choice == 1) {
            dirty = false;
            return true;
        }
        return false;
    }

    private File chooseJsonToSave(String suggestedName) {
        JFileChooser chooser = new JFileChooser();
        if (lastTransferDirectory != null) {
            chooser.setCurrentDirectory(lastTransferDirectory);
        }
        chooser.setDialogTitle("Export audio profiles");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));
        chooser.setSelectedFile(new File(sanitizeFileName(suggestedName) + ".json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".json")) {
            file = new File(file.getParentFile(), file.getName() + ".json");
        }
        lastTransferDirectory = file.getParentFile();
        if (file.exists() && JOptionPane.showConfirmDialog(this,
                "Overwrite the existing file " + file.getName() + "?", "Overwrite",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return null;
        }
        return file;
    }

    private static String sanitizeFileName(String value) {
        String cleaned = value == null ? "profile" : value.trim().replaceAll("[^a-zA-Z0-9._ -]", "_");
        return cleaned.isEmpty() ? "profile" : cleaned;
    }

    private static String importPreviewText(AudioProfileImportPreview preview) {
        StringBuilder text = new StringBuilder();
        text.append("Found ").append(preview.getFoundCount()).append(" profile(s).\n");
        text.append("Importable: ").append(preview.getValidCount()).append('\n');
        text.append("Cannot import: ").append(preview.getInvalidCount()).append('\n');
        text.append("New ids assigned: ").append(preview.getNewIdCount()).append('\n');
        text.append("Name collisions resolved: ").append(preview.getNameCollisionCount()).append('\n');
        for (PlannedProfileImport planned : preview.getImportable()) {
            text.append("  + ").append(planned.getFinalName());
            if (planned.isNameReassigned() || planned.isIdReassigned()) {
                text.append("  (").append(planned.isNameReassigned() ? "renamed" : "")
                        .append(planned.isNameReassigned() && planned.isIdReassigned() ? ", " : "")
                        .append(planned.isIdReassigned() ? "new id" : "").append(')');
            }
            text.append('\n');
        }
        for (RejectedProfileImport rejected : preview.getRejected()) {
            text.append("  ✗ ").append(rejected.getDisplayName())
                    .append(" — ").append(String.join("; ", rejected.getReasons())).append('\n');
        }
        for (String warning : preview.getWarnings()) {
            text.append("! ").append(warning).append('\n');
        }
        if (preview.hasImportableProfiles()) {
            text.append("\nImport these profiles as new profiles?");
        }
        return text.toString();
    }

    private static String importResultText(AudioProfileImportResult result, AudioProfileImportPreview preview) {
        StringBuilder text = new StringBuilder();
        text.append("Imported ").append(result.getImportedCount()).append(" profile(s).\n");
        for (String warning : preview.getWarnings()) {
            text.append("! ").append(warning).append('\n');
        }
        for (String failure : result.getFailures()) {
            text.append("Failed: ").append(failure).append('\n');
        }
        return text.toString();
    }

    private void addBlock() {
        AudioBlockDefinition block = com.aresstack.audio.pipeline.AudioBlockRegistry.getInstance()
                .defaultDefinition(AudioBlockType.PASSTHROUGH, "block-" + UUID.randomUUID().toString());
        workingBlocks.add(block);
        canvas.setBlocks(workingBlocks);
        canvas.setSelectedIndex(workingBlocks.size() - 1);
        inspect(workingBlocks.size() - 1);
        markDirty("Block added. Choose its function in the inspector.");
    }

    private void removeSelectedBlock() {
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= workingBlocks.size()) {
            return;
        }
        workingBlocks.remove(index);
        canvas.setBlocks(workingBlocks);
        int next = workingBlocks.isEmpty() ? -1 : Math.min(index, workingBlocks.size() - 1);
        canvas.setSelectedIndex(next);
        inspect(next);
        markDirty("Block removed.");
    }

    private void replaceSelectedBlock(AudioBlockDefinition block) {
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= workingBlocks.size()) {
            return;
        }
        workingBlocks.set(index, block);
        canvas.setBlocks(workingBlocks);
        canvas.setSelectedIndex(index);
        inspect(index);
        markDirty("Block settings changed.");
    }

    private void inspect(int index) {
        AudioBlockDefinition block = index >= 0 && index < workingBlocks.size()
                ? workingBlocks.get(index) : null;
        inspector.setBlock(block);
        // Roll the settings card down under the selected block, or up again when nothing is selected.
        inspectorCard.setExpanded(block != null);
        canvas.revalidate();
    }

    private void reloadProfiles(String selectedId) {
        List<AudioProcessingProfile> profiles = repository.findAll();
        updatingProfileCombo = true;
        try {
            profileCombo.removeAllItems();
            for (int i = 0; i < profiles.size(); i++) {
                profileCombo.addItem(profiles.get(i));
            }
            AudioProcessingProfile target = findProfile(profiles, selectedId);
            profileCombo.setSelectedItem(target);
            loadWorkingCopy(target);
        } finally {
            updatingProfileCombo = false;
        }
    }

    private static AudioProcessingProfile findProfile(List<AudioProcessingProfile> profiles, String id) {
        if (id != null) {
            for (int i = 0; i < profiles.size(); i++) {
                if (id.equals(profiles.get(i).getId())) {
                    return profiles.get(i);
                }
            }
        }
        return profiles.get(0);
    }

    private void selectProfileInCombo(String id) {
        updatingProfileCombo = true;
        try {
            for (int i = 0; i < profileCombo.getItemCount(); i++) {
                AudioProcessingProfile profile = profileCombo.getItemAt(i);
                if (profile != null && profile.getId().equals(id)) {
                    profileCombo.setSelectedIndex(i);
                    break;
                }
            }
        } finally {
            updatingProfileCombo = false;
        }
    }

    private boolean confirmDiscardChanges() {
        return JOptionPane.showConfirmDialog(this,
                "Discard the unsaved changes to this profile?",
                "Unsaved audio profile", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void markDirty(String message) {
        dirty = true;
        setStatus(message);
        updateButtons();
        if (testPanel != null) {
            testPanel.pipelineChanged(); // any block/param/order change invalidates the current preview
        }
        validateNow();
    }

    // ------------------------------------------------------------------ validation

    /** Validate the current (possibly unsaved) editor snapshot and reflect it in summary/list/canvas/inspector. */
    private void validateNow() {
        AudioProcessingProfile snapshot = currentPipelineSnapshot();
        validation = snapshot == null
                ? new AudioProfileValidationResult(Collections.<AudioProfileValidationIssue>emptyList())
                : validator.validateResult(snapshot);
        updateValidationSummary();
        updateIssueList();
        updateCanvasSeverities();
        updateButtons();
        refreshInspectorValidation();
    }

    private void updateValidationSummary() {
        int errors = validation.errorCount();
        int warnings = validation.warningCount();
        if (errors == 0 && warnings == 0) {
            validationSummary.setText("No problems.");
        } else {
            validationSummary.setText(errors + " error" + (errors == 1 ? "" : "s")
                    + " · " + warnings + " warning" + (warnings == 1 ? "" : "s"));
        }
    }

    private void updateIssueList() {
        updatingIssueSelection = true;
        try {
            issueModel.clear();
            for (AudioProfileValidationIssue issue : validation.getIssues()) {
                issueModel.addElement(issue);
            }
        } finally {
            updatingIssueSelection = false;
        }
    }

    private void updateCanvasSeverities() {
        Map<Integer, AudioValidationSeverity> severities = new HashMap<Integer, AudioValidationSeverity>();
        for (int i = 0; i < workingBlocks.size(); i++) {
            String blockId = workingBlocks.get(i).getId();
            for (AudioProfileValidationIssue issue : validation.issuesForBlock(blockId)) {
                AudioValidationSeverity current = severities.get(i);
                if (current == AudioValidationSeverity.ERROR) {
                    continue; // error already dominates this block
                }
                severities.put(i, issue.getSeverity());
            }
        }
        canvas.setBlockSeverities(severities);
    }

    /** Mark the invalid parameters of the currently selected block in the inspector. */
    private void refreshInspectorValidation() {
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= workingBlocks.size()) {
            inspector.setInvalidParameters(Collections.<String, String>emptyMap());
            return;
        }
        String blockId = workingBlocks.get(index).getId();
        Map<String, String> invalid = new LinkedHashMap<String, String>();
        for (AudioProfileValidationIssue issue : validation.issuesForBlock(blockId)) {
            if (issue.getSeverity() == AudioValidationSeverity.ERROR && issue.getParameterKey() != null) {
                invalid.put(issue.getParameterKey(), issue.getMessage());
            }
        }
        inspector.setInvalidParameters(invalid);
    }

    private void onIssueSelected() {
        if (updatingIssueSelection) {
            return;
        }
        AudioProfileValidationIssue issue = issueList.getSelectedValue();
        if (issue == null) {
            return;
        }
        int index = indexOfBlock(issue.getBlockId());
        if (index >= 0) {
            canvas.setSelectedIndex(index);
            inspect(index);
            refreshInspectorValidation();
            if (issue.getParameterKey() != null) {
                inspector.focusParameter(issue.getParameterKey());
            }
            updateButtons();
        }
    }

    private void selectFirstError() {
        AudioProfileValidationIssue first = validation.firstError();
        if (first == null) {
            return;
        }
        for (int i = 0; i < issueModel.size(); i++) {
            if (issueModel.get(i) == first) {
                issueList.setSelectedIndex(i); // triggers onIssueSelected → selects the block + focuses the field
                return;
            }
        }
    }

    private int indexOfBlock(String blockId) {
        for (int i = 0; i < workingBlocks.size(); i++) {
            if (workingBlocks.get(i).getId().equals(blockId)) {
                return i;
            }
        }
        return -1;
    }

    /** @return true when the action must be blocked because the current pipeline has errors. */
    private boolean blockedByErrors(String action) {
        if (!validation.hasErrors()) {
            return false;
        }
        JOptionPane.showMessageDialog(this,
                "Fix the " + validation.errorCount() + " validation error(s) before you can " + action + ".",
                "Validation", JOptionPane.WARNING_MESSAGE);
        setStatus("Cannot " + action + " while the pipeline has errors.");
        selectFirstError();
        return true;
    }

    private void updateButtons() {
        boolean hasProfile = selectedProfile != null;
        saveButton.setEnabled(hasProfile && dirty && !selectedProfile.isBuiltIn());
        saveAsButton.setEnabled(hasProfile);
        deleteButton.setEnabled(hasProfile && !selectedProfile.isBuiltIn());
        resetButton.setEnabled(hasProfile && dirty);
        // The built-in default profile can never be exported.
        exportSelectedButton.setEnabled(hasProfile && !selectedProfile.isBuiltIn());
        removeButton.setEnabled(canvas.getSelectedIndex() >= 0);
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.isEmpty() ? " " : text);
    }

    private void showError(String message, Exception ex) {
        JOptionPane.showMessageDialog(this, message + "\n" + ex.getMessage(),
                "Audio profile", JOptionPane.ERROR_MESSAGE);
    }
}

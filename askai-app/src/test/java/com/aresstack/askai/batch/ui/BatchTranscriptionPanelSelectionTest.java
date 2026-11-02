package com.aresstack.askai.batch.ui;

import com.aresstack.askai.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.ui.ToggleSelectionList;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The audio file list uses standard multi-select (not toggle): a click drives only the preview and the
 * remove action, never what is transcribed. Models/profiles keep their toggle behaviour.
 */
public class BatchTranscriptionPanelSelectionTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final Function<String, JComponent> FAKE_MD = new Function<String, JComponent>() {
        public JComponent apply(String markdown) {
            return new JTextArea(markdown);
        }
    };

    // 1 + 14: standard multi-interval selection, not a ToggleSelectionList; models/profiles stay toggle.
    @Test
    public void audioListUsesPlainMultiSelectWhileModelsAndProfilesStayToggle() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                assertFalse("audio list is not a toggle list",
                        panel.audioFileListComponent() instanceof ToggleSelectionList);
                assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION,
                        panel.audioFileListComponent().getSelectionMode());
                assertTrue(panel.modelListComponent() instanceof ToggleSelectionList);
                assertTrue(panel.profileListComponent() instanceof ToggleSelectionList);
            }
        });
    }

    // 2 + 3: selecting one file previews it; selecting another replaces the selection and the preview.
    @Test
    public void selectingAFilePreviewsItAndReplacesOnNextSelection() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                File a = add(panel, "a.wav");
                File b = add(panel, "b.wav");
                JList<File> list = panel.audioFileListComponent();

                list.setSelectedIndex(0);
                assertArrayEquals(new int[]{0}, list.getSelectedIndices());
                assertSame(a, panel.lastPreviewTargetForTest());

                list.setSelectedIndex(1);
                assertArrayEquals(new int[]{1}, list.getSelectedIndices());
                assertSame(b, panel.lastPreviewTargetForTest());
            }
        });
    }

    // 4 + 5: Ctrl-style multi-selection; the preview follows the lead index, not the first.
    @Test
    public void multiSelectionPreviewsTheLeadIndex() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                add(panel, "a.wav");
                add(panel, "b.wav");
                File c = add(panel, "c.wav");
                JList<File> list = panel.audioFileListComponent();

                list.setSelectedIndex(0);
                list.addSelectionInterval(2, 2); // lead is now 2
                assertArrayEquals(new int[]{0, 2}, list.getSelectedIndices());
                panel.previewLeadSelection();
                assertSame("preview follows the lead, not the first selected", c,
                        panel.lastPreviewTargetForTest());
            }
        });
    }

    // 6: right-click on an already-selected row keeps the multi-selection (only moves the lead).
    @Test
    public void rightClickOnSelectedRowKeepsMultiSelection() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                File a = add(panel, "a.wav");
                add(panel, "b.wav");
                add(panel, "c.wav");
                JList<File> list = panel.audioFileListComponent();
                list.setSelectedIndex(0);
                list.addSelectionInterval(2, 2); // {0,2}

                boolean offerRemove = panel.selectForContextMenu(0); // 0 is selected
                assertTrue(offerRemove);
                assertArrayEquals("selection unchanged", new int[]{0, 2}, list.getSelectedIndices());
                assertSame(a, panel.lastPreviewTargetForTest());
            }
        });
    }

    // 7: right-click on an unselected row selects only that row.
    @Test
    public void rightClickOnUnselectedRowSelectsOnlyIt() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                add(panel, "a.wav");
                File b = add(panel, "b.wav");
                add(panel, "c.wav");
                JList<File> list = panel.audioFileListComponent();
                list.setSelectedIndex(0);
                list.addSelectionInterval(2, 2); // {0,2}

                boolean offerRemove = panel.selectForContextMenu(1); // 1 not selected
                assertTrue(offerRemove);
                assertArrayEquals(new int[]{1}, list.getSelectedIndices());
                assertSame(b, panel.lastPreviewTargetForTest());
            }
        });
    }

    // 8: right-click in empty space changes nothing and does not offer remove.
    @Test
    public void rightClickInEmptyAreaChangesNothing() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                add(panel, "a.wav");
                JList<File> list = panel.audioFileListComponent();
                list.setSelectedIndex(0);

                boolean offerRemove = panel.selectForContextMenu(-1);
                assertFalse("no remove for an empty-area click", offerRemove);
                assertArrayEquals("selection untouched", new int[]{0}, list.getSelectedIndices());
            }
        });
    }

    // 9 + 11: remove deletes selected model entries (order preserved) and selects a sensible neighbour.
    @Test
    public void removeDeletesSelectedEntriesAndSelectsNeighbour() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                File a = add(panel, "a.wav");
                add(panel, "b.wav");
                add(panel, "c.wav");
                File d = add(panel, "d.wav");
                JList<File> list = panel.audioFileListComponent();

                list.setSelectedIndices(new int[]{1, 2}); // remove b, c
                panel.removeSelectedAudioFiles();

                assertEquals(java.util.Arrays.asList(a, d), panel.inputFilesForTest());
                assertArrayEquals("neighbour that shifted into the first removed slot", new int[]{1},
                        list.getSelectedIndices());
                assertSame(d, panel.lastPreviewTargetForTest());
            }
        });
    }

    // 10: remove never touches the file system.
    @Test
    public void removeDoesNotDeleteFilesFromDisk() throws Exception {
        final File onDisk = folder.newFile("keep.wav");
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                panel.addAudioFileForTest(onDisk);
                panel.audioFileListComponent().setSelectedIndex(0);
                panel.removeSelectedAudioFiles();
                assertTrue(panel.inputFilesForTest().isEmpty());
            }
        });
        assertTrue("the audio file still exists on disk", onDisk.isFile());
    }

    // 12: removing the last entry clears the preview.
    @Test
    public void emptyingTheListClearsThePreview() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                add(panel, "only.wav");
                panel.audioFileListComponent().setSelectedIndex(0);
                panel.removeSelectedAudioFiles();
                assertTrue(panel.inputFilesForTest().isEmpty());
                assertNull("preview cleared", panel.lastPreviewTargetForTest());
                assertEquals("only the fixed Log tab remains", 1, panel.bottomTabsForTest().tabCount());
            }
        });
    }

    // 13: the batch input is the whole list model, independent of Swing selection.
    @Test
    public void batchInputIsTheWholeModelNotTheSelection() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                BatchTranscriptionPanel panel = build();
                File a = add(panel, "a.wav");
                File b = add(panel, "b.wav");
                File c = add(panel, "c.wav");
                panel.audioFileListComponent().setSelectedIndex(1); // only one selected

                assertEquals("all files are batch input regardless of selection",
                        java.util.Arrays.asList(a, b, c), panel.inputFilesForTest());
            }
        });
    }

    // --- helpers ---

    private BatchTranscriptionPanel build() {
        BatchTranscriptionController controller =
                new BatchTranscriptionController(null, new BatchTranscriptionEventPublisher());
        return new BatchTranscriptionPanel(controller, new ArrayList<String>(),
                new ArrayList<AudioProcessingProfile>(), new NoRefresh(), FAKE_MD);
    }

    private File add(BatchTranscriptionPanel panel, String name) {
        File file = new File(folder.getRoot(), name);
        panel.addAudioFileForTest(file);
        return file;
    }

    private static final class NoRefresh implements BatchProfileRefresher {
        public void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback) { }
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}

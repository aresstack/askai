package com.aresstack.askai.batch.ui;

import com.aresstack.askai.batch.service.BatchProfileCatalogLoadedEvent;
import com.aresstack.askai.batch.service.BatchTranscriptionEventPublisher;
import com.aresstack.askai.catalog.GlobalCatalogSnapshot;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The batch panel now receives models + profiles from the global catalog refresh via applyCatalogSnapshot
 * (each part independent), and its local refresh button reloads only the audio profiles.
 */
public class BatchTranscriptionPanelRefreshTest {

    private static final AudioProcessingProfile P1 = profile("id1", "Clean speech");
    private static final AudioProcessingProfile P2 = profile("id2", "Meeting room");

    private static final Function<String, JComponent> FAKE_MD = new Function<String, JComponent>() {
        public JComponent apply(String markdown) {
            return new JTextArea(markdown);
        }
    };

    @Test
    public void snapshotAppliesAudioModelsAndProfiles() throws Exception {
        FakeProfileRefresher fake = new FakeProfileRefresher();
        BatchTranscriptionPanel panel = build(fake);
        onEdt(panel, new Runnable() {
            public void run() {
                panel.applyCatalogSnapshot(snapshot(true, asList("a", "b"), true, asList(P1, P2)));
                assertEquals(2, panel.modelListComponent().getModel().getSize());
                assertEquals(2, panel.profileListComponent().getModel().getSize());
            }
        });
    }

    @Test
    public void snapshotPreservesModelSelectionDropsRemovedNewNotSelected() throws Exception {
        BatchTranscriptionPanel panel = build(new FakeProfileRefresher());
        onEdt(panel, new Runnable() {
            public void run() {
                panel.applyCatalogSnapshot(snapshot(true, asList("model-a", "model-b"), false, null));
                panel.modelListComponent().setSelectedIndices(new int[]{0, 1});
                panel.applyCatalogSnapshot(snapshot(true, asList("model-a", "model-c"), false, null));
                assertEquals(asList("model-a"), panel.modelListComponent().getSelectedValuesList());
                assertEquals(2, panel.modelListComponent().getModel().getSize());
            }
        });
    }

    @Test
    public void snapshotPreservesProfileSelectionByIdMultiSelect() throws Exception {
        BatchTranscriptionPanel panel = build(new FakeProfileRefresher());
        onEdt(panel, new Runnable() {
            public void run() {
                panel.applyCatalogSnapshot(snapshot(false, null, true, asList(P1, P2)));
                panel.profileListComponent().setSelectedIndices(new int[]{0, 1});
                panel.applyCatalogSnapshot(snapshot(false, null, true, asList(P2, P1))); // reorder, both remain
                assertEquals(2, panel.profileListComponent().getSelectedValuesList().size());
            }
        });
    }

    @Test
    public void partialFailureLeavesTheUnloadedListUntouched() throws Exception {
        BatchTranscriptionPanel panel = build(new FakeProfileRefresher());
        onEdt(panel, new Runnable() {
            public void run() {
                panel.applyCatalogSnapshot(snapshot(true, asList("a", "b"), true, asList(P1)));
                // models failed this time (not loaded), profiles loaded -> models kept, profiles updated
                panel.applyCatalogSnapshot(snapshot(false, null, true, asList(P1, P2)));
                assertEquals("models untouched when audio models did not load", 2,
                        panel.modelListComponent().getModel().getSize());
                assertEquals(2, panel.profileListComponent().getModel().getSize());
            }
        });
    }

    @Test
    public void localRefreshReloadsOnlyProfilesAndBlocksASecondRun() throws Exception {
        FakeProfileRefresher fake = new FakeProfileRefresher();
        BatchTranscriptionPanel panel = build(fake);
        onEdt(panel, new Runnable() {
            public void run() {
                panel.refresh();
                panel.refresh(); // ignored while the first is in flight
                assertEquals(1, fake.calls);
            }
        });
        fake.callback.accept(BatchProfileCatalogLoadedEvent.loaded(asList(P1, P2)));
        onEdt(panel, new Runnable() {
            public void run() {
                assertEquals(2, panel.profileListComponent().getModel().getSize());
                panel.refresh(); // finished -> a further refresh is accepted
                assertTrue(fake.calls >= 2);
            }
        });
    }

    // --- helpers ---

    private BatchTranscriptionPanel build(BatchProfileRefresher refresher) throws Exception {
        final BatchTranscriptionPanel[] holder = new BatchTranscriptionPanel[1];
        BatchTranscriptionController controller =
                new BatchTranscriptionController(null, new BatchTranscriptionEventPublisher());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0] = new BatchTranscriptionPanel(controller, new ArrayList<String>(),
                        new ArrayList<AudioProcessingProfile>(), refresher, FAKE_MD);
            }
        });
        return holder[0];
    }

    private static GlobalCatalogSnapshot snapshot(boolean audioModelsLoaded, List<String> audioModels,
                                                  boolean profilesLoaded, List<AudioProcessingProfile> profiles) {
        return new GlobalCatalogSnapshot(true, Collections.<String>emptyList(),
                audioModelsLoaded, audioModels, profilesLoaded, profiles, Collections.<String>emptyList());
    }

    private static AudioProcessingProfile profile(String id, String name) {
        return new AudioProcessingProfile(id, name, false, new ArrayList<AudioBlockDefinition>());
    }

    private static final class FakeProfileRefresher implements BatchProfileRefresher {
        private Consumer<BatchProfileCatalogLoadedEvent> callback;
        private int calls;

        public void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback) {
            calls++;
            this.callback = callback;
        }
    }

    private static void onEdt(BatchTranscriptionPanel panel, Runnable runnable) throws Exception {
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

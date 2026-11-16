package com.aresstack.askai.research.sources;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

/** The sources view builds from the shared repository; two views never share Swing components. */
public class ResearchSourcesViewTest {

    @Test
    public void twoViewsOverOneRepositoryAreIndependentAndShowTheData() throws Exception {
        final ResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        final AtomicReference<ResearchSourcesView> a = new AtomicReference<ResearchSourcesView>();
        final AtomicReference<ResearchSourcesView> b = new AtomicReference<ResearchSourcesView>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                a.set(new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections()));
                b.set(new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections()));
            }
        });
        assertNotSame(a.get(), b.get());
        assertEquals(3, a.get().rowCount());
        assertEquals(3, b.get().rowCount());
    }

    @Test
    public void theTableSpeaksHumanNotEnum() throws Exception {
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        repo.put(ResearchSourceRecord.builder("s-1").title("")
                .url("https://de.wikipedia.org/wiki/Hasenfleisch").revision(1L)
                .status(SourceStatus.PARKED).rerankScore(0.07).build());
        final AtomicReference<ResearchSourcesView> v = new AtomicReference<ResearchSourcesView>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                v.set(new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections()));
            }
        });
        ResearchSourcesView view = v.get();
        assertEquals("an untitled source shows its URL, never an empty main column",
                "https://de.wikipedia.org/wiki/Hasenfleisch", view.cellAt(0, 1));
        assertEquals("the site column is the bare host", "de.wikipedia.org", view.cellAt(0, 2));
        assertEquals("status is a readable label, not a raw enum name", "Unread", view.cellAt(0, 3));
        assertEquals("the score is TYPED so the sorter orders it numerically",
                Double.valueOf(0.07), view.cellAt(0, 4));
        assertEquals("a source that was not read yet is pending (grey)", SourceRowState.PENDING, view.stateAt(0));
    }

    @Test
    public void theStarColumnReflectsUserRelevant() throws Exception {
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        repo.put(ResearchSourceRecord.builder("s-rel").title("Rel").revision(1L).userRelevant(true).build());
        repo.put(ResearchSourceRecord.builder("s-plain").title("Plain").revision(1L).build());
        final AtomicReference<ResearchSourcesView> v = new AtomicReference<ResearchSourcesView>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                v.set(new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections()));
            }
        });
        ResearchSourcesView view = v.get();
        for (int row = 0; row < view.rowCount(); row++) {
            String title = String.valueOf(view.cellAt(row, 1)); // column 1 = Title (⭐ is column 0)
            String star = String.valueOf(view.cellAt(row, 0));
            if ("Rel".equals(title)) {
                assertEquals("★", star);
            } else if ("Plain".equals(title)) {
                assertEquals("", star);
            }
        }
    }
}

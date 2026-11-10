package com.aresstack.askai.research.sources;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The reader in the main area: everything about the selected source, apart from the sidebar list. */
public class SourceFullTextViewTest {

    private static ResearchSourceRecord hares() {
        return ResearchSourceRecord.builder("s-1").title("Hares").url("https://example.org/hares")
                .author("A. Field").revision(1L).status(SourceStatus.NEW).fullText("Hares are fast.")
                .excerpt("…hares are fast…").comment("check the numbers").linkedSectionIds(
                        Arrays.asList("s1", "gone")).build();
    }

    @Test
    public void selectingASourcePublishesItAndTheReaderShowsItsFullText() throws Exception {
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        repo.put(hares());
        final List<ResearchSourceRecord> published = new ArrayList<ResearchSourceRecord>();
        final AtomicReference<String> shown = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchSourcesView view = new ResearchSourcesView(repo, ResearchSourcesView.demoKnownSections());
                view.setSelectionPublisher(published::add);
                view.focusSource("s-1");
                SourceFullTextView reader = new SourceFullTextView();
                if (!published.isEmpty() && published.get(published.size() - 1) != null) {
                    reader.show(published.get(published.size() - 1));
                }
                shown.set(reader.shownText());
            }
        });
        assertTrue("the selection was published", !published.isEmpty());
        assertEquals("s-1", published.get(published.size() - 1).getSourceId());
        assertEquals("Hares are fast.", shown.get());
    }

    @Test
    public void theHeadCarriesAddressAndAuthorAndTheSwitchChangesTheText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SourceFullTextView reader = new SourceFullTextView();
            reader.show(hares());
            assertEquals("https://example.org/hares", reader.shownAddress());
            assertEquals("A. Field", reader.shownAuthor());
            assertFalse("the details are closed until asked for", reader.detailsOpen());
            reader.button("Show the details").doClick();
            assertTrue(reader.detailsOpen());

            reader.selectMode(SourceFullTextView.MODE_EXCERPT);
            assertEquals("…hares are fast…", reader.shownText());
            reader.selectMode(SourceFullTextView.MODE_PASSAGES);
            assertTrue(reader.shownText().startsWith("(no knowledge capability"));
            reader.selectMode(SourceFullTextView.MODE_FULL_TEXT);
            assertEquals("Hares are fast.", reader.shownText());
        });
    }

    @Test
    public void relevantAndCommentAreSavedThroughTheRepositoryAndTheListIsTold() throws Exception {
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        repo.put(hares());
        final int[] told = {0};
        SwingUtilities.invokeAndWait(() -> {
            SourceFullTextView reader = new SourceFullTextView(repo, ResearchSourcesView.demoKnownSections());
            reader.setChangeListener(() -> told[0]++);
            reader.show(repo.get("s-1"));
            assertFalse("nothing to save yet", reader.button("Save the details and the comment").isEnabled());

            reader.button("Mark as relevant").doClick();
            assertTrue(repo.get("s-1").isUserRelevant());

            reader.typeComment("numbers verified");
            reader.typeSections("s1\ns2");
            reader.button("Save the details and the comment").doClick();
            assertEquals("numbers verified", repo.get("s-1").getComment());
            assertEquals(Arrays.asList("s1", "s2"), repo.get("s-1").getLinkedSectionIds());
            assertEquals("Saved.", reader.feedbackText());

            reader.button("Exclude this source (reversible)").doClick();
            assertEquals(SourceStatus.EXCLUDED, repo.get("s-1").getStatus());
        });
        assertEquals(3, told[0]);
    }

    @Test
    public void aRefreshOfTheSameRevisionKeepsTheUnsavedComment() throws Exception {
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        repo.put(hares());
        SwingUtilities.invokeAndWait(() -> {
            SourceFullTextView reader = new SourceFullTextView(repo, ResearchSourcesView.demoKnownSections());
            reader.show(repo.get("s-1"));
            reader.typeComment("half a thought");
            reader.show(repo.get("s-1")); // the list refreshed while the user typed
            assertTrue(reader.button("Save the details and the comment").isEnabled());
            reader.button("Save the details and the comment").doClick();
            assertEquals("half a thought", repo.get("s-1").getComment());
        });
    }

    @Test
    public void sectionsAreReadOnePerLineOrCommaSeparatedWithoutTheOrphanMark() {
        assertEquals(Arrays.asList("s1", "gone", "s2"),
                SourceFullTextView.parseSections("s1\ngone (orphan), s2\n\ns1"));
    }
}

package com.aresstack.askai.research.store;

import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import com.aresstack.askai.research.capture.ImportedSourceStore;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceUpdate;
import com.aresstack.askai.research.sources.SourceUpdateResult;
import com.aresstack.askai.research.visualize.VisualizationProjection;
import com.aresstack.askai.research.visualize.VisualizationResult;
import com.aresstack.askai.research.visualize.VisualizationType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** PR 02: non-ASCII text survives three read-modify-write cycles in every plugin project store. */
public class Utf8PluginStoresTest {

    /** German umlauts, sharp s, typographic quotes, en dash and an emoji (a surrogate pair). */
    static final String TEXT = "Grüße „Zitat“ – ß 😀";
    static final String THRICE = TEXT + TEXT + TEXT;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void sourceRepositoryKeepsText() throws Exception {
        File dir = folder.newFolder("sources");
        new FileResearchSourceRepository(dir).put(ResearchSourceRecord.builder("s1").revision(1L).build());
        for (int cycle = 0; cycle < 3; cycle++) {
            FileResearchSourceRepository repo = new FileResearchSourceRepository(dir);
            ResearchSourceRecord current = repo.get("s1");
            SourceUpdateResult result = repo.update("s1", current.getRevision(), SourceUpdate.from(current)
                    .title(current.getTitle() + TEXT).comment(current.getComment() + TEXT)
                    .author(current.getAuthor() + TEXT).build());
            assertEquals(SourceUpdateResult.Status.UPDATED, result.getStatus());
        }
        ResearchSourceRecord back = new FileResearchSourceRepository(dir).get("s1");
        assertEquals(THRICE, back.getTitle());
        assertEquals(THRICE, back.getComment());
        assertEquals(THRICE, back.getAuthor());
    }

    @Test
    public void twoThreadsUpdatingOneSourceLoseNoField() throws Exception {
        File dir = folder.newFolder("race");
        new FileResearchSourceRepository(dir).put(ResearchSourceRecord.builder("s1").revision(1L).build());
        final int rounds = 40;
        final CountDownLatch start = new CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        Thread titles = writer(dir, start, rounds, true, failure);
        Thread comments = writer(dir, start, rounds, false, failure);
        titles.start();
        comments.start();
        start.countDown();
        titles.join(60000L);
        comments.join(60000L);
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        ResearchSourceRecord back = new FileResearchSourceRepository(dir).get("s1");
        assertEquals("every title append survived", rounds, count(back.getTitle(), "T"));
        assertEquals("every comment append survived", rounds, count(back.getComment(), "C"));
        assertEquals(1L + 2L * rounds, back.getRevision());
    }

    private static Thread writer(final File dir, final CountDownLatch start, final int rounds,
                                 final boolean title, final Throwable[] failure) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                    FileResearchSourceRepository repo = new FileResearchSourceRepository(dir);
                    int done = 0;
                    while (done < rounds) {
                        ResearchSourceRecord current = repo.get("s1");
                        SourceUpdate.Builder update = SourceUpdate.from(current);
                        if (title) {
                            update.title(current.getTitle() + "T");
                        } else {
                            update.comment(current.getComment() + "C");
                        }
                        SourceUpdateResult result = repo.update("s1", current.getRevision(), update.build());
                        if (result.getStatus() == SourceUpdateResult.Status.UPDATED) {
                            done++;
                        } else if (result.getStatus() != SourceUpdateResult.Status.CONFLICT) {
                            throw new IllegalStateException(result.getReason());
                        }
                    }
                } catch (Throwable t) {
                    synchronized (failure) {
                        failure[0] = t;
                    }
                }
            }
        });
    }

    private static int count(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    public void visualizationKeepsText() throws Exception {
        File dir = folder.newFolder("viz");
        for (int cycle = 0; cycle < 3; cycle++) {
            VisualizationProjection previous = new FileVisualizationStore(dir).load();
            String title = (previous == null ? "" : previous.getResult().getTitle()) + TEXT;
            new FileVisualizationStore(dir).save(new VisualizationProjection("research-brief", "h", "scoping",
                    VisualizationResult.diagram(VisualizationType.MINDMAP, title, "mindmap\n  root((" + title + "))")));
        }
        VisualizationProjection back = new FileVisualizationStore(dir).load();
        assertEquals(THRICE, back.getResult().getTitle());
        assertEquals("mindmap\n  root((" + THRICE + "))", back.getResult().getMermaid());
    }

    @Test
    public void scopingProjectionKeepsText() throws Exception {
        File dir = folder.newFolder("scoping");
        for (int cycle = 0; cycle < 3; cycle++) {
            ScopingAssistantUpdate previous = new FileScopingProjectionStore(dir).load();
            String query = (previous == null ? "" : previous.getSearchSuggestions().get(0).getQuery()) + TEXT;
            new FileScopingProjectionStore(dir).save(new ScopingAssistantUpdate("scoping",
                    Collections.singletonList(new ScopingAssistantUpdate.Suggestion(query, query, 1)),
                    "CONTINUE", query));
        }
        ScopingAssistantUpdate back = new FileScopingProjectionStore(dir).load();
        assertEquals(THRICE, back.getSearchSuggestions().get(0).getQuery());
        assertEquals(THRICE, back.getSearchSuggestions().get(0).getPurpose());
        assertEquals(THRICE, back.getAdviceReason());
    }

    @Test
    public void briefKeepsText() throws Exception {
        File dir = folder.newFolder("brief");
        for (int cycle = 0; cycle < 3; cycle++) {
            FileResearchBriefStore store = new FileResearchBriefStore(dir);
            String previous = cycle == 0 ? "" : store.effectiveContent();
            assertTrue(store.updateWorkingCopy(previous + TEXT, cycle + 1L));
            new FileResearchBriefStore(dir).approveCurrent(cycle + 10L);
        }
        assertEquals(THRICE, new FileResearchBriefStore(dir).effectiveContent());
        assertEquals(THRICE, new FileResearchBriefStore(dir).latestApprovedContent());
    }

    @Test
    public void artifactKeepsText() throws Exception {
        File dir = folder.newFolder("artifacts");
        for (int cycle = 0; cycle < 3; cycle++) {
            FileArtifactStore store = new FileArtifactStore(dir);
            ArtifactContent current = store.read("draft");
            assertTrue(store.replace("draft", current.getRevision(), current.getMarkdown() + TEXT).isSuccess());
        }
        assertEquals(THRICE, new FileArtifactStore(dir).read("draft").getMarkdown());
    }

    @Test
    public void conceptKeepsText() throws Exception {
        File dir = folder.newFolder("concept");
        String title = "";
        for (int cycle = 0; cycle < 3; cycle++) {
            FileConceptStore store = new FileConceptStore(dir);
            String content = store.effectiveContent();
            assertTrue(content.contains(title));
            title = title + TEXT;
            store.publishWorking("{\"title\":\"" + title + "\",\"subtitle\":\"\",\"concept\":[]}", null);
        }
        assertEquals("{\"title\":\"" + THRICE + "\",\"subtitle\":\"\",\"concept\":[]}",
                new FileConceptStore(dir).effectiveContent());
    }

    @Test
    public void importedSourceKeepsText() throws Exception {
        File dir = folder.newFolder("imports");
        byte[] raw = TEXT.getBytes(StandardCharsets.UTF_8);
        String sha = FileConceptStore.sha256(TEXT);
        String origin = "file:" + TEXT + ".txt";
        String id = ImportedSourceStore.snapshotIdFor(sha, origin);
        new ImportedSourceStore(dir).save(new ImportedSourceStore.ImportedSourceSnapshot(id, "TEXT", raw, sha,
                origin, 1L), new ImportedSourceStore.ExtractionRecord(id, "plain", sha,
                Collections.singletonList(TEXT)));
        String sourceId = "";
        for (int cycle = 0; cycle < 3; cycle++) {
            ImportedSourceStore.Loaded loaded = new ImportedSourceStore(dir).load(id);
            assertEquals(sourceId, loaded.sourceId == null ? "" : loaded.sourceId);
            sourceId = sourceId + TEXT;
            new ImportedSourceStore(dir).bindSource(id, sourceId);
        }
        ImportedSourceStore.Loaded back = new ImportedSourceStore(dir).load(id);
        assertEquals(THRICE, back.sourceId);
        assertEquals(origin, back.snapshot.originUri);
        assertEquals(Collections.singletonList(TEXT), back.extraction.warnings);
    }
}

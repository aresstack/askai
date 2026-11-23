package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.book.OutlineFreezeHook;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.store.FileOutlineStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OutlineChapterServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void finishOutlineIsRefusedWhileARegenerationRunsAndTheResultIsSavedWithFreshIds() {
        final OutlineWorkspace workspace = new OutlineWorkspace(new FileOutlineStore(folder.getRoot()));
        workspace.save(OutlineBriefWorkerTest.drafts(), 0L, 1L);
        Outline stored = workspace.current();
        final AtomicReference<String> blockerDuringCall = new AtomicReference<String>();
        OutlineBriefWorkerTest.ScriptedChat labels = new OutlineBriefWorkerTest.ScriptedChat("labels");
        labels.byPrompt = prompt -> {
            blockerDuringCall.compareAndSet(null, OutlineFreezeHook.finishBlocker(workspace));
            return "not json";
        };
        OutlineChapterService service = new OutlineChapterService(workspace, workspace.jobs(),
                new ChapterRegenerator(new OutlineBriefWorker(labels, new OutlineBriefWorkerTest.ScriptedChat(
                        "briefs", OutlineBriefWorkerTest.briefAnswer(3)), null),
                        new OutlineBriefWorkerTest.Passages()),
                () -> OutlineBriefWorkerTest.CONTEXT, () -> 2L);
        String chapterId = stored.getChapters().get(1).getId();

        OutlineChapterService.Outcome outcome = service.regenerateChapter(chapterId, 3, "");

        assertNotNull(blockerDuringCall.get());
        assertNull(outcome.getBriefFailure());
        assertNull(OutlineFreezeHook.finishBlocker(workspace));
        Outline saved = workspace.current();
        assertEquals(3, saved.chapter(chapterId).getSections().size());
        for (OutlineSection section : saved.chapter(chapterId).getSections()) {
            assertTrue(section.isUntitled());
            assertTrue(section.getTitle().startsWith("Praxis, part "));
            assertTrue(!section.getId().isEmpty() && !stored.allIds().contains(section.getId()));
        }
        assertEquals(stored.getChapters().get(0), saved.getChapters().get(0));
    }

    @Test
    public void aSecondJobIsRefusedWhileOneRuns() {
        OutlineWorkspace workspace = new OutlineWorkspace(new FileOutlineStore(folder.getRoot()));
        workspace.save(OutlineBriefWorkerTest.drafts(), 0L, 1L);
        OutlineChapterService service = new OutlineChapterService(workspace, workspace.jobs(),
                new ChapterRegenerator(new OutlineBriefWorker(new OutlineBriefWorkerTest.ScriptedChat("l"),
                        new OutlineBriefWorkerTest.ScriptedChat("b"), null), new OutlineBriefWorkerTest.Passages()),
                () -> OutlineBriefWorkerTest.CONTEXT, () -> 2L);
        try (com.aresstack.askai.research.outline.OutlineJobGuard.Job ignored = workspace.jobs().begin("Generating")) {
            assertThrows(IllegalStateException.class, () -> service.regenerateChapter(
                    workspace.current().getChapters().get(0).getId(), 0, ""));
        }
    }
}

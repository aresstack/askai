package com.aresstack.askai.research.store;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan PR 21: the outline store on the concept store's V3 pattern. */
public class FileOutlineStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static Outline outline(String... chapterTitles) {
        OutlineChapter[] chapters = new OutlineChapter[chapterTitles.length];
        for (int i = 0; i < chapterTitles.length; i++) {
            chapters[i] = OutlineChapter.draft(chapterTitles[i],
                    new OutlineBrief("purpose", Arrays.asList("p1"), 500),
                    new OutlineProvenance("node-" + i, Arrays.asList("c1"), Arrays.asList("pa1", "pa2")),
                    Collections.singletonList(OutlineSection.draft(chapterTitles[i] + " a", OutlineBrief.EMPTY,
                            OutlineProvenance.NONE)));
        }
        return new Outline(Arrays.asList(chapters));
    }

    @Test
    public void theStoreAssignsIdsParentsAndOrderAndKeepsThem() {
        FileOutlineStore store = new FileOutlineStore(folder.getRoot());
        assertTrue(store.working().isEmpty());
        FileOutlineStore.Commit first = store.commit(outline("One", "Two"), 0L, 1L);
        assertEquals(1L, first.revision);
        OutlineChapter one = first.outline.getChapters().get(0);
        assertTrue(one.getId().startsWith("ch-"));
        assertEquals(1, one.getOrder());
        assertEquals(one.getId(), one.getSections().get(0).getParentId());
        assertTrue(one.getSections().get(0).getId().startsWith("sec-"));

        // reorder chapters and move the section: ids stay, order and parent follow the position
        OutlineChapter two = first.outline.getChapters().get(1);
        Outline moved = new Outline(Arrays.asList(
                two.withSections(Arrays.asList(two.getSections().get(0), one.getSections().get(0))),
                one.withSections(Collections.<OutlineSection>emptyList())));
        FileOutlineStore.Commit second = store.commit(moved, 1L, 2L);
        assertEquals(first.outline.allIds().size(), second.outline.allIds().size());
        assertEquals(two.getId(), second.outline.getChapters().get(0).getId());
        assertEquals(1, second.outline.getChapters().get(0).getOrder());
        assertEquals(two.getId(), second.outline.getChapters().get(0).getSections().get(1).getParentId());
        assertEquals(second.outline, new FileOutlineStore(folder.getRoot()).working());
    }

    @Test
    public void writesNameTheirRevisionAndUnknownIdsAreRefused() {
        FileOutlineStore store = new FileOutlineStore(folder.getRoot());
        store.commit(outline("One"), 0L, 1L);
        try {
            store.commit(outline("Other"), 0L, 2L);
            fail("an outdated revision is a conflict, never an overwrite");
        } catch (FileOutlineStore.RevisionConflictException expected) {
            assertEquals(1L, expected.getCurrentRevision());
        }
        Outline invented = new Outline(Collections.singletonList(new OutlineChapter("ch-invented", 1, "X",
                OutlineBrief.EMPTY, OutlineProvenance.NONE, false, "", Collections.<OutlineSection>emptyList())));
        try {
            store.commit(invented, 1L, 3L);
            fail("ids are store-owned");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("ch-invented"));
        }
        FileOutlineStore.Commit same = store.commit(store.working(), 1L, 4L);
        assertFalse("an unchanged outline writes no revision", same.changed);
        assertEquals(1L, store.workingRevision());
    }

    @Test
    public void approvalsAreImmutableAndNonAsciiRoundTrips() throws Exception {
        FileOutlineStore store = new FileOutlineStore(folder.getRoot());
        String title = "Größe & „Anführung“ – 日本";
        store.commit(outline(title), 0L, 1L);
        FileOutlineStore.Approval approval = store.approveCurrent(5L);
        assertEquals(1, approval.revisionNumber);
        assertTrue(approval.newRevision);
        assertEquals(1L, approval.workingRevision);
        assertFalse("unchanged: the revision is kept", store.approveCurrent(6L).newRevision);

        FileOutlineStore restarted = new FileOutlineStore(folder.getRoot());
        assertEquals(title, restarted.approved(1).getChapters().get(0).getTitle());
        assertEquals(FileOutlineStore.sha256(restarted.approvedContent(1)), restarted.approvedHash(1));
        String raw = new String(Files.readAllBytes(new File(folder.getRoot(), "revisions/0001.json").toPath()),
                "UTF-8");
        assertTrue("stored as UTF-8 text", raw.contains(title));
    }

    @Test
    public void staleMarksNeedNoRevisionAndAreIdempotent() {
        FileOutlineStore store = new FileOutlineStore(folder.getRoot());
        Outline saved = store.commit(outline("One", "Two"), 0L, 1L).outline;
        Map<String, String> reasons = new HashMap<String, String>();
        reasons.put(saved.getChapters().get(1).getId(), "Concept node removed");
        assertTrue(store.markChaptersStale(reasons, 2L).changed);
        assertFalse("the same marks write nothing", store.markChaptersStale(reasons, 3L).changed);
        assertEquals(2L, store.workingRevision());
        assertFalse(store.working().getChapters().get(0).isStale());
        assertEquals("Concept node removed", store.working().getChapters().get(1).getStaleReason());
    }

    @Test
    public void aDamagedStoreFailsClosedAndIsKept() throws Exception {
        FileOutlineStore store = new FileOutlineStore(folder.getRoot());
        store.commit(outline("One"), 0L, 1L);
        File history = new File(folder.getRoot(), "working-history/1.json");
        Files.write(history.toPath(), "{\"schema\":1,\"chapters\":[]}".getBytes("UTF-8"));
        try {
            store.working();
            fail("a hash mismatch must not be read as an empty outline");
        } catch (FileOutlineStore.CorruptOutlineException expected) {
            assertTrue(expected.getMessage().contains("damaged"));
        }
        try {
            store.commit(Outline.EMPTY, 1L, 2L);
            fail("a damaged store is never overwritten");
        } catch (FileOutlineStore.CorruptOutlineException expected) {
            assertEquals("{\"schema\":1,\"chapters\":[]}",
                    new String(Files.readAllBytes(history.toPath()), "UTF-8"));
        }
    }

    @Test
    public void aLostManifestOrDamagedApprovalIsNeverTakenAsValid() throws Exception {
        FileOutlineStore store = new FileOutlineStore(folder.getRoot());
        store.commit(outline("One"), 0L, 1L);
        store.approveCurrent(2L);
        Files.write(new File(folder.getRoot(), "revisions/0001.json").toPath(), "{}".getBytes("UTF-8"));
        try {
            store.approveCurrent(3L);
            fail("a damaged approval is not reused");
        } catch (FileOutlineStore.CorruptOutlineException expected) {
            assertTrue(expected.getMessage().contains("approved revision 1"));
        }
        assertTrue(new File(folder.getRoot(), "working.properties").delete());
        try {
            store.commit(outline("Two"), 0L, 4L);
            fail("a missing manifest beside working revisions is damage, not a new store");
        } catch (FileOutlineStore.CorruptOutlineException expected) {
            assertTrue(new File(folder.getRoot(), "working-history/1.json").isFile());
        }
    }
}

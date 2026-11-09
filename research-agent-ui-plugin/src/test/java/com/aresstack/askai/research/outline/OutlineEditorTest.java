package com.aresstack.askai.research.outline;

import com.aresstack.askai.research.store.FileOutlineStore;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan PR 23: the editor's gestures, raw JSON and restore keep ids and provenance of untouched entries. */
public class OutlineEditorTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private OutlineWorkspace workspace;
    private Outline stored;

    @Before
    public void seed() {
        workspace = new OutlineWorkspace(new FileOutlineStore(folder.getRoot()));
        Outline draft = new Outline(Arrays.asList(
                chapter("Grundlagen", "n1", "Begriffe"),
                chapter("Methoden", "n2", "Messung"),
                chapter("Ausblick", "n3", "Zukunft")));
        stored = workspace.save(draft, 0L, 1L).outline;
    }

    private static OutlineChapter chapter(String title, String node, String section) {
        return OutlineChapter.draft(title, new OutlineBrief("p", Collections.singletonList("k"), 400),
                new OutlineProvenance(node, Arrays.asList("c-" + node), Arrays.asList("pa-" + node)),
                Collections.singletonList(OutlineSection.draft(section, OutlineBrief.EMPTY,
                        new OutlineProvenance(node + ".1", Collections.<String>emptyList(),
                                Collections.<String>emptyList()))));
    }

    private Outline save(Outline draft) {
        stored = workspace.saveEdits(draft, workspace.workingRevision(), 2L).outline;
        return stored;
    }

    @Test
    public void renameReorderDeleteAndAddRoundTripAndKeepUntouchedEntries() {
        OutlineChapter first = stored.getChapters().get(0);
        OutlineChapter second = stored.getChapters().get(1);
        OutlineChapter third = stored.getChapters().get(2);
        save(OutlineEdits.rename(stored, first.getId(), "Grundlagen – Überblick"));
        save(OutlineEdits.move(stored, third.getId(), -1));
        save(OutlineEdits.delete(stored, second.getSections().get(0).getId()));
        save(OutlineEdits.addSection(stored, second.getId(), "Neu"));
        save(OutlineEdits.addChapter(stored, null, "Anhang"));

        Outline reread = workspace.store().working();
        assertEquals(stored, reread);
        assertEquals(Arrays.asList("Grundlagen – Überblick", "Ausblick", "Methoden", "Anhang"),
                Arrays.asList(reread.getChapters().get(0).getTitle(), reread.getChapters().get(1).getTitle(),
                        reread.getChapters().get(2).getTitle(), reread.getChapters().get(3).getTitle()));
        OutlineChapter renamed = reread.chapter(first.getId());
        assertTrue(renamed.isUserEdited());
        assertEquals(first.getProvenance(), renamed.getProvenance());
        assertEquals(first.getSections().get(0), renamed.getSections().get(0)); // untouched section unchanged
        OutlineChapter methods = reread.chapter(second.getId());
        assertEquals(second.getProvenance(), methods.getProvenance());
        assertEquals(1, methods.getSections().size());
        assertTrue(methods.getSections().get(0).isUserEdited());
        assertTrue(methods.getSections().get(0).getId().startsWith("sec-"));
        assertTrue(reread.getChapters().get(3).getId().startsWith("ch-"));
        assertEquals(third.getSections().get(0).getId(), reread.chapter(third.getId()).getSections().get(0).getId());
    }

    @Test
    public void indentAndOutdentKeepTitleBriefAndProvenance() {
        OutlineChapter second = stored.getChapters().get(1);
        save(OutlineEdits.indent(stored, second.getId()));
        OutlineChapter first = stored.getChapters().get(0);
        assertEquals(2, stored.getChapters().size());
        assertEquals(3, first.getSections().size());
        OutlineSection indented = first.getSections().get(1);
        assertEquals("Methoden", indented.getTitle());
        assertEquals(second.getProvenance(), indented.getProvenance());
        assertEquals(second.getSections().get(0).getId(), first.getSections().get(2).getId());
        save(OutlineEdits.outdent(stored, indented.getId()));
        assertEquals(3, stored.getChapters().size());
        assertEquals("Methoden", stored.getChapters().get(1).getTitle());
        assertEquals(second.getProvenance(), stored.getChapters().get(1).getProvenance());
    }

    @Test
    public void rawJsonKeepsProvenanceOfKnownIdsAndRejectsBadInput() {
        String json = OutlineJson.write(stored).replace("\"Methoden\"", "\"Verfahren\"")
                .replace("\"conceptNode\": \"n2\"", "\"conceptNode\": \"forged\"");
        save(OutlineJson.read(json));
        OutlineChapter edited = stored.getChapters().get(1);
        assertEquals("Verfahren", edited.getTitle());
        assertEquals("n2", edited.getProvenance().getConceptNodeId());
        assertTrue(edited.isUserEdited());
        assertFalse(stored.getChapters().get(2).isUserEdited());

        long before = workspace.workingRevision();
        refused("{not json", "not valid JSON");
        String first = stored.getChapters().get(0).getId();
        refused(OutlineJson.write(stored).replace(stored.getChapters().get(1).getId(), first), "repeats the id");
        refused(OutlineJson.write(stored).replace("\"Verfahren\"", "\"  \""), "empty title");
        refused(OutlineJson.write(stored).replace(first, "ch-invented"), "Unknown chapter id");
        refused(OutlineJson.write(stored).replaceFirst("\"sections\": \\[", "\"sections\": \"none\", \"old\": ["),
                "unknown layout");
        assertEquals(before, workspace.workingRevision());
    }

    private void refused(String json, String reason) {
        try {
            save(OutlineJson.read(json));
            fail("accepted: " + reason);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(reason));
        }
    }

    @Test
    public void restoreBringsBackDeletedIdsAndWritesAreGuarded() {
        String deleted = stored.getChapters().get(2).getId();
        save(OutlineEdits.delete(stored, deleted));
        workspace.restore(1L, workspace.workingRevision(), 3L);
        assertEquals(deleted, workspace.current().getChapters().get(2).getId());
        assertEquals(3L, workspace.workingRevision());

        workspace.setWriteGuard(() -> "Frozen at revision 1. Reopen Outline to change it.");
        try {
            save(OutlineEdits.rename(workspace.current(), deleted, "x"));
            fail();
        } catch (OutlineWorkspace.WriteRefusedException expected) {
            assertTrue(expected.getMessage().startsWith("Frozen"));
        }
        try {
            workspace.restore(2L, workspace.workingRevision(), 4L);
            fail();
        } catch (OutlineWorkspace.WriteRefusedException expected) {
            assertNotEquals(4L, workspace.workingRevision());
        }
    }
}

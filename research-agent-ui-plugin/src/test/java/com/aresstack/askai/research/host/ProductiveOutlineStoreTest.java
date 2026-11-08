package com.aresstack.askai.research.host;

import com.aresstack.askai.research.book.ApprovedOutline;
import com.aresstack.askai.research.concept.ConceptIdentity;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.store.FileConceptStore;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.Fx;
import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.assertHandled;
import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.firstMessage;
import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.newTempDir;
import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.phase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan PR 21 "Done when": the outline store, its ids, the write guard and the freeze over the productive path. */
public class ProductiveOutlineStoreTest {

    private static final String NON_ASCII = "Klassenlader – Überblick 日本語";

    private static void toOutline(Fx fx) {
        firstMessage(fx, "A book about plugin isolation in Java");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("outline/running", phase(fx));
    }

    private static OutlineChapter chapter(String title, String conceptNode, String... sections) {
        List<OutlineSection> list = new ArrayList<OutlineSection>();
        for (String section : sections) {
            list.add(OutlineSection.draft(section, OutlineBrief.EMPTY, OutlineProvenance.NONE));
        }
        return OutlineChapter.draft(title, new OutlineBrief("Why " + title, Arrays.asList("a", "b"), 600),
                new OutlineProvenance(conceptNode, Collections.<String>emptyList(),
                        Collections.<String>emptyList()), list);
    }

    private static Outline save(OutlineWorkspace workspace, OutlineChapter... chapters) {
        return workspace.save(new Outline(Arrays.asList(chapters)), workspace.workingRevision(), 1L).outline;
    }

    private static void assertRefused(OutlineWorkspace workspace, String where) {
        try {
            save(workspace, chapter("Late", "", "Section"));
            fail("a write in " + where + " must be refused");
        } catch (OutlineWorkspace.WriteRefusedException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void writesOnlyInOutlineRunningWhicheverEdgeLedThere() {
        Fx fx = new Fx();
        firstMessage(fx, "A book");
        assertRefused(fx.session.outlineWorkspace(), "scoping/running");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertEquals("research/running", phase(fx));
        assertRefused(fx.session.outlineWorkspace(), "research/running");
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        OutlineWorkspace workspace = fx.session.outlineWorkspace();
        assertEquals("outline/running", phase(fx));
        save(workspace, chapter("Isolation", "", "Class loaders")); // after APPROVE_EVIDENCE: allowed

        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertEquals("outline/waiting_approval", phase(fx));
        assertRefused(workspace, "outline/waiting_approval");
        assertHandled(fx.session.executeCommand("request-changes", "shorter"));
        assertEquals("outline/running", phase(fx));
        Outline current = workspace.current();
        save(workspace, current.getChapters().get(0).withTitle("Isolation, revised")); // after REQUEST_OUTLINE_CHANGES

        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("draft/running", phase(fx));
        assertRefused(workspace, "draft/running");
        assertTrue(workspace.writeRefusal(), workspace.writeRefusal().contains("Frozen at revision 1"));
    }

    @Test
    public void idsStayStableAcrossThreeEditApproveAndReopenCycles() throws Exception {
        Fx fx = new Fx();
        toOutline(fx);
        OutlineWorkspace workspace = fx.session.outlineWorkspace();
        Outline first = save(workspace, chapter("One", "", "One a", "One b"), chapter("Two", "", "Two a"));
        List<String> ids = first.allIds();
        assertEquals(5, ids.size());
        for (int cycle = 1; cycle <= 3; cycle++) {
            Outline current = workspace.current();
            List<OutlineChapter> edited = new ArrayList<OutlineChapter>();
            for (OutlineChapter chapter : current.getChapters()) {
                edited.add(chapter.withTitle(chapter.getTitle() + " " + cycle).withUserEdited(true));
            }
            Outline saved = workspace.save(current.withChapters(edited), workspace.workingRevision(), 1L).outline;
            assertEquals("cycle " + cycle + " edit", ids, saved.allIds());
            assertHandled(fx.session.executeCommand("finish-outline", ""));
            assertHandled(fx.session.executeCommand("approve", ""));
            assertEquals("draft/running", phase(fx));
            ApprovedOutline approved = fx.session.approvedOutline();
            assertEquals(cycle, approved.getRevision());
            assertEquals("cycle " + cycle + " approval", ids, approved.getOutline().allIds());
            assertHandled(fx.session.executeCommand("reopen-outline", ""));
            assertEquals("outline/running", phase(fx));
            assertEquals("cycle " + cycle + " reopen", ids, workspace.current().allIds());
        }
        assertTrue(fx.session.bookManifest().notes().contains("Outline reopened"));
    }

    @Test
    public void theApprovedRevisionAndNonAsciiTitlesSurviveARestart() throws Exception {
        java.io.File dir = newTempDir();
        Fx fx = new Fx(dir);
        toOutline(fx);
        Outline saved = save(fx.session.outlineWorkspace(), chapter(NON_ASCII, "", "Abschnitt äöüß"));
        assertTrue("the outline artifact is rendered from the store",
                fx.resources.getArtifactStore().read("outline").getMarkdown().contains(NON_ASCII));
        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("draft/running", phase(fx));
        fx.session.close();
        fx.resources.close();

        Fx reopened = new Fx(dir);
        assertEquals("draft/running", phase(reopened));
        ApprovedOutline approved = reopened.session.approvedOutline();
        assertNotNull(approved);
        assertEquals(1, approved.getRevision());
        assertEquals(saved, approved.getOutline());
        assertEquals(NON_ASCII, approved.getOutline().getChapters().get(0).getTitle());
    }

    @Test
    public void rebuildingTheLiveProjectionNeverChangesTheStoredOutline() {
        Fx fx = new Fx();
        toOutline(fx);
        OutlineWorkspace workspace = fx.session.outlineWorkspace();
        save(workspace, chapter("Stored", "", "Section"));
        long revision = workspace.workingRevision();
        String artifact = fx.resources.getArtifactStore().read("outline").getMarkdown();
        fx.session.requestOutlineRebuild();
        assertEquals(revision, workspace.workingRevision());
        assertEquals(artifact, fx.resources.getArtifactStore().read("outline").getMarkdown());
        assertEquals("", fx.session.liveOutlineMarkdown());
    }

    @Test
    public void afterAReopenOfConceptOnlyTheRenamedNodesChapterIsStale() {
        Fx fx = new Fx();
        firstMessage(fx, "A book about plugin isolation in Java");
        ConceptTestSupport.makeSubmittable(fx.session); // card "Isolation"
        assertTrue(fx.session.conceptBranchService().addCards(Collections.<String>emptyList(),
                Collections.singletonList("Loaders")).isApplied());
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("outline/running", phase(fx));
        OutlineWorkspace workspace = fx.session.outlineWorkspace();
        Outline saved = save(workspace, chapter("Isolation", nodeId(fx, "Isolation"), "Boundaries"),
                chapter("Loaders", nodeId(fx, "Loaders"), "Delegation"), chapter("Extra", "", "Notes"));

        assertHandled(fx.session.executeCommand("reopen-concept", ""));
        assertEquals("scoping/running", phase(fx));
        assertTrue(fx.session.conceptBranchService().renameNode(Collections.singletonList("Loaders"),
                "Class loaders").isApplied());
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertEquals("research/running", phase(fx));

        Outline after = workspace.current();
        assertEquals("stale marks keep every id", saved.allIds(), after.allIds());
        assertFalse(after.getChapters().get(0).isStale());
        assertTrue(after.getChapters().get(1).isStale());
        assertTrue(after.getChapters().get(1).getStaleReason(),
                after.getChapters().get(1).getStaleReason().contains("renamed to \"Class loaders\""));
        assertFalse("a chapter without a concept node stays", after.getChapters().get(2).isStale());
    }

    private static String nodeId(Fx fx, String card) {
        FileConceptStore store = fx.session.conceptBranchService().store();
        ConceptIdentity identity = ConceptIdentity.parse(store.workingIdentityContent());
        String id = identity.idAtPath(JsonParser.parseString(store.effectiveContent()),
                Collections.singletonList(card));
        assertNotNull(card, id);
        return id;
    }
}

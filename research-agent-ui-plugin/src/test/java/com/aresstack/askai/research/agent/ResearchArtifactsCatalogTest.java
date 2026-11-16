package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.research.store.ResearchProjectContext;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #32: the artifact catalog exposes ONLY artifacts with a clear user-facing responsibility. The legacy
 * per-stage Markdown artifacts (concept, research-notes, findings, draft, final) are gone from the catalog —
 * and old files on disk stay untouched and never resurrect tabs, because the catalog is the single source of
 * the tab list and is independent of what a legacy project directory contains.
 */
public class ResearchArtifactsCatalogTest {

    private static List<String> ids() {
        List<String> ids = new ArrayList<String>();
        for (AgentArtifact artifact : ResearchArtifacts.all()) {
            ids.add(artifact.getId());
        }
        return ids;
    }

    @Test
    public void theCatalogContainsExactlyTheMvpArtifacts() {
        // Plan PR 23: the Outline tab sits between Sources and Document, mirroring the phase order.
        assertEquals(Arrays.asList("research-brief", "sources", "outline", "document", "state"), ids());
    }

    @Test
    public void theLegacyPerStageArtifactsAreGone() {
        List<String> ids = ids();
        for (String legacy : new String[]{"concept", "research-notes", "findings", "draft", "final"}) {
            assertFalse("legacy artifact must not be in the catalog: " + legacy, ids.contains(legacy));
        }
    }

    @Test
    public void theDocumentIsTheOnlyPlainMarkdownWorkProductBesideTheOutline() {
        // #43 slice 9: the document owns its page view now (type research.document) while the
        // persisted file stays the SAME document.md — the store contract did not move.
        for (AgentArtifact artifact : ResearchArtifacts.all()) {
            if ("document".equals(artifact.getId())) {
                assertEquals(ResearchArtifacts.TYPE_DOCUMENT, artifact.getArtifactTypeId());
                assertEquals("document.md", artifact.getRelativePath());
            }
        }
    }

    @Test
    public void legacyFilesOnDiskStayUntouchedAndResurrectNoTabs() throws Exception {
        // A legacy project directory: draft.md and final.md exist with content. Opening the project must
        // neither delete/migrate them nor bring their tabs back (the catalog is static, disk-independent).
        File dir = Files.createTempDirectory("askai-legacy-artifacts").toFile();
        ResearchProjectContext context = ResearchProjectContext.open("proj-legacy", dir);
        context.getArtifactStore().replace("draft", 0L, "# Old draft\n");
        context.getArtifactStore().replace("final", 0L, "# Old final\n");

        ResearchProjectContext reopened = ResearchProjectContext.open("proj-legacy", dir);
        assertEquals("legacy draft content is preserved verbatim",
                "# Old draft\n", reopened.getArtifactStore().read("draft").getMarkdown());
        assertEquals("legacy final content is preserved verbatim",
                "# Old final\n", reopened.getArtifactStore().read("final").getMarkdown());
        assertTrue(new File(new File(dir, "artifacts"), "draft.md").isFile());
        assertTrue(new File(new File(dir, "artifacts"), "final.md").isFile());
        // No migration is attempted: the new document stays empty until someone explicitly writes it.
        assertEquals("", reopened.getArtifactStore().read("document").getMarkdown());
        assertFalse("no legacy tab resurrects", ids().contains("draft"));
        assertFalse("no legacy tab resurrects", ids().contains("final"));
    }
}

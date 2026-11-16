package com.aresstack.askai.research.host;

import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.outline.OutlineWorkspace;

import java.util.Collections;

/** PR 21: brings a productive session's outline to the structural minimum of finish-outline. */
final class OutlineTestSupport {

    private OutlineTestSupport() {
    }

    /** Save a one-chapter, one-section outline when no chapter has a section yet (needs outline/running). */
    static void makeFinishable(ResearchAgentSession session) {
        OutlineWorkspace workspace = session.outlineWorkspace();
        if (workspace.current().hasChapterWithSection()) {
            return;
        }
        workspace.save(new Outline(Collections.singletonList(OutlineChapter.draft("Class loaders",
                        OutlineBrief.EMPTY, OutlineProvenance.NONE,
                        Collections.singletonList(OutlineSection.draft("Delegation", OutlineBrief.EMPTY,
                                OutlineProvenance.NONE))))),
                workspace.workingRevision(), System.currentTimeMillis());
    }
}

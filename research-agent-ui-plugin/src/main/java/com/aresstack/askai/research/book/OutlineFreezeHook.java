package com.aresstack.askai.research.book;

import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.store.FileOutlineStore;
import com.aresstack.askai.research.transition.PhaseTransition;
import com.aresstack.askai.research.transition.PhaseTransitionHook;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * PR 21: the outline freeze on the transition hook (D3, D4, Q24).
 * <ul>
 * <li>before apply, {@code PROPOSE_OUTLINE} ({@code finish-outline}) is refused while no chapter has a
 * section, and while an outline job (generation, PR 22) runs;</li>
 * <li>as the last record step of {@code APPROVE_OUTLINE}, the working outline is approved and appended as an
 * outline pin under the transition id; a failed approval refuses the transition;</li>
 * <li>{@code REOPEN_OUTLINE} appends the note "Outline reopened"; nothing is deleted;</li>
 * <li>after a {@code SUBMIT_SCOPE} the {@link ConceptStaleMarker} effect marks chapters of changed or removed
 * concept nodes stale.</li>
 * </ul>
 * Register this hook after {@link ConceptFreezeHook}, so the stale effect sees the new concept pin.
 */
public final class OutlineFreezeHook implements PhaseTransitionHook {

    public static final String NO_SECTION = "Add at least one section to a chapter before you finish the outline.";
    public static final String REOPENED_NOTE = "Outline reopened";

    private final OutlineWorkspace workspace;
    private final BookManifest manifest;

    public OutlineFreezeHook(OutlineWorkspace workspace, BookManifest manifest) {
        this.workspace = workspace;
        this.manifest = manifest;
    }

    @Override
    public String check(PhaseTransition transition) {
        if (transition.getCommand() != ResearchCommandType.PROPOSE_OUTLINE) {
            return null;
        }
        return finishBlocker(workspace);
    }

    /** The structural reason {@code finish-outline} cannot pass now, or {@code null}. */
    public static String finishBlocker(OutlineWorkspace workspace) {
        if (workspace.jobs().isBusy()) {
            return workspace.jobs().busyReason() + " Finish the outline when it is done.";
        }
        try {
            return workspace.current().hasChapterWithSection() ? null : NO_SECTION;
        } catch (FileOutlineStore.CorruptOutlineException corrupt) {
            return corrupt.getMessage();
        }
    }

    @Override
    public void record(PhaseTransition transition) throws IOException {
        if (transition.getCommand() == ResearchCommandType.APPROVE_OUTLINE) {
            FileOutlineStore.Approval approval;
            try {
                approval = workspace.store().approveCurrent(transition.getTimeMillis());
            } catch (RuntimeException failed) {
                throw new IOException("The outline could not be approved: " + failed.getMessage(), failed);
            }
            manifest.appendOutlinePin(new OutlinePin(transition.getId(), approval.revisionNumber,
                    approval.newRevision, approval.contentHash, approval.workingRevision,
                    transition.getTimeMillis()));
        } else if (transition.getCommand() == ResearchCommandType.REOPEN_OUTLINE) {
            manifest.appendNote(transition.getId(), OutlinePin.PHASE, REOPENED_NOTE, transition.getTimeMillis());
        }
    }

    @Override
    public List<String> effects(PhaseTransition transition) {
        return transition.getCommand() == ResearchCommandType.SUBMIT_SCOPE
                ? Collections.singletonList(ConceptStaleMarker.EFFECT) : Collections.<String>emptyList();
    }
}

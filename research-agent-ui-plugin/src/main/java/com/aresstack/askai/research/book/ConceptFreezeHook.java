package com.aresstack.askai.research.book;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.store.FileConceptStore;
import com.aresstack.askai.research.transition.PhaseTransition;
import com.aresstack.askai.research.transition.PhaseTransitionHook;

import java.io.IOException;

/**
 * PR 07: the concept freeze on the transition hook (D3, D4).
 * <ul>
 * <li>before apply, {@code SUBMIT_SCOPE} is refused on a concept without first-level cards or with an
 * empty mission;</li>
 * <li>as its record, {@code SUBMIT_SCOPE} approves the current concept ({@link FileConceptStore#approveCurrent})
 * and appends the Phase-1 record as a concept pin under the transition id; a failure refuses the
 * transition;</li>
 * <li>{@code REOPEN_CONCEPT} appends the note "Concept reopened"; nothing is deleted.</li>
 * </ul>
 */
public final class ConceptFreezeHook implements PhaseTransitionHook {

    public static final String NO_CARDS = "Add at least one first-level card to the concept before you submit.";
    public static final String NO_MISSION = "Write the mission above the concept before you submit.";
    public static final String REOPENED_NOTE = "Concept reopened";

    private final Phase1Source source;
    private final BookManifest manifest;

    public ConceptFreezeHook(Phase1Source source, BookManifest manifest) {
        this.source = source;
        this.manifest = manifest;
    }

    @Override
    public String check(PhaseTransition transition) {
        if (transition.getCommand() != ResearchCommandType.SUBMIT_SCOPE) {
            return null;
        }
        return submitBlocker(source);
    }

    /** The structural reason {@code SUBMIT_SCOPE} cannot pass now, or {@code null}. */
    public static String submitBlocker(Phase1Source source) {
        FileConceptStore store = source.conceptStore();
        if (store == null || FrozenConcept.firstLevelCards(store.effectiveContent()).isEmpty()) {
            return NO_CARDS;
        }
        if (source.mission() == null || source.mission().trim().isEmpty()) {
            return NO_MISSION;
        }
        return null;
    }

    @Override
    public void record(PhaseTransition transition) throws IOException {
        if (transition.getCommand() == ResearchCommandType.SUBMIT_SCOPE) {
            FileConceptStore store = source.conceptStore();
            String refusal = submitBlocker(source);
            if (refusal != null) {
                throw new IOException(refusal);
            }
            FileConceptStore.FrozenApproval frozen;
            try {
                frozen = store.approveCurrentFrozen(transition.getTimeMillis());
            } catch (RuntimeException failed) {
                throw new IOException("The concept could not be approved: " + failed.getMessage(), failed);
            }
            FileConceptStore.Approval approval = frozen.approval;
            int revision = approval.getRevisionNumber();
            String content = store.approvedContent(revision);
            if (content == null) {
                throw new IOException("The approved concept revision " + revision + " cannot be read.");
            }
            manifest.appendConceptPin(new ConceptPin(transition.getId(), revision, approval.isNewRevision(),
                    FileConceptStore.sha256(content), frozen.workingRevision, transition.getTimeMillis(),
                    source.mission().trim(), source.deliverable(), source.language(), source.blacklist()));
        } else if (transition.getCommand() == ResearchCommandType.REOPEN_CONCEPT) {
            manifest.appendNote(transition.getId(), ConceptPin.PHASE, REOPENED_NOTE, transition.getTimeMillis());
        }
    }
}

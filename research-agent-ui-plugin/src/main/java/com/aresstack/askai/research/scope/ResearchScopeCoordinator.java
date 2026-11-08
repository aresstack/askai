package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopePatch;
import com.aresstack.askai.research.domain.scope.ScopingTurnResult;
import com.aresstack.askai.research.domain.scope.UnresolvedScopeIssue;
import com.aresstack.askai.research.store.FileResearchScopeDraftStore;
import com.aresstack.askai.research.store.ScopeDraftLoadResult;

import java.io.IOException;
import java.util.List;

/**
 * THE owner of the scope draft on the host side: it holds the current revision, applies the changes a
 * scoping turn proposes, and persists the result. The model proposes; this class decides and stores.
 * <p>
 * Two rules it enforces, both learned the hard way:
 * <ul>
 * <li>a DAMAGED persisted draft is never silently replaced by an empty one — the coordinator refuses to
 *     work rather than throw away decisions the user already made;</li>
 * <li>a failed WRITE does not corrupt the in-memory state: the draft is only adopted once it is on disk,
 *     so a retry starts from what is actually persisted.</li>
 * </ul>
 * It deliberately knows nothing about phases, commands or readiness — the user owns the state machine.
 */
public final class ResearchScopeCoordinator {

    private final FileResearchScopeDraftStore store;
    private volatile ResearchScopeDraft draft;
    private final String unusableReason;

    public ResearchScopeCoordinator(FileResearchScopeDraftStore store) {
        this.store = store;
        ScopeDraftLoadResult loaded = store.load();
        if (loaded.isUsableForScoping()) {
            this.draft = loaded.draftOrEmpty();
            this.unusableReason = "";
        } else {
            this.draft = null;
            this.unusableReason = loaded.getStatus() + ": " + loaded.getReason();
        }
    }

    /**
     * The Phase-1 write guard (PR 07, D4): {@code null} lets a patch pass, otherwise the reason it is refused.
     * Outside {@code scoping/running} only the blacklist edits of Q2 may pass.
     */
    public interface WriteGuard {
        String refusal(ScopePatch patch, List<UnresolvedScopeIssue> issues);
    }

    private volatile WriteGuard writeGuard;

    public void setWriteGuard(WriteGuard guard) {
        this.writeGuard = guard;
    }

    /** False when the persisted draft could not be read; scoping must then be repaired, not overwritten. */
    public boolean isUsable() {
        return draft != null;
    }

    /** Why the draft is unusable ("" when it is fine) — surfaced to the user as a repair hint. */
    public String unusableReason() {
        return unusableReason;
    }

    /** The current draft; an empty one for a fresh project. */
    public ResearchScopeDraft current() {
        ResearchScopeDraft snapshot = draft;
        return snapshot == null ? ResearchScopeDraft.empty() : snapshot;
    }

    /**
     * Apply what a scoping turn proposed: the patch first, then the turn's open issues (an assistant that
     * reports an uncertainty without an explicit operation still gets it recorded).
     *
     * @return what actually changed, so the caller can log/show it — never a silent merge
     */
    public synchronized ScopeUpdateResult apply(ScopingTurnResult turn) {
        if (turn == null) {
            return ScopeUpdateResult.unchanged(current());
        }
        return apply(turn.getPatch(), turn.getUnresolvedIssues());
    }

    /**
     * Apply a patch (and optionally the turn's issue list) to the current draft. Patch and issues become
     * ONE new revision: a single turn must not count twice in the history.
     */
    public synchronized ScopeUpdateResult apply(ScopePatch patch, List<UnresolvedScopeIssue> issues) {
        if (draft == null) {
            return ScopeUpdateResult.rejected(ResearchScopeDraft.empty(),
                    "the persisted scope draft is unusable (" + unusableReason + ")");
        }
        WriteGuard guard = writeGuard;
        String refusal = guard == null ? null : guard.refusal(patch, issues);
        if (refusal != null) {
            return ScopeUpdateResult.rejected(draft, refusal);
        }
        boolean changed = patch != null && !patch.isEmpty();
        // The revision is NOT decided here: the store owns the counter, so the builder is left untouched
        // in that respect (applying the patch itself would otherwise bump it a second time).
        ResearchScopeDraft.Builder builder = draft.toBuilder();
        if (patch != null) {
            patch.applyToBuilder(builder);
        }
        if (issues != null) {
            for (UnresolvedScopeIssue issue : issues) {
                if (issue != null && isNewOrDifferent(draft, issue)) {
                    builder.putUnresolvedIssue(issue);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return ScopeUpdateResult.unchanged(draft);
        }
        try {
            // Adopt ONLY what the store actually wrote — a failed write must not leave a phantom revision
            // in memory that no retry can reproduce.
            draft = store.save(builder.build());
        } catch (IOException writeFailed) {
            return ScopeUpdateResult.rejected(draft,
                    "the scope draft could not be persisted: " + writeFailed.getMessage());
        }
        return ScopeUpdateResult.applied(draft,
                patch == null ? java.util.Collections.<String>emptyList() : patch.describeOperations());
    }

    /** Restating a known uncertainty unchanged is not a change — it must not produce a new revision. */
    private static boolean isNewOrDifferent(ResearchScopeDraft draft, UnresolvedScopeIssue candidate) {
        for (UnresolvedScopeIssue existing : draft.getUnresolvedIssues()) {
            if (existing.getIssueId().equals(candidate.getIssueId())) {
                return !existing.getDescription().equals(candidate.getDescription())
                        || existing.getSignificance() != candidate.getSignificance()
                        || !existing.getAffectedFacetIds().equals(candidate.getAffectedFacetIds());
            }
        }
        return true;
    }
}

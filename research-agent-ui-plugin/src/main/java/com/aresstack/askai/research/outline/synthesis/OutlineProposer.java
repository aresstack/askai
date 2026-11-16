package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.runtime.team.MainModelChat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3b/3c in one call, from the brief to the outline proposal: the synthesis with the quality check between the
 * revision rounds, the safe fixes, the final review and the assembly with word budgets, provenance and the report.
 * "Generate outline" and the acceptance tests run exactly this sequence; nothing here touches the store.
 */
public final class OutlineProposer {

    private final MainModelChat chat;
    private final OutlineSynthesisSettings synthesis;
    private final OutlineLengthSettings length;
    private final OutlineSynthesizer.Telemetry telemetry;

    /** @param chat the chat on {@code phase.outline}, or {@code null} for the plan from the analysis alone */
    public OutlineProposer(MainModelChat chat, OutlineSynthesisSettings synthesis, OutlineLengthSettings length,
                           OutlineSynthesizer.Telemetry telemetry) {
        this.chat = chat;
        this.synthesis = synthesis == null ? OutlineSynthesisSettings.defaults() : synthesis;
        this.length = length == null ? OutlineLengthSettings.defaults() : length;
        this.telemetry = telemetry;
    }

    /**
     * @param snapshotId the topic-analysis snapshot the brief was made from
     * @param notes      notes of the caller for the report (e.g. sources still being processed)
     * @throws ClusteringMonitor.ClusteringCancelledException when the monitor cancels between two model calls
     */
    public Outline propose(SynthesisBrief brief, String snapshotId, List<String> notes, ClusteringMonitor monitor) {
        OutlineQualityCheck check = new OutlineQualityCheck(length);
        OutlineSynthesizer.Result result = new OutlineSynthesizer(chat, check, synthesis, telemetry)
                .synthesize(brief, monitor);
        OutlineQualityCheck.Fixed fixed = check.fix(result.getPlan(), result.getBrief());
        List<String> allNotes = new ArrayList<String>(result.getNotes());
        allNotes.addAll(fixed.getNotes());
        List<PlanFinding> findings = check.review(fixed.getPlan(), result.getBrief());
        if (blocking(result.getFindings()) && !blocking(findings)) {
            // Live run 4: the note "still has findings" stood beside an empty findings list; say how it ended.
            allNotes.add("After the safe fixes the review has no blocking findings left.");
        }
        allNotes.addAll(notes == null ? Collections.<String>emptyList() : notes);
        return new OutlineAssembler(length).assemble(fixed.getPlan(), result.getBrief(), snapshotId, findings,
                allNotes);
    }

    private static boolean blocking(List<PlanFinding> findings) {
        for (PlanFinding finding : findings) {
            if (finding.isBlocking()) {
                return true;
            }
        }
        return false;
    }
}

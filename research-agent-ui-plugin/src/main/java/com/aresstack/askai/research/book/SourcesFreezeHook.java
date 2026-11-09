package com.aresstack.askai.research.book;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceEligibility;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.transition.PhaseTransition;
import com.aresstack.askai.research.transition.PhaseTransitionHook;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan PR 14 (D4): the record of {@code APPROVE_EVIDENCE} appends the approved source set (id, content checksum and
 * status of every accepted source) as a sources pin under the transition id. A failure refuses the transition. The
 * session registers it as the last hook, so it is the last record step.
 */
public final class SourcesFreezeHook implements PhaseTransitionHook {

    private final ResearchSourceRepository repository;
    private final BookManifest manifest;

    public SourcesFreezeHook(ResearchSourceRepository repository, BookManifest manifest) {
        this.repository = repository;
        this.manifest = manifest;
    }

    /**
     * The research sources of this moment ({@link SourceEligibility}: read, not excluded), in repository order.
     * The pin records each one's status, so a source the user confirmed ({@code ACCEPTED}) stays told apart from
     * one the funnel accepted ({@code NEW}).
     */
    public static List<SourcesPin.Entry> approvedSet(ResearchSourceRepository repository) {
        List<SourcesPin.Entry> entries = new ArrayList<SourcesPin.Entry>();
        for (ResearchSourceRecord record : repository.find(SourceQuery.all())) {
            if (SourceEligibility.isResearchSource(record)) {
                entries.add(new SourcesPin.Entry(record.getSourceId(), record.getChecksum(),
                        record.getStatus().name()));
            }
        }
        return entries;
    }

    @Override
    public void record(PhaseTransition transition) throws IOException {
        if (transition.getCommand() != ResearchCommandType.APPROVE_EVIDENCE) {
            return;
        }
        List<SourcesPin.Entry> entries;
        try {
            entries = approvedSet(repository);
        } catch (RuntimeException failed) {
            throw new IOException("The accepted sources cannot be read: " + failed.getMessage(), failed);
        }
        manifest.appendSourcesPin(new SourcesPin(transition.getId(), transition.getTimeMillis(), entries));
    }
}

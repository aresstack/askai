package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.SourceCapture;

/**
 * A {@link SourceCaptureReader} that reads a capture the knowledge project already persisted from THERE first —
 * the immutable original — and only otherwise asks the delegate (in-memory capture store, then the durable source
 * record). Needed since a re-read page refreshes its source record in place: the source's full text is then the
 * NEWEST capture's text, so rebuilding an OLDER capture (re-embedding after a model switch, a retry after a restart)
 * from the source record would hand the pipeline different content under the old capture id.
 */
public final class PersistedFirstSourceCaptureReader implements SourceCaptureReader {

    private final FileResearchProjectRepository repository;
    private final SourceCaptureReader delegate;

    public PersistedFirstSourceCaptureReader(FileResearchProjectRepository repository, SourceCaptureReader delegate) {
        if (repository == null || delegate == null) {
            throw new IllegalArgumentException("repository and delegate are required");
        }
        this.repository = repository;
        this.delegate = delegate;
    }

    @Override
    public SourceCapture read(String captureId) {
        return read(captureId, "");
    }

    @Override
    public SourceCapture read(String captureId, String sourceId) {
        SourceCapture persisted = repository.persistedCapture(captureId);
        return persisted != null ? persisted : delegate.read(captureId, sourceId);
    }
}

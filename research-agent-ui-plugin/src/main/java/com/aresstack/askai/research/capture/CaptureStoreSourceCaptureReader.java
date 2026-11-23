package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.processing.SourceCaptureReader;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;

/**
 * The productive {@link SourceCaptureReader}: reads an accepted capture from the per-session {@link CaptureStore}
 * and maps it losslessly to the canonical {@link SourceCapture} via {@link VisitedCaptureSourceCaptureAdapter}.
 * It resolves the capture's owning source id through an injected {@link SourceIdResolver} (provenance only) so
 * the reader stays free of the source repository for the in-memory path.
 *
 * <p>DURABLE fallback (issue #29): the in-memory capture store is bounded working material — after a restart
 * (or eviction) the capture is gone, but the accepted source record persisted its full cleaned text. When the
 * job carries the accepted source id, a missing in-memory capture falls back to the PERSISTED
 * {@link ResearchSourceRecord} so a delayed, user-triggered segmentation still has its canonical input. Only
 * when neither exists does the worker see {@code null} (a permanent failure).</p>
 */
public final class CaptureStoreSourceCaptureReader implements SourceCaptureReader {

    /** Resolves the accepted source id that owns a visited capture; "" when it cannot (yet) be linked. */
    public interface SourceIdResolver {
        String resolve(VisitedCapture capture);

        SourceIdResolver NONE = new SourceIdResolver() {
            public String resolve(VisitedCapture capture) {
                return "";
            }
        };
    }

    private final CaptureStore captures;
    private final SourceIdResolver sourceIds;
    /** OPTIONAL durable fallback: the persisted source records (full text survives a restart); may be null. */
    private final ResearchSourceRepository sourceRepository;

    public CaptureStoreSourceCaptureReader(CaptureStore captures, SourceIdResolver sourceIds) {
        this(captures, sourceIds, null);
    }

    public CaptureStoreSourceCaptureReader(CaptureStore captures, SourceIdResolver sourceIds,
                                           ResearchSourceRepository sourceRepository) {
        this.captures = captures;
        this.sourceIds = sourceIds == null ? SourceIdResolver.NONE : sourceIds;
        this.sourceRepository = sourceRepository;
    }

    @Override
    public SourceCapture read(String captureId) {
        return read(captureId, "");
    }

    @Override
    public SourceCapture read(String captureId, String sourceId) {
        VisitedCapture capture = captures == null ? null : captures.get(captureId);
        if (capture != null) {
            // The job names the source the acceptance created or refreshed — authoritative. Only a job without
            // one falls back to the canonical-URL lookup (which picks the FIRST source of that address).
            String resolved = sourceId != null && !sourceId.trim().isEmpty()
                    ? sourceId.trim() : sourceIds.resolve(capture);
            return VisitedCaptureSourceCaptureAdapter.toSourceCapture(capture,
                    resolved == null ? "" : resolved);
        }
        return fromPersistedSource(captureId, sourceId);
    }

    /** Rebuild the canonical capture from the DURABLE source record (restart/eviction survival). */
    private SourceCapture fromPersistedSource(String captureId, String sourceId) {
        if (sourceRepository == null || sourceId == null || sourceId.trim().isEmpty()) {
            return null;
        }
        ResearchSourceRecord record = sourceRepository.get(sourceId.trim());
        if (record == null || record.getFullText() == null || record.getFullText().trim().isEmpty()) {
            return null; // parked/never-visited records carry no text — not a segmentable capture
        }
        return new SourceCapture(captureId, record.getSourceId(),
                CaptureStore.canonicalize(record.getUrl()), record.getCapturedAt(),
                record.getChecksum(), record.getTitle(), "",
                VisitedCaptureSourceCaptureAdapter.paragraphBlocks(record.getFullText()));
    }
}

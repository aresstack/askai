package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.manuscript.SectionRevision;

/**
 * The section writer the writing job calls (plan PR 28 provides it; PR 29 drives it). One call writes one whole
 * section or, with a comment and the current text, rewrites it per key-point part with the writer's word target
 * and budget (a cut-off part gets one continuation without the old text). The job sets the outline fingerprints;
 * the writer fills text, citations, gaps and its model metadata.
 */
public interface SectionWriter {

    /** Polled by the writer between and inside model calls; a cancelled call ends with {@link Cancelled}. */
    interface Cancellation {
        boolean isCancelled();
    }

    /** The call was stopped through {@link Cancellation}; the section stays as it was. */
    final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("Writing was stopped.");
        }
    }

    /**
     * @return the new revision's content (never {@code null})
     * @throws Cancelled when the cancellation fired
     * @throws Exception for a failed attempt; its message is shown with the failed section
     */
    SectionRevision.Builder write(SectionWriteRequest request, Cancellation cancellation) throws Exception;
}

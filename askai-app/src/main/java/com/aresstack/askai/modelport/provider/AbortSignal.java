package com.aresstack.askai.modelport.provider;

/**
 * The port's abort of one call (a cancel by the caller or the call's time limit). A provider polls
 * {@link #isAborted()} between streamed chunks and registers what frees its connection at once with
 * {@link #onAbort(Runnable)}, so a blocked read ends without waiting for the next chunk.
 */
public interface AbortSignal {

    /** A signal that never fires. */
    AbortSignal NEVER = new AbortSignal() {
        public boolean isAborted() {
            return false;
        }

        public void onAbort(Runnable action) {
        }
    };

    boolean isAborted();

    /** Runs {@code action} when the call is aborted; at once (on the calling thread) when it already is. */
    void onAbort(Runnable action);
}

package com.aresstack.askai.research.runtime.search.service;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The shared queue of pages a run's workers take their next URL from. The search service is the only producer;
 * any number of consumers may poll. Nothing here blocks a producer.
 */
public final class PageCandidateQueue {

    private final LinkedBlockingQueue<PageCandidate> queue = new LinkedBlockingQueue<PageCandidate>();

    public void offer(PageCandidate candidate) {
        if (candidate != null) {
            queue.offer(candidate);
        }
    }

    /** The next page, or {@code null} when none arrived within the timeout. */
    public PageCandidate poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /** The next page, or {@code null} when the queue is empty right now. */
    public PageCandidate poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }
}

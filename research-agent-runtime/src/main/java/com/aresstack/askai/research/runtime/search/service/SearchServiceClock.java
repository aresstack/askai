package com.aresstack.askai.research.runtime.search.service;

/** Time source and sleeper of the search service; tests replace it with a virtual clock. */
public interface SearchServiceClock {

    SearchServiceClock SYSTEM = new SearchServiceClock() {
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        public void sleep(long millis) throws InterruptedException {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        }
    };

    long nowMillis();

    void sleep(long millis) throws InterruptedException;
}

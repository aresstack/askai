package com.aresstack.askai.batch.service;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Publish application events to loosely coupled observers. */
public final class BatchTranscriptionEventPublisher {

    public interface Subscription {
        void unsubscribe();
    }

    private final CopyOnWriteArrayList<Consumer<BatchTranscriptionEvent>> observers =
            new CopyOnWriteArrayList<Consumer<BatchTranscriptionEvent>>();

    public Subscription subscribe(final Consumer<BatchTranscriptionEvent> observer) {
        if (observer == null) {
            throw new IllegalArgumentException("Observer must not be null.");
        }
        observers.add(observer);
        return new Subscription() {
            public void unsubscribe() {
                observers.remove(observer);
            }
        };
    }

    public void publish(BatchTranscriptionEvent event) {
        for (Consumer<BatchTranscriptionEvent> observer : observers) {
            try {
                observer.accept(event);
            } catch (RuntimeException ignored) {
                // Isolate observers so one broken UI listener cannot stop the batch.
            }
        }
    }
}

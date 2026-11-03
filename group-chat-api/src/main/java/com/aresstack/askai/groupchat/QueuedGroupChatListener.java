package com.aresstack.askai.groupchat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Delivery queue between a transport and its {@link GroupChatListener}.
 *
 * <p>Locking rule for transports: a transport never invokes application callbacks while it holds
 * its own lock. Instead it hands every event to this queue, which delivers them in order on one
 * dedicated daemon thread ({@code askai-party-events}) with no transport lock held. That breaks
 * every lock-order cycle between the transport and the application (the PartySession↔JGroups
 * deadlock), and keeps the JGroups receive thread from waiting on application code.</p>
 *
 * <p>Events are delivered strictly in the order they were posted. A listener exception is
 * contained, so one failing callback never stops later deliveries.</p>
 */
public final class QueuedGroupChatListener implements GroupChatListener {

    private final GroupChatListener delegate;
    private final ExecutorService delivery;

    public QueuedGroupChatListener(GroupChatListener delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
        this.delivery = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "askai-party-events");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** @return the application listener behind this queue */
    public GroupChatListener delegate() {
        return delegate;
    }

    /**
     * Stop accepting events; already queued events are still delivered. Does not wait, so it is
     * safe to call from the delivery thread itself or under a transport lock.
     */
    public void shutdown() {
        delivery.shutdown();
    }

    /** Wait (bounded) until all queued events were delivered; for tests and orderly shutdown. */
    public boolean awaitDrained(long timeout, TimeUnit unit) throws InterruptedException {
        return delivery.awaitTermination(timeout, unit);
    }

    private void post(final Runnable event) {
        try {
            delivery.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        event.run();
                    } catch (RuntimeException ignored) {
                        // Contained: a failing listener must not stop the delivery of later events.
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Queue already shut down (room left): late events are dropped on purpose.
        }
    }

    @Override
    public void onMessage(final GroupChatMessage message) {
        post(new Runnable() {
            public void run() {
                delegate.onMessage(message);
            }
        });
    }

    @Override
    public void onParticipantJoined(final Participant participant) {
        post(new Runnable() {
            public void run() {
                delegate.onParticipantJoined(participant);
            }
        });
    }

    @Override
    public void onParticipantLeft(final Participant participant) {
        post(new Runnable() {
            public void run() {
                delegate.onParticipantLeft(participant);
            }
        });
    }

    @Override
    public void onParticipantsChanged(final List<Participant> participants) {
        post(new Runnable() {
            public void run() {
                delegate.onParticipantsChanged(participants);
            }
        });
    }

    @Override
    public void onConnectionStateChanged(final GroupChatConnectionState state) {
        post(new Runnable() {
            public void run() {
                delegate.onConnectionStateChanged(state);
            }
        });
    }

    @Override
    public void onColorMapChanged(final ColorMap colorMap) {
        post(new Runnable() {
            public void run() {
                delegate.onColorMapChanged(colorMap);
            }
        });
    }

    @Override
    public void onBotClaim(final BotClaim claim) {
        post(new Runnable() {
            public void run() {
                delegate.onBotClaim(claim);
            }
        });
    }
}

package com.aresstack.askai.party;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Application-wide guard ensuring at most one chat tab is in Partying mode at a time.
 *
 * <p>The participant identity is installation-scoped, so two tabs joining the same room would
 * appear as one participant joining twice — breaking membership, colors and bot election. This
 * guard lets the first tab acquire the party and refuses the others until it is released (on
 * leaving Partying or closing the tab), so a fresh tab can take over once the previous one is
 * gone.</p>
 */
public final class PartyModeGuard {

    private static final AtomicReference<Object> OWNER = new AtomicReference<Object>();

    private PartyModeGuard() {
    }

    /**
     * Try to become the single active party owner.
     *
     * @return {@code true} when {@code owner} now holds the party (or already held it);
     *         {@code false} when another owner holds it
     */
    public static boolean acquire(Object owner) {
        if (owner == null) {
            return false;
        }
        return OWNER.compareAndSet(null, owner) || OWNER.get() == owner;
    }

    /** Release the party if {@code owner} holds it; no-op otherwise. */
    public static void release(Object owner) {
        OWNER.compareAndSet(owner, null);
    }

    /** @return {@code true} when some tab currently holds the party. */
    public static boolean isHeld() {
        return OWNER.get() != null;
    }

    /** @return {@code true} when {@code owner} currently holds the party. */
    public static boolean isHeldBy(Object owner) {
        return owner != null && OWNER.get() == owner;
    }
}

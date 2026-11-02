package com.aresstack.askai.groupchat;

/**
 * Reservation of a palette color for a recently departed participant.
 *
 * <p>While the lease is unexpired the color is only handed to the leased participant (on rejoin);
 * after {@link #getExpiresAtMillis()} the color returns to the free pool.</p>
 */
public final class ColorLease {

    private final String participantId;
    private final long expiresAtMillis;

    public ColorLease(String participantId, long expiresAtMillis) {
        if (participantId == null || participantId.trim().isEmpty()) {
            throw new IllegalArgumentException("participantId must not be blank");
        }
        this.participantId = participantId;
        this.expiresAtMillis = expiresAtMillis;
    }

    /** The departed participant the color is reserved for. */
    public String getParticipantId() {
        return participantId;
    }

    /** Epoch millis after which the reservation lapses. */
    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    /** @return {@code true} when the lease is expired at {@code nowMillis}. */
    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    @Override
    public String toString() {
        return "ColorLease{participant=" + participantId + ", expiresAt=" + expiresAtMillis + "}";
    }
}

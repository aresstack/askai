package com.aresstack.askai.groupchat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accepts at most one valid bot response per addressed message.
 *
 * <p>Every peer runs its own arbiter over the same event stream: a claim is accepted when it is
 * the first claim for its addressed message and its host matches the deterministic election for
 * the claimed view; a bot response is accepted when it is the first response for its addressed
 * message and comes from the accepted claim's host. Everything else — duplicates from partition
 * merges, stale claims from a previous view, responses from non-elected hosts — is rejected.</p>
 */
public final class BotResponseArbiter {

    /** Bound on remembered message IDs so long-running rooms don't grow without limit. */
    private static final int MAX_TRACKED = 2048;

    private final Map<String, String> acceptedClaimHostByMessage = boundedMap();
    private final Map<String, Boolean> respondedMessages = boundedMap();

    /**
     * Register a claim.
     *
     * @param claim          the received claim
     * @param electedHostId  the deterministically elected host for the current view ({@code null}
     *                       accepts the claimer, e.g. while the view is still settling)
     * @return {@code true} when the claim is accepted (first claim, host matches election)
     */
    public synchronized boolean acceptClaim(BotClaim claim, String electedHostId) {
        if (claim == null) {
            return false;
        }
        if (respondedMessages.containsKey(claim.getAddressedMessageId())) {
            return false; // already answered — stale claim after partition merge
        }
        if (electedHostId != null && !electedHostId.equals(claim.getBotHostParticipantId())) {
            return false;
        }
        String existing = acceptedClaimHostByMessage.get(claim.getAddressedMessageId());
        if (existing != null && !existing.equals(claim.getBotHostParticipantId())) {
            return false;
        }
        acceptedClaimHostByMessage.put(claim.getAddressedMessageId(), claim.getBotHostParticipantId());
        return true;
    }

    /**
     * Register an incoming bot response for {@code addressedMessageId} produced by
     * {@code botHostParticipantId}.
     *
     * @return {@code true} when this is the first response and the host holds the accepted claim
     *         (or no claim was seen, e.g. the response outran the claim broadcast)
     */
    public synchronized boolean acceptResponse(String addressedMessageId, String botHostParticipantId) {
        if (addressedMessageId == null || addressedMessageId.trim().isEmpty()) {
            return false;
        }
        if (respondedMessages.containsKey(addressedMessageId)) {
            return false; // exactly one response per addressed message
        }
        String claimedHost = acceptedClaimHostByMessage.get(addressedMessageId);
        if (claimedHost != null && !claimedHost.equals(botHostParticipantId)) {
            return false;
        }
        respondedMessages.put(addressedMessageId, Boolean.TRUE);
        return true;
    }

    /**
     * Release an accepted claim whose host disappeared before responding, so a replacement host
     * (the next deterministic election winner) can claim and answer the message.
     */
    public synchronized void releaseClaim(String addressedMessageId, String botHostParticipantId) {
        String existing = acceptedClaimHostByMessage.get(addressedMessageId);
        if (existing != null && existing.equals(botHostParticipantId)
                && !respondedMessages.containsKey(addressedMessageId)) {
            acceptedClaimHostByMessage.remove(addressedMessageId);
        }
    }

    /** @return {@code true} when a response for {@code addressedMessageId} was already accepted. */
    public synchronized boolean hasResponse(String addressedMessageId) {
        return respondedMessages.containsKey(addressedMessageId);
    }

    private static <V> Map<String, V> boundedMap() {
        return new LinkedHashMap<String, V>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_TRACKED;
            }
        };
    }
}

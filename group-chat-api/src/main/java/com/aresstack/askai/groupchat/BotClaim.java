package com.aresstack.askai.groupchat;

/**
 * A published claim that one peer is about to produce the single logical bot response for one
 * addressed message.
 *
 * <p>Peers publish the claim before running the model. Everybody validates the claim against the
 * deterministic {@link BotElection} result for the current membership view; duplicate or stale
 * claims (and the responses referencing them) are rejected so exactly one bot response survives
 * per addressed message.</p>
 */
public final class BotClaim {

    private final String claimId;
    private final String addressedMessageId;
    private final String membershipViewId;
    private final String botHostParticipantId;
    private final long createdAt;

    public BotClaim(String claimId, String addressedMessageId, String membershipViewId,
                    String botHostParticipantId, long createdAt) {
        if (claimId == null || claimId.trim().isEmpty()) {
            throw new IllegalArgumentException("claimId must not be blank");
        }
        if (addressedMessageId == null || addressedMessageId.trim().isEmpty()) {
            throw new IllegalArgumentException("addressedMessageId must not be blank");
        }
        if (botHostParticipantId == null || botHostParticipantId.trim().isEmpty()) {
            throw new IllegalArgumentException("botHostParticipantId must not be blank");
        }
        this.claimId = claimId;
        this.addressedMessageId = addressedMessageId;
        this.membershipViewId = membershipViewId != null ? membershipViewId : "";
        this.botHostParticipantId = botHostParticipantId;
        this.createdAt = createdAt;
    }

    /** Unique claim identifier. */
    public String getClaimId() { return claimId; }

    /** The {@code @AskAI}-addressed message this claim answers. */
    public String getAddressedMessageId() { return addressedMessageId; }

    /** Identifier of the membership view the claimer saw when electing itself. */
    public String getMembershipViewId() { return membershipViewId; }

    /** The physical peer that will execute the model request. */
    public String getBotHostParticipantId() { return botHostParticipantId; }

    /** Claim creation time, epoch millis. */
    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "BotClaim{claim=" + claimId + ", message=" + addressedMessageId
                + ", host=" + botHostParticipantId + "}";
    }
}

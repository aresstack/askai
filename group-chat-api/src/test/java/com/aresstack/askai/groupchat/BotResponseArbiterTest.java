package com.aresstack.askai.groupchat;

import org.junit.Test;

import static org.junit.Assert.*;

public final class BotResponseArbiterTest {

    private static BotClaim claim(String claimId, String messageId, String host) {
        return new BotClaim(claimId, messageId, "view-1", host, 1000L);
    }

    @Test
    public void firstMatchingClaimAccepted() {
        BotResponseArbiter arbiter = new BotResponseArbiter();
        assertTrue(arbiter.acceptClaim(claim("c1", "m1", "alice"), "alice"));
    }

    @Test
    public void mismatchedHostClaimRejected() {
        BotResponseArbiter arbiter = new BotResponseArbiter();
        assertFalse("Claim from a non-elected host must be rejected",
                arbiter.acceptClaim(claim("c1", "m1", "bob"), "alice"));
    }

    @Test
    public void duplicateClaimFromSameHostToleratedButOtherHostRejected() {
        BotResponseArbiter arbiter = new BotResponseArbiter();
        assertTrue(arbiter.acceptClaim(claim("c1", "m1", "alice"), "alice"));
        assertFalse("A second claim by a different host must lose",
                arbiter.acceptClaim(claim("c2", "m1", "bob"), null));
    }

    @Test
    public void exactlyOneResponseAcceptedPerMessage() {
        BotResponseArbiter arbiter = new BotResponseArbiter();
        assertTrue(arbiter.acceptClaim(claim("c1", "m1", "alice"), "alice"));
        assertTrue(arbiter.acceptResponse("m1", "alice"));
        assertFalse("Second response for the same message must be rejected",
                arbiter.acceptResponse("m1", "alice"));
        assertTrue(arbiter.hasResponse("m1"));
    }

    @Test
    public void responseFromNonClaimingHostRejected() {
        BotResponseArbiter arbiter = new BotResponseArbiter();
        assertTrue(arbiter.acceptClaim(claim("c1", "m1", "alice"), "alice"));
        assertFalse(arbiter.acceptResponse("m1", "bob"));
        assertFalse(arbiter.hasResponse("m1"));
    }

    @Test
    public void staleClaimAfterResponseRejected() {
        BotResponseArbiter arbiter = new BotResponseArbiter();
        assertTrue(arbiter.acceptClaim(claim("c1", "m1", "alice"), "alice"));
        assertTrue(arbiter.acceptResponse("m1", "alice"));
        assertFalse("Claims arriving after the response (partition merge) must be rejected",
                arbiter.acceptClaim(claim("c2", "m1", "alice"), "alice"));
    }

    @Test
    public void responseWithoutClaimAccepted() {
        BotResponseArbiter arbiter = new BotResponseArbiter();
        assertTrue("Response that outran its claim broadcast is accepted",
                arbiter.acceptResponse("m1", "alice"));
    }
}

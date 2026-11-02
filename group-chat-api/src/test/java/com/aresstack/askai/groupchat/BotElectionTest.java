package com.aresstack.askai.groupchat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class BotElectionTest {

    private static Participant peer(String id, boolean capable, boolean ready) {
        return new Participant(id, id, null, null, capable, ready);
    }

    @Test
    public void lowestBotReadyIdWins() {
        List<Participant> members = Arrays.asList(
                peer("charlie", true, true), peer("alice", true, true), peer("bob", true, false));
        assertEquals("alice", BotElection.electBotHost(members));
    }

    @Test
    public void fallsBackToBotCapableWhenNobodyReady() {
        List<Participant> members = Arrays.asList(
                peer("charlie", true, false), peer("alice", false, false), peer("bob", true, false));
        assertEquals("bob", BotElection.electBotHost(members));
    }

    @Test
    public void nullWhenNobodyCanHost() {
        List<Participant> members = Arrays.asList(peer("alice", false, false), peer("bob", false, false));
        assertNull(BotElection.electBotHost(members));
        assertNull(BotElection.electBotHost(Collections.<Participant>emptyList()));
        assertNull(BotElection.electBotHost(null));
    }

    @Test
    public void readyPeerBeatsCapableOnlyPeerWithLowerId() {
        List<Participant> members = Arrays.asList(peer("alice", true, false), peer("zoe", true, true));
        assertEquals("Ready peers take precedence over capable-only peers",
                "zoe", BotElection.electBotHost(members));
    }

    @Test
    public void viewIdIsStableAcrossMemberOrder() {
        List<Participant> forward = Arrays.asList(peer("alice", false, false),
                peer("bob", false, false), peer("charlie", false, false));
        List<Participant> reversed = Arrays.asList(peer("charlie", false, false),
                peer("bob", false, false), peer("alice", false, false));
        assertEquals(BotElection.viewId(forward), BotElection.viewId(reversed));
        assertEquals("alice,bob,charlie", BotElection.viewId(forward));
        assertEquals("", BotElection.viewId(Collections.<Participant>emptyList()));
    }
}

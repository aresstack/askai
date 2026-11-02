package com.aresstack.askai.groupchat;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class InMemoryGroupChatTransportTest {

    @Test
    public void singleParticipantJoinAndSend() {
        InMemoryGroupChatTransport transport = new InMemoryGroupChatTransport();
        GroupChatRoom room = new GroupChatRoom("room1", "Test Room", "secret");
        Participant alice = new Participant("alice-id", "Alice", "violet");

        final AtomicReference<String> statusRef = new AtomicReference<String>();
        final AtomicReference<GroupChatMessage> messageRef = new AtomicReference<GroupChatMessage>();

        transport.join(room, alice, new GroupChatListener() {
            public void onMessage(GroupChatMessage message) { messageRef.set(message); }
            public void onParticipantJoined(Participant participant) {}
            public void onParticipantLeft(Participant participant) {}
            public void onParticipantsChanged(List<Participant> participants) {}
            public void onConnectionStateChanged(GroupChatConnectionState state) { statusRef.set(state.getMemberCount() == 1 ? "1 party member" : state.getMemberCount() + " party members"); }
        });

        assertTrue(transport.isConnected());
        assertEquals("1 party member", statusRef.get());

        GroupChatMessage msg = new GroupChatMessage.Builder()
                .messageId("msg-1")
                .roomId("room1")
                .senderParticipantId("alice-id")
                .senderSequence(1)
                .markdown("Hello, world!")
                .build();
        transport.send(msg);
        assertEquals("msg-1", messageRef.get().getMessageId());

        transport.leave();
        assertFalse(transport.isConnected());

        InMemoryGroupChatTransport.clearRoom("room1");
    }

    @Test
    public void twoParticipantsSeeEachOthersMessages() throws InterruptedException {
        InMemoryGroupChatTransport t1 = new InMemoryGroupChatTransport();
        InMemoryGroupChatTransport t2 = new InMemoryGroupChatTransport();
        GroupChatRoom room = new GroupChatRoom("room2", "Shared Room", "secret");
        Participant alice = new Participant("alice", "Alice", null);
        Participant bob = new Participant("bob", "Bob", null);

        final CountDownLatch joinLatch = new CountDownLatch(1);
        final AtomicReference<GroupChatMessage> aliceReceived = new AtomicReference<GroupChatMessage>();
        final AtomicReference<GroupChatMessage> bobReceived = new AtomicReference<GroupChatMessage>();
        final AtomicReference<Participant> joinedRef = new AtomicReference<Participant>();

        t1.join(room, alice, new GroupChatListener() {
            public void onMessage(GroupChatMessage m) { aliceReceived.set(m); }
            public void onParticipantJoined(Participant p) {
                joinedRef.set(p);
                joinLatch.countDown();
            }
            public void onParticipantLeft(Participant p) {}
            public void onParticipantsChanged(List<Participant> ps) {}
            public void onConnectionStateChanged(GroupChatConnectionState state) {}
        });

        t2.join(room, bob, new GroupChatListener() {
            public void onMessage(GroupChatMessage m) { bobReceived.set(m); }
            public void onParticipantJoined(Participant p) {}
            public void onParticipantLeft(Participant p) {}
            public void onParticipantsChanged(List<Participant> ps) {}
            public void onConnectionStateChanged(GroupChatConnectionState state) {}
        });

        assertTrue("Alice should have been notified of Bob joining",
                joinLatch.await(1, TimeUnit.SECONDS));
        assertEquals("bob", joinedRef.get().getParticipantId());

        GroupChatMessage msg = new GroupChatMessage.Builder()
                .messageId("msg-b1")
                .roomId("room2")
                .senderParticipantId("bob")
                .senderSequence(1)
                .markdown("Hi Alice!")
                .build();
        t2.send(msg);

        assertEquals("msg-b1", aliceReceived.get().getMessageId());
        assertEquals("msg-b1", bobReceived.get().getMessageId());

        t1.leave();
        t2.leave();
        InMemoryGroupChatTransport.clearRoom("room2");
    }

    @Test
    public void participantCountUpdatesOnJoinAndLeave() {
        InMemoryGroupChatTransport t1 = new InMemoryGroupChatTransport();
        InMemoryGroupChatTransport t2 = new InMemoryGroupChatTransport();
        GroupChatRoom room = new GroupChatRoom("room3", "Count Room", "secret");

        t1.join(room, new Participant("p1", "P1", null), noopListener());
        assertEquals(1, t1.getParticipants().size());

        t2.join(room, new Participant("p2", "P2", null), noopListener());
        assertEquals(2, t1.getParticipants().size());

        t2.leave();
        assertEquals(1, t1.getParticipants().size());

        t1.leave();
        InMemoryGroupChatTransport.clearRoom("room3");
    }

    private static GroupChatListener noopListener() {
        return new GroupChatListener() {
            public void onMessage(GroupChatMessage m) {}
            public void onParticipantJoined(Participant p) {}
            public void onParticipantLeft(Participant p) {}
            public void onParticipantsChanged(List<Participant> ps) {}
            public void onConnectionStateChanged(GroupChatConnectionState state) {}
        };
    }
}

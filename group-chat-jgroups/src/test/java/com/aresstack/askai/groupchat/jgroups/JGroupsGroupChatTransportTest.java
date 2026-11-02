package com.aresstack.askai.groupchat.jgroups;

import com.aresstack.askai.groupchat.BotClaim;
import com.aresstack.askai.groupchat.ColorMap;
import com.aresstack.askai.groupchat.GroupChatConnectionState;
import com.aresstack.askai.groupchat.GroupChatListener;
import com.aresstack.askai.groupchat.GroupChatMessage;
import com.aresstack.askai.groupchat.GroupChatRoom;
import com.aresstack.askai.groupchat.Participant;

import org.jgroups.protocols.SHARED_LOOPBACK;
import org.jgroups.protocols.SHARED_LOOPBACK_PING;
import org.jgroups.stack.Protocol;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration tests for {@link JGroupsGroupChatTransport} running multiple channels in one JVM.
 *
 * <p>The transport + discovery head of the stack is substituted with
 * SHARED_LOOPBACK / SHARED_LOOPBACK_PING via the protected factory hook; the security tail
 * (SYM_ENCRYPT + AUTH) and everything above it stay identical to production.</p>
 */
public class JGroupsGroupChatTransportTest {

    private static final long TIMEOUT_MILLIS = 20_000L;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<JGroupsGroupChatTransport> transports =
            new ArrayList<JGroupsGroupChatTransport>();

    @After
    public void tearDown() {
        for (JGroupsGroupChatTransport transport : transports) {
            try {
                transport.leave();
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
        }
        transports.clear();
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    public void twoPeersSeeEachOtherAndExchangeMessagesExactlyOnce() throws Exception {
        GroupChatRoom room = newRoom("s3cret");
        JGroupsGroupChatTransport a = newLoopbackTransport(null);
        JGroupsGroupChatTransport b = newLoopbackTransport(null);
        RecordingListener la = new RecordingListener();
        RecordingListener lb = new RecordingListener();
        Participant alice = new Participant("id-alice", "Alice Smith", "AliceSmith", "violet");
        Participant bob = new Participant("id-bob", "Bob Jones", "BobJones", null);

        a.join(room, alice, la);
        b.join(room, bob, lb);

        waitUntil("A sees 2 participants", new Condition() {
            @Override
            public boolean holds() {
                return participantCount(a) == 2;
            }
        });
        waitUntil("B sees 2 participants", new Condition() {
            @Override
            public boolean holds() {
                return participantCount(b) == 2;
            }
        });

        // Profiles (incl. mention handles) preserved across the wire.
        Participant aliceAtB = findParticipant(b, "id-alice");
        assertNotNull("B must know Alice's profile", aliceAtB);
        assertEquals("Alice Smith", aliceAtB.getDisplayName());
        assertEquals("AliceSmith", aliceAtB.getMentionHandle());
        assertEquals("violet", aliceAtB.getPreferredColor());
        Participant bobAtA = findParticipant(a, "id-bob");
        assertNotNull("A must know Bob's profile", bobAtA);
        assertEquals("BobJones", bobAtA.getMentionHandle());

        // A message from A arrives at B exactly once (and at A itself via loopback).
        GroupChatMessage message = message(room, alice, "m-1", "Hello **party**!");
        a.send(message);
        waitUntil("B receives m-1", new Condition() {
            @Override
            public boolean holds() {
                return lb.messageCount("m-1") == 1;
            }
        });
        waitUntil("A receives its own m-1", new Condition() {
            @Override
            public boolean holds() {
                return la.messageCount("m-1") == 1;
            }
        });

        // A duplicate send of the same messageId is dropped by the receivers.
        a.send(message(room, alice, "m-1", "Hello **party**!"));
        Thread.sleep(1500);
        assertEquals("duplicate must be dropped at B", 1, lb.messageCount("m-1"));
        assertEquals("duplicate must be dropped at A", 1, la.messageCount("m-1"));
    }

    @Test
    public void wrongSecretCannotJoinTheGroup() throws Exception {
        String roomId = "room-" + UUID.randomUUID();
        GroupChatRoom goodRoom = new GroupChatRoom(roomId, "Party", "correct-secret");
        GroupChatRoom badRoom = new GroupChatRoom(roomId, "Party", "wrong-secret");

        JGroupsGroupChatTransport a = newLoopbackTransport(null);
        JGroupsGroupChatTransport b = newLoopbackTransport(null);
        JGroupsGroupChatTransport intruder = newLoopbackTransport(null);
        RecordingListener la = new RecordingListener();

        a.join(goodRoom, new Participant("id-a", "A", null), la);
        b.join(goodRoom, new Participant("id-b", "B", null), new RecordingListener());
        waitUntil("A sees 2 participants", new Condition() {
            @Override
            public boolean holds() {
                return participantCount(a) == 2;
            }
        });

        // The intruder tries to join the same cluster with the wrong secret in the background
        // (its join blocks until the GMS join attempts run out, then it forms its own island
        // or fails outright — it must never become a member of A/B's group).
        final CountDownLatch intruderDone = new CountDownLatch(1);
        Thread joiner = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    intruder.join(badRoom, new Participant("id-intruder", "Eve", null),
                            new RecordingListener());
                } catch (Exception expectedForRejectedJoin) {
                    // Acceptable: the join may fail outright.
                } finally {
                    intruderDone.countDown();
                }
            }
        }, "intruder-join");
        joiner.setDaemon(true);
        joiner.start();

        // Observe for a few seconds: the good group must stay at 2 and never learn of the
        // intruder, and the intruder must never learn a good peer's profile.
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            assertEquals("good group must stay at 2 members", 2, participantCount(a));
            assertTrue("intruder profile must never reach A", findParticipant(a, "id-intruder") == null);
            assertTrue("intruder must never learn A's profile",
                    findParticipant(intruder, "id-a") == null);
            assertTrue("intruder must never learn B's profile",
                    findParticipant(intruder, "id-b") == null);
            Thread.sleep(200);
        }
        assertTrue("no intruder messages at A", la.claims.isEmpty());
        intruderDone.await(30, TimeUnit.SECONDS);
    }

    @Test
    public void leaveNotifiesRemainingPeers() throws Exception {
        GroupChatRoom room = newRoom("s3cret");
        JGroupsGroupChatTransport a = newLoopbackTransport(null);
        JGroupsGroupChatTransport b = newLoopbackTransport(null);
        RecordingListener lb = new RecordingListener();

        a.join(room, new Participant("id-a", "A", null), new RecordingListener());
        b.join(room, new Participant("id-b", "B", null), lb);
        waitUntil("B sees 2 participants", new Condition() {
            @Override
            public boolean holds() {
                return participantCount(b) == 2;
            }
        });

        a.leave();

        waitUntil("B is notified that A left", new Condition() {
            @Override
            public boolean holds() {
                return lb.leftIds().contains("id-a") && participantCount(b) == 1;
            }
        });
        assertFalse(a.isConnected());
        assertTrue(b.isConnected());
    }

    @Test
    public void colorMapConvergesOnAllPeersAndHonorsPreference() throws Exception {
        GroupChatRoom room = newRoom("s3cret");
        JGroupsGroupChatTransport a = newLoopbackTransport(null);
        JGroupsGroupChatTransport b = newLoopbackTransport(null);

        a.join(room, new Participant("id-a", "A", null, "violet"), new RecordingListener());
        b.join(room, new Participant("id-b", "B", null, "teal"), new RecordingListener());

        waitUntil("both peers converge to the same 2-entry color map", new Condition() {
            @Override
            public boolean holds() {
                ColorMap ma = a.getColorMap();
                ColorMap mb = b.getColorMap();
                return ma.getAssignments().size() == 2
                        && ma.getVersion() == mb.getVersion()
                        && ma.getAssignments().equals(mb.getAssignments());
            }
        });
        assertEquals("violet", a.getColorMap().colorOf("id-a"));
        assertEquals("teal", a.getColorMap().colorOf("id-b"));
        assertEquals(a.getColorMap().getAssignments(), b.getColorMap().getAssignments());
    }

    @Test
    public void botClaimReachesAllListeners() throws Exception {
        GroupChatRoom room = newRoom("s3cret");
        JGroupsGroupChatTransport a = newLoopbackTransport(null);
        JGroupsGroupChatTransport b = newLoopbackTransport(null);
        RecordingListener la = new RecordingListener();
        RecordingListener lb = new RecordingListener();

        a.join(room, new Participant("id-a", "A", null), la);
        b.join(room, new Participant("id-b", "B", null), lb);
        waitUntil("A sees 2 participants", new Condition() {
            @Override
            public boolean holds() {
                return participantCount(a) == 2;
            }
        });

        a.publishBotClaim(new BotClaim("claim-1", "m-42", "view-1", "id-a",
                System.currentTimeMillis()));

        waitUntil("claim arrives at B", new Condition() {
            @Override
            public boolean holds() {
                return lb.claimIds().contains("claim-1");
            }
        });
        waitUntil("claim loops back to A", new Condition() {
            @Override
            public boolean holds() {
                return la.claimIds().contains("claim-1");
            }
        });
    }

    @Test
    public void lateJoinerReceivesHistoryDeduplicated() throws Exception {
        GroupChatRoom room = newRoom("s3cret");
        JGroupsGroupChatTransport a = newLoopbackTransport(tmp.newFolder("history-a"));
        RecordingListener la = new RecordingListener();
        Participant alice = new Participant("id-a", "A", null);

        a.join(room, alice, la);
        a.send(message(room, alice, "h-1", "first"));
        a.send(message(room, alice, "h-2", "second"));
        a.send(message(room, alice, "h-3", "third"));
        waitUntil("A has all 3 messages in its local log", new Condition() {
            @Override
            public boolean holds() {
                return a.localHistory().size() == 3;
            }
        });

        // C joins later and must receive all 3 messages through the history sync.
        JGroupsGroupChatTransport c = newLoopbackTransport(tmp.newFolder("history-c"));
        RecordingListener lc = new RecordingListener();
        c.join(room, new Participant("id-c", "C", null), lc);

        waitUntil("C receives the 3 historical messages", new Condition() {
            @Override
            public boolean holds() {
                return lc.messageCount("h-1") == 1
                        && lc.messageCount("h-2") == 1
                        && lc.messageCount("h-3") == 1;
            }
        });
        Thread.sleep(1000);
        assertEquals("history must be deduplicated at C", 1, lc.messageCount("h-1"));
        assertEquals("history must be deduplicated at C", 1, lc.messageCount("h-2"));
        assertEquals("history must be deduplicated at C", 1, lc.messageCount("h-3"));
        assertEquals("C persisted the synced history", 3, c.localHistory().size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Production transport with the head swapped for SHARED_LOOPBACK (in-JVM clustering). */
    private JGroupsGroupChatTransport newLoopbackTransport(java.io.File historyDirectory) {
        JGroupsTransportConfig config = JGroupsTransportConfig.builder()
                .historyDirectory(historyDirectory)
                .build();
        JGroupsGroupChatTransport transport = new JGroupsGroupChatTransport(config) {
            @Override
            protected List<Protocol> createTransportAndDiscovery() {
                return Arrays.<Protocol>asList(new SHARED_LOOPBACK(), new SHARED_LOOPBACK_PING());
            }
        };
        transports.add(transport);
        return transport;
    }

    private static GroupChatRoom newRoom(String secret) {
        return new GroupChatRoom("room-" + UUID.randomUUID(), "Party", secret);
    }

    private static GroupChatMessage message(GroupChatRoom room, Participant sender,
                                            String messageId, String markdown) {
        return new GroupChatMessage.Builder()
                .messageId(messageId)
                .roomId(room.getRoomId())
                .senderParticipantId(sender.getParticipantId())
                .markdown(markdown)
                .build();
    }

    private static int participantCount(JGroupsGroupChatTransport transport) {
        return transport.getParticipants().size();
    }

    private static Participant findParticipant(JGroupsGroupChatTransport transport, String id) {
        for (Participant participant : transport.getParticipants()) {
            if (participant.getParticipantId().equals(id)) {
                return participant;
            }
        }
        return null;
    }

    private interface Condition {
        boolean holds();
    }

    private static void waitUntil(String what, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting until: " + what);
    }

    /** Thread-safe recording listener. */
    private static final class RecordingListener implements GroupChatListener {
        final List<GroupChatMessage> messages =
                Collections.synchronizedList(new ArrayList<GroupChatMessage>());
        final List<Participant> joined =
                Collections.synchronizedList(new ArrayList<Participant>());
        final List<Participant> left =
                Collections.synchronizedList(new ArrayList<Participant>());
        final List<GroupChatConnectionState> states =
                Collections.synchronizedList(new ArrayList<GroupChatConnectionState>());
        final List<ColorMap> colorMaps =
                Collections.synchronizedList(new ArrayList<ColorMap>());
        final List<BotClaim> claims =
                Collections.synchronizedList(new ArrayList<BotClaim>());

        @Override
        public void onMessage(GroupChatMessage message) {
            messages.add(message);
        }

        @Override
        public void onParticipantJoined(Participant participant) {
            joined.add(participant);
        }

        @Override
        public void onParticipantLeft(Participant participant) {
            left.add(participant);
        }

        @Override
        public void onParticipantsChanged(List<Participant> participants) {
            // Snapshot-based assertions use transport.getParticipants() instead.
        }

        @Override
        public void onConnectionStateChanged(GroupChatConnectionState state) {
            states.add(state);
        }

        @Override
        public void onColorMapChanged(ColorMap colorMap) {
            colorMaps.add(colorMap);
        }

        @Override
        public void onBotClaim(BotClaim claim) {
            claims.add(claim);
        }

        int messageCount(String messageId) {
            int count = 0;
            synchronized (messages) {
                for (GroupChatMessage message : messages) {
                    if (message.getMessageId().equals(messageId)) {
                        count++;
                    }
                }
            }
            return count;
        }

        List<String> leftIds() {
            List<String> ids = new ArrayList<String>();
            synchronized (left) {
                for (Participant participant : left) {
                    ids.add(participant.getParticipantId());
                }
            }
            return ids;
        }

        List<String> claimIds() {
            List<String> ids = new ArrayList<String>();
            synchronized (claims) {
                for (BotClaim claim : claims) {
                    ids.add(claim.getClaimId());
                }
            }
            return ids;
        }
    }
}

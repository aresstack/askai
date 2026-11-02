package com.aresstack.askai.groupchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process, in-memory {@link GroupChatTransport} used for the G1 foundational slice.
 *
 * <p>Multiple instances can share a static "cluster" map keyed by room ID, allowing two
 * {@code InMemoryGroupChatTransport} instances in the same JVM to communicate.  This is useful for
 * unit tests and manual smoke-testing; the real LAN transport ships with G2.</p>
 *
 * <p>Thread safety: all state mutations are synchronized on the instance; listener callbacks are
 * dispatched on the calling thread.</p>
 */
public final class InMemoryGroupChatTransport implements GroupChatTransport {

    /**
     * Shared room registries, keyed by room ID.  All transports that join the same room ID share
     * the same {@link RoomBus} so messages are delivered in-process.
     */
    private static final Map<String, RoomBus> ROOMS =
            Collections.synchronizedMap(new LinkedHashMap<String, RoomBus>());

    private GroupChatRoom currentRoom;
    private Participant self;
    private GroupChatListener listener;
    private boolean connected;

    @Override
    public synchronized void join(GroupChatRoom room, Participant self, GroupChatListener listener) {
        if (connected) {
            leave();
        }
        this.currentRoom = room;
        this.self = self;
        this.listener = listener;
        this.connected = true;

        RoomBus bus = getOrCreateBus(room.getRoomId());
        bus.addTransport(this);

        // Notify all other participants that this peer joined.
        bus.broadcastJoin(self, this);

        // Tell the joining client the current member count and full participant list.
        int count = bus.participantCount();
        listener.onConnectionStateChanged(GroupChatConnectionState.connected(count));
        listener.onParticipantsChanged(bus.participants());

        // Recompute and (on change) broadcast the room color map.
        bus.recomputeColorMap();
    }

    @Override
    public synchronized void send(GroupChatMessage message) {
        if (!connected || currentRoom == null) {
            return;
        }
        if (!currentRoom.getRoomId().equals(message.getRoomId())) {
            throw new IllegalArgumentException(
                    "Cross-room send rejected: expected roomId=" + currentRoom.getRoomId()
                            + " but message has roomId=" + message.getRoomId());
        }
        boolean botMessageFromSelf = message.isBotMessage()
                && self != null && self.getParticipantId().equals(message.getBotHostParticipantId());
        if (self != null && !botMessageFromSelf
                && !self.getParticipantId().equals(message.getSenderParticipantId())) {
            throw new IllegalArgumentException(
                    "Sender spoofing rejected: joined as participantId=" + self.getParticipantId()
                            + " but message claims senderParticipantId=" + message.getSenderParticipantId());
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        if (bus != null) {
            bus.broadcast(message);
        }
    }

    @Override
    public synchronized void leave() {
        if (!connected) {
            return;
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        if (bus != null) {
            bus.removeTransport(this);
            bus.broadcastLeave(self, this);
            bus.recomputeColorMap();
        }
        connected = false;
        currentRoom = null;
        self = null;
        listener = null;
    }

    @Override
    public synchronized List<Participant> getParticipants() {
        if (!connected || currentRoom == null) {
            return Collections.emptyList();
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        return bus != null ? bus.participants() : Collections.<Participant>emptyList();
    }

    @Override
    public synchronized boolean isConnected() {
        return connected;
    }

    @Override
    public synchronized void publishBotClaim(BotClaim claim) {
        if (!connected || currentRoom == null || claim == null) {
            return;
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        if (bus != null) {
            bus.broadcastBotClaim(claim);
        }
    }

    @Override
    public synchronized void updateSelf(Participant self) {
        if (!connected || currentRoom == null || self == null) {
            return;
        }
        this.self = self;
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        if (bus != null) {
            bus.broadcastParticipantsChanged();
            bus.recomputeColorMap();
        }
    }

    @Override
    public synchronized ColorMap getColorMap() {
        if (!connected || currentRoom == null) {
            return ColorMap.EMPTY;
        }
        RoomBus bus = ROOMS.get(currentRoom.getRoomId());
        return bus != null ? bus.colorMap() : ColorMap.EMPTY;
    }

    Participant getSelf() {
        return self;
    }

    GroupChatListener getListener() {
        return listener;
    }

    // ------------------------------------------------------------------

    private static RoomBus getOrCreateBus(String roomId) {
        synchronized (ROOMS) {
            RoomBus bus = ROOMS.get(roomId);
            if (bus == null) {
                bus = new RoomBus(roomId);
                ROOMS.put(roomId, bus);
            }
            return bus;
        }
    }

    /** Visible for tests: remove the shared room bus for a given roomId. */
    public static void clearRoom(String roomId) {
        ROOMS.remove(roomId);
    }

    // ------------------------------------------------------------------

    private static final class RoomBus {
        private final String roomId;
        private final List<InMemoryGroupChatTransport> transports =
                new CopyOnWriteArrayList<InMemoryGroupChatTransport>();
        private final DuplicateFilter duplicates = new DuplicateFilter();
        private volatile ColorMap colorMap = ColorMap.EMPTY;

        RoomBus(String roomId) {
            this.roomId = roomId;
        }

        void addTransport(InMemoryGroupChatTransport t) {
            transports.add(t);
        }

        void removeTransport(InMemoryGroupChatTransport t) {
            transports.remove(t);
            if (transports.isEmpty()) {
                ROOMS.remove(roomId);
            }
        }

        void broadcast(GroupChatMessage message) {
            if (!duplicates.firstTime(message.getMessageId())) {
                return; // duplicate delivery (e.g. rebroadcast) — drop silently
            }
            for (InMemoryGroupChatTransport t : transports) {
                GroupChatListener l = t.getListener();
                if (l != null) {
                    l.onMessage(message);
                }
            }
        }

        /** Fan a bot claim out to every joined listener, including the publisher's own. */
        void broadcastBotClaim(BotClaim claim) {
            for (InMemoryGroupChatTransport t : transports) {
                GroupChatListener l = t.getListener();
                if (l != null) {
                    l.onBotClaim(claim);
                }
            }
        }

        /** Notify every member of the current participant list (after a profile update). */
        void broadcastParticipantsChanged() {
            List<Participant> all = participants();
            for (InMemoryGroupChatTransport t : transports) {
                GroupChatListener l = t.getListener();
                if (l != null) {
                    l.onParticipantsChanged(all);
                }
            }
        }

        /** Recompute the color map for the current membership; broadcast when it changed. */
        void recomputeColorMap() {
            ColorMap updated = ColorAssignmentEngine.recompute(colorMap, participants(),
                    System.currentTimeMillis());
            if (updated.getVersion() == colorMap.getVersion()) {
                return;
            }
            colorMap = updated;
            for (InMemoryGroupChatTransport t : transports) {
                GroupChatListener l = t.getListener();
                if (l != null) {
                    l.onColorMapChanged(updated);
                }
            }
        }

        ColorMap colorMap() {
            return colorMap;
        }

        void broadcastJoin(Participant joined, InMemoryGroupChatTransport source) {
            List<Participant> all = participants();
            int count = all.size();
            for (InMemoryGroupChatTransport t : transports) {
                GroupChatListener l = t.getListener();
                if (l == null) {
                    continue;
                }
                if (t == source) {
                    // The joining peer already received onConnectionStateChanged in join(); skip.
                    continue;
                }
                l.onParticipantJoined(joined);
                l.onParticipantsChanged(all);
                l.onConnectionStateChanged(GroupChatConnectionState.connected(count));
            }
        }

        void broadcastLeave(Participant left, InMemoryGroupChatTransport source) {
            List<Participant> all = participants();
            int count = all.size();
            for (InMemoryGroupChatTransport t : transports) {
                if (t == source) {
                    continue;
                }
                GroupChatListener l = t.getListener();
                if (l == null) {
                    continue;
                }
                l.onParticipantLeft(left);
                l.onParticipantsChanged(all);
                l.onConnectionStateChanged(GroupChatConnectionState.connected(count));
            }
        }

        List<Participant> participants() {
            List<Participant> result = new ArrayList<Participant>();
            for (InMemoryGroupChatTransport t : transports) {
                Participant p = t.getSelf();
                if (p != null) {
                    result.add(p);
                }
            }
            return Collections.unmodifiableList(result);
        }

        int participantCount() {
            return transports.size();
        }
    }
}

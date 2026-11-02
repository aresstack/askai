package com.aresstack.askai.groupchat.jgroups;

import com.aresstack.askai.groupchat.BotClaim;
import com.aresstack.askai.groupchat.ColorAssignmentEngine;
import com.aresstack.askai.groupchat.ColorMap;
import com.aresstack.askai.groupchat.DuplicateFilter;
import com.aresstack.askai.groupchat.FileRoomHistoryLog;
import com.aresstack.askai.groupchat.GroupChatConnectionState;
import com.aresstack.askai.groupchat.GroupChatListener;
import com.aresstack.askai.groupchat.GroupChatMessage;
import com.aresstack.askai.groupchat.GroupChatRoom;
import com.aresstack.askai.groupchat.GroupChatTransport;
import com.aresstack.askai.groupchat.GroupChatWire;
import com.aresstack.askai.groupchat.HistoryMerger;
import com.aresstack.askai.groupchat.Participant;
import com.aresstack.askai.groupchat.RoomHistoryLog;
import com.aresstack.askai.groupchat.WireEnvelope;

import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.MergeView;
import org.jgroups.Message;
import org.jgroups.Receiver;
import org.jgroups.View;
import org.jgroups.auth.MD5Token;
import org.jgroups.protocols.AUTH;
import org.jgroups.protocols.FD_ALL;
import org.jgroups.protocols.FD_SOCK;
import org.jgroups.protocols.FRAG2;
import org.jgroups.protocols.MERGE3;
import org.jgroups.protocols.MFC;
import org.jgroups.protocols.PING;
import org.jgroups.protocols.SYM_ENCRYPT;
import org.jgroups.protocols.TCP;
import org.jgroups.protocols.TCPPING;
import org.jgroups.protocols.UDP;
import org.jgroups.protocols.UFC;
import org.jgroups.protocols.UNICAST3;
import org.jgroups.protocols.VERIFY_SUSPECT;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.stack.Protocol;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * LAN {@link GroupChatTransport} for the Partying mode, built on JGroups 4.2 with a programmatic
 * protocol stack (no XML).
 *
 * <p><b>Discovery:</b> UDP multicast by default; when multicast is disabled or manual peers are
 * configured, a TCP stack with a static TCPPING host list is used instead (reliable on networks
 * that filter multicast).</p>
 *
 * <p><b>Security (G3):</b> all traffic is encrypted on the wire with AES-128 via
 * {@code SYM_ENCRYPT}; the key is derived from the room secret with PBKDF2WithHmacSHA256
 * (salt {@code "askai-party:" + roomId}, 10000 iterations). Joins are additionally authenticated
 * by {@code AUTH} with a token derived from SHA-256(roomSecret + roomId), so knowing the cluster
 * name alone does not permit joining. The cluster name itself is derived from the roomId hash,
 * never from the secret.</p>
 *
 * <p><b>History (G4):</b> with a configured history directory every peer keeps a local
 * {@link FileRoomHistoryLog}; on join and after partition merges the transport asks the
 * coordinator for recent messages, deduplicates them by message ID and delivers them through the
 * normal {@link GroupChatListener#onMessage} path in deterministic {@link HistoryMerger} order.</p>
 *
 * <p>No JGroups types appear in the public API; all callbacks arrive on transport threads as per
 * the {@link GroupChatListener} contract.</p>
 */
public class JGroupsGroupChatTransport implements GroupChatTransport {

    /** Overlap window subtracted from the newest local message when requesting history. */
    private static final long HISTORY_OVERLAP_MILLIS = 60_000L;

    /** Maximum number of messages served in one history response. */
    private static final int HISTORY_RESPONSE_CAP = 500;

    private static final int PBKDF2_ITERATIONS = 10_000;
    private static final int AES_KEY_BITS = 128;

    private final JGroupsTransportConfig config;
    private final Object lock = new Object();

    private JChannel channel;
    private GroupChatRoom room;
    private Participant self;
    private GroupChatListener listener;
    private DuplicateFilter dedup;
    private RoomHistoryLog historyLog;
    private ColorMap colorMap = ColorMap.EMPTY;
    private long roomEpoch;
    private boolean connected;

    /** participantId → profile, in arrival order (self first). */
    private final Map<String, Participant> profiles = new LinkedHashMap<String, Participant>();

    /** JGroups address → participantId, learned from received profiles. */
    private final Map<Address, String> addressToParticipant = new LinkedHashMap<Address, String>();

    /** Members of the last installed view. */
    private List<Address> lastViewMembers = new ArrayList<Address>();

    /** Executor for sends triggered from JGroups callbacks (avoids blocking the up-thread). */
    private ExecutorService asyncSender;

    /** Create a transport with the default configuration (UDP multicast, no persistence). */
    public JGroupsGroupChatTransport() {
        this(JGroupsTransportConfig.defaults());
    }

    public JGroupsGroupChatTransport(JGroupsTransportConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    // ------------------------------------------------------------------
    // GroupChatTransport
    // ------------------------------------------------------------------

    @Override
    public void join(GroupChatRoom room, Participant self, GroupChatListener listener) {
        if (room == null || self == null || listener == null) {
            throw new IllegalArgumentException("room, self and listener must not be null");
        }
        synchronized (lock) {
            if (connected) {
                leave();
            }
            this.room = room;
            this.self = self;
            this.listener = listener;
            this.dedup = new DuplicateFilter();
            this.colorMap = ColorMap.EMPTY;
            this.roomEpoch = 0L;
            this.profiles.clear();
            this.addressToParticipant.clear();
            this.lastViewMembers = new ArrayList<Address>();
            this.profiles.put(self.getParticipantId(), self);
            this.asyncSender = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "askai-party-sender");
                    t.setDaemon(true);
                    return t;
                }
            });

            File historyDir = config.getHistoryDirectory();
            if (historyDir != null) {
                this.historyLog = new FileRoomHistoryLog(
                        historyDir, room.getRoomId(), config.getHistoryRetention());
            }

            try {
                JChannel ch = new JChannel(createProtocols(room));
                ch.name(self.getDisplayName());
                ch.setReceiver(new ChannelReceiver());
                this.channel = ch;
                ch.connect(clusterName(room.getRoomId()));
                this.connected = true;
            } catch (Exception e) {
                cleanupAfterFailedJoin();
                listener.onConnectionStateChanged(
                        GroupChatConnectionState.disconnected("join failed: " + shortReason(e)));
                throw new IllegalStateException("Failed to join room " + room.getRoomId(), e);
            }

            // Announce our profile and ask the room for everybody else's.
            trySendBroadcast(GroupChatWire.TYPE_PROFILE, GroupChatWire.encodeParticipant(self));
            trySendBroadcast(GroupChatWire.TYPE_PROFILE_REQUEST, new byte[0]);
            requestHistoryFromCoordinator();

            listener.onConnectionStateChanged(GroupChatConnectionState.connected(profiles.size()));
            listener.onParticipantsChanged(snapshotParticipants());
            maybeRecomputeColors();
        }
    }

    @Override
    public void send(GroupChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        synchronized (lock) {
            if (!connected || room == null) {
                return;
            }
            if (!room.getRoomId().equals(message.getRoomId())) {
                throw new IllegalArgumentException(
                        "Cross-room send rejected: expected roomId=" + room.getRoomId()
                                + " but message has roomId=" + message.getRoomId());
            }
            boolean botMessageFromSelf = message.isBotMessage()
                    && self != null && self.getParticipantId().equals(message.getBotHostParticipantId());
            if (self != null && !botMessageFromSelf
                    && !self.getParticipantId().equals(message.getSenderParticipantId())) {
                throw new IllegalArgumentException(
                        "Sender spoofing rejected: joined as participantId=" + self.getParticipantId()
                                + " but message claims senderParticipantId="
                                + message.getSenderParticipantId());
            }
            try {
                broadcastEnvelope(GroupChatWire.TYPE_MESSAGE, GroupChatWire.encodeMessage(message));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to send message " + message.getMessageId(), e);
            }
        }
    }

    @Override
    public void leave() {
        GroupChatListener departedListener;
        synchronized (lock) {
            if (!connected) {
                return;
            }
            departedListener = listener;
            try {
                if (channel != null && self != null) {
                    broadcastEnvelope(GroupChatWire.TYPE_LEAVE, GroupChatWire.encodeParticipant(self));
                }
            } catch (Exception ignored) {
                // Best effort: peers will still notice via the view change.
            }
            cleanupAfterFailedJoin();
        }
        if (departedListener != null) {
            departedListener.onConnectionStateChanged(GroupChatConnectionState.disconnected(null));
        }
    }

    @Override
    public List<Participant> getParticipants() {
        synchronized (lock) {
            if (!connected) {
                return Collections.emptyList();
            }
            return snapshotParticipants();
        }
    }

    @Override
    public boolean isConnected() {
        synchronized (lock) {
            return connected;
        }
    }

    @Override
    public void publishBotClaim(BotClaim claim) {
        if (claim == null) {
            return;
        }
        synchronized (lock) {
            if (!connected) {
                return;
            }
            try {
                broadcastEnvelope(GroupChatWire.TYPE_BOT_CLAIM, GroupChatWire.encodeBotClaim(claim));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to publish bot claim " + claim.getClaimId(), e);
            }
        }
    }

    @Override
    public void updateSelf(Participant self) {
        if (self == null) {
            return;
        }
        synchronized (lock) {
            if (!connected || this.self == null) {
                return;
            }
            if (!this.self.getParticipantId().equals(self.getParticipantId())) {
                throw new IllegalArgumentException("updateSelf must keep the participantId stable");
            }
            this.self = self;
            profiles.put(self.getParticipantId(), self);
            trySendBroadcast(GroupChatWire.TYPE_PROFILE, GroupChatWire.encodeParticipant(self));
            listener.onParticipantsChanged(snapshotParticipants());
            maybeRecomputeColors();
        }
    }

    @Override
    public ColorMap getColorMap() {
        synchronized (lock) {
            return colorMap;
        }
    }

    // ------------------------------------------------------------------
    // Extras (not part of GroupChatTransport)
    // ------------------------------------------------------------------

    /**
     * @return the locally persisted room history in deterministic order, or an empty list when
     *         persistence is disabled.  Replaying this into the UI is the application's job.
     */
    public List<GroupChatMessage> localHistory() {
        synchronized (lock) {
            if (historyLog == null) {
                return Collections.emptyList();
            }
            return historyLog.readAll();
        }
    }

    @Override
    public void clearHistory() {
        synchronized (lock) {
            if (historyLog instanceof FileRoomHistoryLog) {
                ((FileRoomHistoryLog) historyLog).clear();
            }
        }
    }

    /** @return the names of the protocols in the running stack, bottom to top (G7 diagnostics). */
    public String describeStack() {
        synchronized (lock) {
            if (channel == null) {
                return "(not joined)";
            }
            List<Protocol> protocols = channel.getProtocolStack().getProtocols();
            // getProtocols() lists top-down; reverse for a bottom-up (transport-first) view.
            StringBuilder sb = new StringBuilder();
            for (int i = protocols.size() - 1; i >= 0; i--) {
                if (sb.length() > 0) {
                    sb.append(" -> ");
                }
                sb.append(protocols.get(i).getName());
            }
            return sb.toString();
        }
    }

    /**
     * Best-effort multicast diagnostics for the Settings dialog (G7): reports which network
     * interfaces are up, multicast-capable and IPv4-addressed.
     */
    public static String diagnoseMulticast() {
        StringBuilder sb = new StringBuilder();
        boolean anyMulticast = false;
        boolean anyIpv4 = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                boolean up;
                boolean multicast;
                try {
                    up = nic.isUp();
                    multicast = nic.supportsMulticast();
                } catch (SocketException e) {
                    continue;
                }
                if (!up || nic.isLoopback()) {
                    continue;
                }
                boolean ipv4 = false;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.getAddress() != null && address.getAddress().length == 4) {
                        ipv4 = true;
                        break;
                    }
                }
                anyMulticast |= multicast;
                anyIpv4 |= ipv4;
                sb.append(nic.getName())
                        .append(" (").append(nic.getDisplayName()).append("): up")
                        .append(multicast ? ", multicast" : ", NO multicast")
                        .append(ipv4 ? ", IPv4" : ", no IPv4")
                        .append('\n');
            }
        } catch (SocketException e) {
            return "Could not enumerate network interfaces: " + e.getMessage();
        }
        if (sb.length() == 0) {
            return "No active non-loopback network interface found.";
        }
        sb.append(anyMulticast && anyIpv4
                ? "Multicast discovery should work on this machine."
                : "Multicast discovery may NOT work; consider manual peers (TCP).");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Protocol stack construction
    // ------------------------------------------------------------------

    /**
     * Build the full protocol stack for the given room: transport + discovery head (overridable
     * for tests) followed by the common tail with SYM_ENCRYPT/AUTH security.
     */
    private Protocol[] createProtocols(GroupChatRoom room) throws Exception {
        List<Protocol> protocols = new ArrayList<Protocol>(createTransportAndDiscovery());
        protocols.add(new MERGE3());
        protocols.add(new FD_SOCK());
        protocols.add(new FD_ALL());
        protocols.add(new VERIFY_SUSPECT());

        SYM_ENCRYPT encrypt = new SYM_ENCRYPT();
        encrypt.setSecretKey(deriveRoomKey(room.getRoomSecret(), room.getRoomId()));
        protocols.add(encrypt);

        AUTH auth = new AUTH();
        auth.setAuthToken(new MD5Token(authTokenValue(room.getRoomSecret(), room.getRoomId())));
        protocols.add(auth);

        protocols.add(new NAKACK2());
        protocols.add(new UNICAST3());
        protocols.add(new STABLE());
        GMS gms = new GMS();
        gms.setJoinTimeout(2000L);
        gms.setMaxJoinAttempts(3);
        protocols.add(gms);
        protocols.add(new UFC());
        protocols.add(new MFC());
        FRAG2 frag = new FRAG2();
        frag.setFragSize(config.useTcpStack() ? 60_000 : 8_000);
        protocols.add(frag);
        return protocols.toArray(new Protocol[0]);
    }

    /**
     * Create the transport + discovery head of the stack (bottom-up order).  Tests override this
     * to substitute SHARED_LOOPBACK / SHARED_LOOPBACK_PING so multiple channels can run in one
     * JVM while keeping the security tail identical.
     */
    protected List<Protocol> createTransportAndDiscovery() throws Exception {
        List<Protocol> head = new ArrayList<Protocol>();
        if (config.useTcpStack()) {
            TCP tcp = new TCP();
            tcp.setBindPort(config.getTcpBindPort());
            if (config.getBindInterface() != null) {
                tcp.setBindAddress(InetAddress.getByName(config.getBindInterface()));
            }
            head.add(tcp);
            TCPPING tcpping = new TCPPING();
            tcpping.setInitialHosts(parseManualPeers(config.getManualPeers(), config.getTcpBindPort()));
            head.add(tcpping);
        } else {
            UDP udp = new UDP();
            if (config.getBindInterface() != null) {
                udp.setBindAddress(InetAddress.getByName(config.getBindInterface()));
            }
            head.add(udp);
            head.add(new PING());
        }
        return head;
    }

    /** Parse {@code host} / {@code host:port} strings into socket addresses. */
    static List<InetSocketAddress> parseManualPeers(List<String> peers, int defaultPort) {
        List<InetSocketAddress> result = new ArrayList<InetSocketAddress>();
        for (String peer : peers) {
            if (peer == null || peer.trim().isEmpty()) {
                continue;
            }
            String trimmed = peer.trim();
            String host = trimmed;
            int port = defaultPort;
            int colon = trimmed.lastIndexOf(':');
            if (colon > 0 && colon < trimmed.length() - 1) {
                try {
                    port = Integer.parseInt(trimmed.substring(colon + 1));
                    host = trimmed.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // Whole string is the host (e.g. a name containing a colon-free typo).
                }
            }
            result.add(new InetSocketAddress(host, port));
        }
        return result;
    }

    /** Cluster name derived from the roomId hash only — never from the secret. */
    static String clusterName(String roomId) {
        return "askai-party-" + sha256Hex(roomId).substring(0, 16);
    }

    /** Derive the 128-bit AES room key from the secret via PBKDF2WithHmacSHA256. */
    static SecretKey deriveRoomKey(String roomSecret, String roomId) throws Exception {
        String password = (roomSecret != null && !roomSecret.isEmpty()) ? roomSecret : roomId;
        byte[] salt = ("askai-party:" + roomId).getBytes(StandardCharsets.UTF_8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** AUTH token value: SHA-256 hex of (roomSecret + roomId). */
    static String authTokenValue(String roomSecret, String roomId) {
        return sha256Hex((roomSecret != null ? roomSecret : "") + roomId);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Wire plumbing
    // ------------------------------------------------------------------

    private void broadcastEnvelope(int type, byte[] payload) throws Exception {
        channel.send(new Message(null, encodeEnvelope(type, payload)));
    }

    private void unicastEnvelope(Address dest, int type, byte[] payload) throws Exception {
        channel.send(new Message(dest, encodeEnvelope(type, payload)));
    }

    private byte[] encodeEnvelope(int type, byte[] payload) {
        WireEnvelope envelope = new WireEnvelope(GroupChatWire.PROTOCOL_VERSION, type,
                room.getRoomId(), roomEpoch, payload, null);
        return GroupChatWire.encodeEnvelope(envelope);
    }

    /** Broadcast on the calling thread, swallowing errors (used for best-effort announcements). */
    private void trySendBroadcast(int type, byte[] payload) {
        try {
            broadcastEnvelope(type, payload);
        } catch (Exception ignored) {
            // Best effort; a PROFILE_REQUEST from peers will trigger a retry.
        }
    }

    /** Schedule a send on the async sender (used from JGroups callback threads). */
    private void sendAsync(final Runnable sendTask) {
        ExecutorService sender = asyncSender;
        if (sender == null || sender.isShutdown()) {
            return;
        }
        try {
            sender.execute(sendTask);
        } catch (Exception ignored) {
            // Shutting down.
        }
    }

    private void requestHistoryFromCoordinator() {
        JChannel ch = channel;
        if (ch == null) {
            return;
        }
        View view = ch.getView();
        if (view == null || view.getMembers().size() < 2) {
            return;
        }
        Address target = view.getMembers().get(0);
        if (target.equals(ch.getAddress())) {
            target = view.getMembers().get(1);
        }
        long since = 0L;
        if (historyLog != null) {
            List<GroupChatMessage> local = historyLog.readAll();
            if (!local.isEmpty()) {
                long newest = 0L;
                for (GroupChatMessage message : local) {
                    newest = Math.max(newest, message.getCreatedAt());
                }
                since = Math.max(0L, newest - HISTORY_OVERLAP_MILLIS);
            }
        }
        final Address dest = target;
        final byte[] payload = GroupChatWire.encodeHistoryRequest(since);
        try {
            unicastEnvelope(dest, GroupChatWire.TYPE_HISTORY_REQUEST, payload);
        } catch (Exception ignored) {
            // Best effort; the next merge triggers another request.
        }
    }

    // ------------------------------------------------------------------
    // Incoming traffic
    // ------------------------------------------------------------------

    /** JGroups receiver adapter — the only place JGroups callbacks enter the transport. */
    private final class ChannelReceiver implements Receiver {

        @Override
        public void receive(Message msg) {
            byte[] raw = msg.getBuffer();
            if (raw == null || raw.length == 0) {
                return;
            }
            WireEnvelope envelope;
            try {
                envelope = GroupChatWire.decodeEnvelope(raw);
            } catch (Exception e) {
                return; // Not one of ours; drop.
            }
            handleEnvelope(envelope, msg.getSrc());
        }

        @Override
        public void viewAccepted(View view) {
            handleViewChange(view);
        }
    }

    private void handleEnvelope(WireEnvelope envelope, Address src) {
        synchronized (lock) {
            if (!connected || room == null) {
                return;
            }
            if (envelope.getProtocolVersion() != GroupChatWire.PROTOCOL_VERSION) {
                return; // Unsupported protocol version.
            }
            if (!room.getRoomId().equals(envelope.getRoomId())) {
                return; // Wrong room.
            }
            try {
                dispatchEnvelope(envelope, src);
            } catch (Exception ignored) {
                // Malformed payload from a peer; drop.
            }
        }
    }

    private void dispatchEnvelope(WireEnvelope envelope, Address src) throws Exception {
        int type = envelope.getType();
        if (type == GroupChatWire.TYPE_MESSAGE) {
            handleIncomingMessage(GroupChatWire.decodeMessage(envelope.getPayload()));
        } else if (type == GroupChatWire.TYPE_PROFILE) {
            handleIncomingProfile(GroupChatWire.decodeParticipant(envelope.getPayload()), src);
        } else if (type == GroupChatWire.TYPE_PROFILE_REQUEST) {
            handleProfileRequest(src);
        } else if (type == GroupChatWire.TYPE_HISTORY_REQUEST) {
            handleHistoryRequest(GroupChatWire.decodeHistoryRequest(envelope.getPayload()), src);
        } else if (type == GroupChatWire.TYPE_HISTORY_RESPONSE) {
            handleHistoryResponse(GroupChatWire.decodeMessageList(envelope.getPayload()));
        } else if (type == GroupChatWire.TYPE_COLOR_MAP) {
            handleIncomingColorMap(GroupChatWire.decodeColorMap(envelope.getPayload()));
        } else if (type == GroupChatWire.TYPE_BOT_CLAIM) {
            listener.onBotClaim(GroupChatWire.decodeBotClaim(envelope.getPayload()));
        } else if (type == GroupChatWire.TYPE_LEAVE) {
            handleIncomingLeave(GroupChatWire.decodeParticipant(envelope.getPayload()), src);
        }
        // Unknown types within a supported protocol version are ignored (forward compatibility).
    }

    private void handleIncomingMessage(GroupChatMessage message) {
        if (!dedup.firstTime(message.getMessageId())) {
            return;
        }
        if (historyLog != null) {
            historyLog.append(message);
        }
        listener.onMessage(message);
    }

    private void handleIncomingProfile(Participant participant, Address src) {
        Participant previous = profiles.put(participant.getParticipantId(), participant);
        if (src != null) {
            addressToParticipant.put(src, participant.getParticipantId());
        }
        boolean isSelf = self != null && self.getParticipantId().equals(participant.getParticipantId());
        if (isSelf) {
            maybeRecomputeColors();
            return; // Loopback of our own announcement.
        }
        if (previous == null) {
            listener.onParticipantJoined(participant);
            listener.onParticipantsChanged(snapshotParticipants());
            listener.onConnectionStateChanged(GroupChatConnectionState.connected(profiles.size()));
        } else {
            listener.onParticipantsChanged(snapshotParticipants());
        }
        maybeRecomputeColors();
    }

    private void handleProfileRequest(Address src) {
        if (self == null || src == null || src.equals(channel.getAddress())) {
            return;
        }
        final Address requester = src;
        final byte[] payload = GroupChatWire.encodeParticipant(self);
        sendAsync(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (!connected) {
                        return;
                    }
                    try {
                        unicastEnvelope(requester, GroupChatWire.TYPE_PROFILE, payload);
                    } catch (Exception ignored) {
                        // Requester will retry on the next view change.
                    }
                }
            }
        });
    }

    private void handleHistoryRequest(long sinceMillis, Address src) {
        if (historyLog == null || src == null || src.equals(channel.getAddress())) {
            return;
        }
        List<GroupChatMessage> matching = historyLog.readSince(sinceMillis);
        if (matching.isEmpty()) {
            return;
        }
        if (matching.size() > HISTORY_RESPONSE_CAP) {
            matching = matching.subList(matching.size() - HISTORY_RESPONSE_CAP, matching.size());
        }
        final Address requester = src;
        final byte[] payload = GroupChatWire.encodeMessageList(matching);
        sendAsync(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (!connected) {
                        return;
                    }
                    try {
                        unicastEnvelope(requester, GroupChatWire.TYPE_HISTORY_RESPONSE, payload);
                    } catch (Exception ignored) {
                        // Requester will re-request after the next merge.
                    }
                }
            }
        });
    }

    private void handleHistoryResponse(List<GroupChatMessage> messages) {
        // Wire order is not guaranteed: deliver in deterministic HistoryMerger order, deduped.
        for (GroupChatMessage message : HistoryMerger.sort(messages)) {
            if (!room.getRoomId().equals(message.getRoomId())) {
                continue;
            }
            handleIncomingMessage(message);
        }
    }

    private void handleIncomingColorMap(ColorMap incoming) {
        if (incoming.getVersion() > colorMap.getVersion()) {
            colorMap = incoming;
            listener.onColorMapChanged(incoming);
        }
    }

    private void handleIncomingLeave(Participant departed, Address src) {
        if (self != null && self.getParticipantId().equals(departed.getParticipantId())) {
            return; // Loopback of our own leave.
        }
        if (src != null) {
            addressToParticipant.remove(src);
        }
        Participant removed = profiles.remove(departed.getParticipantId());
        if (removed != null) {
            listener.onParticipantLeft(removed);
            listener.onParticipantsChanged(snapshotParticipants());
            listener.onConnectionStateChanged(GroupChatConnectionState.connected(profiles.size()));
            maybeRecomputeColors();
        }
    }

    // ------------------------------------------------------------------
    // View handling
    // ------------------------------------------------------------------

    private void handleViewChange(View view) {
        synchronized (lock) {
            if (room == null || listener == null) {
                return;
            }
            roomEpoch = view.getViewId() != null ? view.getViewId().getId() : roomEpoch;
            List<Address> current = new ArrayList<Address>(view.getMembers());

            // Members that disappeared without a TYPE_LEAVE (crash, cable pull).
            boolean membershipShrunk = false;
            for (Address gone : new ArrayList<Address>(lastViewMembers)) {
                if (current.contains(gone)) {
                    continue;
                }
                String participantId = addressToParticipant.remove(gone);
                if (participantId != null) {
                    Participant removed = profiles.remove(participantId);
                    if (removed != null) {
                        membershipShrunk = true;
                        listener.onParticipantLeft(removed);
                    }
                }
            }

            boolean newcomers = false;
            for (Address address : current) {
                if (!lastViewMembers.contains(address)) {
                    newcomers = true;
                    break;
                }
            }
            boolean merge = view instanceof MergeView;
            lastViewMembers = current;

            if (membershipShrunk) {
                listener.onParticipantsChanged(snapshotParticipants());
                listener.onConnectionStateChanged(GroupChatConnectionState.connected(profiles.size()));
            }

            if (connected && (newcomers || merge)) {
                // Re-announce our profile so newcomers/merged peers learn who we are, and after a
                // merge re-sync history (message IDs dedup any overlap).
                final boolean resyncHistory = merge;
                sendAsync(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (lock) {
                            if (!connected) {
                                return;
                            }
                            trySendBroadcast(GroupChatWire.TYPE_PROFILE,
                                    GroupChatWire.encodeParticipant(self));
                            if (resyncHistory) {
                                requestHistoryFromCoordinator();
                            }
                        }
                    }
                });
            }
            maybeRecomputeColors();
        }
    }

    // ------------------------------------------------------------------
    // Colors (G5 transport side)
    // ------------------------------------------------------------------

    /** When we are the view coordinator: recompute the color map and replicate it if it changed. */
    private void maybeRecomputeColors() {
        if (!connected || channel == null || !isCoordinator()) {
            return;
        }
        ColorMap next = ColorAssignmentEngine.recompute(colorMap, snapshotParticipants(),
                System.currentTimeMillis());
        if (next.getVersion() <= colorMap.getVersion()) {
            return;
        }
        colorMap = next;
        listener.onColorMapChanged(next);
        final byte[] payload = GroupChatWire.encodeColorMap(next);
        sendAsync(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    if (!connected) {
                        return;
                    }
                    trySendBroadcast(GroupChatWire.TYPE_COLOR_MAP, payload);
                }
            }
        });
    }

    private boolean isCoordinator() {
        JChannel ch = channel;
        if (ch == null) {
            return false;
        }
        View view = ch.getView();
        return view != null && !view.getMembers().isEmpty()
                && view.getMembers().get(0).equals(ch.getAddress());
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private List<Participant> snapshotParticipants() {
        return Collections.unmodifiableList(new ArrayList<Participant>(profiles.values()));
    }

    /** Release channel + log + state without emitting events (callers emit what is appropriate). */
    private void cleanupAfterFailedJoin() {
        if (asyncSender != null) {
            asyncSender.shutdownNow();
            asyncSender = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception ignored) {
                // Closing is best effort.
            }
            channel = null;
        }
        if (historyLog != null) {
            try {
                historyLog.close();
            } catch (Exception ignored) {
                // Closing is best effort.
            }
            historyLog = null;
        }
        connected = false;
        room = null;
        self = null;
        listener = null;
        colorMap = ColorMap.EMPTY;
        profiles.clear();
        addressToParticipant.clear();
        lastViewMembers = new ArrayList<Address>();
    }

    private static String shortReason(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
    }
}

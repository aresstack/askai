package com.aresstack.askai.groupchat.jgroups;

import com.aresstack.askai.groupchat.HistoryRetentionPolicy;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration for {@link JGroupsGroupChatTransport}.
 *
 * <p>The default configuration uses UDP multicast discovery. When multicast is disabled or manual
 * peers are given, the transport falls back to a TCP stack with a static initial-host list
 * (TCPPING), which is the reliable choice on networks that filter multicast (many office LANs and
 * most VPNs).</p>
 */
public final class JGroupsTransportConfig {

    /** Default TCP bind port for the TCP fallback stack and for manual peers without a port. */
    public static final int DEFAULT_TCP_PORT = 7810;

    private final boolean multicastDiscovery;
    private final String bindInterface;
    private final List<String> manualPeers;
    private final int tcpBindPort;
    private final File historyDirectory;
    private final HistoryRetentionPolicy historyRetention;

    private JGroupsTransportConfig(Builder builder) {
        this.multicastDiscovery = builder.multicastDiscovery;
        this.bindInterface = builder.bindInterface;
        this.manualPeers = Collections.unmodifiableList(new ArrayList<String>(builder.manualPeers));
        this.tcpBindPort = builder.tcpBindPort;
        this.historyDirectory = builder.historyDirectory;
        this.historyRetention = builder.historyRetention != null
                ? builder.historyRetention : HistoryRetentionPolicy.UNLIMITED;
    }

    /** @return a config with all defaults (UDP multicast discovery, auto bind, no persistence). */
    public static JGroupsTransportConfig defaults() {
        return builder().build();
    }

    /** @return a new builder initialised with the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether UDP multicast discovery is used ({@code true} by default). */
    public boolean isMulticastDiscovery() {
        return multicastDiscovery;
    }

    /**
     * Bind interface address (e.g. {@code "192.168.1.10"}), or {@code null} to let JGroups choose
     * automatically.
     */
    public String getBindInterface() {
        return bindInterface;
    }

    /**
     * Manually configured peers as {@code host} or {@code host:port} strings.  A non-empty list
     * combined with disabled multicast selects the TCP/TCPPING stack.
     */
    public List<String> getManualPeers() {
        return manualPeers;
    }

    /** TCP bind port used by the TCP fallback stack (default {@link #DEFAULT_TCP_PORT}). */
    public int getTcpBindPort() {
        return tcpBindPort;
    }

    /**
     * Directory for the per-room history log, or {@code null} to disable persistence entirely.
     */
    public File getHistoryDirectory() {
        return historyDirectory;
    }

    /** Retention limits applied to the per-room history log (never {@code null}). */
    public HistoryRetentionPolicy getHistoryRetention() {
        return historyRetention;
    }

    /** @return {@code true} when the TCP/TCPPING stack should be used instead of UDP multicast. */
    public boolean useTcpStack() {
        return !multicastDiscovery || !manualPeers.isEmpty();
    }

    @Override
    public String toString() {
        return "JGroupsTransportConfig{multicast=" + multicastDiscovery
                + ", bindInterface=" + bindInterface
                + ", manualPeers=" + manualPeers
                + ", tcpBindPort=" + tcpBindPort
                + ", historyDirectory=" + historyDirectory + "}";
    }

    /** Fluent builder for {@link JGroupsTransportConfig}. */
    public static final class Builder {
        private boolean multicastDiscovery = true;
        private String bindInterface;
        private List<String> manualPeers = new ArrayList<String>();
        private int tcpBindPort = DEFAULT_TCP_PORT;
        private File historyDirectory;
        private HistoryRetentionPolicy historyRetention = HistoryRetentionPolicy.UNLIMITED;

        /** Enable or disable UDP multicast discovery (enabled by default). */
        public Builder multicastDiscovery(boolean multicastDiscovery) {
            this.multicastDiscovery = multicastDiscovery;
            return this;
        }

        /** Bind to a specific local interface address; {@code null} = automatic. */
        public Builder bindInterface(String bindInterface) {
            this.bindInterface = bindInterface;
            return this;
        }

        /** Manually configured peers as {@code host} or {@code host:port} strings. */
        public Builder manualPeers(List<String> manualPeers) {
            this.manualPeers = manualPeers != null
                    ? new ArrayList<String>(manualPeers)
                    : new ArrayList<String>();
            return this;
        }

        /** TCP bind port for the TCP fallback stack. */
        public Builder tcpBindPort(int tcpBindPort) {
            if (tcpBindPort <= 0 || tcpBindPort > 65535) {
                throw new IllegalArgumentException("tcpBindPort out of range: " + tcpBindPort);
            }
            this.tcpBindPort = tcpBindPort;
            return this;
        }

        /** Directory for per-room history logs; {@code null} disables persistence. */
        public Builder historyDirectory(File historyDirectory) {
            this.historyDirectory = historyDirectory;
            return this;
        }

        /** Retention limits for the history log; {@code null} = unlimited (default). */
        public Builder historyRetention(HistoryRetentionPolicy historyRetention) {
            this.historyRetention = historyRetention;
            return this;
        }

        public JGroupsTransportConfig build() {
            return new JGroupsTransportConfig(this);
        }
    }
}

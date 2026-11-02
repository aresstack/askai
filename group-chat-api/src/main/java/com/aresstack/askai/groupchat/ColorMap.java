package com.aresstack.askai.groupchat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable replicated room color state: which participant renders in which palette color.
 *
 * <p>The map is computed deterministically by {@link ColorAssignmentEngine} on every membership
 * change and replicated to all peers together with its {@link #getVersion() version}; a peer only
 * applies a received map when its version is higher than the local one. A departed participant's
 * color stays reserved as a {@link ColorLease lease} for a short period so a quick reconnect gets
 * the same color back.</p>
 */
public final class ColorMap {

    /** An empty version-0 color map. */
    public static final ColorMap EMPTY = new ColorMap(0, ParticipantColorPalette.VERSION,
            Collections.<String, String>emptyMap(), Collections.<String, ColorLease>emptyMap());

    private final long version;
    private final int paletteVersion;
    private final Map<String, String> assignments;
    private final Map<String, ColorLease> leases;

    /**
     * @param version        monotonically increasing map version
     * @param paletteVersion the {@link ParticipantColorPalette#VERSION} the tokens refer to
     * @param assignments    participantId → color token
     * @param leases         color token → lease reserving it for a recently departed participant
     */
    public ColorMap(long version, int paletteVersion, Map<String, String> assignments,
                    Map<String, ColorLease> leases) {
        this.version = version;
        this.paletteVersion = paletteVersion;
        this.assignments = Collections.unmodifiableMap(new LinkedHashMap<String, String>(assignments));
        this.leases = Collections.unmodifiableMap(new LinkedHashMap<String, ColorLease>(leases));
    }

    /** Monotonically increasing version; higher versions replace lower ones on all peers. */
    public long getVersion() {
        return version;
    }

    /** The palette version the tokens in this map refer to. */
    public int getPaletteVersion() {
        return paletteVersion;
    }

    /** participantId → color token; unmodifiable. */
    public Map<String, String> getAssignments() {
        return assignments;
    }

    /** color token → lease held for a recently departed participant; unmodifiable. */
    public Map<String, ColorLease> getLeases() {
        return leases;
    }

    /** @return the color token assigned to {@code participantId}, or {@code null}. */
    public String colorOf(String participantId) {
        return assignments.get(participantId);
    }

    @Override
    public String toString() {
        return "ColorMap{v=" + version + ", assignments=" + assignments + "}";
    }
}

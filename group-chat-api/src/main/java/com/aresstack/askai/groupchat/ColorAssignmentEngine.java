package com.aresstack.askai.groupchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic participant color assignment.
 *
 * <p>Every peer runs {@link #recompute} over the same inputs (current map, membership, time) and
 * arrives at the same result, so no coordination protocol is needed: the map with the highest
 * version simply wins. Existing assignments are kept stable, departed participants' colors are
 * reserved via {@link ColorLease leases} for {@link #LEASE_MILLIS}, and new participants are
 * processed in sorted participant-ID order so simultaneous joins resolve identically on all
 * peers.</p>
 */
public final class ColorAssignmentEngine {

    /** How long a departed participant's color stays reserved for a quick rejoin. */
    public static final long LEASE_MILLIS = 5 * 60 * 1000L;

    private ColorAssignmentEngine() {
    }

    /**
     * Recompute the room color map for the given membership.
     *
     * @param current   the current map ({@link ColorMap#EMPTY} allowed; {@code null} treated as empty)
     * @param members   the current room members (the logical bot is skipped)
     * @param nowMillis deterministic "now" used for lease expiry and new lease creation
     * @return {@code current} unchanged when nothing changed, otherwise a new map with
     *         {@code version = current.getVersion() + 1}
     */
    public static ColorMap recompute(ColorMap current, List<Participant> members, long nowMillis) {
        if (current == null) {
            current = ColorMap.EMPTY;
        }
        List<Participant> humanMembers = new ArrayList<Participant>();
        if (members != null) {
            for (Participant member : members) {
                if (member != null && !GroupChatBot.PARTICIPANT_ID.equals(member.getParticipantId())) {
                    humanMembers.add(member);
                }
            }
        }
        Map<String, Participant> memberById = new LinkedHashMap<String, Participant>();
        for (Participant member : humanMembers) {
            memberById.put(member.getParticipantId(), member);
        }

        // 1. Drop expired leases.
        Map<String, ColorLease> leases = new LinkedHashMap<String, ColorLease>();
        for (Map.Entry<String, ColorLease> entry : current.getLeases().entrySet()) {
            if (!entry.getValue().isExpired(nowMillis)) {
                leases.put(entry.getKey(), entry.getValue());
            }
        }

        // 2. Keep assignments of still-present members; lease colors of departed ones.
        Map<String, String> assignments = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : current.getAssignments().entrySet()) {
            String participantId = entry.getKey();
            String color = entry.getValue();
            if (memberById.containsKey(participantId)) {
                assignments.put(participantId, color);
            } else {
                leases.put(color, new ColorLease(participantId, nowMillis + LEASE_MILLIS));
            }
        }

        // 3. Assign colors to new members in sorted participant-ID order (deterministic).
        List<String> newcomerIds = new ArrayList<String>();
        for (String participantId : memberById.keySet()) {
            if (!assignments.containsKey(participantId)) {
                newcomerIds.add(participantId);
            }
        }
        Collections.sort(newcomerIds);
        for (String participantId : newcomerIds) {
            String color = pickColor(memberById.get(participantId), assignments, leases);
            leases.remove(color);
            assignments.put(participantId, color);
        }

        // 4. Only bump the version when something actually changed.
        if (assignments.equals(current.getAssignments())
                && leases.equals(current.getLeases())
                && current.getPaletteVersion() == ParticipantColorPalette.VERSION) {
            return current;
        }
        return new ColorMap(current.getVersion() + 1, ParticipantColorPalette.VERSION, assignments, leases);
    }

    /** Deterministically pick the color for one new member. */
    private static String pickColor(Participant member, Map<String, String> assignments,
                                    Map<String, ColorLease> leases) {
        String participantId = member.getParticipantId();

        // A rejoining participant whose lease still exists gets the leased color back.
        for (Map.Entry<String, ColorLease> entry : leases.entrySet()) {
            if (participantId.equals(entry.getValue().getParticipantId())
                    && !assignments.containsValue(entry.getKey())) {
                return entry.getKey();
            }
        }

        // Preferred color, when known, free and not leased to somebody else.
        String preferred = member.getPreferredColor();
        if (preferred != null && ParticipantColorPalette.byToken(preferred) != null
                && !assignments.containsValue(preferred)) {
            ColorLease lease = leases.get(preferred);
            if (lease == null || participantId.equals(lease.getParticipantId())) {
                return preferred;
            }
        }

        // Free color maximizing the minimum circular hue distance to all assigned colors;
        // ties resolved by palette entry order.
        String best = null;
        int bestDistance = -1;
        for (ParticipantColorPalette.Entry entry : ParticipantColorPalette.entries()) {
            String token = entry.getToken();
            if (assignments.containsValue(token) || leases.containsKey(token)) {
                continue;
            }
            int minDistance = Integer.MAX_VALUE;
            for (String assignedToken : assignments.values()) {
                ParticipantColorPalette.Entry assigned = ParticipantColorPalette.byToken(assignedToken);
                if (assigned != null) {
                    minDistance = Math.min(minDistance,
                            circularHueDistance(entry.getHueDegrees(), assigned.getHueDegrees()));
                }
            }
            if (minDistance > bestDistance) {
                bestDistance = minDistance;
                best = token;
            }
        }
        if (best != null) {
            return best;
        }

        // Every palette color is taken: reuse round-robin by palette order.
        List<ParticipantColorPalette.Entry> entries = ParticipantColorPalette.entries();
        return entries.get(assignments.size() % entries.size()).getToken();
    }

    /** Circular distance between two hues in degrees (0-180). */
    private static int circularHueDistance(int hueA, int hueB) {
        int diff = Math.abs(hueA - hueB) % 360;
        return diff > 180 ? 360 - diff : diff;
    }
}

package com.aresstack.askai.groupchat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public final class ColorAssignmentEngineTest {

    private static final long NOW = 1_000_000L;

    private static Participant member(String id, String preferredColor) {
        return new Participant(id, id, null, preferredColor);
    }

    @Test
    public void preferredColorHonoredWhenFree() {
        ColorMap map = ColorAssignmentEngine.recompute(ColorMap.EMPTY,
                Collections.singletonList(member("alice", "green")), NOW);
        assertEquals("green", map.colorOf("alice"));
        assertEquals(1, map.getVersion());
        assertEquals(ParticipantColorPalette.VERSION, map.getPaletteVersion());
    }

    @Test
    public void preferenceCollisionResolvedDeterministically() {
        List<Participant> members = Arrays.asList(member("b", "blue"), member("a", "blue"));
        ColorMap map = ColorAssignmentEngine.recompute(ColorMap.EMPTY, members, NOW);
        // Sorted-ID order: "a" wins the preference; "b" gets the hue-farthest free color.
        assertEquals("blue", map.colorOf("a"));
        // Distances from blue (210°): orange (30°) is farthest at 180°.
        assertEquals("orange", map.colorOf("b"));
    }

    @Test
    public void existingAssignmentsStayStableAcrossRecompute() {
        List<Participant> members = Arrays.asList(member("alice", "green"), member("bob", null));
        ColorMap first = ColorAssignmentEngine.recompute(ColorMap.EMPTY, members, NOW);
        ColorMap second = ColorAssignmentEngine.recompute(first, members, NOW + 1000);
        assertSame("Unchanged input must return the same instance", first, second);

        // A third member joining must not disturb the existing assignments.
        List<Participant> grown = Arrays.asList(member("alice", "green"), member("bob", null),
                member("carol", null));
        ColorMap third = ColorAssignmentEngine.recompute(second, grown, NOW + 2000);
        assertEquals(first.colorOf("alice"), third.colorOf("alice"));
        assertEquals(first.colorOf("bob"), third.colorOf("bob"));
        assertNotNull(third.colorOf("carol"));
    }

    @Test
    public void departedParticipantColorLeasedAndReturnedOnRejoin() {
        ColorMap joined = ColorAssignmentEngine.recompute(ColorMap.EMPTY,
                Collections.singletonList(member("alice", "green")), NOW);
        ColorMap departed = ColorAssignmentEngine.recompute(joined,
                Collections.<Participant>emptyList(), NOW + 1000);
        assertNull(departed.colorOf("alice"));
        ColorLease lease = departed.getLeases().get("green");
        assertNotNull("Departed color must be leased", lease);
        assertEquals("alice", lease.getParticipantId());
        assertEquals(NOW + 1000 + ColorAssignmentEngine.LEASE_MILLIS, lease.getExpiresAtMillis());

        // Rejoin within the lease — even without a preference — returns the leased color.
        ColorMap rejoined = ColorAssignmentEngine.recompute(departed,
                Collections.singletonList(member("alice", null)), NOW + 2000);
        assertEquals("green", rejoined.colorOf("alice"));
        assertFalse("Lease must be consumed on rejoin", rejoined.getLeases().containsKey("green"));
    }

    @Test
    public void expiredLeaseFreesColorForOthers() {
        ColorMap joined = ColorAssignmentEngine.recompute(ColorMap.EMPTY,
                Collections.singletonList(member("alice", "green")), NOW);
        ColorMap departed = ColorAssignmentEngine.recompute(joined,
                Collections.<Participant>emptyList(), NOW + 1000);
        long afterExpiry = NOW + 1000 + ColorAssignmentEngine.LEASE_MILLIS;
        ColorMap next = ColorAssignmentEngine.recompute(departed,
                Collections.singletonList(member("bob", "green")), afterExpiry);
        assertEquals("green", next.colorOf("bob"));
        assertTrue(next.getLeases().isEmpty());
    }

    @Test
    public void unexpiredLeaseBlocksPreferenceOfOtherParticipant() {
        ColorMap joined = ColorAssignmentEngine.recompute(ColorMap.EMPTY,
                Collections.singletonList(member("alice", "green")), NOW);
        ColorMap departed = ColorAssignmentEngine.recompute(joined,
                Collections.<Participant>emptyList(), NOW + 1000);
        ColorMap next = ColorAssignmentEngine.recompute(departed,
                Collections.singletonList(member("bob", "green")), NOW + 2000);
        assertNotNull(next.colorOf("bob"));
        assertFalse("Leased color must not be given to a different participant",
                "green".equals(next.colorOf("bob")));
    }

    @Test
    public void moreMembersThanPaletteColorsStillAssignsEveryone() {
        int paletteSize = ParticipantColorPalette.entries().size();
        List<Participant> members = new ArrayList<Participant>();
        for (int i = 0; i < paletteSize + 3; i++) {
            members.add(member(String.format("user-%02d", i), null));
        }
        ColorMap map = ColorAssignmentEngine.recompute(ColorMap.EMPTY, members, NOW);
        assertEquals(paletteSize + 3, map.getAssignments().size());
        Set<String> distinct = new HashSet<String>(map.getAssignments().values());
        assertEquals("First wave must use every palette color", paletteSize, distinct.size());
    }

    @Test
    public void botParticipantGetsNoColor() {
        List<Participant> members = Arrays.asList(member("alice", null),
                member(GroupChatBot.PARTICIPANT_ID, "violet"));
        ColorMap map = ColorAssignmentEngine.recompute(ColorMap.EMPTY, members, NOW);
        assertNull(map.colorOf(GroupChatBot.PARTICIPANT_ID));
        assertNotNull(map.colorOf("alice"));
        assertEquals(1, map.getAssignments().size());
    }

    @Test
    public void sameInputsProduceSameOutputs() {
        List<Participant> members = Arrays.asList(member("c", null), member("a", "red"),
                member("b", "red"), member("d", null));
        ColorMap first = ColorAssignmentEngine.recompute(ColorMap.EMPTY, members, NOW);
        ColorMap second = ColorAssignmentEngine.recompute(ColorMap.EMPTY, members, NOW);
        assertEquals(first.getVersion(), second.getVersion());
        assertEquals(first.getAssignments(), second.getAssignments());
    }
}

package com.aresstack.askai.groupchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic election of the single active bot host for a membership view.
 *
 * <p>All peers run the same pure function over the same sorted membership view and therefore
 * agree on the elected host without exchanging votes. When the elected host disappears, the next
 * view simply elects its replacement.</p>
 */
public final class BotElection {

    private BotElection() {
    }

    /**
     * Elect the bot host: the bot-ready participant with the lexicographically smallest
     * participant ID. Falls back to bot-capable participants when nobody is ready yet.
     *
     * @return the elected participant's ID, or {@code null} when no member can host the bot
     */
    public static String electBotHost(List<Participant> members) {
        String elected = smallestIdWhere(members, true);
        if (elected == null) {
            elected = smallestIdWhere(members, false);
        }
        return elected;
    }

    /**
     * A stable identifier for a membership view: the sorted participant IDs joined with commas.
     * Two peers that see the same members compute the same view ID.
     */
    public static String viewId(List<Participant> members) {
        List<String> ids = new ArrayList<String>();
        if (members != null) {
            for (Participant member : members) {
                ids.add(member.getParticipantId());
            }
        }
        Collections.sort(ids);
        StringBuilder builder = new StringBuilder();
        for (String id : ids) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private static String smallestIdWhere(List<Participant> members, boolean requireReady) {
        String smallest = null;
        if (members == null) {
            return null;
        }
        for (Participant member : members) {
            boolean eligible = requireReady ? member.isBotReady() : member.isBotCapable();
            if (!eligible) {
                continue;
            }
            if (smallest == null || member.getParticipantId().compareTo(smallest) < 0) {
                smallest = member.getParticipantId();
            }
        }
        return smallest;
    }
}

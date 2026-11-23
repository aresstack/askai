package com.aresstack.askai.research.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The research view ON the conversation — and NOTHING else. It stores which research phase a message belongs
 * to (keyed by the host's stable message id) and one outcome summary per phase. It deliberately holds NO
 * message text: the host's chat record is the single truth for that, so the two can never drift apart.
 * <p>
 * A message without an id (plain chat turns, records written before ids existed) simply has no phase here.
 * That stays "unknown" forever — guessing by text or timestamp would silently produce wrong attributions.
 * <p>
 * Persisted as small JSON next to the research project, so the attribution survives a restart. This is not a
 * second history: it is metadata about messages that live elsewhere.
 */
public final class ResearchPhaseJournal {

    /** messageId → phaseId, in the order messages were attributed. */
    private final Map<String, String> phaseByMessageId = new LinkedHashMap<String, String>();
    /** phaseId → the ONE outcome summary of that phase (latest wins). */
    private final Map<String, String> outcomeByPhase = new LinkedHashMap<String, String>();
    /** Keys of transition effects already shown in the chat (divider, change note), once per transition. */
    private final java.util.Set<String> shown = new java.util.LinkedHashSet<String>();

    public synchronized boolean hasShown(String key) {
        return shown.contains(key);
    }

    public synchronized void markShown(String key) {
        shown.add(key);
    }

    /** Attribute one message to a phase. Empty ids are ignored — never invent an attribution. */
    public synchronized boolean attribute(String messageId, String phaseId) {
        if (messageId == null || messageId.trim().isEmpty()
                || phaseId == null || phaseId.trim().isEmpty()) {
            return false;
        }
        String previous = phaseByMessageId.put(messageId.trim(), phaseId.trim());
        return !phaseId.trim().equals(previous);
    }

    /** The phase's ONE summary (latest wins) — e.g. the structured run-outcome narrative. */
    public synchronized boolean recordOutcome(String phaseId, String summary) {
        if (phaseId == null || phaseId.trim().isEmpty() || summary == null || summary.trim().isEmpty()) {
            return false;
        }
        String previous = outcomeByPhase.put(phaseId.trim(), summary.trim());
        return !summary.trim().equals(previous);
    }

    /** The phase of this message, or "" when unknown (no id, or a message from before this journal). */
    public synchronized String phaseOf(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) {
            return "";
        }
        String phase = phaseByMessageId.get(messageId.trim());
        return phase == null ? "" : phase;
    }

    /** The outcome summary of a phase, or "". */
    public synchronized String outcomeOf(String phaseId) {
        String outcome = phaseId == null ? null : outcomeByPhase.get(phaseId.trim());
        return outcome == null ? "" : outcome;
    }

    public synchronized boolean isEmpty() {
        return phaseByMessageId.isEmpty() && outcomeByPhase.isEmpty();
    }

    public synchronized String toJson() {
        JsonObject messages = new JsonObject();
        for (Map.Entry<String, String> entry : phaseByMessageId.entrySet()) {
            messages.addProperty(entry.getKey(), entry.getValue());
        }
        JsonObject outcomes = new JsonObject();
        for (Map.Entry<String, String> entry : outcomeByPhase.entrySet()) {
            outcomes.addProperty(entry.getKey(), entry.getValue());
        }
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.add("messagePhases", messages);
        document.add("phaseOutcomes", outcomes);
        com.google.gson.JsonArray shownKeys = new com.google.gson.JsonArray();
        for (String key : shown) {
            shownKeys.add(key);
        }
        document.add("shownEffects", shownKeys);
        return document.toString();
    }

    /**
     * Parse a persisted journal. Unreadable or foreign content yields an EMPTY journal rather than an error:
     * losing the phase attribution costs annotation, never conversation content — the messages themselves
     * live in the host's chat record.
     */
    public static ResearchPhaseJournal fromJson(String json) {
        try {
            return parse(json);
        } catch (IllegalArgumentException unreadable) {
            return new ResearchPhaseJournal();
        }
    }

    /**
     * The strict parse of PR 06: unreadable content throws, so the session moves the file aside and reports
     * it instead of loading an empty journal that the next save would write over.
     */
    public static ResearchPhaseJournal parse(String json) {
        ResearchPhaseJournal journal = new ResearchPhaseJournal();
        if (json == null || json.trim().isEmpty()) {
            return journal;
        }
        try {
            JsonObject document = JsonParser.parseString(json).getAsJsonObject();
            copyInto(document.getAsJsonObject("messagePhases"), journal.phaseByMessageId);
            copyInto(document.getAsJsonObject("phaseOutcomes"), journal.outcomeByPhase);
            com.google.gson.JsonArray shownKeys = document.getAsJsonArray("shownEffects");
            if (shownKeys != null) {
                for (JsonElement key : shownKeys) {
                    journal.shown.add(key.getAsString());
                }
            }
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException("phase journal unreadable: " + unreadable.getMessage(),
                    unreadable);
        }
        return journal;
    }

    private static void copyInto(JsonObject source, Map<String, String> target) {
        if (source == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                target.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }
}

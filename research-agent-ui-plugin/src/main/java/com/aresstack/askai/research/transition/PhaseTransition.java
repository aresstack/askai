package com.aresstack.askai.research.transition;

import com.aresstack.askai.research.state.CommandActor;
import com.aresstack.askai.research.state.CommandSurface;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One immutable state transition (D3): id, command, from and to, who sent it through which surface, the
 * correlation id it shares with its user action, the revision the sender saw, the user's comment, the time
 * and the effects that run after the commit.
 */
public final class PhaseTransition {

    private final String id;
    private final ResearchCommandType command;
    private final ResearchStateMemento from;
    private final ResearchStateMemento to;
    private final CommandActor actor;
    private final CommandSurface surface;
    private final String correlationId;
    private final long seenRevision;
    private final String comment;
    private final long timeMillis;
    private final List<String> effects;

    public PhaseTransition(String id, ResearchCommandType command, ResearchStateMemento from,
                           ResearchStateMemento to, CommandActor actor, CommandSurface surface,
                           String correlationId, long seenRevision, String comment, long timeMillis,
                           List<String> effects) {
        this.id = id;
        this.command = command;
        this.from = from;
        this.to = to;
        this.actor = actor;
        this.surface = surface;
        this.correlationId = correlationId == null ? "" : correlationId;
        this.seenRevision = seenRevision;
        this.comment = comment == null ? "" : comment;
        this.timeMillis = timeMillis;
        this.effects = effects == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(effects));
    }

    PhaseTransition withEffects(List<String> newEffects) {
        return new PhaseTransition(id, command, from, to, actor, surface, correlationId, seenRevision,
                comment, timeMillis, newEffects);
    }

    public String getId() {
        return id;
    }

    public ResearchCommandType getCommand() {
        return command;
    }

    public ResearchStateMemento getFrom() {
        return from;
    }

    public ResearchStateMemento getTo() {
        return to;
    }

    public CommandActor getActor() {
        return actor;
    }

    public CommandSurface getSurface() {
        return surface;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public long getSeenRevision() {
        return seenRevision;
    }

    public String getComment() {
        return comment;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    public List<String> getEffects() {
        return effects;
    }

    /** Whether the transition leaves its phase (including every {@code REOPEN_*}). */
    public boolean changesPhase() {
        return !from.getPhaseId().equals(to.getPhaseId());
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("command", command.name());
        json.add("from", memento(from));
        json.add("to", memento(to));
        json.addProperty("actor", actor.name());
        json.addProperty("surface", surface.name());
        json.addProperty("correlationId", correlationId);
        json.addProperty("seenRevision", seenRevision);
        json.addProperty("comment", comment);
        json.addProperty("time", timeMillis);
        JsonArray list = new JsonArray();
        for (String effect : effects) {
            list.add(effect);
        }
        json.add("effects", list);
        return json;
    }

    static PhaseTransition fromJson(JsonObject json) {
        List<String> effects = new ArrayList<String>();
        JsonArray list = json.getAsJsonArray("effects");
        if (list != null) {
            for (JsonElement element : list) {
                effects.add(element.getAsString());
            }
        }
        return new PhaseTransition(json.get("id").getAsString(),
                ResearchCommandType.valueOf(json.get("command").getAsString()),
                memento(json.getAsJsonObject("from")), memento(json.getAsJsonObject("to")),
                CommandActor.valueOf(json.get("actor").getAsString()),
                CommandSurface.valueOf(json.get("surface").getAsString()),
                string(json, "correlationId"), json.get("seenRevision").getAsLong(),
                string(json, "comment"), json.get("time").getAsLong(), effects);
    }

    private static JsonObject memento(ResearchStateMemento memento) {
        JsonObject json = new JsonObject();
        json.addProperty("phaseId", memento.getPhaseId());
        json.addProperty("stateId", memento.getStateId());
        json.addProperty("continuationStateId", memento.getContinuationStateId());
        json.addProperty("revision", memento.getRevision());
        json.addProperty("pendingApprovalId", memento.getPendingApprovalId());
        return json;
    }

    private static ResearchStateMemento memento(JsonObject json) {
        return new ResearchStateMemento(string(json, "phaseId"), string(json, "stateId"),
                nullableString(json, "continuationStateId"), json.get("revision").getAsLong(),
                nullableString(json, "pendingApprovalId"));
    }

    private static String string(JsonObject json, String key) {
        String value = nullableString(json, key);
        return value == null ? "" : value;
    }

    private static String nullableString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    @Override
    public String toString() {
        return id + " " + command + " " + from.getPhaseId() + "/" + from.getStateId() + " -> "
                + to.getPhaseId() + "/" + to.getStateId() + " by " + actor + "/" + surface;
    }
}

package com.aresstack.askai.research.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Component 7, record "Entscheidung der Leitung" of the shared data model: what the research lead (component 8)
 * decided in a round, about what, why, and on which counts. Every decision can be read back. Immutable.
 * <p>
 * Field names of the data model: Runde, Ziel (Frage, Thema oder Lauf), Handlung, Grund, Zählwerte. Added, as for
 * every stored record: Modell, Zeit.
 */
public final class LeadDecision {

    /** What the decision is about. */
    public enum Target { FRAGE, THEMA, LAUF }

    /** Handlung. "Beenden" never means "everything answered". */
    public enum Action { WEITER_VERFOLGEN, STRATEGIE_WECHSELN, NEUE_FRAGEN, ZURUECKSTELLEN, BEENDEN }

    private final int round;
    private final Target target;
    private final String targetId;
    private final Action action;
    private final String reason;
    private final Map<String, Integer> counts;
    private final String model;
    private final long timeMillis;

    /**
     * @param targetId the question or topic id, {@code ""} for the whole run
     * @param counts   the counts the decision rests on, by name ({@code direkt}, {@code hinweise}, ...)
     * @param model    the model that decided, {@code ""} when a guardrail rule did
     */
    public LeadDecision(int round, Target target, String targetId, Action action, String reason,
                        Map<String, Integer> counts, String model, long timeMillis) {
        this.round = Math.max(1, round);
        this.target = target == null ? Target.LAUF : target;
        this.targetId = Texts.clean(targetId);
        this.action = action;
        this.reason = Texts.clean(reason);
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(
                counts == null ? Collections.<String, Integer>emptyMap() : counts));
        this.model = Texts.clean(model);
        this.timeMillis = timeMillis;
        if (action == null) {
            throw new IllegalArgumentException("a decision needs an action");
        }
        if (this.target != Target.LAUF && this.targetId.isEmpty()) {
            throw new IllegalArgumentException("a decision about a " + this.target + " needs its id");
        }
    }

    public int getRound() {
        return round;
    }

    public Target getTarget() {
        return target;
    }

    public String getTargetId() {
        return targetId;
    }

    public Action getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Integer> getCounts() {
        return counts;
    }

    public String getModel() {
        return model;
    }

    public long getTimeMillis() {
        return timeMillis;
    }
}

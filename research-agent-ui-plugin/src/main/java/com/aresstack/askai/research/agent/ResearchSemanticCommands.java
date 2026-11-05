package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE table of the semantic USER command vocabulary: semantic name → the concrete
 * {@link ResearchCommandType} candidates it resolves against (first allowed wins). Red action
 * tags, slash commands, the MCP {@code run_command} vocabulary, the State tab's phase clicks and
 * {@link ResearchAgentSession#executeCommand} all project from exactly this map — internal enum
 * names are never user API, and there is no second action matrix anywhere.
 */
final class ResearchSemanticCommands {

    private static final Map<String, List<ResearchCommandType>> CANDIDATES;

    static {
        Map<String, List<ResearchCommandType>> map =
                new LinkedHashMap<String, List<ResearchCommandType>>();
        map.put("submit-scope", Arrays.asList(ResearchCommandType.SUBMIT_SCOPE));
        // D1: every phase closes with a USER command that reaches its review gate.
        map.put("finish-sources", Arrays.asList(ResearchCommandType.REQUEST_EVIDENCE_REVIEW));
        map.put("finish-outline", Arrays.asList(ResearchCommandType.PROPOSE_OUTLINE));
        map.put("finish-document", Arrays.asList(ResearchCommandType.REQUEST_DRAFT_REVIEW));
        // APPROVE_DRAFT has no edge in the graph and is no user command.
        map.put("approve", Arrays.asList(ResearchCommandType.APPROVE_OUTLINE,
                ResearchCommandType.APPROVE_EVIDENCE, ResearchCommandType.APPROVE_FINAL));
        map.put("request-changes", Arrays.asList(ResearchCommandType.REQUEST_OUTLINE_CHANGES,
                ResearchCommandType.REQUEST_REVISION));
        map.put("continue", Arrays.asList(ResearchCommandType.START_RESEARCH,
                ResearchCommandType.START_DRAFTING));
        map.put("retry", Arrays.asList(ResearchCommandType.RETRY));
        map.put("resume", Arrays.asList(ResearchCommandType.RESUME, ResearchCommandType.UNBLOCK));
        // D1: no user surface dispatches PAUSE or CANCEL; the graph keeps both edges.
        // The way back (always possible): reopen an earlier phase; nothing is deleted.
        map.put("reopen-concept", Arrays.asList(ResearchCommandType.REOPEN_CONCEPT));
        map.put("reopen-sources", Arrays.asList(ResearchCommandType.REOPEN_SOURCES));
        map.put("reopen-outline", Arrays.asList(ResearchCommandType.REOPEN_OUTLINE));
        CANDIDATES = Collections.unmodifiableMap(map);
    }

    private ResearchSemanticCommands() {
    }

    /** All semantic command names, in stable declaration order. */
    static List<String> names() {
        return new ArrayList<String>(CANDIDATES.keySet());
    }

    /** Whether a name is a semantic command (each one dispatches a state command). */
    static boolean isSemantic(String name) {
        return name != null && CANDIDATES.containsKey(name);
    }

    /** The concrete candidates for a semantic name, or an empty list for an unknown name. */
    static List<ResearchCommandType> candidates(String name) {
        List<ResearchCommandType> candidates = CANDIDATES.get(name);
        return candidates == null ? Collections.<ResearchCommandType>emptyList() : candidates;
    }

    /** The semantic USER name a concrete command belongs to, or {@code null} for agent-internal ones. */
    static String semanticNameFor(ResearchCommandType type) {
        for (Map.Entry<String, List<ResearchCommandType>> entry : CANDIDATES.entrySet()) {
            if (entry.getValue().contains(type)) {
                return entry.getKey();
            }
        }
        return null;
    }
}

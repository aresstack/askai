package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the model context for ONE turn from the active phase's {@link PhaseAssistantProfile}: the phase's
 * own system prompt, the per-turn research-context system message, and the running history. This is the single
 * place where "active phase → its assistant context" is realized, and the seam where RA-P6 §10/§11 will later
 * filter to this phase's own chat plus the latest relevant artifacts (per the profile's
 * {@link PhaseContextPolicy}). For now history is passed through unchanged; the policy is carried, not enforced.
 */
public final class PhaseContextAssembler {

    /** Supplies the current date ({@code YYYY-MM-DD}) as runtime context — never inferred from model memory. */
    public interface CurrentDate {
        String today();
    }

    /**
     * Supplies the session's CURRENT working-language display name ("English"/"German") per turn — read at
     * assemble time, so a live {@code set_language} applies to the NEXT turn without touching history.
     */
    public interface CurrentLanguage {
        String displayName();
    }

    /** Supplies the rendered scope fence for the NEXT turn — read at assemble time, like the language. */
    public interface CurrentScope {
        String rendered();
    }

    private static final CurrentScope EMPTY_SCOPE = new CurrentScope() {
        public String rendered() {
            return "";
        }
    };

    /**
     * Supplies the AUTHORITATIVE persisted concept block for the NEXT turn (K2e): with the
     * concept tools active, EVERY inference must see the real workpiece BEFORE it answers —
     * a type=none turn once claimed an "Arduino focus" that existed nowhere but in the chat.
     * Empty = no block (no tools / old host / fetch failed).
     */
    public interface CurrentConcept {
        String rendered();
    }

    private static final CurrentConcept EMPTY_CONCEPT = new CurrentConcept() {
        public String rendered() {
            return "";
        }
    };

    private static final CurrentLanguage ENGLISH_DEFAULT = new CurrentLanguage() {
        public String displayName() {
            return "English";
        }
    };

    private final CurrentDate currentDate;
    private final CurrentLanguage currentLanguage;
    /** Supplies the host's AUTHORITATIVE scope projection per turn; "" when the host sent none. */
    private volatile CurrentScope currentScope = EMPTY_SCOPE;
    private volatile CurrentConcept currentConcept = EMPTY_CONCEPT;

    public PhaseContextAssembler() {
        this(systemDate(), ENGLISH_DEFAULT);
    }

    /** Inject a fixed date (tests / deterministic runs). */
    public PhaseContextAssembler(CurrentDate currentDate) {
        this(currentDate, ENGLISH_DEFAULT);
    }

    /** System clock + the session's live working language (the production wiring). */
    public PhaseContextAssembler(CurrentLanguage currentLanguage) {
        this(systemDate(), currentLanguage);
    }

    /** The scope projection source; without it a turn simply carries no scope context. */
    public PhaseContextAssembler withCurrentScope(CurrentScope scope) {
        this.currentScope = scope == null ? EMPTY_SCOPE : scope;
        return this;
    }

    /** The persisted-concept source; without it a turn simply carries no concept context. */
    public PhaseContextAssembler withCurrentConcept(CurrentConcept concept) {
        this.currentConcept = concept == null ? EMPTY_CONCEPT : concept;
        return this;
    }

    public PhaseContextAssembler(CurrentDate currentDate, CurrentLanguage currentLanguage) {
        this.currentDate = currentDate;
        this.currentLanguage = currentLanguage == null ? ENGLISH_DEFAULT : currentLanguage;
    }

    /** The live working language's display name — the greeting bootstrap names it explicitly. */
    public String workingLanguageDisplayName() {
        return currentLanguage.displayName();
    }

    private static CurrentDate systemDate() {
        return new CurrentDate() {
            public String today() {
                return java.time.LocalDate.now().toString();
            }
        };
    }

    /**
     * system(phase prompt) + system(current date) + system(current working language) + system(live research
     * context) + the running history. The
     * profile decides the FIRST system message, so a different active phase yields a different assistant
     * context. The current date is supplied from the host clock so the model never invents a year in, e.g.,
     * search queries.
     */
    public List<ChatMessage> assemble(PhaseAssistantProfile profile, TeamAgentStateView state,
                                      String confirmedQuestion, List<String> confirmedAspects,
                                      String proposedQuestion, List<String> proposedAspects,
                                      List<ChatMessage> history) {
        return assemble(profile, state, confirmedQuestion, confirmedAspects, proposedQuestion, proposedAspects,
                history, new PromptBlockMeter());
    }

    /** As above, and counts every block it adds into {@code meter} (plan PR 01); the messages are the same. */
    public List<ChatMessage> assemble(PhaseAssistantProfile profile, TeamAgentStateView state,
                                      String confirmedQuestion, List<String> confirmedAspects,
                                      String proposedQuestion, List<String> proposedAspects,
                                      List<ChatMessage> history, PromptBlockMeter meter) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        add(messages, meter, PromptBlockMeter.PROFILE, ChatMessage.system(profile.getSystemPrompt()));
        add(messages, meter, PromptBlockMeter.DATE, ChatMessage.system("Current date: " + currentDate.today()));
        add(messages, meter, PromptBlockMeter.LANGUAGE, ChatMessage.system(
                TeamAgentPlaybook.workingLanguageContext(currentLanguage.displayName())));
        add(messages, meter, PromptBlockMeter.STATE, ChatMessage.system(TeamAgentPlaybook.stateContext(
                state, confirmedQuestion, confirmedAspects, proposedQuestion, proposedAspects)));
        // The scope the HOST holds — authoritative for this turn. Without it the model would rebuild the
        // scope from the conversation, which is how earlier decisions quietly disappear.
        String scope = currentScope.rendered();
        if (!scope.isEmpty()) {
            add(messages, meter, PromptBlockMeter.FENCE,
                    ChatMessage.system(TeamAgentPlaybook.scopeFenceContext(scope)));
        }
        // The persisted CONCEPT — authoritative for this turn, exactly like the scope fence:
        // without it a type=none inference can only reconstruct the concept from the chat,
        // which is how unpersisted "focus" claims are born.
        String concept = currentConcept.rendered();
        if (!concept.isEmpty()) {
            add(messages, meter, PromptBlockMeter.CONCEPT, ChatMessage.system(concept));
        }
        if (history != null) {
            messages.addAll(history);
            meter.add(PromptBlockMeter.HISTORY, history);
        }
        return messages;
    }

    private static void add(List<ChatMessage> messages, PromptBlockMeter meter, String block, ChatMessage message) {
        messages.add(message);
        meter.add(block, message);
    }
}

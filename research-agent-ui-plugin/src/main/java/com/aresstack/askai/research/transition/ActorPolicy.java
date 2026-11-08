package com.aresstack.askai.research.transition;

import com.aresstack.askai.research.state.CommandActor;
import com.aresstack.askai.research.state.ResearchCommandType;

/**
 * The pure actor policy of D2 and Q13. Every {@link ResearchCommandType} is a state command, so only the
 * {@link CommandActor#USER} may send one: a bot is refused with "Phase changes need the user", and the host
 * dispatches no state command of its own. Commands that dispatch no state command (service commands, run
 * offers such as {@code run-plan}) never reach the state machine and are not decided here.
 */
public final class ActorPolicy {

    public static final String BOT_REFUSAL = "Phase changes need the user";
    public static final String HOST_REFUSAL = "The host dispatches no state command";

    private ActorPolicy() {
    }

    /** @return {@code null} when the actor may send the command, otherwise the refusal shown to the sender. */
    public static String refusal(ResearchCommandType command, CommandActor actor) {
        if (command == null || actor == null) {
            return "No command or actor given";
        }
        switch (actor) {
            case USER:
                return null;
            case BOT:
                return BOT_REFUSAL;
            default:
                return HOST_REFUSAL;
        }
    }
}

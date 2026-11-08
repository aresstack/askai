package com.aresstack.askai.research.state;

/** Who sent a command (D2). Only {@link #USER} may change the workflow state (Q13). */
public enum CommandActor {
    /** The person in front of the app: tags, slash commands, plates, pill and the Workflow tab. */
    USER,
    /** A connected bot: the per-session bot control or the app-wide ChatGPT connector. */
    BOT,
    /** The host itself. It dispatches no state command of its own; the actor policy refuses them all. */
    HOST
}

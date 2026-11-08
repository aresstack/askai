package com.aresstack.askai.research.state;

/** The surface a command came through (D2); the transition journal records it next to the actor. */
public enum CommandSurface {
    /** A user surface of the app: action tags, slash commands, plates, the phase pill, the Workflow tab. */
    USER_INTERFACE,
    /** The per-session bot control endpoint. */
    BOT_CONTROL,
    /** The app-wide ChatGPT connector, reaching the session through the session directory. */
    CHATGPT_CONNECTOR,
    /** The host process itself. */
    HOST
}

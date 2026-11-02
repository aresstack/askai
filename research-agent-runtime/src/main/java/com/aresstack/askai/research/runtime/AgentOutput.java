package com.aresstack.askai.research.runtime;

/**
 * Where the research agent writes what it has to say during one turn (askai #50): a plain method call into the
 * research plugin inside the AskAI JVM. It replaces the ACP prompt context of the former agent process.
 */
public interface AgentOutput {

    /** One agent message (wire lines and readable text alike). */
    void sendMessage(String text);
}

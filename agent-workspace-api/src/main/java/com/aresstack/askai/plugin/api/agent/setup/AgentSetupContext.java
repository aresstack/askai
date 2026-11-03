package com.aresstack.askai.plugin.api.agent.setup;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

/** What the host hands a setup page. */
public interface AgentSetupContext {

    /** The host-wide state store (the same one the agent's sessions read host-wide settings from). */
    WorkspaceStateStore getHostStateStore();
}

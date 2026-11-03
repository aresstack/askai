package com.aresstack.askai.plugin.api.agent.setup;

import javax.swing.JComponent;

/** One contributed section of the setup wizard. Called on the EDT. */
public interface AgentSetupPage {

    /** The controls shown in the wizard; transparent backgrounds fit the wizard's look. */
    JComponent getComponent();

    /** Stores the entries; called when the wizard saves the setup (also after a skipped step). */
    void save();

    /** Stops work the page started (polling, downloads keep running in the background); idempotent. */
    default void dispose() {
    }
}

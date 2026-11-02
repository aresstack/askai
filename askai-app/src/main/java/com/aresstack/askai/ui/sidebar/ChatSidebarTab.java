package com.aresstack.askai.ui.sidebar;

import javax.swing.JComponent;

/**
 * One tab in the chat sidebar (drawer). The app itself contributes the default "Chats" tab; this
 * interface is the EXTENSION POINT through which plugins (e.g. Research) will hang further tabs into
 * the drawer later — they only need a title and a lazily built component, mirroring the
 * {@code AgentSettingsContribution} pattern.
 */
public interface ChatSidebarTab {

    /** The tab title shown in the drawer. */
    String getTitle();

    /** The tab content; called on the EDT when the drawer (re)builds its tabs. */
    JComponent getComponent();
}

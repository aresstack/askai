package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.ChatSessionLauncher;

import javax.swing.SwingUtilities;

/**
 * The host implementation of {@link ChatSessionLauncher}. Creating a chat and activating its agent is pure
 * Swing work, so it is marshalled onto the EDT and the caller (typically an MCP worker) waits for the
 * result — the port promises that the chat exists when the call returns.
 */
public final class LocalChatSessionLauncher implements ChatSessionLauncher {

    /** The actual creation, always executed ON the EDT; supplied by the frame that owns the workspace. */
    public interface EdtChatCreator {
        /** @return the new chat session id, or "" when no chat could be created. */
        String create(String agentId, String title);
    }

    private final EdtChatCreator creator;

    public LocalChatSessionLauncher(EdtChatCreator creator) {
        this.creator = creator;
    }

    @Override
    public String createChatSession(final String agentId, final String title) {
        if (creator == null) {
            return "";
        }
        if (SwingUtilities.isEventDispatchThread()) {
            return safeCreate(agentId, title);
        }
        final String[] created = {""};
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    created[0] = safeCreate(agentId, title);
                }
            });
        } catch (Exception interruptedOrFailed) {
            Thread.currentThread().interrupt();
            System.err.println("[chat-launcher] could not create a chat: " + interruptedOrFailed);
            return "";
        }
        return created[0];
    }

    /** A failing agent start must come back as "no chat" rather than as an exception on the EDT. */
    private String safeCreate(String agentId, String title) {
        try {
            String id = creator.create(agentId, title);
            return id == null ? "" : id;
        } catch (RuntimeException failed) {
            System.err.println("[chat-launcher] chat creation failed: " + failed);
            return "";
        }
    }
}

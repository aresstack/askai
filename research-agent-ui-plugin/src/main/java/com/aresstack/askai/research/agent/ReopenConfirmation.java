package com.aresstack.askai.research.agent;

import javax.swing.JOptionPane;
import java.awt.Component;

/**
 * The reopen confirmation of D10, shared by every surface that sends a reopen (phase pill, plates and,
 * later, the frozen banner and the Workflow tab). Cancel dispatches nothing.
 */
public final class ReopenConfirmation {

    /** Swappable for tests: answers whether the user confirmed the question. */
    public interface Asker {
        boolean confirm(Component parent, String title, String message);
    }

    private static final Asker DIALOG = new Asker() {
        public boolean confirm(Component parent, String title, String message) {
            return JOptionPane.showConfirmDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
        }
    };

    private static volatile Asker asker = DIALOG;

    private ReopenConfirmation() {
    }

    static void setAsker(Asker replacement) {
        asker = replacement == null ? DIALOG : replacement;
    }

    /** The question for a semantic reopen command, or {@code null} when the command is no reopen. */
    public static String messageFor(String semantic) {
        if ("reopen-concept".equals(semantic)) {
            return "Sources, Outline and Document may become stale. The running search stops. "
                    + "Nothing is deleted.";
        }
        if ("reopen-sources".equals(semantic)) {
            return "Outline and Document may become stale. Running jobs stop. Nothing is deleted.";
        }
        if ("reopen-outline".equals(semantic)) {
            return "Document may become stale. Running writing stops. Nothing is deleted.";
        }
        return null;
    }

    /** @return {@code true} when {@code semantic} is no reopen or the user confirmed it. */
    public static boolean confirm(Component parent, String semantic) {
        String message = messageFor(semantic);
        if (message == null) {
            return true;
        }
        String phase = semantic.substring("reopen-".length());
        String title = "Reopen " + Character.toUpperCase(phase.charAt(0)) + phase.substring(1) + "?";
        return asker.confirm(parent, title, message);
    }
}

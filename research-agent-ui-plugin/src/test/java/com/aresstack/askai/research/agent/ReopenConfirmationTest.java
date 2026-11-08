package com.aresstack.askai.research.agent;

import org.junit.After;
import org.junit.Test;

import java.awt.Component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReopenConfirmationTest {

    private final String[] asked = new String[2];

    @After
    public void restore() {
        ReopenConfirmation.setAsker(null);
    }

    private void answer(final boolean ok) {
        ReopenConfirmation.setAsker(new ReopenConfirmation.Asker() {
            public boolean confirm(Component parent, String title, String message) {
                asked[0] = title;
                asked[1] = message;
                return ok;
            }
        });
    }

    @Test
    public void aReopenAsksFirstAndCancelDispatchesNothing() {
        answer(false);
        assertFalse(ReopenConfirmation.confirm(null, "reopen-concept"));
        assertEquals("Reopen Concept?", asked[0]);
        assertTrue(asked[1].contains("Nothing is deleted."));
        assertTrue(asked[1].contains("The running search stops."));
        answer(true);
        assertTrue(ReopenConfirmation.confirm(null, "reopen-concept"));
    }

    @Test
    public void otherCommandsAskNothing() {
        answer(false);
        assertTrue(ReopenConfirmation.confirm(null, "finish-sources"));
        assertNull(asked[0]);
        assertNull(ReopenConfirmation.messageFor("submit-scope"));
    }
}

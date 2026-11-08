package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConceptPaperViewFreezeTest {

    @Test
    public void aModeMessageMakesMissionAndConceptReadOnlyAndShowsWhy() {
        ConceptPaperView view = new ConceptPaperView();
        view.renderMission("Plugin isolation", "");
        assertFalse(view.isFrozen());
        assertEquals("", view.modeText());

        view.renderMission("Plugin isolation", "Frozen at revision 1. Reopen Concept to change it.");
        assertTrue(view.isFrozen());
        assertFalse("the raw JSON editor is read-only too", view.isJsonEditable());
        assertEquals("Frozen at revision 1. Reopen Concept to change it.", view.modeText());
        assertEquals("Plugin isolation", view.missionText());

        view.renderMission("Plugin isolation", "");
        assertFalse(view.isFrozen());
    }
}

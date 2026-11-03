package com.aresstack.askai.ui.setup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Witch knows what to say in every step of the wizard. */
public class WizardGuideTest {

    private final WizardGuide guide = new WizardGuide();

    @Test
    public void everyStepHasItsHintsAndAFrameForFreeQuestions() {
        assertEquals(SetupWizardPanel.STEP_NAMES.length, WizardGuide.steps());
        for (int step = 0; step < WizardGuide.steps(); step++) {
            assertNotNull(guide.whatToDo(step));
            assertNotNull(guide.whyNeeded(step));
            assertTrue(guide.systemPrompt(step).contains(guide.whatToDo(step)));
        }
        assertNull("before a runtime answers Witch is silent anyway", guide.enteredStep(0));
        assertNull(guide.whatToDo(WizardGuide.steps()));
    }

    @Test
    public void heGreetsOnceAndAsAReunionLater() {
        assertEquals(WizardGuide.FIRST_GREETING, guide.greeting(false));
        assertEquals(WizardGuide.WELCOME_BACK, guide.greeting(true));
    }
}

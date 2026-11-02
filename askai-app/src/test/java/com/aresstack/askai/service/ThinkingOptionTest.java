package com.aresstack.askai.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The typed thinking option maps cleanly to the wire {@code think} value. */
public class ThinkingOptionTest {

    @Test
    public void defaultOmitsTheField() {
        assertNull(ThinkingOption.defaultOption().toWireValue());
        assertNull(ThinkingOption.of(ThinkingOption.Mode.DEFAULT).toWireValue());
    }

    @Test
    public void booleanModes() {
        assertEquals(Boolean.TRUE, ThinkingOption.of(ThinkingOption.Mode.ENABLED).toWireValue());
        assertEquals(Boolean.FALSE, ThinkingOption.of(ThinkingOption.Mode.DISABLED).toWireValue());
    }

    @Test
    public void levelModes() {
        assertEquals("low", ThinkingOption.of(ThinkingOption.Mode.LOW).toWireValue());
        assertEquals("medium", ThinkingOption.of(ThinkingOption.Mode.MEDIUM).toWireValue());
        assertEquals("high", ThinkingOption.of(ThinkingOption.Mode.HIGH).toWireValue());
        assertEquals("max", ThinkingOption.of(ThinkingOption.Mode.MAX).toWireValue());
    }

    @Test
    public void ofLevelParsesUiStrings() {
        assertEquals(ThinkingOption.Mode.HIGH, ThinkingOption.ofLevel("High").getMode());
        assertEquals(ThinkingOption.Mode.MEDIUM, ThinkingOption.ofLevel("medium").getMode());
        assertEquals(ThinkingOption.Mode.DEFAULT, ThinkingOption.ofLevel("off").getMode());
        assertEquals(ThinkingOption.Mode.DEFAULT, ThinkingOption.ofLevel(null).getMode());
    }
}

package com.aresstack.askai.ui;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** The settings dropdown options: NONE first (keep "no selection"), only installed names, preserve a stray current. */
public class AiModelsPanelOptionsTest {

    @Test
    public void offersNoneFirstThenOnlyInstalledModels() {
        List<String> options = AiModelsPanel.optionsFor("", Arrays.asList("apache/de", "apache/en"));
        assertEquals(Arrays.asList("", "apache/de", "apache/en"), options);
    }

    @Test
    public void keepingNoSelectionStaysPossible() {
        assertEquals(Collections.singletonList(""),
                AiModelsPanel.optionsFor(null, Collections.<String>emptyList()));
    }

    @Test
    public void aStillSelectedButUninstalledModelIsPreservedNotLost() {
        List<String> options = AiModelsPanel.optionsFor("apache/removed", Arrays.asList("apache/de"));
        assertEquals(Arrays.asList("", "apache/de", "apache/removed"), options);
    }

    @Test
    public void anInstalledCurrentSelectionIsNotDuplicated() {
        List<String> options = AiModelsPanel.optionsFor("apache/de", Arrays.asList("apache/de", "apache/en"));
        assertEquals(Arrays.asList("", "apache/de", "apache/en"), options);
    }
}

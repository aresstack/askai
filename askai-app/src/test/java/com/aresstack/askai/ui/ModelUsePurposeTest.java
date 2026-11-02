package com.aresstack.askai.ui;

import com.aresstack.askai.config.AiModelSelections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** One model per purpose: assigning replaces the previous holder, un-assigning clears only the own slot. */
public class ModelUsePurposeTest {

    @Test
    public void assigningReplacesPreviousModel() {
        AiModelSelections selections = new AiModelSelections("a", "", "e1");
        AiModelSelections next = ModelUsePurpose.EMBEDDINGS.apply(selections, "e2", true);
        assertEquals("e2", next.getEmbeddingsModel());
        assertTrue(ModelUsePurpose.EMBEDDINGS.isSelected(next, "e2"));
        assertFalse(ModelUsePurpose.EMBEDDINGS.isSelected(next, "e1"));
        assertEquals("a", next.getMainModel());
    }

    @Test
    public void unassigningClearsOnlyOwnSlot() {
        AiModelSelections selections = new AiModelSelections("a", "r", "e");
        assertEquals("", ModelUsePurpose.CHAT.apply(selections, "a", false).getMainModel());
        assertEquals("a", ModelUsePurpose.CHAT.apply(selections, "other", false).getMainModel());
        assertEquals("", ModelUsePurpose.RERANKER.apply(selections, "r", false).getRerankerModel());
    }
}

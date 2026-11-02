package com.aresstack.askai.ui;

import com.aresstack.askai.client.OllamaModelDetails;
import com.aresstack.askai.client.OllamaModelInfo;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The installed-model card shows every /api/show capability, not just input modalities. */
public class OllamaModelCardTest {

    private OllamaModelCard cardWithCapabilities(final List<String> tags) throws Exception {
        final AtomicReference<OllamaModelCard> ref = new AtomicReference<OllamaModelCard>();
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                OllamaModelInfo model = new OllamaModelInfo("m", "m", "", 0L, "", OllamaModelDetails.empty());
                OllamaModelCard card = OllamaModelCard.installed(model,
                        new Runnable() {
                            public void run() { // search add-ons on Hugging Face
                            }
                        },
                        new Runnable() {
                            public void run() { // select local projector file
                            }
                        }, null, new Runnable() {
                            public void run() { // delete
                            }
                        });
                card.setCapabilities(tags);
                ref.set(card);
            }
        });
        return ref.get();
    }

    @Test
    public void completionToolsThinkingAreAllShown() throws Exception {
        Set<ModelCapability> shown = cardWithCapabilities(
                Arrays.asList("completion", "tools", "thinking")).shownCapabilities();
        assertTrue(shown.contains(ModelCapability.TEXT));
        assertTrue(shown.contains(ModelCapability.TOOLS));
        assertTrue(shown.contains(ModelCapability.THINKING));
    }

    @Test
    public void embeddingIsShown() throws Exception {
        assertTrue(cardWithCapabilities(Collections.singletonList("embedding"))
                .shownCapabilities().contains(ModelCapability.EMBEDDING));
    }

    @Test
    public void inputModalitiesStillShown() throws Exception {
        Set<ModelCapability> shown = cardWithCapabilities(
                Arrays.asList("completion", "vision", "audio")).shownCapabilities();
        assertTrue(shown.contains(ModelCapability.TEXT));
        assertTrue(shown.contains(ModelCapability.VISION));
        assertTrue(shown.contains(ModelCapability.AUDIO));
    }

    @Test
    public void unknownTagsAreIgnored() throws Exception {
        Set<ModelCapability> shown = cardWithCapabilities(
                Arrays.asList("completion", "totally-unknown", "")).shownCapabilities();
        assertEquals(1, shown.size());
        assertTrue(shown.contains(ModelCapability.TEXT));
    }
}

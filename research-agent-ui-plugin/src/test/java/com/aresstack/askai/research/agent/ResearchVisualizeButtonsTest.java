package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Regression: "Visualize sources" lives in the source detail row, where the selected source's
 * full text covers the chat. The mindmap overlay must hand the main area back to the chat FIRST,
 * otherwise it only appears after switching to the Concept pane.
 */
public class ResearchVisualizeButtonsTest {

    @Test
    public void theSourcesMindmapClearsTheMainAreaBeforeOpening() {
        List<String> calls = new ArrayList<String>();
        ResearchVisualizeButtons.sourcesOverlay(recording(calls)).showDiagram("mindmap", "Sources Mindmap");
        assertEquals(Arrays.asList("clearMainContent", "showDiagramOverlay"), calls);
    }

    @Test
    public void theSourcesHintAlsoClearsTheMainAreaFirst() {
        List<String> calls = new ArrayList<String>();
        ResearchVisualizeButtons.sourcesOverlay(recording(calls)).showHint(new JLabel("x"), "t");
        assertEquals(Arrays.asList("clearMainContent", "showTranscriptOverlay"), calls);
    }

    private static ArtifactViewContext recording(final List<String> calls) {
        return (ArtifactViewContext) Proxy.newProxyInstance(
                ArtifactViewContext.class.getClassLoader(), new Class<?>[] {ArtifactViewContext.class},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        calls.add(method.getName());
                        return null;
                    }
                });
    }
}

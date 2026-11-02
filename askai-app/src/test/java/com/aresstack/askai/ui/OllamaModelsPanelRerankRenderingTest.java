package com.aresstack.askai.ui;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * The reranker test-dialog rendering: results come out ordered exactly as the local runtime
 * returned them (best first), each line carries the raw score and the matching document, and a
 * server-side error is surfaced instead of pretending results.
 */
public class OllamaModelsPanelRerankRenderingTest {

    @Test
    public void rendersOrderedScoresWithTheirDocuments() {
        List<String> documents = Arrays.asList(
                "DirectML is a Windows API for hardware-accelerated machine learning.",
                "Paris is the capital of France.",
                "Shoes are available in many sizes.");
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("results", Arrays.asList(
                result(0, 8.42), result(1, -4.15), result(2, -5.99)));

        String rendered = OllamaModelsPanel.renderRerankResponse(response, documents);
        String[] lines = rendered.trim().split("\n");
        assertTrue(lines[0].startsWith("1. score=8"));
        assertTrue("the relevant document must be on top: " + lines[0],
                lines[0].contains("DirectML"));
        assertTrue(lines[1].contains("Paris"));
        assertTrue(lines[2].contains("Shoes"));
    }

    @Test
    public void surfacesServerErrorsInsteadOfPretendingResults() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("error", "model 'x' does not support rerank");
        String rendered = OllamaModelsPanel.renderRerankResponse(response,
                Arrays.asList("a"));
        assertTrue(rendered.contains("does not support rerank"));
    }

    private static Map<String, Object> result(int index, double score) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("index", index);
        map.put("score", score);
        return map;
    }
}

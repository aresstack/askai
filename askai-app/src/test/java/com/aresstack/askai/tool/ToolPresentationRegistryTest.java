package com.aresstack.askai.tool;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Safe, deterministic tool presentation with argument redaction. */
public class ToolPresentationRegistryTest {

    @Test
    public void unknownToolGetsASafeGenericPresentationWithoutArguments() {
        ToolPresentation presentation = new ToolPresentationRegistry().presentationFor("open_page");
        assertEquals("open page", presentation.getDisplayName());
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("url", "https://example.com/secret-path");
        String purpose = presentation.describePurpose(args);
        // The generic presentation never echoes argument values.
        assertFalse(purpose, purpose.contains("example.com"));
        assertTrue(purpose, purpose.contains("open page"));
    }

    @Test
    public void sanitizeArgumentsDropsSensitiveKeys() {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("query", "gemma");
        args.put("api_key", "sk-123");
        args.put("Authorization", "Bearer x");
        args.put("session_cookie", "abc");
        Map<String, Object> safe = ToolPresentationRegistry.sanitizeArguments(args);
        assertTrue(safe.containsKey("query"));
        assertFalse(safe.containsKey("api_key"));
        assertFalse(safe.containsKey("Authorization"));
        assertFalse(safe.containsKey("session_cookie"));
    }

    @Test
    public void registeredPresentationWins() {
        ToolPresentationRegistry registry = new ToolPresentationRegistry();
        registry.register("browser", new ToolPresentation() {
            public String getDisplayName() {
                return "Open manufacturer page";
            }

            public String describePurpose(Map<String, Object> safeArguments) {
                return "Confirm the technical data from the official source.";
            }

            public String summarizeResult(ToolExecutionResult result) {
                return result.isSuccess() ? "Verified" : "Unreachable";
            }
        });
        ToolPresentation presentation = registry.presentationFor("browser");
        assertEquals("Open manufacturer page", presentation.getDisplayName());
        assertEquals("Verified", presentation.summarizeResult(ToolExecutionResult.success("x")));
    }
}

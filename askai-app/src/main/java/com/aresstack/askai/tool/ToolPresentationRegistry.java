package com.aresstack.askai.tool;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a tool name to a safe {@link ToolPresentation}. Unregistered tools fall back to a deterministic,
 * generic presentation that never echoes arguments. Arguments handed to a presentation are pre-sanitized:
 * obviously sensitive keys (token, password, secret, cookie, authorization, ...) are dropped entirely.
 */
public final class ToolPresentationRegistry {

    private static final List<String> SENSITIVE_KEY_MARKERS = Arrays.asList(
            "token", "password", "passwd", "secret", "apikey", "api_key", "key", "cookie",
            "authorization", "auth", "credential", "session", "bearer");

    private final Map<String, ToolPresentation> presentations = new LinkedHashMap<String, ToolPresentation>();

    public void register(String toolName, ToolPresentation presentation) {
        if (toolName == null || toolName.trim().isEmpty() || presentation == null) {
            throw new IllegalArgumentException("toolName and presentation are required");
        }
        presentations.put(toolName.trim().toLowerCase(Locale.ROOT), presentation);
    }

    /** @return the registered presentation for {@code toolName}, or a safe generic default. */
    public ToolPresentation presentationFor(String toolName) {
        String key = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        ToolPresentation registered = presentations.get(key);
        return registered != null ? registered : new DefaultToolPresentation(toolName);
    }

    /**
     * @return a copy of {@code arguments} with sensitive-looking keys removed. This is a safety net; a
     *         presentation should still avoid echoing argument values verbatim.
     */
    public static Map<String, Object> sanitizeArguments(Map<String, Object> arguments) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>();
        if (arguments == null) {
            return safe;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!isSensitive(entry.getKey())) {
                safe.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(safe);
    }

    private static boolean isSensitive(String key) {
        if (key == null) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (int i = 0; i < SENSITIVE_KEY_MARKERS.size(); i++) {
            if (lower.contains(SENSITIVE_KEY_MARKERS.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The generic fallback: a humanized tool name and a neutral rationale that never includes the
     * arguments, so an unknown tool can never leak its payload into the UI.
     */
    static final class DefaultToolPresentation implements ToolPresentation {
        private final String toolName;

        DefaultToolPresentation(String toolName) {
            this.toolName = toolName == null || toolName.trim().isEmpty() ? "tool" : toolName.trim();
        }

        public String getDisplayName() {
            return humanize(toolName);
        }

        public String describePurpose(Map<String, Object> safeArguments) {
            return "Running the " + humanize(toolName) + " tool.";
        }

        public String summarizeResult(ToolExecutionResult result) {
            if (result == null) {
                return "Done";
            }
            return result.isSuccess() ? "Done" : "Failed";
        }

        private static String humanize(String name) {
            String cleaned = name.replace('_', ' ').replace('-', ' ').trim();
            return cleaned.isEmpty() ? "tool" : cleaned;
        }
    }
}

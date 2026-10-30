package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the captured content of accepted sources for the verifier and the writer: one read-only
 * {@code source_review_context} call scoped to exactly the given source ids. Nothing is fetched: the content is
 * what the research funnel captured when it accepted the source (ADR 0001, driver 2: one acquisition path).
 * The bounds per source are the user's review-context settings, applied by the host.
 */
public final class SourceMaterial {

    public static final String TOOL = "source_review_context";

    private static final String NO_TEXT = "(no readable text was captured for this source)";

    private SourceMaterial() {
    }

    /**
     * @return the rendered block per source id (title, URL, query, text) for every id the host served; a
     *         source the host does not serve (parked, excluded, unknown) is absent
     */
    public static Map<String, String> read(ToolInvoker research, List<String> sourceIds)
            throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("source_ids", String.join(",", sourceIds));
        return parse(research.call(TOOL, args));
    }

    /** Splits the tool's answer into its {@code --- <id>} blocks. */
    static Map<String, String> parse(String answer) {
        Map<String, String> blocks = new LinkedHashMap<String, String>();
        if (answer == null) {
            return blocks;
        }
        String id = null;
        StringBuilder block = new StringBuilder();
        for (String line : answer.split("\n", -1)) {
            if (line.startsWith("--- ")) {
                if (id != null) {
                    blocks.put(id, block.toString().trim());
                }
                id = line.substring(4).trim();
                block.setLength(0);
            } else if (id != null && !line.matches("\\(\\d+ further sources not shown.*")) {
                block.append(line).append('\n');
            }
        }
        if (id != null) {
            blocks.put(id, block.toString().trim());
        }
        return blocks;
    }

    /** True when the host captured no readable text for the block's source. */
    static boolean hasText(String block) {
        return block != null && block.contains("text: ") && !block.contains(NO_TEXT);
    }
}

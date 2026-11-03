package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import java.util.ArrayList;
import java.util.List;

/**
 * THE central mapping from the hierarchical research state to the offered MCP tools — there is no second
 * phase matrix anywhere else. Visibility comes from (phaseId, stateId); every write handler re-checks the SAME
 * rule against the live {@link ResearchControlContext} state at execution time (visibility ≠ authorization).
 *
 * <p>Always readable: {@code research_status}, {@code artifact_read(name)}, {@code source_list}. Write tools
 * are bound to phase AND run-state (never in waiting_approval/paused/blocked/failed/terminal):
 * RESEARCH/running → {@code source_accept}, {@code source_park}; DRAFT/running and FINALIZATION/running →
 * {@code document_read}, {@code document_save} — BOTH phases work on the ONE canonical document (issue #32).
 * The legacy per-stage tools ({@code concept_save}, {@code finding_add}, {@code notes_append},
 * {@code draft_*}, {@code final_*}) are gone: the ResearchBrief is the scoping truth and the removed legacy
 * artifacts must no longer be written by the active workflow. {@code outline_save} stays ONLY for persisted
 * old sessions still sitting in the legacy OUTLINE phase. There is deliberately NO phase-transition tool
 * (no advance_phase/approve_phase/set_state).</p>
 */
public final class ResearchToolPolicy {

    private ResearchToolPolicy() {
    }

    /** @return whether write tools of {@code phaseId} are usable in {@code stateId} (running only). */
    static boolean writable(String phaseId, String stateId, String requiredPhaseId) {
        return requiredPhaseId.equals(phaseId) && ResearchStateIds.RUNNING.equals(stateId);
    }

    /** Compute the offered tool set for the current state. */
    public static List<McpToolContribution> toolsFor(String phaseId, String stateId,
                                                     ResearchControlContext ctx) {
        List<McpToolContribution> tools = new ArrayList<McpToolContribution>();
        // Always readable.
        tools.add(statusTool(ctx));
        tools.add(artifactReadTool(ctx));
        tools.add(sourceListTool(ctx));
        tools.add(sourceReviewContextTool(ctx));
        // The Konzeptpapier tools (Fragestellung→Konzept rework). READING the concept is allowed in
        // every phase (frozen artifacts stay readable); WRITING is bite-wise branch editing and is
        // bound to SCOPING/running. Absent service (clickdummy/old fakes) → no concept tools at all.
        if (ctx.conceptBranchService() != null) {
            tools.add(conceptReadTool(ctx));
            if (writable(phaseId, stateId, ResearchStateIds.SCOPING)) {
                // add_cards slice: the ATOMIC list add is the model's capture instrument (the
                // live gate lost "Debugging" to the four-round budget of single adds).
                // concept_add stays registered for OLD runtimes only — the active contract
                // sends add_cards even for a single card.
                tools.add(conceptAddCardsTool(ctx));
                tools.add(conceptAddTool(ctx));
                // Safety slice after the slice-2 gate: NO destructive model tools. remove and
                // rewrite left the offer entirely — the only concept removal is the host-owned
                // conflict flow (exclude_topic -> user confirms -> the SESSION removes), and
                // deeper restructuring stays manual until the tree editor. An old runtime still
                // calling concept_remove/concept_rewrite gets an honest "not offered" error.
                tools.add(conceptRenameTool(ctx));
                // move_leaf slice: exactly ONE existing leaf changes its parent — UUID, label
                // and card data stay; the tool never creates targets, never moves branches.
                tools.add(conceptMoveLeafTool(ctx));
                // The ONE-command exclusion facade (live-gate 4): the model quotes the user's
                // term, the host owns id/facet/blacklist and the concept-conflict check.
                tools.add(excludeTopicTool(ctx));
                tools.add(resolveConflictTool(ctx));
                // The search-offer command (gate 8b): AI-authored orientation searches become
                // the user's yellow exploration tags.
                tools.add(offerSearchesTool(ctx));
                // scope_probe (AP3): the read-only closure sensor — SCOPING only, like every
                // scope command; it measures, it never edits.
                tools.add(scopeProbeTool(ctx));
            }
        }
        // Phase + run-state gated writes. SCOPING has NO document tool anymore: the ResearchBrief is the
        // canonical scoping artifact (issue #32) — no second concept document beside it.
        if (writable(phaseId, stateId, ResearchStateIds.OUTLINE)) {
            // Legacy operability only: persisted old sessions still sitting in the OUTLINE phase.
            tools.add(saveTool(ctx, "outline_save", "outline", ResearchStateIds.OUTLINE));
        }
        if (writable(phaseId, stateId, ResearchStateIds.RESEARCH)) {
            tools.add(sourceAcceptTool(ctx));
            tools.add(sourceParkTool(ctx));
        }
        // #43: DOCUMENT (id "draft") is the ONE writing phase — drafting, review and
        // finalization are activities inside it, so the document tools live here alone.
        if (writable(phaseId, stateId, ResearchStateIds.DRAFT)) {
            tools.add(readTool(ctx, "document_read", "document"));
            tools.add(saveTool(ctx, "document_save", "document", ResearchStateIds.DRAFT));
        }
        return tools;
    }

    // ------------------------------------------------------------------ handlers (server-side re-check)

    private static McpToolResult requireWritable(ResearchControlContext ctx, String requiredPhaseId) {
        if (!writable(ctx.currentPhaseId(), ctx.currentStateId(), requiredPhaseId)) {
            return McpToolResult.error("Not allowed in the current state ("
                    + ctx.currentPhaseId() + "/" + ctx.currentStateId() + ").");
        }
        return null;
    }

    private static McpToolContribution statusTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("research_status", "Current research phase, run state and revision.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok(ctx.statusLine());
                    }
                });
    }

    private static McpToolContribution artifactReadTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("artifact_read", "Read a markdown artifact by name.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String name = call.getString("name");
                        if (name == null || name.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: name");
                        }
                        ArtifactContent content = ctx.artifactStore().read(name.trim());
                        return McpToolResult.ok("revision=" + content.getRevision()
                                + "\n" + content.getMarkdown());
                    }
                },
                McpToolParameter.string("name", true, "Artifact id: outline, document"));
    }

    private static McpToolContribution sourceListTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_list", "List the research sources.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        StringBuilder sb = new StringBuilder();
                        for (ResearchSourceRecord r : ctx.sourceRepository().find(SourceQuery.all())) {
                            sb.append(r.getSourceId()).append(": ").append(r.getTitle())
                              .append(" [").append(r.getStatus()).append("] rev=")
                              .append(r.getRevision()).append('\n');
                        }
                        return McpToolResult.ok(sb.length() == 0 ? "No sources." : sb.toString());
                    }
                });
    }

    /**
     * How many sources one review turn may see, and how much of each. A review is a reading task with a
     * finite context: handing over the whole corpus would push the material that matters out of the
     * window, and handing over nothing is what made the agent summarise titles.
     */
    // The bounds themselves are the user's settings, served by the context (never local constants):
    // ResearchControlContext.reviewContextMaximumSources() / reviewContextMaximumCharactersPerSource().

    /** Statuses that carry real material; PARKED candidates have no text yet, excluded ones are out. */
    private static boolean isReviewable(ResearchSourceRecord record) {
        return record.getStatus() == com.aresstack.askai.research.sources.SourceStatus.NEW
                || record.getStatus() == com.aresstack.askai.research.sources.SourceStatus.REVIEWED
                || record.getStatus() == com.aresstack.askai.research.sources.SourceStatus.ACCEPTED;
    }

    /**
     * The READ-ONLY content the post-search review actually works on.
     * <p>
     * {@code source_list} answers "which sources exist" with ids, titles and statuses — the right answer to
     * a different question. A review asked to report what WE LEARNED had nothing but those titles to go on,
     * so it could only invent. This tool hands over the material itself: what was searched for, what the
     * page promised, and a bounded piece of what it actually said.
     * <p>
     * {@code captured_through} pins the review to the same corpus the host recorded as its target, so a
     * source that arrives while the review runs is not silently counted as reviewed.
     */
    private static McpToolContribution sourceReviewContextTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_review_context",
                "Read the content of the research sources to review (bounded, newest first).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        long capturedThrough = parseLong(call.getString("captured_through"));
                        // The WINDOW decides what "the new sources" means: without a lower bound every
                        // review re-read the whole cumulative corpus and produced the same summary and the
                        // same clusters, search after search.
                        long capturedSince = parseLong(call.getString("captured_since"));
                        // Request-id scoping beats the time window: sources tagged with a DIFFERENT
                        // search are OUT even inside the window. Untagged (legacy/agent) records keep
                        // falling back to the window alone.
                        String requestId = call.getString("search_request_id");
                        String wantedRequestId = requestId == null ? "" : requestId.trim();
                        // Id scoping (ADR 0001 lane G): a worker role that verifies or writes from the
                        // sources its upstream packages accepted reads exactly those, nothing else.
                        java.util.Set<String> wantedIds = sourceIds(call.getString("source_ids"));
                        List<ResearchSourceRecord> reviewable = new ArrayList<ResearchSourceRecord>();
                        for (ResearchSourceRecord record : ctx.sourceRepository().find(SourceQuery.all())) {
                            boolean requestMatches = wantedRequestId.isEmpty()
                                    || record.getSearchRequestId().isEmpty()
                                    || wantedRequestId.equals(record.getSearchRequestId());
                            boolean idMatches = wantedIds.isEmpty() || wantedIds.contains(record.getSourceId());
                            if (isReviewable(record) && requestMatches && idMatches
                                    && (capturedThrough <= 0L
                                            || record.getCapturedAt() <= capturedThrough)
                                    && (capturedSince <= 0L
                                            || record.getCapturedAt() >= capturedSince)) {
                                reviewable.add(record);
                            }
                        }
                        // RELEVANCE order, not arrival order: the review's bounded context takes the
                        // BEST-ranked material first, so a poorly-ranked source can never crowd out a
                        // good one — treating them all alike made junk exactly as loud as the hits.
                        java.util.Collections.sort(reviewable,
                                new java.util.Comparator<ResearchSourceRecord>() {
                                    public int compare(ResearchSourceRecord a, ResearchSourceRecord b) {
                                        boolean aScored = !Double.isNaN(a.getRerankScore());
                                        boolean bScored = !Double.isNaN(b.getRerankScore());
                                        if (aScored && bScored
                                                && a.getRerankScore() != b.getRerankScore()) {
                                            return Double.compare(b.getRerankScore(),
                                                    a.getRerankScore());
                                        }
                                        if (aScored != bScored) {
                                            return aScored ? -1 : 1; // scored material first
                                        }
                                        return Long.compare(b.getCapturedAt(), a.getCapturedAt());
                                    }
                                });
                        StringBuilder sb = new StringBuilder();
                        int rendered = 0;
                        for (ResearchSourceRecord record : reviewable) {
                            if (rendered >= ctx.reviewContextMaximumSources()) {
                                sb.append("(").append(reviewable.size() - rendered)
                                  .append(" further sources not shown in this review context)\n");
                                break;
                            }
                            appendSource(sb, record, ctx.reviewContextMaximumCharactersPerSource());
                            rendered++;
                        }
                        return McpToolResult.ok(sb.length() == 0
                                ? "No sources to review." : sb.toString());
                    }
                },
                McpToolParameter.string("captured_through", false,
                        "Only sources captured at or before this epoch-millis timestamp (0 = all)"),
                McpToolParameter.string("captured_since", false,
                        "Only sources captured at or after this epoch-millis timestamp (0 = all) — "
                                + "the lower edge of \"the new sources\" of one search"),
                McpToolParameter.string("search_request_id", false,
                        "Only sources found by THIS manual-search request (empty = no id scoping; "
                                + "untagged legacy sources fall back to the time window)"),
                McpToolParameter.string("source_ids", false,
                        "Only these source ids, comma separated (empty = no id filter)"));
    }

    /** The comma-separated ids of {@code source_review_context}'s {@code source_ids}; empty = no filter. */
    static java.util.Set<String> sourceIds(String value) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
        if (value != null) {
            for (String id : value.split(",")) {
                if (!id.trim().isEmpty()) {
                    ids.add(id.trim());
                }
            }
        }
        return ids;
    }

    private static void appendSource(StringBuilder sb, ResearchSourceRecord record,
                                     int maximumCharacters) {
        sb.append("--- ").append(record.getSourceId()).append('\n');
        appendIfPresent(sb, "title: ", record.getTitle());
        appendIfPresent(sb, "url: ", record.getUrl());
        appendIfPresent(sb, "found via: ", record.getSearchQuery());
        if (!Double.isNaN(record.getRerankScore())) {
            // The ranking travels WITH the material: the reviewer weighs a weak hit as a weak hit.
            appendIfPresent(sb, "relevance score (higher = more relevant to the query): ",
                    String.valueOf(record.getRerankScore()));
        }
        String text = record.getFullText() == null || record.getFullText().trim().isEmpty()
                ? record.getExcerpt() : record.getFullText();
        if (text != null && !text.trim().isEmpty()) {
            String trimmed = text.trim();
            boolean cut = trimmed.length() > maximumCharacters;
            sb.append("text: ")
              .append(cut ? trimmed.substring(0, maximumCharacters) : trimmed)
              .append(cut ? " […]" : "").append('\n');
        } else {
            // Say it rather than let the agent assume the page said nothing worth reporting.
            sb.append("text: (no readable text was captured for this source)\n");
        }
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            sb.append(label).append(value.trim()).append('\n');
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.trim().isEmpty() ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    private static McpToolContribution readTool(final ResearchControlContext ctx,
                                                String toolName, final String artifactId) {
        return McpToolContribution.of(toolName, "Read the " + artifactId + " document.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        ArtifactContent content = ctx.artifactStore().read(artifactId);
                        return McpToolResult.ok("revision=" + content.getRevision()
                                + "\n" + content.getMarkdown());
                    }
                });
    }

    private static McpToolContribution saveTool(final ResearchControlContext ctx, String toolName,
                                                final String artifactId, final String requiredPhaseId) {
        return McpToolContribution.of(toolName,
                "Replace the " + artifactId + " document (optimistic locking via expected_revision).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, requiredPhaseId);
                        if (denied != null) {
                            return denied;
                        }
                        String markdown = call.getString("markdown");
                        long expected = call.getInteger("expected_revision", -1L);
                        if (markdown == null || expected < 0) {
                            return McpToolResult.error("Required: markdown, expected_revision");
                        }
                        ArtifactWriteResult result =
                                ctx.artifactStore().replace(artifactId, expected, markdown);
                        return result.isSuccess()
                                ? McpToolResult.ok("saved revision=" + result.getRevision())
                                : McpToolResult.error("Conflict: " + result.getReason()
                                        + " current revision=" + result.getRevision());
                    }
                },
                McpToolParameter.string("markdown", true, "The full new markdown content"),
                McpToolParameter.integer("expected_revision", true, "The revision this edit is based on"));
    }

    private static McpToolContribution sourceAcceptTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_accept",
                "Accept a visited capture as a persistent research source.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.RESEARCH);
                        if (denied != null) {
                            return denied;
                        }
                        String captureId = call.getString("capture_id");
                        if (captureId == null || captureId.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: capture_id");
                        }
                        // The context returns the compact Commit-37 acceptance line verbatim
                        // (status/source_id/title/passage_count/duplicate[/index=STALE]).
                        String result = ctx.acceptCapture(captureId.trim());
                        return result == null
                                ? McpToolResult.error("Unknown capture: " + captureId)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("capture_id", true, "The capture id from a visited page"));
    }

    private static McpToolContribution sourceParkTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("source_park",
                "Park a reranked search candidate as a scored source before it is visited (empty full text).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.RESEARCH);
                        if (denied != null) {
                            return denied;
                        }
                        String url = call.getString("url");
                        if (url == null || url.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: url");
                        }
                        String result = ctx.parkCandidate(url.trim(),
                                emptyIfNull(call.getString("title")),
                                emptyIfNull(call.getString("excerpt")),
                                parseScore(call.getString("score")), "");
                        return result == null ? McpToolResult.error("Could not park: " + url)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("url", true, "The candidate's resolved target URL"),
                McpToolParameter.string("title", false, "The candidate title"),
                McpToolParameter.string("excerpt", false, "The search-result snippet/excerpt"),
                McpToolParameter.string("score", false, "The reranker relevance score (a double)"));
    }

    // ------------------------------------------------------------------ Konzeptpapier tools
    //
    // The SMALL-MODEL facade (K2c, the MainframeMate lesson): tiny atomic operations addressed
    // by a human-readable name path — no handles, no revisions, no full-branch replacement in
    // the model contract. Examples live IN the tool description; required arguments are
    // validated server-side BEFORE dispatch; all transactional machinery (strict parse,
    // candidate validation, atomic commit) runs inside ConceptBranchService on every call.

    private static McpToolContribution conceptReadTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_read",
                "Read the concept tree or one of its branches. "
                        + "Example: path=\"\" (whole concept). Example: path=\"<card>\". "
                        + "Example: path=\"<card>/<child card>\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        com.aresstack.askai.research.concept.ConceptBranchService.ReadResult result =
                                ctx.conceptBranchService()
                                        .readBranch(segmentsOf(call, "path"), 0);
                        if (!result.isOk()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        StringBuilder sb = new StringBuilder();
                        // Revision as DISPLAYED state (the CURRENT_CONCEPT block cites it) — it
                        // is never a token the model must echo back.
                        sb.append("revision=").append(result.getWorkingRevision()).append('\n');
                        if (result.getParentName() != null) {
                            sb.append("parent=").append(result.getParentName()).append('\n');
                        }
                        if (!result.getSiblingNames().isEmpty()) {
                            sb.append("siblings=");
                            for (int i = 0; i < result.getSiblingNames().size(); i++) {
                                sb.append(i > 0 ? ", " : "").append(result.getSiblingNames().get(i));
                            }
                            sb.append('\n');
                        }
                        sb.append(result.getBranchJson());
                        // Zielbild auto-cleanup: a read hands the model the branch AND the
                        // relevant blacklist truth — a later rewrite must omit these names.
                        String suppressed = suppressedNamesIn(result.getBranchJson(), ctx);
                        if (!suppressed.isEmpty()) {
                            sb.append("\nSUPPRESSED IN THIS BRANCH (blacklisted — research on "
                                    + "them stays suppressed): ").append(suppressed);
                            // Slice-2 gate observability finding: the hint reached only the
                            // model — the technical log must show the suppression truth too.
                            ctx.conceptToolLog("concept_read -> SUPPRESSED IN THIS BRANCH: "
                                    + suppressed);
                        }
                        return McpToolResult.ok(sb.toString());
                    }
                },
                McpToolParameter.string("path", false,
                        "Node names from the concept root, separated by '/'. Empty = whole concept."),
                McpToolParameter.string("path_json", false,
                        "The segments as a JSON array of card names — the unambiguous form"));
    }

    /**
     * The ATOMIC list add: all genuinely new cards of one call appear under ONE parent in ONE
     * revision — or none at all. Blacklisted names are skipped as {@code SUPPRESSED_BY_SCOPE}
     * (the blacklist stays authoritative), existing siblings come back {@code ALREADY_PRESENT}
     * (idempotent repeats bump nothing). The receipt names every input's fate — the model may
     * only claim what the receipt confirms.
     */
    private static McpToolContribution conceptAddCardsTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_add_cards",
                "Add ALL named topic cards in ONE atomic step under one parent (empty parent = "
                        + "top level; a plain topic list stays FLAT). Example: parent_path=\"\", "
                        + "names_json=[\"Grundlagen\",\"Architektur\",\"Debugging\"].",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        java.util.List<String> names = namesOf(call.getString("names_json"));
                        if (names == null || names.isEmpty()) {
                            return McpToolResult.error("Missing argument: names_json — a JSON "
                                    + "array of card names, e.g. names_json=[\"Grundlagen\","
                                    + "\"Computer Science\"] (multi-word names stay ONE name)");
                        }
                        java.util.List<String> allowed = new java.util.ArrayList<String>();
                        java.util.List<String> suppressed = new java.util.ArrayList<String>();
                        for (String name : names) {
                            if (isBlacklisted(name, ctx)) {
                                suppressed.add(name);
                            } else {
                                allowed.add(name);
                            }
                        }
                        long revision;
                        String createdParent = null;
                        java.util.List<String> added;
                        java.util.List<String> alreadyPresent;
                        if (allowed.isEmpty()) {
                            added = java.util.Collections.emptyList();
                            alreadyPresent = java.util.Collections.emptyList();
                            revision = ctx.conceptBranchService().snapshot()
                                    .getWorkingRevision();
                        } else {
                            com.aresstack.askai.research.concept.ConceptBranchService
                                    .AddCardsResult result = ctx.conceptBranchService()
                                    .addCards(segmentsOf(call, "parent_path"), allowed);
                            if (!result.isApplied()) {
                                // The parent gate failed → the WHOLE call fails, no mutation.
                                return McpToolResult.error(
                                        result.getDiagnostic().describeForModel());
                            }
                            added = result.getAdded();
                            alreadyPresent = result.getAlreadyPresent();
                            createdParent = result.getCreatedParent();
                            revision = result.getNewRevision();
                            if (!added.isEmpty() || createdParent != null) {
                                ctx.onConceptChanged(revision);
                            }
                        }
                        // NO_CHANGE is honest bookkeeping (the gate saw a misleading APPLIED
                        // on a batch that changed nothing); every single receipt line is
                        // mirrored into the technical log — the live observer must see the
                        // same fates the model sees.
                        boolean changed = !added.isEmpty() || createdParent != null;
                        StringBuilder receipt = new StringBuilder(
                                changed ? "APPLIED" : "NO_CHANGE")
                                .append(" revision=").append(revision);
                        java.util.List<String> lines = new java.util.ArrayList<String>();
                        if (createdParent != null) {
                            lines.add("CREATED_PARENT: " + createdParent);
                        }
                        for (String name : added) {
                            lines.add("ADDED: " + name);
                        }
                        for (String name : alreadyPresent) {
                            lines.add("ALREADY_PRESENT: " + name);
                        }
                        for (String name : suppressed) {
                            lines.add("SUPPRESSED_BY_SCOPE: " + name);
                        }
                        ctx.conceptToolLog("concept_add_cards -> "
                                + (changed ? "APPLIED" : "NO_CHANGE")
                                + " revision=" + revision);
                        for (String line : lines) {
                            receipt.append('\n').append(line);
                            ctx.conceptToolLog("concept_add_cards -> " + line);
                        }
                        return McpToolResult.ok(receipt.toString());
                    }
                },
                McpToolParameter.string("parent_path", false,
                        "The parent card's names from the concept root, separated by '/'. "
                                + "Empty = top-level cards."),
                McpToolParameter.string("parent_path_json", false,
                        "The parent segments as a JSON array of card names — the unambiguous "
                                + "form"),
                McpToolParameter.string("names_json", true,
                        "The card names as a JSON array (preferred). Comma-, semicolon-, "
                                + "newline- or bullet-separated lists are tolerated; bare "
                                + "spaces are NEVER split."));
    }

    /**
     * Permissive technical list parse: a JSON array is canonical; otherwise newline-, bullet-,
     * semicolon- and comma-separated entries are accepted, with double quotes protecting
     * multi-word segments. Bare spaces are NEVER a separator ("Computer Science" is one name).
     */
    static java.util.List<String> namesOf(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String text = raw.trim();
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(text);
            if (parsed.isJsonArray()) {
                java.util.List<String> names = new java.util.ArrayList<String>();
                for (com.google.gson.JsonElement element : parsed.getAsJsonArray()) {
                    if (element.isJsonPrimitive()) {
                        names.add(element.getAsString());
                    }
                }
                return names;
            }
        } catch (RuntimeException notJson) {
            // fall through to the permissive separators
        }
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (String line : text.split("\\r?\\n")) {
            String entry = line.trim();
            // Strip bullet markers ("- ", "* ", "• ", "3. ") — one per line.
            entry = entry.replaceFirst("^([-*•]|\\d+[.)])\\s+", "");
            for (String part : entry.split("[;,]")) {
                String name = part.trim();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1).trim();
                }
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static McpToolContribution conceptAddTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_add",
                "Add ONE new topic card to the concept. "
                        + "Example: parent_path=\"\", name=\"<card>\" (a top-level card). "
                        + "Example: parent_path=\"<card>/<child card>\", "
                        + "name=\"<subtopic>\". "
                        + "If unsure whether the parent exists, call concept_read first.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String name = call.getString("name");
                        if (name == null || name.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: name — example: "
                                    + "parent_path=\"<card>\", name=\"<subtopic>\"");
                        }
                        com.aresstack.askai.research.concept.ConceptBranchService.EditResult result =
                                ctx.conceptBranchService().addNode(
                                        segmentsOf(call, "parent_path"), name.trim());
                        if (!result.isApplied()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        ctx.onConceptChanged(result.getNewRevision());
                        return McpToolResult.ok("added \"" + name.trim() + "\" revision="
                                + result.getNewRevision());
                    }
                },
                McpToolParameter.string("parent_path", false,
                        "The parent card's names from the concept root, separated by '/'. "
                                + "Empty = add a top-level card."),
                McpToolParameter.string("parent_path_json", false,
                        "The parent segments as a JSON array of card names — the unambiguous "
                                + "form ('/' in a name stays a character)"),
                McpToolParameter.string("name", true, "The new card's name (short noun phrase)"));
    }

    /** Rename ONE card, any depth — never a deletion; children and position stay untouched. */
    private static McpToolContribution conceptRenameTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_rename",
                "Rename ONE concept card; its children and position stay untouched. "
                        + "Example: path=\"<card>/<child card>\", name=\"<new name>\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String name = call.getString("name");
                        if (name == null || name.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: name — example: "
                                    + "path=\"<card>/<child card>\", name=\"<new name>\"");
                        }
                        java.util.List<String> path = segmentsOf(call, "path");
                        com.aresstack.askai.research.concept.ConceptBranchService.EditResult result =
                                ctx.conceptBranchService().renameNode(path, name.trim());
                        if (!result.isApplied()) {
                            return McpToolResult.error(result.getDiagnostic().describeForModel());
                        }
                        ctx.onConceptChanged(result.getNewRevision());
                        String note = isBlacklisted(name.trim(), ctx)
                                ? " (note: \"" + name.trim() + "\" is blacklisted — the card "
                                        + "will stay suppressed for research)"
                                : "";
                        return McpToolResult.ok("renamed to \"" + name.trim() + "\" revision="
                                + result.getNewRevision() + note);
                    }
                },
                McpToolParameter.string("path", false,
                        "The card's names from the concept root, separated by '/'"),
                McpToolParameter.string("path_json", false,
                        "The segments as a JSON array of card names — the unambiguous form"),
                McpToolParameter.string("name", true, "The new card name — ONE label"));
    }

    /**
     * The atomic leaf move: one leaf, one target parent, one revision, UNCHANGED UUID. Every
     * receipt line mirrors into the technical log; every refusal ({@code SOURCE_NOT_LEAF},
     * {@code TARGET_PARENT_NOT_FOUND}, {@code AMBIGUOUS_*}, {@code TARGET_NAME_COLLISION})
     * leaves document and sidecar byte-identical.
     */
    private static McpToolContribution conceptMoveLeafTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("concept_move_leaf",
                "Move ONE existing LEAF card under an existing parent (or the top level). "
                        + "Never a branch, never creates the target. Example: "
                        + "source_json=[\"<child card>\"], parent_path_json=[\"<new parent>\"]; "
                        + "parent_path_json=[] moves to the top level.",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        java.util.List<String> source = segmentsOf(call, "source");
                        if (source.isEmpty()) {
                            return McpToolResult.error("Missing argument: source_json — the "
                                    + "leaf as a JSON array of card names (a globally unique "
                                    + "single name is enough), e.g. "
                                    + "source_json=[\"<child card>\"]");
                        }
                        com.aresstack.askai.research.concept.ConceptBranchService
                                .MoveLeafResult result = ctx.conceptBranchService()
                                .moveLeaf(source, segmentsOf(call, "parent_path"));
                        if (!result.isApplied()) {
                            String diagnostic = result.getDiagnostic().describeForModel();
                            ctx.conceptToolLog("concept_move_leaf -> REFUSED "
                                    + diagnostic.replace("\r", "").replace('\n', ' '));
                            return McpToolResult.error(diagnostic);
                        }
                        java.util.List<String> lines = new java.util.ArrayList<String>();
                        StringBuilder receipt;
                        if (result.isNoChange()) {
                            receipt = new StringBuilder("NO_CHANGE revision=")
                                    .append(result.getNewRevision());
                            lines.add("ALREADY_AT_TARGET: " + result.getLabel());
                        } else {
                            receipt = new StringBuilder("APPLIED revision=")
                                    .append(result.getNewRevision());
                            lines.add("MOVED: " + result.getLabel());
                            lines.add("FROM: " + result.getFromPath());
                            lines.add("TO: " + result.getToPath());
                            ctx.onConceptChanged(result.getNewRevision());
                        }
                        lines.add("ID: " + result.getNodeId());
                        ctx.conceptToolLog("concept_move_leaf -> "
                                + (result.isNoChange() ? "NO_CHANGE" : "APPLIED")
                                + " revision=" + result.getNewRevision());
                        for (String line : lines) {
                            receipt.append('\n').append(line);
                            ctx.conceptToolLog("concept_move_leaf -> " + line);
                        }
                        return McpToolResult.ok(receipt.toString());
                    }
                },
                McpToolParameter.string("source", false,
                        "The leaf's names from the concept root, separated by '/' (or ONE "
                                + "globally unique card name)"),
                McpToolParameter.string("source_json", false,
                        "The leaf as a JSON array of card names — the unambiguous form"),
                McpToolParameter.string("parent_path", false,
                        "The target parent's names separated by '/'. Empty = top level."),
                McpToolParameter.string("parent_path_json", false,
                        "The target parent segments as a JSON array ([] = top level)"));
    }

    /** Case-insensitive exact match against the session's blacklist terms. */
    private static boolean isBlacklisted(String name, ResearchControlContext ctx) {
        String normalized = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        for (String term : ctx.blacklistedTerms()) {
            if (normalized.equals(term == null ? ""
                    : term.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** The blacklisted card names present in a branch JSON, comma-joined ("" when none). */
    private static String suppressedNamesIn(String branchJson, ResearchControlContext ctx) {
        java.util.List<String> terms = ctx.blacklistedTerms();
        if (terms.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> hits = new java.util.LinkedHashSet<String>();
        for (java.util.List<String> path : com.aresstack.askai.research.concept
                .ConceptTopicScanner.collectCardPaths(
                        "{\"concept\":[" + branchJson + "]}")) {
            String card = path.get(path.size() - 1);
            if (isBlacklisted(card, ctx)) {
                hits.add(card);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String hit : hits) {
            sb.append(sb.length() > 0 ? ", " : "").append(hit);
        }
        return sb.toString();
    }

    /**
     * The ONE-command exclusion facade: the model hands over the USER'S term verbatim; the host
     * derives the id, records the EXCLUDED facet (immediately effective), republishes the fence
     * and scans the concept for an EXACT name match. A conflict comes back as a structured
     * {@code conceptConflict} with an opaque conflictId + {@code INFORM_AND_ASK_REMOVE} — the
     * tool NEVER deletes anything itself.
     */
    private static McpToolContribution excludeTopicTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("exclude_topic",
                "Record a topic the user ruled out, in the USER'S words. The application derives "
                        + "the id, suppresses the topic for all research and checks the concept "
                        + "for a conflicting entry. Example: topic=\"<term>\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String topic = call.getString("topic");
                        if (topic == null || topic.trim().isEmpty()) {
                            return McpToolResult.error(
                                    "Missing argument: topic — example: topic=\"<term>\"");
                        }
                        String reply = ctx.excludeTopic(topic.trim());
                        if (reply == null) {
                            return McpToolResult.error("This session has no scope system.");
                        }
                        // A rejected commit is the tool's REJECTION (ToolFailure on the runtime
                        // side, repair budget) — never a green result.
                        return reply.contains("\"result\":\"REJECTED\"")
                                ? McpToolResult.error(reply) : McpToolResult.ok(reply);
                    }
                },
                McpToolParameter.string("topic", true,
                        "The excluded topic in the user's own words, e.g. \"<term>\""));
    }

    /** The search-offer command: 3-5 AI-authored lookups rendered as clickable exploration tags. */
    private static McpToolContribution offerSearchesTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("offer_searches",
                "Offer 3-5 orientation searches the user can run with one click (yellow tags). "
                        + "Nothing runs by itself. Example: suggestions_json="
                        + "[{\"query\":\"<topic> basics overview\","
                        + "\"purpose\":\"<core aspect>\"}]",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String suggestionsJson = call.getString("suggestions_json");
                        if (suggestionsJson == null || suggestionsJson.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: suggestions_json — "
                                    + "example: [{\"query\":\"<topic> basics overview\","
                                    + "\"purpose\":\"<core aspect>\"}]");
                        }
                        String reply = ctx.offerSearches(suggestionsJson.trim());
                        if (reply == null) {
                            return McpToolResult.error("This session has no scoping surface.");
                        }
                        return reply.startsWith("{") ? McpToolResult.ok(reply)
                                : McpToolResult.error(reply);
                    }
                },
                McpToolParameter.string("suggestions_json", true,
                        "JSON array of {query, purpose} objects — the user's exploration tags"));
    }

    /** scope_probe (AP3): read-only semantic measurement — a sensor, never an author. */
    private static McpToolContribution scopeProbeTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("scope_probe",
                "READ-ONLY: measure where the given terms sit relative to the negotiated "
                        + "research scope (LIKELY_IN / LIKELY_OUT / BOUNDARY / NOVEL). Changes "
                        + "nothing. Use it only for the late closure check. Example: "
                        + "terms_json=[\"<term 1>\",\"<term 2>\"].",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        java.util.List<String> terms = namesOf(call.getString("terms_json"));
                        if (terms == null || terms.isEmpty()) {
                            return McpToolResult.error("Missing argument: terms_json — a JSON "
                                    + "array of terms, e.g. terms_json=[\"<term 1>\","
                                    + "\"<term 2>\"]");
                        }
                        String reply = ctx.scopeProbe(terms);
                        if (reply == null) {
                            return McpToolResult.error("This session has no scope system.");
                        }
                        // STALE_FENCE / PROBE_FAILED / PROBE_UNAVAILABLE are honest sensor
                        // rejections — never a green result over discarded readings.
                        return reply.startsWith("PROBED")
                                ? McpToolResult.ok(reply) : McpToolResult.error(reply);
                    }
                },
                McpToolParameter.string("terms_json", true,
                        "The terms to probe as a JSON array of strings"));
    }

    /** Resolve a reported concept conflict — only ever AFTER the user answered the question. */
    private static McpToolContribution resolveConflictTool(final ResearchControlContext ctx) {
        return McpToolContribution.of("resolve_concept_conflict",
                "Resolve a concept conflict exclude_topic reported, AFTER the user decided: "
                        + "decision=REMOVE deletes the concept entry, decision=KEEP_SUPPRESSED "
                        + "keeps it (research stays suppressed). Example: "
                        + "conflict_id=\"conflict-1\" decision=\"REMOVE\".",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        McpToolResult denied = requireWritable(ctx, ResearchStateIds.SCOPING);
                        if (denied != null) {
                            return denied;
                        }
                        String conflictId = call.getString("conflict_id");
                        String decision = call.getString("decision");
                        if (conflictId == null || conflictId.trim().isEmpty()
                                || decision == null || decision.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: conflict_id and "
                                    + "decision are required — example: "
                                    + "conflict_id=\"conflict-1\" decision=\"REMOVE\"");
                        }
                        String reply = ctx.resolveConceptConflict(conflictId.trim(),
                                decision.trim());
                        return reply == null
                                ? McpToolResult.error("This session has no scope system.")
                                : (reply.startsWith("{") ? McpToolResult.ok(reply)
                                        : McpToolResult.error(reply));
                    }
                },
                McpToolParameter.string("conflict_id", true,
                        "The opaque id from exclude_topic's conceptConflict"),
                McpToolParameter.string("decision", true,
                        "REMOVE (delete the concept entry) or KEEP_SUPPRESSED (keep it)"));
    }

    /**
     * Segment resolution with the UNAMBIGUOUS form first: {@code <name>_json} carries a JSON
     * array of card names (the runtime's transport — '/' in a name stays a character, a missed
     * parent can never collapse into one literal root name); the plain {@code <name>} parameter
     * keeps the slash-joined convenience for external drivers and humans.
     */
    private static java.util.List<String> segmentsOf(McpToolCall call, String parameter) {
        String json = call.getString(parameter + "_json");
        if (json != null && !json.trim().isEmpty()) {
            java.util.List<String> segments = new ArrayList<String>();
            try {
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(json);
                if (parsed.isJsonArray()) {
                    for (com.google.gson.JsonElement element : parsed.getAsJsonArray()) {
                        String segment = element.isJsonPrimitive() ? element.getAsString() : "";
                        if (!segment.trim().isEmpty()) {
                            segments.add(segment.trim());
                        }
                    }
                    return segments;
                }
            } catch (RuntimeException notJson) {
                // fall through to the slash form — an unreadable array must not silently
                // reinterpret the call
            }
        }
        return splitPath(call.getString(parameter));
    }

    /** "A/B/C" → [A, B, C]; empty/null → the concept root. */
    private static java.util.List<String> splitPath(String path) {
        java.util.List<String> names = new ArrayList<String>();
        if (path != null) {
            for (String part : path.split("/")) {
                if (!part.trim().isEmpty()) {
                    names.add(part.trim());
                }
            }
        }
        return names;
    }

    private static String emptyIfNull(String v) {
        return v == null ? "" : v;
    }

    private static double parseScore(String v) {
        if (v == null || v.trim().isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

}

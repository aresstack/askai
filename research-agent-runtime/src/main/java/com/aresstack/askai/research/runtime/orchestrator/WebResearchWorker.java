package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.role.RoleToolCatalog;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The web research worker: one work package = one search run of the session's acquisition funnel for the
 * package's query (ADR 0001, 4.1: "web/literature/technical research workers (acquisition runner + readiness
 * judge)"). Each accepted source becomes a {@code SOURCE_REFERENCE} artifact of the task (project ref = the
 * source id, content = URL and title), the run's stop line a {@code TEXT} artifact. The worker adds nothing to
 * the acquisition: what is searched, opened and accepted is decided by the funnel's gates exactly as in the
 * single autonomous loop.
 */
public final class WebResearchWorker implements TaskWorker {

    /**
     * The role: its model use is the acquisition runner's page readiness judge (the judge slot); its tools on
     * the research-control lane are reading the status and the sources and accepting or parking a source. The
     * requests carry their prompts (the judge's), hence {@code CALLER}.
     */
    public static final ResearchRole ROLE = ResearchRole.builder("web-researcher", ResearchModelSlots.JUDGE)
            .purpose("searches the web for one query through the research funnel and accepts relevant sources")
            .promptFromCaller()
            .tools(RoleToolCatalog.of("research_status", "source_list", "source_accept", "source_park"))
            .output(RoleOutput.text("WebAcquisition result (stop reason + accepted source references)"))
            .build();

    private final WebAcquisition acquisition;
    private final com.aresstack.askai.research.runtime.loop.ToolInvoker research;

    /**
     * @param acquisition the session's funnel
     * @param research    the session's research-control lane (restricted per task to the role's tools)
     */
    public WebResearchWorker(WebAcquisition acquisition,
                             com.aresstack.askai.research.runtime.loop.ToolInvoker research) {
        this.acquisition = acquisition;
        this.research = research;
    }

    @Override
    public ResearchRole role() {
        return ROLE;
    }

    @Override
    public boolean needsBrowser() {
        return true;
    }

    @Override
    public WorkerResult execute(ResearchTask task, final WorkerContext context) {
        String query = context.query();
        if (query.isEmpty()) {
            return WorkerResult.failed("the package has no query");
        }
        final AtomicInteger accepted = new AtomicInteger();
        context.log("search: " + query);
        WebAcquisition.Result result = acquisition.acquire(query, context.permitted(research),
                new Consumer<WebSearchApplicationService.AcceptedSource>() {
                    public void accept(WebSearchApplicationService.AcceptedSource source) {
                        if (source.getSourceId() == null || source.getSourceId().isEmpty()) {
                            return;
                        }
                        accepted.incrementAndGet();
                        String title = source.getPageTitle() == null ? "" : source.getPageTitle().trim();
                        context.artifact(TaskArtifact.Kind.SOURCE_REFERENCE,
                                source.getPageUrl() + (title.isEmpty() ? "" : " " + title), source.getSourceId());
                        context.receipt("source_accept", true, "accepted " + source.getSourceId()
                                + (source.isDuplicate() ? " (duplicate)" : ""));
                    }
                });
        ResearchStopReason reason = result.getStopReason();
        String line = "search run stopped: " + reason + ", " + accepted.get() + " source(s) accepted"
                + (result.getSummary().isEmpty() ? "" : "; " + result.getSummary());
        context.artifact(TaskArtifact.Kind.TEXT, line, "");
        return map(reason, line);
    }

    static WorkerResult map(ResearchStopReason reason, String line) {
        if (reason == null) {
            return WorkerResult.retryable("no stop reason");
        }
        switch (reason) {
            case SUFFICIENT_EVIDENCE:
            case TOOL_BUDGET_EXHAUSTED:
            case PAGE_BUDGET_EXHAUSTED:
            case SOURCE_BUDGET_EXHAUSTED:
            case TIME_BUDGET_EXHAUSTED:
            case ERROR_BUDGET_EXHAUSTED:
            case NO_RELEVANT_PATHS:
            case NO_SEMANTIC_MATCHES:
                // Zero sources is a result too: the orchestrator judges it as a gap.
                return WorkerResult.completed(line);
            case SEARCH_TECHNICAL_PROBLEM:
            case RERANKER_UNAVAILABLE:
            case RERANKER_INVALID_RESPONSE:
            case RERANKER_TIMEOUT:
            case MCP_UNAVAILABLE:
                return WorkerResult.retryable(line);
            case USER_CANCELLED:
                return WorkerResult.canceled(line);
            default:
                // Configuration errors, the scope control down, a changed host state: retrying cannot help.
                return WorkerResult.failed(line);
        }
    }
}

package com.aresstack.askai.research.runtime.browser;

import com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.orchestrator.WebAcquisition;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The orchestrated run's {@link WebAcquisition} on worker user contexts: every search run gets its OWN user
 * context from the chat's {@link BrowserUserContextPool} (own cookies, storage and sign-in, with its first
 * browsing context) and gives it back when the run ends — on success, failure, cancel and before a retry, so
 * a retried package never inherits a broken context. The search run itself is the session's ONE funnel; only
 * the browser lane it drives differs. Pages a worker reads land in the chat's capture store through the
 * lease's tool contract, so source acceptance, duplicate detection and scope checks stay the session's.
 *
 * <p>Without a pool (or one without capacity) every run drives the chat browser, one after another as the
 * executor's browser-slot setting allows.</p>
 */
public final class UserContextWebAcquisition implements WebAcquisition {

    /** One search run of the session's funnel over {@code browser}. */
    public interface SearchRun {
        Result run(ToolInvoker browser, String query, ToolInvoker research,
                   Consumer<WebSearchApplicationService.AcceptedSource> accepted, long maxDurationMillis,
                   AtomicLong tokens);
    }

    /** The worker id the leases carry (diagnostics and per-chat bookkeeping in the pool). */
    public static final String WORKER_ID = "web-research";

    private final BrowserUserContextPool pool;
    private final SearchRun run;
    private final AtomicLong runs = new AtomicLong();

    private UserContextWebAcquisition(BrowserUserContextPool pool, SearchRun run) {
        this.pool = pool;
        this.run = run;
    }

    /**
     * @param pool          the chat's worker user contexts, or null
     * @param sharedBrowser the chat browser, used when the pool has no capacity
     */
    public static WebAcquisition over(BrowserUserContextPool pool, final ToolInvoker sharedBrowser,
                                      final SearchRun run) {
        if (browserSlots(pool) > 0) {
            return new UserContextWebAcquisition(pool, run);
        }
        return (query, research, accepted, maxDurationMillis, tokens) ->
                run.run(sharedBrowser, query, research, accepted, maxDurationMillis, tokens);
    }

    /** How many browser-bound tasks may run at once on {@code pool}; 0 = no pool (use the setting). */
    public static int browserSlots(BrowserUserContextPool pool) {
        return pool == null ? 0 : Math.max(0, pool.capacity());
    }

    @Override
    public Result acquire(String query, ToolInvoker research,
                          Consumer<WebSearchApplicationService.AcceptedSource> accepted, long maxDurationMillis,
                          AtomicLong tokens) {
        // Waits at most the "Worker browsers" wait setting for a free user context.
        BrowserUserContextRequest request = BrowserUserContextRequest.of(WORKER_ID,
                "search-run-" + runs.incrementAndGet());
        try (BrowserUserContextLease lease = pool.acquire(request)) {
            return run.run(lease.browser(), query, research, accepted, maxDurationMillis, tokens);
        } catch (BrowserCapacityException busy) {
            // Retryable: another package may have given its user context back by the next attempt.
            return new Result(ResearchStopReason.MCP_UNAVAILABLE, "no worker browser: " + busy.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(ResearchStopReason.USER_CANCELLED, "cancelled while waiting for a worker browser");
        }
    }
}

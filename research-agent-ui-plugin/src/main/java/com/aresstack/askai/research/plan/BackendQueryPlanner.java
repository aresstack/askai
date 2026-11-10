package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.search.ResearchServiceCommandWire;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Plan PR 14: the host's client of the {@code plan_queries} wire of PR 13. It sends {@code #RSC1# plan_queries} and
 * waits, off the UI thread, for the correlated {@code #RSX1# query_plan} answer that the session hands to
 * {@link #deliver} on the backend callback thread. A timeout, an unreachable backend or an unreadable answer return
 * {@code null}: the plan then keeps the base queries (D5).
 */
public final class BackendQueryPlanner implements SearchPlanBuilder.VariantPlanner {

    /** Sends a service command; returns {@code false} when no backend session is there. */
    public interface Submitter {
        boolean submit(String serviceCommand);
    }

    private final Submitter submitter;
    private final long timeoutMillis;
    /** The book's Weidezaun as the planner reads it (the rendered scope); "" when there is none. */
    public interface AreaSource {
        String area();
    }

    private volatile AreaSource areaSource;

    /** Every request from now on carries the current Weidezaun, at the seed plan and at every later cycle. */
    public void setAreaSource(AreaSource source) {
        this.areaSource = source;
    }

    private final Map<String, BlockingQueue<String>> pending = new ConcurrentHashMap<String, BlockingQueue<String>>();

    public BackendQueryPlanner(Submitter submitter, long timeoutMillis) {
        this.submitter = submitter;
        this.timeoutMillis = Math.max(1L, timeoutMillis);
    }

    @Override
    public ConceptQueryPlan.Result plan(ConceptQueryPlan.Request request) {
        String requestId = "plan-queries-" + java.util.UUID.randomUUID();
        BlockingQueue<String> answer = new ArrayBlockingQueue<String>(1);
        pending.put(requestId, answer);
        try {
            AreaSource source = areaSource;
            String area = source == null ? "" : source.area();
            ConceptQueryPlan.Request fenced = request.getArea().isEmpty() && area != null && !area.trim().isEmpty()
                    ? request.withArea(area) : request;
            if (!submitter.submit(ResearchServiceCommandWire.planQueries(requestId,
                    QueryPlanWireCodec.encodeRequest(fenced)))) {
                return null;
            }
            String payload = answer.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (payload == null) {
                System.err.println("[search-plan] no query plan within " + timeoutMillis + " ms; base queries only");
                return null;
            }
            return QueryPlanWireCodec.decodeResult(payload);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException unreadable) {
            System.err.println("[search-plan] the query plan is unusable: " + unreadable.getMessage());
            return null;
        } finally {
            pending.remove(requestId);
        }
    }

    /** Route a correlated {@code query_plan} payload to its waiting request. */
    public boolean deliver(String requestId, String payloadJson) {
        BlockingQueue<String> answer = requestId == null ? null : pending.get(requestId);
        return answer != null && answer.offer(payloadJson == null ? "" : payloadJson);
    }
}

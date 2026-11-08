package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.runtime.plan.QueryPlanWire;
import com.aresstack.askai.research.runtime.service.ResearchServiceCommand;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** Plan PR 13: the host codec against the runtime wire (runtime classes on this module's classpath). */
public class QueryPlanWireCodecRoundTripTest {

    @Test
    public void theRequestRoundTripsHostToRuntimeAndBack() {
        ConceptQueryPlan.Request request = new ConceptQueryPlan.Request("en", "Isolating Java plugins",
                Collections.singletonList(new ConceptQueryPlan.Node("n1", "Class loaders",
                        Arrays.asList("Plugins", "Class loaders"), "root", "java class loader plugins")),
                Collections.singletonList("osgi"), Arrays.asList("Spring", "Tomcat"), 2);
        String envelope = com.aresstack.askai.research.search.ResearchServiceCommandWire.planQueries("r1",
                QueryPlanWireCodec.encodeRequest(request));
        ResearchServiceCommand command =
                com.aresstack.askai.research.runtime.service.ResearchServiceCommandWire.parse(envelope);
        assertEquals(ResearchServiceCommand.TYPE_PLAN_QUERIES, command.getType());

        ConceptQueryPlan.Request runtime = QueryPlanWire.parseRequest(command.getRequest());
        ConceptQueryPlan.Request back = QueryPlanWireCodec.decodeRequest(QueryPlanWire.renderRequest(runtime));
        assertEquals("en", back.getLanguage());
        assertEquals("Isolating Java plugins", back.getBookTitle());
        assertEquals(2, back.getMaxVariantsPerNode());
        assertEquals(Collections.singletonList("osgi"), back.getQueriesRun());
        assertEquals(Arrays.asList("Spring", "Tomcat"), back.getBlacklist());
        assertEquals("java class loader plugins", back.getNodes().get(0).getBaseQuery());
        assertEquals(Arrays.asList("Plugins", "Class loaders"), back.getNodes().get(0).getPath());
        assertEquals("root", back.getNodes().get(0).getParentId());
    }

    @Test
    public void theResultRoundTripsRuntimeToHost() {
        ConceptQueryPlan.Result runtime = new ConceptQueryPlan.Result(Collections.singletonList(
                new ConceptQueryPlan.NodePlan("n1", "java class loader plugins",
                        Arrays.asList("parent first class loading", "plugin class loader leak"), "")));
        ConceptQueryPlan.Result host = QueryPlanWireCodec.decodeResult(QueryPlanWire.renderResult(runtime));
        assertEquals(Arrays.asList("java class loader plugins", "parent first class loading",
                "plugin class loader leak"), host.getNodes().get(0).queries());
        assertEquals(QueryPlanWire.renderResult(runtime), QueryPlanWireCodec.encodeResult(host));
    }
}

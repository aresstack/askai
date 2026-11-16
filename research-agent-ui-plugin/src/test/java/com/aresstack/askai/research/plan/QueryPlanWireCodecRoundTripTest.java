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
    public void aLaterCycleCarriesItsNumberAndTheDomainsFoundSoFar() {
        ConceptQueryPlan.Request request = new ConceptQueryPlan.Request("de", "Wearables am Bau",
                Collections.singletonList(new ConceptQueryPlan.Node("n1", "Sensorarten",
                        Arrays.asList("Wearables", "Sensorarten"), "root", "Sensorarten Wearables Baustelle",
                        Arrays.asList("amazon.de", "thalia.de"))),
                Arrays.asList("Sensorarten Wearables Baustelle", "Wearables Bau Sensoren"),
                Collections.<String>emptyList(), 3, 2);
        ConceptQueryPlan.Request runtime = QueryPlanWire.parseRequest(QueryPlanWireCodec.encodeRequest(request));
        assertEquals(2, runtime.getCycle());
        assertEquals(Arrays.asList("amazon.de", "thalia.de"), runtime.getNodes().get(0).getFoundDomains());
        ConceptQueryPlan.Request back = QueryPlanWireCodec.decodeRequest(QueryPlanWire.renderRequest(runtime));
        assertEquals(2, back.getCycle());
        assertEquals(Arrays.asList("amazon.de", "thalia.de"), back.getNodes().get(0).getFoundDomains());
        // a first-cycle request has neither
        ConceptQueryPlan.Request first = QueryPlanWire.parseRequest(QueryPlanWireCodec.encodeRequest(
                new ConceptQueryPlan.Request("de", "", Collections.singletonList(
                        new ConceptQueryPlan.Node("n1", "x", Collections.singletonList("x"), "", "abc")),
                        Collections.<String>emptyList(), Collections.<String>emptyList(), 2)));
        assertEquals(1, first.getCycle());
        assertEquals(Collections.emptyList(), first.getNodes().get(0).getFoundDomains());
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

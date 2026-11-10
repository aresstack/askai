package com.aresstack.askai.research.host;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** D6: the session's analysis inputs: nothing to analyse before the concept; domains are hosts without www. */
public class TopicAnalysisInputsTest {

    @Test
    public void withoutAnApprovedConceptThereIsNoRequest() throws Exception {
        TopicAnalysisInputs inputs = new TopicAnalysisInputs(new TopicAnalysisInputs.Source() {
            public com.aresstack.askai.research.book.FrozenConcept frozenConcept() {
                return null;
            }

            public com.aresstack.askai.research.store.FileConceptStore conceptStore() {
                return null;
            }

            public String approvedTitle(com.aresstack.askai.research.book.FrozenConcept frozen) {
                return "";
            }

            public java.util.Collection<String> pinnedSourceIds() {
                return java.util.Collections.emptyList();
            }

            public java.util.Collection<com.aresstack.askai.research.sources.ResearchSourceRecord> sources() {
                return java.util.Collections.emptyList();
            }

            public com.aresstack.askai.research.knowledge.topic.ScopeFence fence() {
                return null;
            }

            public java.util.Set<String> pendingSourceIds() {
                return java.util.Collections.emptySet();
            }

            public EvidenceAnalysisSettings settings() {
                return EvidenceAnalysisSettings.defaults();
            }

            public String embeddingFingerprint() {
                return "fp";
            }
        }, null);
        assertNull(inputs.request());
    }

    @Test
    public void theDomainIsTheHostWithoutWww() {
        assertEquals("example.org", TopicAnalysisInputs.host("https://www.Example.org/a?b"));
        assertEquals("", TopicAnalysisInputs.host(""));
        assertEquals("", TopicAnalysisInputs.host("not a url"));
    }
}

package com.aresstack.askai.research.host;

import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.ScopeAlignment;
import com.aresstack.askai.research.plan.EvidenceGaps;
import com.aresstack.askai.research.plan.SearchPlanEntry;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TopicAnalysisGapReviewTest {

    @Test
    public void everyStatusAndVerdictOfTheSharedAnalysisHasItsGapReviewCounterpart() {
        for (EvidenceReport.Status status : EvidenceReport.Status.values()) {
            assertEquals(status.name(), TopicAnalysisGapReview.status(status).name());
        }
        for (ScopeAlignment.Verdict verdict : ScopeAlignment.Verdict.values()) {
            assertEquals(verdict.name(), TopicAnalysisGapReview.scope(verdict).name());
        }
        assertEquals(EvidenceGaps.Scope.UNCLASSIFIED, TopicAnalysisGapReview.scope(null));
        assertNull(TopicAnalysisGapReview.view(null));
    }

    @Test
    public void withoutTheKnowledgeCapabilityTheReviewReportsNoGap() {
        TopicAnalysisGapReview review = new TopicAnalysisGapReview(new TopicAnalysisGapReview.Inputs() {
            public com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery discovery() {
                return null;
            }

            public java.util.List<String> blacklist() {
                return Collections.emptyList();
            }

            public long waitMillis() {
                return 0L;
            }

            public long recheckMillis() {
                return 0L;
            }

            public int maxNewTopics() {
                return 3;
            }

            public int maxFailedQueries() {
                return 3;
            }
        });
        assertTrue(review.atCycleBoundary(Collections.singletonList("a"),
                Collections.<SearchPlanEntry>emptyList()).isEmpty());
    }
}

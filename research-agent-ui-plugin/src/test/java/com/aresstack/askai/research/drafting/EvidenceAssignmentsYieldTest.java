package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.runtime.writer.SectionAssignment;
import com.aresstack.askai.research.writer.WriterPackSettings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Live run 5: the pack's per-source cap scales with the parts, the word target follows the actual pack. */
public class EvidenceAssignmentsYieldTest {

    @Test
    public void perSourceCapScalesWithThePartsLikeThePassageCount() {
        WriterPackSettings three = WriterPackSettings.LARGE.forParts(3);
        assertEquals(WriterPackSettings.LARGE.getPassageCount() * 3, three.getPassageCount());
        assertEquals(WriterPackSettings.LARGE.getPassagesPerSource() * 3, three.getPassagesPerSource());
        assertEquals(WriterPackSettings.LARGE, WriterPackSettings.LARGE.forParts(1));
        assertEquals(WriterPackSettings.LARGE, WriterPackSettings.LARGE.forParts(0));
    }

    @Test
    public void wordTargetIsCappedByThePackTimesWordsPerPassage() {
        assertEquals(960, EvidenceAssignments.wordTarget(1100, 24, 40));
        assertEquals(400, EvidenceAssignments.wordTarget(1100, 10, 40));
        assertEquals(600, EvidenceAssignments.wordTarget(600, 24, 40));
        assertEquals(SectionAssignment.MIN_WORDS_PER_CALL, EvidenceAssignments.wordTarget(1100, 0, 40));
        assertEquals(1100, EvidenceAssignments.wordTarget(1100, 10, 0));
        assertEquals(0, EvidenceAssignments.wordTarget(0, 10, 40));
    }
}

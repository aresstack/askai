package com.aresstack.askai.research.book;

import com.aresstack.askai.research.concept.questions.MissionQuestion;
import com.aresstack.askai.research.concept.questions.MissionQuestionList;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Repair plan step 2a, part A: the question list approved with the concept is frozen in the Phase-1 record; a record
 * written before (without questions) reads as an empty list, so Phase 2 runs as before.
 */
public class ConceptPinQuestionsTest {

    private static final BookManifest.CommitCheck ALL = new BookManifest.CommitCheck() {
        public boolean isCommitted(String transitionId) {
            return true;
        }
    };

    @Test
    public void theQuestionsAreFrozenWithTheConcept() throws Exception {
        Map<String, List<String>> perCard = new LinkedHashMap<String, List<String>>();
        perCard.put("Technik", Arrays.asList("Welche Sensoren gibt es?"));
        perCard.put("Recht", Arrays.asList("Was regelt die DSGVO?"));
        MissionQuestionList questions = MissionQuestionList.of(perCard, MissionQuestion.Origin.APP);
        File dir = Files.createTempDirectory("askai-pin-questions").toFile();
        BookManifest manifest = new BookManifest(dir);
        manifest.appendConceptPin(new ConceptPin("t1", 1, true, "hash", 7L, 1000L, "Mission", "20-30 pages", "de",
                Collections.<String>emptyList(), questions));

        ConceptPin pin = new BookManifest(dir).validConceptPin(ALL);

        assertEquals(questions.toText(), pin.getQuestions().toText());
        assertEquals("F02", pin.getQuestions().getQuestions().get(1).getId());
    }

    @Test
    public void aRecordWithoutQuestionsReadsAsAnEmptyList() throws Exception {
        File dir = Files.createTempDirectory("askai-pin-questions").toFile();
        BookManifest manifest = new BookManifest(dir);
        manifest.appendConceptPin(new ConceptPin("t1", 1, true, "hash", 7L, 1000L, "Mission", "", "de",
                Collections.<String>emptyList()));

        ConceptPin pin = new BookManifest(dir).validConceptPin(ALL);

        assertTrue(pin.getQuestions().isEmpty());
        assertFalse("no questions field is written for an empty list",
                new String(Files.readAllBytes(manifest.file().toPath()), "UTF-8").contains("\"questions\""));
    }
}

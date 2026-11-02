package com.aresstack.askai.config;

import com.aresstack.askai.config.HuggingFaceSearchSuggestion.Modality;
import com.aresstack.askai.config.HuggingFaceSearchSuggestion.Target;

import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The suggestion parser: the new third {@code | local} field is real meta-information, backward compatible
 * with the historical one- and two-column forms, and round-trips through {@link HuggingFaceSearchSuggestion#toLine()}.
 */
public class HuggingFaceSearchSuggestionTest {

    @Test
    public void oneColumnStaysGeneralTextModel() {
        HuggingFaceSearchSuggestion s = HuggingFaceSearchSuggestion.parseLine("llama-3.1-8b-instruct");
        assertEquals("llama-3.1-8b-instruct", s.getTerm());
        assertEquals(EnumSet.of(Modality.TEXT), s.getModalities());
        assertEquals(Target.GENERAL, s.getTarget());
        assertFalse(s.isLocalEngine());
    }

    @Test
    public void twoColumnIsBackwardCompatibleAndGeneral() {
        HuggingFaceSearchSuggestion s = HuggingFaceSearchSuggestion.parseLine("voxtral-mini-3b | text,audio");
        assertEquals("voxtral-mini-3b", s.getTerm());
        assertEquals(EnumSet.of(Modality.TEXT, Modality.AUDIO), s.getModalities());
        assertEquals(Target.GENERAL, s.getTarget());
    }

    @Test
    public void thirdFieldLocalPromotesToTheLocalEngine() {
        HuggingFaceSearchSuggestion s =
                HuggingFaceSearchSuggestion.parseLine("google/gemma-3-270m-it | text | local");
        assertEquals("google/gemma-3-270m-it", s.getTerm());
        assertEquals(Target.ASKAI_LOCAL_ENGINE, s.getTarget());
        assertTrue(s.isLocalEngine());
    }

    @Test
    public void unknownThirdFieldStaysGeneralAndForgiving() {
        HuggingFaceSearchSuggestion s =
                HuggingFaceSearchSuggestion.parseLine("some/model | text | something-else");
        assertEquals(Target.GENERAL, s.getTarget());
        // Local is case-insensitive.
        assertTrue(HuggingFaceSearchSuggestion.parseLine("m | text | LOCAL").isLocalEngine());
    }

    @Test
    public void toLineRoundTripsBothForms() {
        HuggingFaceSearchSuggestion general =
                new HuggingFaceSearchSuggestion("a/b", EnumSet.of(Modality.TEXT, Modality.VISION));
        assertEquals("a/b | text,vision", general.toLine());
        assertEquals(general.getTarget(),
                HuggingFaceSearchSuggestion.parseLine(general.toLine()).getTarget());

        HuggingFaceSearchSuggestion local = new HuggingFaceSearchSuggestion(
                "c/d", EnumSet.of(Modality.TEXT), Target.ASKAI_LOCAL_ENGINE);
        assertEquals("c/d | text | local", local.toLine());
        HuggingFaceSearchSuggestion reparsed = HuggingFaceSearchSuggestion.parseLine(local.toLine());
        assertEquals("c/d", reparsed.getTerm());
        assertTrue(reparsed.isLocalEngine());
    }

    @Test
    public void parseListDropsBlanksAndDuplicateTerms() {
        List<HuggingFaceSearchSuggestion> list = HuggingFaceSearchSuggestion.parseList(
                "a/b | text | local\n\n a/b | text \n c/d | text");
        assertEquals(2, list.size());
        assertTrue(list.get(0).isLocalEngine()); // first occurrence (with local) wins
        assertEquals("c/d", list.get(1).getTerm());
    }
}

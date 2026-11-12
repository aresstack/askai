package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds sentences in the first person plural ("wir", "unsere", "we", "our"). A book written from sources has no "we"
 * of its own: the word belongs to a passage's author, as in the manufacturer's "unsere tragbaren Geräte" and
 * "implementieren wir" that live run 4 carried into books 2 and 3. A quotation keeps its author's voice, so quoted
 * text is left out. German and English pronouns; "US" in capitals is the country. Stateless and deterministic, no
 * model call.
 */
public final class VoiceCheck {

    /** wir, uns, unser, unsere, unserem, unseren, unserer, unseres, we, us, our, ours (first letter in any case). */
    private static final Pattern FIRST_PERSON_PLURAL = Pattern.compile(
            "(?<![\\p{L}\\p{Nd}])(?:[Ww]ir|[Uu]ns(?:er(?:e[mnrs]?)?)?|[Ww]e|[Uu]s|[Oo]urs?)(?![\\p{L}\\p{Nd}])");
    /** Quoted text in the quotation marks a German or English book uses. */
    private static final Pattern QUOTED = Pattern.compile(
            "\"[^\"]*\"|„[^“”\"]*[“”\"]|“[^”]*”|«[^»]*»|»[^«]*«|‚[^‘’]*[‘’]");

    /** The first-person word a sentence uses outside its quotations, or "" when it has none. */
    public String firstPersonWord(String sentence) {
        String unquoted = QUOTED.matcher(sentence == null ? "" : sentence).replaceAll(" ");
        Matcher m = FIRST_PERSON_PLURAL.matcher(CitationValidator.MARKER.matcher(unquoted).replaceAll(" "));
        return m.find() ? m.group() : "";
    }

    /** One {@link ClaimFlag.Kind#FIRST_PERSON_VOICE} flag per sentence of the answer that speaks as "we". */
    public List<ClaimFlag> check(CitationValidator.Result result) {
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        List<CitationValidator.Paragraph> paragraphs = result.getParagraphs();
        for (int p = 0; p < paragraphs.size(); p++) {
            for (CitationValidator.Sentence sentence : paragraphs.get(p).getSentences()) {
                String word = firstPersonWord(sentence.getText());
                if (!word.isEmpty()) {
                    flags.add(new ClaimFlag(ClaimFlag.Kind.FIRST_PERSON_VOICE, p, sentence.getText(), word));
                }
            }
        }
        return flags;
    }
}

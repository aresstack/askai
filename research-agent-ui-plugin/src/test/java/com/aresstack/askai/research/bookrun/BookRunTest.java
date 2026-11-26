package com.aresstack.askai.research.bookrun;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.planwriter.PlanSectionWriter;
import com.aresstack.askai.research.planwriter.WrittenSection;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.Reader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 4 as a whole on the invented controlled basket with a scripted model and word vectors: passages outside the
 * fence by their source's profile are set aside before the clustering, questions come from the mission and from the
 * groups and are checked against mission and fence, questions outside are kept with their reason and not pursued, a
 * demanded question the basket does not answer is a gap for a further search, passages outside the fence are neither
 * evidence nor cited. No concept goes in.
 */
public class BookRunTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
    private static final List<String> OUTSIDE_PASSAGES = Arrays.asList("p03", "p08", "p10", "p13", "p14");

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    @Test
    public void fromTheBasketToTheBook() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        Script script = new Script();
        BookRun.Settings settings = new BookRun.Settings("de", stock.targetWords, 0, null, null, null,
                PlanSectionWriter.Options.defaults().withTopUp(false));

        BookRun.Result result = new BookRun(script, script, script, new WordVectors(), settings, CLOCK, null)
                .run(stock.fence, stock.basket, stock);

        BookRun.Opening opening = result.getOpening();
        assertEquals(7, opening.getProfiles().size());
        assertEquals("the pool part of the mixed source and the two sources outside are set aside",
                Arrays.asList("p08", "p10", "p13"), new ArrayList<String>(opening.getSetAside().keySet()));
        assertEquals(11, opening.getPassages().size());
        assertEquals(Arrays.asList("quellensteckbriefe", "gruppierung", "fragen", "fragenpruefung", "belegpruefung",
                "abschnittsplan", "schreiben"), names(result.getSteps()));
        assertEquals(7, result.getSteps().get(0).getCalls());
        assertEquals("no model call for the clustering", 0, result.getSteps().get(1).getCalls());
        assertEquals("one call for the mission, one per group", 1 + opening.getGroups().size(),
                result.getSteps().get(2).getCalls());
        assertEquals("all questions in one bundle", 1, result.getSteps().get(3).getCalls());
        assertEquals(0, result.getModelSwitches());
        Set<String> grouped = new LinkedHashSet<String>();
        for (BookRun.Group group : opening.getGroups()) {
            grouped.addAll(group.getPassageIds());
        }
        assertEquals("every passage inside is in a group", 11, grouped.size());

        Set<String> found = new LinkedHashSet<String>();
        Set<String> demanded = new LinkedHashSet<String>();
        for (BookRun.Question question : opening.getQuestions()) {
            (question.getOrigin() == BookRun.Question.Origin.PFLICHT ? demanded : found).add(question.getText());
            assertEquals(question.getText(), question.getText().equals(Script.DRINKING), !question.isPursued());
        }
        assertTrue(demanded.toString(), demanded.containsAll(Script.ANSWERS.keySet()));
        assertTrue("a question both sides ask is one question", java.util.Collections.disjoint(found, demanded));
        assertTrue("the question outside is kept", found.contains(Script.DRINKING));
        assertFalse("the pool part was not clustered", found.contains(Script.POOL));
        assertTrue(demanded.toString(), demanded.contains(Script.FUNDING));

        for (EvidenceVerdict verdict : result.getVerdicts()) {
            if (OUTSIDE_PASSAGES.contains(verdict.getPassageId())) {
                assertFalse(verdict.getPassageId(), verdict.getKind() == EvidenceVerdict.Kind.BELEGT);
            }
        }
        assertFalse(result.getSections().isEmpty());
        for (WrittenSection section : result.getSections()) {
            assertEquals(section.getFailure(), WrittenSection.Status.OK, section.getStatus());
            for (WrittenSection.Citation citation : section.getCitations().values()) {
                assertFalse(citation.getPassageId(), OUTSIDE_PASSAGES.contains(citation.getPassageId()));
            }
        }
        for (BookRun.QuestionLine line : result.getQuestionLines()) {
            if (!line.getQuestion().isPursued()) {
                assertEquals(BookRun.QuestionLine.State.AUSSERHALB, line.getState());
                assertTrue(line.getReason(), line.getReason().startsWith("außerhalb des Weidezauns: "));
            } else if (line.getQuestion().getText().equals(Script.FUNDING)) {
                assertEquals(BookRun.QuestionLine.State.NACHRECHERCHE, line.getState());
                assertTrue(line.getReason(), line.getReason().contains("Nachrecherche"));
            } else {
                assertEquals(line.getQuestion().getText() + ": " + line.getReason(),
                        BookRun.QuestionLine.State.BEANTWORTET, line.getState());
            }
        }
        assertEquals(0, result.getCitedOutsideFence());
        assertTrue(result.book(), result.book().startsWith("# "));

        File out = scratch.newFolder("out");
        JsonObject json = stock.write(result, out, new JsonObject());
        for (String name : new String[]{"fragen.json", "fragen.md", "ergebnis.json", "buch.md", "bericht.txt"}) {
            assertTrue(name, new File(out, name).isFile());
        }
        assertEquals(0, json.getAsJsonObject("klassen").getAsJsonObject("irrelevant").get("belegt").getAsInt());
        assertEquals(0, json.getAsJsonObject("klassen").getAsJsonObject("gemischt-aussen").get("zitiert").getAsInt());
        assertEquals(Script.ANSWERS.size(),
                json.getAsJsonObject("fragenAbdeckung").get("BEANTWORTET").getAsInt());
        assertEquals(1, json.getAsJsonObject("fragenAbdeckung").get("NACHRECHERCHE").getAsInt());
    }

    @Test
    public void theOpeningAloneNeedsNoWriter() throws Exception {
        BasketStock stock;
        try (Reader in = BasketStock.resource("korb-kontrolliert.json")) {
            stock = BasketStock.korb(in);
        }
        Script script = new Script();
        BookRun.Opening opening = new BookRun(script, script, null, new WordVectors(),
                BookRun.Settings.of("de", 0), CLOCK, null).open(stock.fence, stock.basket, stock);

        assertEquals(4, opening.getSteps().size());
        assertFalse(opening.getQuestions().isEmpty());
        JsonObject json = stock.writeOpening(opening, scratch.newFolder("fragen"), new JsonObject());
        assertTrue(json.getAsJsonObject("fragenNachUrteil").get("AUSSEN").getAsInt() > 0);
    }

    private static List<String> names(List<BookRun.Step> steps) {
        List<String> out = new ArrayList<String>();
        for (BookRun.Step step : steps) {
            out.add(step.getName());
        }
        return out;
    }

    /**
     * Word vectors: one dimension per word of six letters or more (hashed), so passages with the same words lie close.
     * Deterministic and enough to group the invented basket.
     */
    static final class WordVectors implements EmbeddingPort {
        private static final int DIMENSIONS = 256;

        @Override
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (String text : texts) {
                float[] values = new float[DIMENSIONS];
                values[0] = 0.01f;
                for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
                    if (word.length() >= 6) {
                        values[Math.floorMod(word.hashCode(), DIMENSIONS - 1) + 1] += 1f;
                    }
                }
                out.add(new EmbeddingVector("words", "words-" + role, values));
            }
            return out;
        }
    }

    /** The scripted model: profiles, questions, question check, evidence, writer and sentence check by role. */
    static final class Script implements MainModelChat {
        private static final Pattern BLOCK = Pattern.compile("\\[(P\\d+)][^\\n]*\\n(?:[^\\n]*\\n)*?\"\"\"\\n(.*?)( …)?"
                + "\\n\"\"\"", Pattern.DOTALL);
        private static final Pattern DRAFT_BLOCK = Pattern.compile("\\[\\d+][^\\n]*\\n\"\"\"\\n(.*?)\\n\"\"\"",
                Pattern.DOTALL);
        private static final Pattern QUESTION = Pattern.compile("\\[(F\\d+)] ([^\\n]*)");
        private static final Pattern WRITER_PASSAGE = Pattern.compile("\\[(\\d+)] \"\"\"(.*?)\"\"\"", Pattern.DOTALL);
        private static final Pattern SENTENCE_LABEL = Pattern.compile("\\[(S\\d+)] „");
        static final String POOL = "Wie reinigt man einen Pool vor der Saison?";
        static final String DRINKING = "Wie bereitet man Regenwasser zu Trinkwasser auf?";
        /** Demanded by the mission, answered by no passage of the basket. */
        static final String FUNDING = "Welche Förderprogramme gibt es für Regenwasseranlagen?";
        /** The questions the invented passages answer, each with words of the passages that answer it. */
        static final Map<String, List<String>> ANSWERS = new LinkedHashMap<String, List<String>>();
        /** Questions outside the fence a careless draft would ask, with the words that tempt it. */
        static final Map<String, List<String>> TEMPTING = new LinkedHashMap<String, List<String>>();

        static {
            ANSWERS.put("Aus welchen Bauteilen besteht eine Regenwassernutzungsanlage?",
                    Arrays.asList("besteht aus einem Filter"));
            ANSWERS.put("Wofür eignet sich Regenwasser im Haushalt?",
                    Arrays.asList("eignet sich für die Toilettenspülung", "Regentonne"));
            ANSWERS.put("Wem muss man die Anlage anzeigen, bevor sie in Betrieb geht?",
                    Arrays.asList("Gesundheitsamt"));
            ANSWERS.put("Wie werden Trinkwasser- und Regenwasserleitungen getrennt?",
                    Arrays.asList("nie mit dem Trinkwassernetz"));
            ANSWERS.put("Was kostet eine Anlage und wann rechnet sie sich?",
                    Arrays.asList("3.000 und 6.000", "15 bis 20 Jahren", "fünf Jahren"));
            ANSWERS.put("Wie viel Trinkwasser lässt sich einsparen?", Arrays.asList("40 Prozent", "Hälfte"));
            TEMPTING.put(POOL, Arrays.asList("Pool"));
            TEMPTING.put(DRINKING, Arrays.asList("Trinkwasser aufbereiten"));
        }

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            calls.incrementAndGet();
            if (messages.get(0).getContent().startsWith("You write one sentence of a non-fiction book again")) {
                return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE, "no rewrite");
            }
            Matcher passage = WRITER_PASSAGE.matcher(messages.get(messages.size() - 1).getContent());
            StringBuilder text = new StringBuilder();
            while (passage.find()) {
                text.append(firstSentence(passage.group(2))).append(" [").append(passage.group(1)).append("]. ");
            }
            return text.length() == 0 ? MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE,
                    "no passage") : MainModelChatResult.ok(text.toString().trim());
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            String system = messages.get(0).getContent();
            String prompt = messages.get(messages.size() - 1).getContent();
            if (system.startsWith("Du beschreibst Quellen")) {
                return MainModelChatResult.ok(profile(prompt));
            }
            if (system.startsWith("Du planst ein Sachbuch")) {
                JsonArray questions = new JsonArray();
                for (String question : ANSWERS.keySet()) {
                    questions.add(question);
                }
                questions.add(FUNDING);
                JsonObject o = new JsonObject();
                o.addProperty("titel", "Regenwasser im Haus");
                o.add("fragen", questions);
                return MainModelChatResult.ok(o.toString());
            }
            if (system.startsWith("Du erschließt einen Wissensbestand")) {
                return MainModelChatResult.ok(draft(prompt));
            }
            if (system.startsWith("Du prüfst Fragen eines Sachbuchs")) {
                return MainModelChatResult.ok(check(prompt));
            }
            if (system.startsWith("Du prüfst für ein Sachbuch, welche Passagen")) {
                return MainModelChatResult.ok(evidence(prompt));
            }
            if (system.startsWith("Du prüfst Sätze")) {
                return MainModelChatResult.ok(sentences(prompt, schemaJson != null && schemaJson.contains("urteile")));
            }
            return MainModelChatResult.failure(MainModelChatResult.Status.INVALID_RESPONSE, "unknown role");
        }

        @Override
        public String modelName() {
            return "script";
        }

        private static String profile(String prompt) {
            JsonObject o = new JsonObject();
            o.addProperty("anbieter", "Herausgeber");
            o.addProperty("grund", "Erfundene Quelle des Tests.");
            o.addProperty("quellenart", "SONSTIGE");
            o.addProperty("rechtsraum", prompt.contains("alpenland") ? "AT" : "DE");
            o.addProperty("eigeninteresse", "NEIN");
            o.addProperty("datum", "");
            o.addProperty("gegenstand", "Wasser");
            JsonArray outside = new JsonArray();
            if (prompt.contains("haus-und-garten")) {
                o.addProperty("zaun", "GEMISCHT");
                outside.add("Wasser rund ums Haus > Den Pool richtig reinigen");
            } else {
                o.addProperty("zaun", prompt.contains("industrie-wasser.example") || prompt.contains("alpenland")
                        ? "AUSSEN" : "INNEN");
            }
            o.add("aussenTeile", outside);
            return o.toString();
        }

        /** Every question whose words stand in a passage of the group, the tempting ones as well. */
        private static String draft(String prompt) {
            JsonArray questions = new JsonArray();
            Matcher block = DRAFT_BLOCK.matcher(prompt);
            Set<String> asked = new LinkedHashSet<String>();
            while (block.find()) {
                String text = block.group(1);
                for (Map<String, List<String>> map : Arrays.asList(ANSWERS, TEMPTING)) {
                    for (Map.Entry<String, List<String>> entry : map.entrySet()) {
                        for (String mark : entry.getValue()) {
                            if (text.contains(mark) && asked.add(entry.getKey())) {
                                questions.add(entry.getKey());
                            }
                        }
                    }
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("titel", asked.isEmpty() ? "Sonstiges" : asked.iterator().next());
            o.add("fragen", questions);
            return o.toString();
        }

        private static String check(String prompt) {
            JsonArray list = new JsonArray();
            Matcher question = QUESTION.matcher(prompt);
            while (question.find()) {
                boolean outside = TEMPTING.containsKey(question.group(2).trim());
                JsonObject o = new JsonObject();
                o.addProperty("nr", question.group(1));
                o.addProperty("grund", outside ? "Im Weidezaun ausgeschlossen." : "Gehört zum Auftrag.");
                o.addProperty("urteil", outside ? "AUSSEN" : "INNEN");
                list.add(o);
            }
            JsonObject top = new JsonObject();
            top.add("urteile", list);
            return top.toString();
        }

        private static String evidence(String prompt) {
            String question = between(prompt, "Frage, die das Buch beantworten muss:\n", "\n");
            List<String> answersTo = ANSWERS.containsKey(question) ? ANSWERS.get(question)
                    : new ArrayList<String>();
            JsonArray list = new JsonArray();
            Matcher block = BLOCK.matcher(prompt);
            while (block.find()) {
                String text = block.group(2);
                boolean outside = text.contains("Trinkwasser aufbereiten");
                for (String word : Arrays.asList("Pool", "Brauereien", "Österreich", "Rasenmäher")) {
                    outside |= text.contains(word);
                }
                boolean answers = false;
                for (String mark : answersTo) {
                    answers |= text.contains(mark);
                }
                JsonObject o = new JsonObject();
                o.addProperty("nr", block.group(1));
                o.addProperty("zitat", answers && !outside ? firstSentence(text) : "");
                o.addProperty("aussage", answers && !outside ? firstSentence(text) + "." : "nichts");
                o.addProperty("anwendbar", outside ? "NEIN" : "JA");
                o.addProperty("urteil", answers && !outside ? "BELEGT" : "KEIN");
                list.add(o);
            }
            JsonObject top = new JsonObject();
            top.add("urteile", list);
            return top.toString();
        }

        private static String sentences(String prompt, boolean bundled) {
            String passage = between(prompt, "Zitierte Passage:\n„", "“\n");
            if (!bundled) {
                return entry(passage, null).toString();
            }
            JsonArray list = new JsonArray();
            Matcher label = SENTENCE_LABEL.matcher(prompt);
            while (label.find()) {
                list.add(entry(passage, label.group(1)));
            }
            JsonObject top = new JsonObject();
            top.add("urteile", list);
            return top.toString();
        }

        private static JsonObject entry(String passage, String label) {
            JsonObject o = new JsonObject();
            if (label != null) {
                o.addProperty("nr", label);
            }
            o.addProperty("belegart", "TEXT");
            o.addProperty("zitat", passage);
            o.addProperty("grund", "Die Passage sagt das.");
            o.addProperty("urteil", "GEDECKT");
            return o;
        }

        private static String firstSentence(String text) {
            String clean = text.replaceAll("\\s+", " ").trim();
            int end = clean.indexOf(". ");
            String first = end < 0 ? clean : clean.substring(0, end);
            return first.endsWith(".") ? first.substring(0, first.length() - 1) : first;
        }

        private static String between(String text, String start, String end) {
            int from = text.indexOf(start);
            if (from < 0) {
                return "";
            }
            from += start.length();
            int to = text.indexOf(end, from);
            return to < 0 ? text.substring(from) : text.substring(from, to);
        }
    }
}

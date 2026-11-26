package com.aresstack.askai.research.conceptrun;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The concept-led run as a whole on the invented controlled bestand with a scripted model: every step is called, the
 * calls are counted per step, passages outside the fence are neither evidence nor cited, every original question has
 * a line. The script answers by role; it decides what lies outside by words of the invented passages.
 */
public class ConceptRunTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
    private static final List<String> OUTSIDE = Arrays.asList("Pool", "Brauereien", "Österreich", "Rasenmäher");

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    @Test
    public void theWholeRunOnTheControlledBestand() throws Exception {
        ConceptStock stock;
        try (Reader in = ConceptStock.resource("bestand-kontrolliert.json")) {
            stock = ConceptStock.bestand(in);
        }
        Script script = new Script();
        ConceptRun.Settings settings = new ConceptRun.Settings("de", stock.targetWords, null, null,
                PlanSectionWriter.Options.defaults().withTopUp(false));

        ConceptRun.Result result = new ConceptRun(script, script, script, settings, CLOCK, null).run(stock.fence,
                stock.cards, stock.questions, stock.passages, null);

        assertEquals(7, result.getProfiles().size());
        assertEquals("quellensteckbriefe", result.getSteps().get(0).getName());
        assertEquals(7, result.getSteps().get(0).getCalls());
        assertEquals("one call per question: the 14 passages fit into one bundle", 6,
                result.getSteps().get(1).getCalls());
        assertEquals(0, result.getSteps().get(2).getCalls());
        assertTrue(result.getSteps().get(3).getCalls() > 0);
        assertEquals(0, result.getModelSwitches());
        for (EvidenceVerdict verdict : result.getVerdicts()) {
            if (Arrays.asList("p08", "p10", "p13", "p14").contains(verdict.getPassageId())) {
                assertEquals(verdict.getPassageId(), EvidenceVerdict.Kind.KEIN, verdict.getKind());
            }
        }
        assertFalse(result.getSections().isEmpty());
        for (WrittenSection section : result.getSections()) {
            assertEquals(section.getFailure(), WrittenSection.Status.OK, section.getStatus());
            for (WrittenSection.Citation citation : section.getCitations().values()) {
                assertFalse(citation.getPassageId(), Arrays.asList("p08", "p10", "p13", "p14")
                        .contains(citation.getPassageId()));
            }
        }
        assertEquals(6, result.getQuestions().size());
        for (ConceptRun.QuestionLine line : result.getQuestions()) {
            assertEquals(line.getQuestion().getId() + ": " + line.getReason(), ConceptRun.QuestionLine.State.BEANTWORTET,
                    line.getState());
        }
        assertEquals(0, result.getCitedOutsideFence());
        assertTrue(result.book(), result.book().startsWith("# "));

        File out = scratch.newFolder("out");
        JsonObject json = stock.write(result, out, new JsonObject());
        assertTrue(new File(out, "buch.md").isFile());
        assertTrue(new File(out, "bericht.txt").isFile());
        assertEquals(0, json.getAsJsonObject("klassen").getAsJsonObject("irrelevant").get("belegt").getAsInt());
        assertEquals(0, json.getAsJsonObject("klassen").getAsJsonObject("gemischt-aussen").get("zitiert").getAsInt());
        assertEquals(6, json.getAsJsonObject("fragenAbdeckung").get("BEANTWORTET").getAsInt());
    }

    /** The scripted model: source profiles, evidence bundles, the writer and the sentence check by role. */
    static final class Script implements MainModelChat {
        private static final Pattern BLOCK = Pattern.compile("\\[(P\\d+)][^\\n]*\\n(?:[^\\n]*\\n)*?\"\"\"\\n(.*?)( …)?"
                + "\\n\"\"\"", Pattern.DOTALL);
        private static final Pattern WRITER_PASSAGE = Pattern.compile("\\[(\\d+)] \"\"\"(.*?)\"\"\"", Pattern.DOTALL);
        private static final Pattern SENTENCE_LABEL = Pattern.compile("\\[(S\\d+)] „");
        /** By a word of the question, words of the passages that answer it (what the invented texts say). */
        private static final Map<String, List<String>> ANSWERS = new LinkedHashMap<String, List<String>>();

        static {
            ANSWERS.put("Bauteilen", Arrays.asList("besteht aus einem Filter"));
            ANSWERS.put("Wofür", Arrays.asList("eignet sich für die Toilettenspülung", "Regentonne"));
            ANSWERS.put("Anzeige", Arrays.asList("Gesundheitsamt"));
            ANSWERS.put("getrennt", Arrays.asList("nie mit dem Trinkwassernetz"));
            ANSWERS.put("kostet", Arrays.asList("3.000 und 6.000", "15 bis 20 Jahren", "fünf Jahren"));
            ANSWERS.put("einsparen", Arrays.asList("40 Prozent", "Hälfte"));
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
                o.addProperty("zaun", prompt.contains("industrie-wasser.example") || prompt.contains("alpenland") ? "AUSSEN"
                        : "INNEN");
            }
            o.add("aussenTeile", outside);
            return o.toString();
        }

        private static String evidence(String prompt) {
            String question = between(prompt, "Frage, die das Buch beantworten muss:\n", "\n");
            List<String> answersTo = Collections.emptyList();
            for (Map.Entry<String, List<String>> entry : ANSWERS.entrySet()) {
                if (question.contains(entry.getKey())) {
                    answersTo = entry.getValue();
                }
            }
            JsonArray list = new JsonArray();
            Matcher block = BLOCK.matcher(prompt);
            while (block.find()) {
                String text = block.group(2);
                boolean outside = text.contains("Trinkwasser aufbereiten");
                for (String word : OUTSIDE) {
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

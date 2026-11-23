package com.aresstack.askai.research.pagenoise;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.pagenoise.PageNoiseCheck.Options;
import com.aresstack.askai.research.pagenoise.PageNoiseCheck.Passage;
import com.aresstack.askai.research.pagenoise.PageNoiseCheck.Result;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The page-noise check alone (JUnit, no model): self-written road traffic passages, a scripted model. Stage 1 marks
 * without a call, stage 2 is asked only for what stage 1 lets through, FEHLER is never noise, a mark that holds is not
 * given again, every mark is written the moment it is there.
 */
public class PageNoiseCheckTest {

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    static final String CONTENT = "In Tempo-30-Zonen gilt für alle Fahrzeuge höchstens 30 km/h, und die Gemeinde "
            + "ordnet eine solche Zone erst nach einer Prüfung der Verkehrslage an.";
    static final String CONTENT_2 = "Wer innerorts deutlich zu schnell fährt, muss mit einem Bußgeld, mit Punkten in "
            + "Flensburg und bei groben Verstößen mit einem Fahrverbot rechnen.";
    static final String AD = "Unsere Fahrschule bietet Ihnen moderne Fahrzeuge, erfahrene Lehrer und flexible Termine, "
            + "damit Sie schnell und sicher zum Führerschein kommen.";
    static final String CONTENTS = "Kapitel 1 Grundregeln im Straßenverkehr, Kapitel 2 Vorfahrt und Abbiegen, "
            + "Kapitel 3 Parken und Halten, Kapitel 4 Bußgelder.";
    static final String MIXED = "Ein Fahrradhelm senkt das Risiko schwerer Kopfverletzungen deutlich, wie mehrere "
            + "Studien zeigen. Unsere Helme finden Sie im Laden um die Ecke.";
    static final String MENU = "Startseite Verkehrsregeln Bußgeldrechner Fahrschulen Kontakt Impressum";
    static final String COOKIES = "Diese Website verwendet Cookies, damit wir die Seite für Sie verbessern und die "
            + "Nutzung der Inhalte auswerten können.";
    static final String COUPON = "Mit dem Gutschein FAHR10 sparen Sie zehn Prozent auf alle Kurse der Fahrschule, "
            + "wenn Sie bis Ende Mai buchen.";
    static final String LINKS = "Mehr dazu finden Sie unter www.verkehr.example, https://verkehr.example/a und "
            + "https://verkehr.example/b sowie www.amt.example.";

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T06:00:00Z"), ZoneOffset.UTC);

    /** Answers by the words of the passage, the way a careful small model would. */
    static String careful(String prompt) {
        if (prompt.contains("Fahrschule bietet Ihnen")) {
            return answer("Eine Fahrschule wirbt für ihre Kurse.", "WERBUNG");
        }
        if (prompt.contains("Kapitel 1")) {
            return answer("Ein Inhaltsverzeichnis verweist auf Kapitel.", "VERZEICHNIS");
        }
        if (prompt.contains("Unsere Helme")) {
            return answer("Ein Text erklärt den Nutzen von Helmen, am Ende steht ein Werbesatz.", "AUSSAGE");
        }
        return answer("Ein Text nennt eine Regel.", "AUSSAGE");
    }

    /** An answer whose statement fits the kind: a rule for AUSSAGE, "keine" for a leftover. */
    static String answer(String description, String kind) {
        return answer(description, kind.trim().equalsIgnoreCase("AUSSAGE") ? "Für Tempo-30-Zonen gilt eine Regel."
                : "keine", kind);
    }

    static String answer(String description, String statement, String kind) {
        JsonObject json = new JsonObject();
        json.addProperty("beschreibung", description);
        json.addProperty("aussage", statement);
        json.addProperty("art", kind);
        return json.toString();
    }

    /** A chat model that answers each prompt through a function. */
    static final class ScriptedChat implements MainModelChat {
        final Function<String, String> answer;
        final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());
        final List<List<ChatMessage>> calls = Collections.synchronizedList(new ArrayList<List<ChatMessage>>());
        final List<String> schemas = Collections.synchronizedList(new ArrayList<String>());

        ScriptedChat(Function<String, String> answer) {
            this.answer = answer;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            prompts.add(messages.get(1).getContent());
            calls.add(new ArrayList<ChatMessage>(messages));
            schemas.add(schemaJson);
            String text = answer.apply(messages.get(messages.size() - 1).getContent().contains("ungültig")
                    ? "REPARATUR " + messages.get(1).getContent() : messages.get(1).getContent());
            return text == null ? MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "down")
                    : MainModelChatResult.ok(text);
        }

        @Override
        public String modelName() {
            return "gemma4:e2b";
        }
    }

    static Passage passage(String id, String text) {
        return Passage.of(id, "s1", "https://www.verkehr.example/regeln", text);
    }

    static PageNoiseCheck check(MainModelChat chat) {
        return new PageNoiseCheck(chat, "de", Options.defaults(), null, CLOCK);
    }

    @Test
    public void theProseRulesMarkWithoutAModelCall() throws Exception {
        ScriptedChat chat = new ScriptedChat(prompt -> {
            throw new AssertionError("no call for what the rules mark: " + prompt);
        });
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Arrays.asList(passage("p1", MENU), passage("p2", COOKIES),
                passage("p3", COUPON), passage("p4", LINKS)), marks);

        assertEquals(4, result.getByRules());
        assertEquals(0, result.getAsked());
        List<NoiseMark.Kind> kinds = new ArrayList<NoiseMark.Kind>();
        for (NoiseMark mark : result.getMarks()) {
            assertTrue(mark.isNoise());
            assertEquals(NoiseMark.RULES, mark.getStage());
            assertFalse(mark.getReason().isEmpty());
            assertEquals("", mark.getModel());
            kinds.add(mark.getKind());
        }
        assertEquals(Arrays.asList(NoiseMark.Kind.STRUKTUR, NoiseMark.Kind.SEITENHINWEIS, NoiseMark.Kind.WERBUNG,
                NoiseMark.Kind.NAVIGATION), kinds);
        assertEquals("6 words < 10", marks.mark(PageNoiseCheck.textKey(passage("p1", MENU))).getReason());
        assertTrue(marks.isNoise(PageNoiseCheck.textKey(passage("p2", COOKIES))));
    }

    /** Live run 4: calendar slogans between the product lines of a shop page formed a topic of their own. */
    @Test
    public void aLoneShortSentenceOnAListingPageIsACaption() throws Exception {
        List<Passage> shop = new ArrayList<Passage>();
        for (int i = 0; i < 40; i++) {
            shop.add(Passage.of("h" + i, "shop", "https://shop.example/helme", "Helm Modell " + i + " Blau Größe M"));
        }
        shop.set(10, Passage.of("h10", "shop", "https://shop.example/helme", "Sicher ankommen ist das Ziel jeder "
                + "Fahrt mit dem Rad."));
        List<Passage> page = new ArrayList<Passage>(shop);
        page.add(passage("p1", CONTENT));
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();

        check(chat).check(page, Arrays.asList("h10", "p1"), marks);

        NoiseMark caption = marks.mark(PageNoiseCheck.textKey(shop.get(10)));
        assertEquals(NoiseMark.Kind.BILDUNTERSCHRIFT, caption.getKind());
        assertEquals(NoiseMark.RULES, caption.getStage());
        assertEquals("only the content of the other page went to the model", 1, chat.prompts.size());
        assertEquals("only the wanted passages get a mark", 2, marks.size());
    }

    @Test
    public void onlyWhatTheRulesLetThroughGoesToTheModel() throws Exception {
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Arrays.asList(passage("p1", CONTENT), passage("p2", AD),
                passage("p3", MENU), passage("p4", CONTENTS), passage("p5", MIXED)), marks);

        assertEquals(5, result.getPassages());
        assertEquals(5, result.getTexts());
        assertEquals(1, result.getByRules());
        assertEquals(4, result.getAsked());
        assertEquals(4, chat.prompts.size());
        List<String> got = new ArrayList<String>();
        for (NoiseMark mark : result.getMarks()) {
            got.add(mark.getPassageId() + " " + mark.getVerdict() + " " + mark.getKind() + " " + mark.getStage());
        }
        assertEquals(Arrays.asList("p1 INHALT KEINE 2", "p2 RAUSCHEN WERBUNG 2", "p3 RAUSCHEN STRUKTUR 1",
                "p4 RAUSCHEN VERZEICHNIS 2", "p5 INHALT KEINE 2"), got);
        assertEquals(3, result.count(NoiseMark.Verdict.RAUSCHEN));
        assertEquals("the model gives no MISCH", 0, result.count(NoiseMark.Verdict.MISCH));
        assertEquals(Integer.valueOf(1), result.noiseByKind().get(NoiseMark.Kind.VERZEICHNIS));
        assertFalse("a statement with one line of advertising is content: its biggest part decides",
                marks.isNoise(PageNoiseCheck.textKey(passage("p5", MIXED))));
        NoiseMark ad = marks.mark(PageNoiseCheck.textKey(passage("p2", AD)));
        assertEquals("gemma4:e2b", ad.getModel());
        assertEquals("Eine Fahrschule wirbt für ihre Kurse.", ad.getReason());
        assertEquals(PageNoiseCheck.VERSION, ad.getVersion());
        assertEquals(CLOCK.millis(), ad.getTimeMillis());
    }

    @Test
    public void thePromptShowsThePassageAndItsDomainButNoMission() throws Exception {
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);

        check(chat).check(Collections.singletonList(passage("p1", CONTENT)), new NoiseMarks());

        String prompt = chat.prompts.get(0);
        assertTrue(prompt, prompt.startsWith("Textstück von verkehr.example:\n„" + CONTENT + "“\n\nBestimme, was für "
                + "ein Text das Stück ist. Entscheide nach seinem größten Teil.\n\nSeitenrest gehört zur Seite, nicht "
                + "zur Sache:\n- \"WERBUNG\": will verkaufen oder Kunden gewinnen"));
        int at = 0;
        for (String kind : PageNoiseCheck.kindWords()) {
            int next = prompt.indexOf("\n- \"" + kind + "\": ", at);
            assertTrue("every kind once, in order: " + kind, next > at);
            at = next;
        }
        assertTrue(prompt, prompt.contains("Aufrufe zum Kaufen, Bestellen, Buchen, Testen, Anmelden oder Anfragen, Lob "
                + "oder Empfehlung des eigenen Angebots."));
        assertTrue(prompt, prompt.contains("spricht über die Website, die Reihe oder die Organisation selbst statt über "
                + "die Sache"));
        assertTrue("version 6", prompt.contains("Was ein Produkt, eine Leistung oder ein Dokument leistet, enthält oder "
                + "regelt, ist keine Seitenbeschreibung."));
        assertTrue("version 6", prompt.contains("Verweise auf andere Beiträge der eigenen Seite"));
        assertTrue(prompt, prompt.contains("etwa Eckdaten eines Dokuments oder einer Vorschrift (Titel, Datum, Stand, "
                + "Änderungen, Fundstelle, Aktenzeichen, Herausgeber), bloße Namenslisten (Autoren, Mitwirkende, "
                + "Mitglieder) oder Formularfelder."));
        assertTrue(prompt, prompt.contains("Sagt ein Anbieter sachlich, was ein Produkt oder eine Leistung kann oder "
                + "bringt, ohne zum Kauf aufzurufen und ohne es zu loben, ist das keine Werbung."));
        assertTrue(prompt, prompt.contains("auch was ein Anbieter sachlich über die Sache erklärt oder darüber, was "
                + "ein Produkt oder eine Leistung kann oder bringt."));
        assertFalse("version 5: a provider's factual description is no advertising any more",
                prompt.contains("selbst wenn es sachlich klingt"));
        assertTrue(prompt, prompt.contains("Eine Liste, deren Einträge etwas aussagen, ist kein Verzeichnis."));
        assertTrue(prompt, prompt.contains("die nur Fundstellen nennen, auch wenn ein einleitender Satz davorsteht."));
        assertTrue(prompt, prompt.contains("eine Zeile, die nur ankündigt, was folgt (etwa eine Liste), ohne selbst "
                + "etwas auszusagen."));
        assertFalse("version 6", prompt.contains("was der Leser darin erfährt"));
        assertTrue(prompt, prompt.contains("\nKein Seitenrest:\n- \"AUSSAGE\": informiert über die Sache"));
        assertTrue(prompt, prompt.contains("Prüfstein: Ein Sachbuch könnte das Stück als Aussage zur Sache zitieren."));
        assertTrue(prompt, prompt.contains("Ob die Sache zu einem Buch passt, spielt keine Rolle."));
        assertTrue(prompt, prompt.endsWith("Antworte nur mit JSON: {\"beschreibung\": ein kurzer Satz, wer hier "
                + "spricht und wozu, \"aussage\": was das Stück selbst über die Sache aussagt, in einem kurzen Satz, "
                + "oder \"keine\", wenn es nichts darüber aussagt (es kündigt nur an, verweist, wirbt, stellt vor oder "
                + "nennt nur Namen, Titel, Daten oder Fundstellen), \"art\": eine der Arten oben}"));
        for (String verdict : new String[]{"RAUSCHEN", "\"INHALT\"", "MISCH", "KEINE", "urteil"}) {
            assertFalse("the model gives no verdict: " + verdict, prompt.contains(verdict));
        }
        assertFalse("the prose rules' kind is not the model's", prompt.contains("STRUKTUR"));
        assertFalse("no mission", prompt.contains("Buchauftrag"));
        assertFalse("no id", prompt.matches("(?s).*\\b(p1|s1)\\b.*"));
        assertEquals(PageNoiseCheck.SYSTEM_DE, chat.calls.get(0).get(0).getContent());
        assertEquals(PageNoiseCheck.SCHEMA, chat.schemas.get(0));
        for (String word : new String[]{"STRUKTUR", "KEINE", "RAUSCHEN", "MISCH", "urteil", "klasse"}) {
            assertFalse(word, PageNoiseCheck.SCHEMA.contains(word));
        }
        assertTrue(PageNoiseCheck.SCHEMA.contains("\"required\":[\"beschreibung\",\"aussage\",\"art\"]"));
    }

    @Test
    public void anEnglishBookGetsEnglishPromptsAndALongPassageIsCut() throws Exception {
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        StringBuilder longText = new StringBuilder();
        while (longText.length() < 3000) {
            longText.append(CONTENT).append(' ');
        }

        new PageNoiseCheck(chat, "en", Options.defaults().withPassageChars(500), null, CLOCK)
                .check(Collections.singletonList(new Passage("p1", "s1", "", longText.toString())), new NoiseMarks());

        String prompt = chat.prompts.get(0);
        assertTrue(prompt, prompt.startsWith("Piece of text from a source without address:\n\""));
        assertTrue(prompt, prompt.contains("Say what kind of text the piece is. Decide by its biggest part."));
        assertTrue(prompt, prompt.contains("\n- \"VERZEICHNIS\": points to other texts"));
        assertTrue(prompt, prompt.contains("\nNo page leftover:\n- \"AUSSAGE\": informs about the matter"));
        assertTrue(prompt, prompt.contains("Whether the matter fits a book does not count."));
        assertTrue(prompt, prompt.endsWith("\"aussage\": what the piece itself says about the matter, in one short "
                + "sentence, or \"keine\" if it says nothing about it (it only announces, points elsewhere, "
                + "advertises, presents someone or only names names, titles, dates or references), \"art\": one of "
                + "the kinds above}"));
        assertTrue(prompt, prompt.contains(" …\"\n\n"));
        assertTrue(prompt, prompt.indexOf("\"\n\n") < 600);
        assertEquals(PageNoiseCheck.SYSTEM_EN, chat.calls.get(0).get(0).getContent());
    }

    @Test
    public void anInvalidAnswerGetsOneRepair() throws Exception {
        ScriptedChat chat = new ScriptedChat(prompt -> prompt.startsWith("REPARATUR")
                ? answer("Ein Text nennt eine Regel zum Tempo.", " aussage ") : "{\"art\":\"VIELLEICHT\"}");
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Collections.singletonList(passage("p1", CONTENT)), marks);

        NoiseMark mark = result.getMarks().get(0);
        assertEquals(NoiseMark.Status.OK, mark.getStatus());
        assertEquals(NoiseMark.Verdict.INHALT, mark.getVerdict());
        assertEquals(2, chat.calls.size());
        List<ChatMessage> repair = chat.calls.get(1);
        assertEquals(4, repair.size());
        assertTrue(repair.get(3).getContent(), repair.get(3).getContent().startsWith("Deine Antwort war ungültig: "
                + "\"art\" must be one of WERBUNG, SEITENBESCHREIBUNG, VERZEICHNIS, LITERATUR, NAVIGATION, "
                + "BILDUNTERSCHRIFT, UEBERSCHRIFT, IMPRESSUM, SEITENHINWEIS, SONSTIGES, AUSSAGE"));
    }

    @Test
    public void aFailedCallIsFehlerNeverNoiseAndIsCheckedAgain() throws Exception {
        AtomicInteger round = new AtomicInteger();
        ScriptedChat chat = new ScriptedChat(prompt -> round.get() == 0 ? null : careful(prompt));
        NoiseMarks marks = new NoiseMarks();
        PageNoiseCheck check = check(chat);

        Result first = check.check(Collections.singletonList(passage("p2", AD)), marks);

        NoiseMark failed = first.getMarks().get(0);
        assertEquals(NoiseMark.Status.FEHLER, failed.getStatus());
        assertNull(failed.getVerdict());
        assertTrue(failed.getProblem(), failed.getProblem().startsWith("model call failed (PROVIDER_FAILURE)"));
        assertFalse("FEHLER is never noise", marks.isNoise(PageNoiseCheck.textKey(passage("p2", AD))));
        assertEquals("one repair", 2, chat.calls.size());

        round.set(1);
        Result second = check.check(Collections.singletonList(passage("p2", AD)), marks);

        assertEquals(0, second.getReused());
        assertEquals(1, second.getAsked());
        assertTrue(marks.isNoise(PageNoiseCheck.textKey(passage("p2", AD))));
    }

    @Test
    public void aMarkThatHoldsIsNotGivenAgainUnderTheSameVersion() throws Exception {
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();
        List<Passage> passages = Arrays.asList(passage("p1", CONTENT), passage("p2", AD), passage("p3", MENU));
        check(chat).check(passages, marks);
        assertEquals(2, chat.prompts.size());

        Result again = check(chat).check(passages, marks);

        assertEquals(3, again.getReused());
        assertEquals(0, again.getAsked());
        assertTrue(again.getMarks().isEmpty());
        assertEquals(2, chat.prompts.size());

        marks.record(NoiseMark.of(PageNoiseCheck.textKey(passage("p1", CONTENT)), "p1", "s1",
                NoiseMark.Verdict.INHALT, NoiseMark.Kind.KEINE, NoiseMark.MODEL, "alt", "gemma4:e2b",
                PageNoiseCheck.VERSION - 1, 1L, 0));
        Result older = check(chat).check(passages, marks);
        assertEquals("a mark of an older version is given again", 1, older.getAsked());
    }

    @Test
    public void withoutAModelTheRulesDecideUntilAModelChecks() throws Exception {
        NoiseMarks marks = new NoiseMarks();
        List<Passage> passages = Arrays.asList(passage("p1", CONTENT), passage("p2", AD), passage("p3", MENU));

        Result rulesOnly = check(null).check(passages, marks);

        assertEquals(0, rulesOnly.getAsked());
        NoiseMark ad = marks.mark(PageNoiseCheck.textKey(passage("p2", AD)));
        assertEquals(NoiseMark.Verdict.INHALT, ad.getVerdict());
        assertEquals(NoiseMark.RULES, ad.getStage());
        assertEquals("passes the prose rules; no model", ad.getReason());
        assertEquals("without a model the rules' marks hold", 3, check(null).check(passages, marks).getReused());

        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        Result withModel = check(chat).check(passages, marks);

        assertEquals("the rules' noise holds", 1, withModel.getReused());
        assertEquals(2, withModel.getAsked());
        assertTrue(marks.isNoise(PageNoiseCheck.textKey(passage("p2", AD))));
    }

    @Test
    public void theSameTextIsCheckedOnceWhereverItStands() throws Exception {
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Arrays.asList(Passage.of("p1", "s1", "https://a.example/x", AD),
                Passage.of("p2", "s2", "https://b.example/y", "  " + AD.replace(" ", "\n"))), marks);

        assertEquals(2, result.getPassages());
        assertEquals(1, result.getTexts());
        assertEquals(1, result.getAsked());
        assertEquals(1, marks.size());
        assertEquals("p1", result.getMarks().get(0).getPassageId());
    }

    @Test
    public void everyMarkIsWrittenTheMomentItIsThere() throws Exception {
        File project = scratch.newFolder("projekt");
        NoiseMarks marks = new NoiseMarks(project);
        marks.open();
        List<Integer> seen = new ArrayList<Integer>();
        ScriptedChat chat = new ScriptedChat(prompt -> {
            seen.add(onDisk(project));
            return careful(prompt);
        });

        new PageNoiseCheck(chat, "de", Options.defaults().withParallel(1), null, CLOCK)
                .check(Arrays.asList(passage("p1", CONTENT), passage("p2", AD), passage("p3", CONTENTS)), marks);

        assertEquals("each call finds the marks before it on disk", Arrays.asList(0, 1, 2), seen);
    }

    private static int onDisk(File project) {
        NoiseMarks read = new NoiseMarks(project);
        try {
            read.open();
        } catch (Exception unreadable) {
            throw new AssertionError(unreadable);
        }
        return read.size();
    }

    @Test
    public void aMarkThatCannotBeWrittenStopsTheRun() throws Exception {
        File project = scratch.newFolder("projekt");
        assertTrue(new File(project, "page-noise").createNewFile());
        NoiseMarks marks = new NoiseMarks(project);
        marks.open();
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);

        try {
            new PageNoiseCheck(chat, "de", Options.defaults().withParallel(1), null, CLOCK)
                    .check(Arrays.asList(passage("p1", CONTENT), passage("p2", AD), passage("p3", CONTENTS)), marks);
            fail("a mark that could not be written went unnoticed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("a mark of the page-noise check could "
                    + "not be written"));
        }
        assertEquals("no further text was asked", 1, chat.prompts.size());
        assertEquals(0, marks.size());
    }

    @Test
    public void parallelCallsKeepTheOrderOfThePassages() throws Exception {
        ScriptedChat chat = new ScriptedChat(prompt -> {
            try {
                Thread.sleep(prompt.contains("Kapitel") ? 60 : 5);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            return careful(prompt);
        });
        List<Passage> passages = new ArrayList<Passage>();
        for (int i = 0; i < 8; i++) {
            passages.add(passage("p" + i, (i % 2 == 0 ? CONTENTS : CONTENT_2) + " Fall " + i + "."));
        }

        Result result = new PageNoiseCheck(chat, "de", Options.defaults().withParallel(4), null, CLOCK)
                .check(passages, new NoiseMarks());

        for (int i = 0; i < 8; i++) {
            assertEquals("p" + i, result.getMarks().get(i).getPassageId());
            assertEquals(i % 2 == 0 ? NoiseMark.Verdict.RAUSCHEN : NoiseMark.Verdict.INHALT,
                    result.getMarks().get(i).getVerdict());
        }
    }

    @Test
    public void theVerdictFollowsFromTheKindTheModelNames() {
        for (String word : PageNoiseCheck.kindWords()) {
            PageNoiseCheck.Answer answer = PageNoiseCheck.parse(answer("Ein kurzer Satz dazu.", word));
            assertEquals(word, "", answer.problem);
            if (word.equals("AUSSAGE")) {
                assertEquals(NoiseMark.Verdict.INHALT, answer.verdict);
                assertEquals(NoiseMark.Kind.KEINE, answer.kind);
            } else {
                assertEquals(word, NoiseMark.Verdict.RAUSCHEN, answer.verdict);
                assertEquals(NoiseMark.Kind.valueOf(word), answer.kind);
            }
        }
        assertEquals(11, PageNoiseCheck.kindWords().size());
        assertEquals("a sentence about advertising is no advertisement", NoiseMark.Verdict.INHALT,
                PageNoiseCheck.parse(answer("Ein Text erklärt, welche Regeln für Werbung mit Fahrschulen gelten.",
                        "AUSSAGE")).verdict);
    }

    @Test
    public void aFirstSentenceThatPlainlyNamesALeftoverOverrulesAussage() throws Exception {
        PageNoiseCheck.Answer ad = PageNoiseCheck.parse(answer("Eine Fahrschule empfiehlt ihre Kurse.", "AUSSAGE"));
        assertEquals("", ad.problem);
        assertEquals(NoiseMark.Verdict.RAUSCHEN, ad.verdict);
        assertEquals(NoiseMark.Kind.WERBUNG, ad.kind);
        assertTrue(ad.overruled);
        assertEquals("Eine Fahrschule empfiehlt ihre Kurse. (Art AUSSAGE, die Beschreibung nennt WERBUNG)", ad.reason);
        PageNoiseCheck.Answer contents = PageNoiseCheck.parse(answer("Das Stück ist ein Inhaltsverzeichnis.",
                "LITERATUR"));
        assertEquals("a kind of leftover stays as the model named it", NoiseMark.Kind.LITERATUR, contents.kind);
        assertFalse(contents.overruled);

        assertFalse("version 5: describing its own offer is no advertising by itself", PageNoiseCheck.parse(
                answer("Eine Fahrschule beschreibt ihr Angebot.", "AUSSAGE")).overruled);
        ScriptedChat chat = new ScriptedChat(prompt -> prompt.contains("Fahrschule bietet Ihnen")
                ? answer("Eine Fahrschule lobt ihr Angebot.", "AUSSAGE") : careful(prompt));
        NoiseMarks marks = new NoiseMarks();
        Result result = check(chat).check(Arrays.asList(passage("p1", CONTENT), passage("p2", AD)), marks);

        assertEquals(1, result.getOverruled());
        NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage("p2", AD)));
        assertTrue(mark.isNoise());
        assertEquals(NoiseMark.Kind.WERBUNG, mark.getKind());
        assertTrue(PageNoiseCheck.isOverruled(mark));
        assertFalse(PageNoiseCheck.isOverruled(marks.mark(PageNoiseCheck.textKey(passage("p1", CONTENT)))));
    }

    @Test
    public void answersAreReadLeniently() {
        PageNoiseCheck.Answer menu = PageNoiseCheck.parse("{\"beschreibung\":\"Ein Menü der Seite.\",\"art\":"
                + "\" navigation \"}");
        assertEquals("", menu.problem);
        assertEquals(NoiseMark.Verdict.RAUSCHEN, menu.verdict);
        assertEquals(NoiseMark.Kind.NAVIGATION, menu.kind);
        assertEquals("Ein Menü der Seite.", menu.reason);
        assertEquals(NoiseMark.Kind.SEITENBESCHREIBUNG, PageNoiseCheck.parse(answer("Die Seite stellt sich vor.",
                "Seitenbeschreibung")).kind);
        for (String bad : new String[]{"kaputt", "[1]", "{\"beschreibung\":\"Ein Rest der Seite.\"}",
                answer("", "WERBUNG"), answer("Ein Text nennt eine Regel.", "INHALT"),
                answer("Kaum Wörter.", "STRUKTUR"), answer("Ein Rest.", "KEINE"),
                answer("Teils Text, teils Werbung.", "MISCH"),
                "{\"grund\":\"Ein Menü.\",\"urteil\":\"RAUSCHEN\",\"klasse\":\"NAVIGATION\"}"}) {
            assertFalse(bad, PageNoiseCheck.parse(bad).problem.isEmpty());
        }
        assertTrue(PageNoiseCheck.parse(answer("", "WERBUNG")).problem.startsWith("\"beschreibung\" must say"));
        assertEquals("AUSSAGE needs its statement", "\"aussage\" must say in a short sentence what the piece states, "
                + "or \"keine\"", PageNoiseCheck.parse("{\"beschreibung\":\"Ein Text nennt eine Regel.\",\"art\":"
                + "\"AUSSAGE\"}").problem);
        assertEquals("a leftover needs none", "", PageNoiseCheck.parse("{\"beschreibung\":\"Ein Menü der Seite.\","
                + "\"aussage\":\" \",\"art\":\"NAVIGATION\"}").problem);
    }

    /** Acceptance of version 3: lines that only announce a list, teasers, lists of names went through as AUSSAGE. */
    @Test
    public void aussageWithoutAStatementIsALeftover() throws Exception {
        for (String nothing : new String[]{"keine", "Keine.", "„keine“", "keine Aussage zur Sache",
                "keine eigene Aussage", "Keine, es kündigt nur eine Liste an.", "keine (es nennt nur Namen)",
                "keine – nur Titel und Daten", "nichts", "none", "Das Stück sagt nichts über die Sache aus.",
                "Sagt selbst nichts aus", "It says nothing about the matter.", "no statement"}) {
            PageNoiseCheck.Answer answer = PageNoiseCheck.parse(answer("Ein Text leitet eine Liste ein.", nothing,
                    "AUSSAGE"));
            assertEquals(nothing, "", answer.problem);
            assertEquals(nothing, NoiseMark.Verdict.RAUSCHEN, answer.verdict);
            assertEquals(nothing, NoiseMark.Kind.SONSTIGES, answer.kind);
            assertTrue(nothing, answer.overruled);
            assertEquals("Ein Text leitet eine Liste ein. (Art AUSSAGE, aber keine Aussage zur Sache)", answer.reason);
        }
        for (String statement : new String[]{"Keine Person darf ohne Helm Rad fahren.", "Keiner muss vor einer roten "
                + "Ampel länger als zwei Minuten warten.", "Nichts spricht gegen Tempo 30 vor Schulen.",
                "Kein Beleg ist nötig, wenn die Gemeinde die Zone anordnet.", "Die Gemeinde prüft die Verkehrslage."}) {
            PageNoiseCheck.Answer answer = PageNoiseCheck.parse(answer("Ein Text nennt eine Regel.", statement,
                    "AUSSAGE"));
            assertEquals("a statement that begins with the word says something: " + statement,
                    NoiseMark.Verdict.INHALT, answer.verdict);
            assertFalse(statement, answer.overruled);
        }
        PageNoiseCheck.Answer list = PageNoiseCheck.parse(answer("Ein Verlag nennt seine Normen.",
                "Für Helme gilt die Norm DIN EN 1078.", "LITERATUR"));
        assertEquals("a kind of leftover stays as the model named it", NoiseMark.Kind.LITERATUR, list.kind);
        assertFalse(list.overruled);
        PageNoiseCheck.Answer both = PageNoiseCheck.parse(answer("Eine Fahrschule empfiehlt ihre Kurse.", "keine",
                "AUSSAGE"));
        assertEquals("the first sentence's kind comes first", NoiseMark.Kind.WERBUNG, both.kind);

        ScriptedChat chat = new ScriptedChat(prompt -> prompt.contains("Kapitel 1")
                ? answer("Ein Text nennt Kapitel.", "keine", "AUSSAGE") : careful(prompt));
        NoiseMarks marks = new NoiseMarks();
        Result result = check(chat).check(Arrays.asList(passage("p1", CONTENT), passage("p2", CONTENTS)), marks);

        assertEquals(1, result.getOverruled());
        NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage("p2", CONTENTS)));
        assertTrue(mark.isNoise());
        assertTrue(PageNoiseCheck.isWithoutStatement(mark));
        assertFalse(PageNoiseCheck.isOverruled(mark));
        assertFalse(PageNoiseCheck.isWithoutStatement(marks.mark(PageNoiseCheck.textKey(passage("p1", CONTENT)))));
    }

    /** Acceptance of version 4: lines that only announce what follows, with the dot of an abbreviation or a date. */
    @Test
    public void aLeadInIsAHeadingByRule() throws Exception {
        String date = "Die wichtigsten Regeln für Lastenräder, die seit dem 1. Januar 2025 gelten, finden Sie hier im "
                + "Überblick:";
        String abbreviation = "Für Radfahrer gelten auf gemeinsamen Geh- und Radwegen z. B. folgende Pflichten nach "
                + "§ 2 Abs. 4 der Verordnung:";
        String question = "Was gilt für Radfahrer auf Gehwegen und in Fußgängerzonen? Hier finden Sie die Antworten im "
                + "Überblick:";
        String statement = "Radfahrer dürfen in Fußgängerzonen nur fahren, wenn ein Zusatzzeichen es ausdrücklich "
                + "erlaubt. Dabei gilt:";
        String year = "Die neue Regel für Lastenräder auf Radwegen gilt seit dem Jahr 2025. Für Anhänger ändert sich "
                + "dagegen Folgendes:";
        String longer = "Für Radfahrer, die mit Kindern im Anhänger unterwegs sind, gelten z. B. auf Radwegen, auf "
                + "Gehwegen, in Fußgängerzonen und auf Landstraßen besondere Pflichten, die Eltern kennen sollten, "
                + "bevor sie losfahren:";
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Arrays.asList(passage("p1", date), passage("p2", abbreviation),
                passage("p3", question), passage("p4", statement), passage("p5", year), passage("p6", longer)),
                marks);

        for (String leadIn : new String[]{date, abbreviation, question}) {
            NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage("p", leadIn)));
            assertEquals(leadIn, NoiseMark.RULES, mark.getStage());
            assertEquals(NoiseMark.Kind.UEBERSCHRIFT, mark.getKind());
            assertTrue(PageNoiseCheck.isLeadIn(mark));
        }
        assertEquals("a lead-in: 17 words that end in a colon, without a sentence of their own",
                marks.mark(PageNoiseCheck.textKey(passage("p1", date))).getReason());
        assertEquals("a sentence of its own, a year at the end of one or more than 25 words go to the model", 3,
                result.getAsked());
        for (String model : new String[]{statement, year, longer}) {
            assertFalse(model, PageNoiseCheck.isLeadIn(marks.mark(PageNoiseCheck.textKey(passage("p", model)))));
        }
        assertEquals("", PageNoiseCheck.leadIn(CONTENT));
        assertEquals("", PageNoiseCheck.leadIn("Ein Satz ohne Doppelpunkt am Ende, aber mit Abs. 2 und z. B. mehr."));
    }

    /** Acceptance of version 6: a row of short link texts is NAVIGATION by rule, a list that states something is not. */
    @Test
    public void aMenuIsNavigationByRule() throws Exception {
        String menu = "Startseite | Verkehrsregeln | Radwege | Lastenräder | Parken | Kontakt | Über uns | Presse. "
                + "Suche";
        String breadcrumb = "Sie sind hier: Startseite > Ratgeber > Radverkehr > Lastenräder im Alltag\nStand: "
                + "3. Mai 2026.\nHelmpflicht\nAnhänger\nNachtfahrt";
        String list = "Folgende Ausrüstung schreibt die Verordnung für jedes Fahrrad vor:\nKlingel\nVorderlicht\n"
                + "Rücklicht\nReflektoren an den Pedalen.";
        String statements = "Lastenräder dürfen Radwege nutzen.\nAnhänger brauchen ein Rücklicht.\nKinder bis acht "
                + "Jahre fahren auf dem Gehweg.\nHelme sind empfohlen, aber keine Pflicht.";
        String longParts = "Wie breit ein Radweg mindestens sein muss | Wann Radfahrer die Fahrbahn nutzen dürfen | "
                + "Welche Regeln für Lastenräder gelten | Was beim Abbiegen zu beachten ist.";
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Arrays.asList(passage("p1", menu), passage("p2", breadcrumb),
                passage("p3", list), passage("p4", statements), passage("p5", longParts)), marks);

        for (String nav : new String[]{menu, breadcrumb}) {
            NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage("p", nav)));
            assertEquals(nav, NoiseMark.RULES, mark.getStage());
            assertEquals(NoiseMark.Kind.NAVIGATION, mark.getKind());
            assertTrue(PageNoiseCheck.isMenu(mark));
        }
        assertEquals("a menu: 8 parts of 1.3 words on average",
                marks.mark(PageNoiseCheck.textKey(passage("p1", menu))).getReason());
        assertEquals("an introduced list, sentences and long parts go to the model", 3, result.getAsked());
        for (String model : new String[]{list, statements, longParts}) {
            assertFalse(model, PageNoiseCheck.isMenu(marks.mark(PageNoiseCheck.textKey(passage("p", model)))));
        }
        assertEquals("", PageNoiseCheck.menu(CONTENT));
        assertEquals("", PageNoiseCheck.menu("Radweg | Gehweg | Fahrbahn"));
    }

    /** Acceptance of version 6: a lone question or exclamation line is an UEBERSCHRIFT by rule. */
    @Test
    public void aTeaserLineIsAHeadingByRule() throws Exception {
        String question = "Was bedeutet die neue Regel für Lastenräder eigentlich für Familien mit kleinen Kindern im "
                + "Alltag?";
        String exclamation = "Die Zukunft des Radverkehrs in unseren Städten beginnt schon heute auf jeder einzelnen "
                + "Straße!";
        String twoQuestions = "Darf ich mit dem Lastenrad auf dem Gehweg fahren? Und was gilt eigentlich für "
                + "Anhänger?";
        String statement = "Lastenräder dürfen seit 2025 alle Radwege nutzen. Gilt das auch für Anhänger mit zwei "
                + "Kindern?";
        String lines = "Was gilt für Lastenräder auf Radwegen?\nSie dürfen jeden Radweg nutzen, der breit genug ist.";
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Arrays.asList(passage("p1", question), passage("p2", exclamation),
                passage("p3", twoQuestions), passage("p4", statement), passage("p5", lines)), marks);

        for (String teaser : new String[]{question, exclamation, twoQuestions}) {
            NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage("p", teaser)));
            assertEquals(teaser, NoiseMark.RULES, mark.getStage());
            assertEquals(NoiseMark.Kind.UEBERSCHRIFT, mark.getKind());
            assertTrue(PageNoiseCheck.isTeaser(mark));
            assertFalse(PageNoiseCheck.isLeadIn(mark));
        }
        assertEquals("a teaser line: 15 words that end in a question, without a sentence of their own",
                marks.mark(PageNoiseCheck.textKey(passage("p1", question))).getReason());
        assertEquals("a sentence before the end and a second line go to the model", 2, result.getAsked());
        assertEquals("", PageNoiseCheck.teaser(CONTENT));
    }

    /** Acceptance of version 3: the site-notice rule dropped two passages of content on a single word. */
    @Test
    public void aSingleNoticeThatIsAWordOfProseGoesToTheModel() throws Exception {
        String faq = "Laut den FAQ des Verkehrsministeriums gilt die Helmpflicht nur für Kinder unter zwölf Jahren, "
                + "und Eltern haften für ihre Kinder auf dem Gehweg.";
        String settings = "Viele Fahrradcomputer speichern Wege und Zeiten; die Datenschutzeinstellungen legen fest, "
                + "wer die Daten sehen darf und wie lange sie bleiben.";
        String credit = "Ein Radweg muss mindestens zwei Meter breit sein, damit sich Radfahrer gefahrlos überholen "
                + "können. Bildquelle: Stadt Beispielhausen";
        String two = "Unsere FAQ beantworten alle Fragen zu Kursen und Preisen der Fahrschule. Weitere Angaben finden "
                + "Sie im Impressum der Seite.";
        ScriptedChat chat = new ScriptedChat(PageNoiseCheckTest::careful);
        NoiseMarks marks = new NoiseMarks();

        Result result = check(chat).check(Arrays.asList(passage("p1", faq), passage("p2", settings),
                passage("p3", credit), passage("p4", two), passage("p5", COOKIES)), marks);

        assertEquals("the two words of prose go to the model", 2, result.getAsked());
        assertFalse(marks.isNoise(PageNoiseCheck.textKey(passage("p1", faq))));
        assertFalse(marks.isNoise(PageNoiseCheck.textKey(passage("p2", settings))));
        for (String rules : new String[]{credit, two, COOKIES}) {
            NoiseMark mark = marks.mark(PageNoiseCheck.textKey(passage("p", rules)));
            assertEquals("a picture credit, two notices or a cookie notice stay with the rules: " + rules,
                    NoiseMark.RULES, mark.getStage());
            assertEquals(NoiseMark.Kind.SEITENHINWEIS, mark.getKind());
        }
    }

    @Test
    public void theRoleIsSmallAndDoesNotThink() {
        assertEquals("page-noise-check", PageNoiseCheck.ROLE.getId());
        assertEquals("phase.research.noise", PageNoiseCheck.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.OFF, PageNoiseCheck.ROLE.getThinking());
        assertEquals(8192, PageNoiseCheck.ROLE.getContextTokens());
    }

    @Test
    public void optionsRejectNonsense() {
        Options options = Options.defaults();
        assertEquals(4, options.getParallel());
        assertEquals(512, options.getAnswerTokens());
        assertEquals(2000, options.getPassageChars());
        for (Runnable bad : new Runnable[]{() -> options.withParallel(0), () -> options.withParallel(17),
                () -> options.withAnswerTokens(10), () -> options.withPassageChars(50)}) {
            try {
                bad.run();
                fail("accepted");
            } catch (IllegalArgumentException expected) {
                assertEquals("invalid options of the page-noise check", expected.getMessage());
            }
        }
    }
}

package com.aresstack.askai.research.planwriter;

import com.aresstack.askai.research.memory.QuestionEvidence;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.writer.CitationValidator;
import com.aresstack.askai.research.sectionplan.SectionPlan;
import com.aresstack.askai.research.sentencecheck.SentenceVerdicts;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 10, the writer, on stored data of the book of 17 Nov, on the owner's machine; skipped without its files.
 * The sample test runs the same code on self-written files about city traffic with a scripted model in every build.
 * <p>
 * It writes the sections of a stored section plan (component 9) with a real model and checks every cited sentence
 * with the sentence check (component 11) on a real model:
 * <ul>
 * <li>{@code plan}: the {@code plan.json} of a run of the section plan's stock test;</li>
 * <li>{@code korpus}: the stock the plan's passage ids come from ({@code replay-corpus.json}: {@code language} and
 * {@code passages} with {@code id}, {@code sourceId}, {@code domain}, {@code text});</li>
 * <li>{@code quellen} (optional): kind and legal space of a source by its domain ({@code quellen-1711.json}:
 * {@code domains} with {@code domain}, {@code quellenart}, {@code rechtsraum}); without it no passage gets the note
 * of a maker, an association or another legal space, and the check asks neither;</li>
 * <li>{@code out}: the folder for the results; its project {@code <out>/projekt} must not exist yet, unless
 * {@code weiter=true} goes on with the verdicts and sections stored there;</li>
 * <li>{@code chatModel} (default gemma4:12b) on {@code chatUrl} (default http://127.0.0.1:11434) writes,
 * {@code pruefModell} (default the same model; {@code aus}: no check) checks; {@code numCtx} (8192 for both, so the
 * model is loaded once; {@code omit} sends none), {@code think} (false; {@code omit} sends none),
 * {@code timeoutSekunden} (900);</li>
 * <li>{@code abschnitte} (optional): section ids, comma-separated (default all); {@code parallel} (2 sections at a
 * time), {@code woerterJePassage} (70: S5's bound per plan passage with text; 0: none), {@code pruefParallel} (2),
 * {@code woerterJeAufruf} (450), {@code antwortTokens} (1400),
 * {@code passagenJeAufruf} (10), {@code neufassung} (true), {@code auffuellen} (true), {@code sprecherEinfuegen}
 * (true: the speaker put in where a claim still stands as a fact), {@code rechtsraum} (DE), {@code sprache} (default
 * the stock's).</li>
 * </ul>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*PlanSectionWriterStockTest*' \
 *   -Daskai.stock.writer.plan=&lt;plan.json&gt; -Daskai.stock.writer.korpus=replay-corpus.json \
 *   -Daskai.stock.writer.quellen=quellen-1711.json -Daskai.stock.writer.out=&lt;folder&gt;
 * </pre>
 * Criteria proposed (the architect fixes them before the run): S1 every section asked for has a result and none is
 * FEHLER; S2 of the cited sentences the check judged at most 10 % stay NICHT_GEDECKT in the final text, and at most
 * 2 % of the cited sentences stay unjudged; S3 at most 15 % of all sentences cite no passage; S4 at least 90 % of the
 * questions with direct evidence in a section get one of their direct passages cited; S5 every section written holds
 * at least 60 % of its planned words, bounded by {@code woerterJePassage} (70) x its plan passages with text
 * (Angelo's "Nach Stoff begrenzen"; the sum and the sections below stand beside it, the unbounded measure under
 * {@code ungedeckelt}; until c10 S5 was the sum of all sections, while the pre-registration asked for each); S6 at
 * most 10 % of the sentences citing a maker, seller or association state its claim as a fact; S7 at most 10 % of the sentences citing a rule of another legal space leave open where it holds;
 * S8 no citation of a passage the plan did not give the section. Report only: sentences sent back and rewritten,
 * claims the check called facts whose sentence names the speaker, speakers put in, sources named by a letter, gaps by
 * kind, calls, answers cut off, time. A passage's source is named by the domain the stock gives it.
 * <p>
 * {@code <out>/result.json} holds ids and counts only, no text of a passage, a sentence, a quote or a reason.
 * {@code <out>/buch.md} (the sections with their marks and gaps), {@code <out>/log.txt} (counts per section) and
 * {@code <out>/projekt} (the stores: the written sections with the passages they cite, the verdicts with their quotes)
 * hold text of the sources or of the book: they stay on the machine.
 */
public class PlanSectionWriterStockTest {

    private static final String P = "askai.stock.writer.";
    private static final int FAILURE_CHARS = 200;
    /** S5: the share of its planned words each section written holds at least. */
    static final double UNDER_TARGET = 0.6;
    /**
     * S5 measures a section against min(planned words, this many x its plan passages with text): Angelo's decision
     * of 22.11.2026 ("Nach Stoff begrenzen", component 9 from 8a6ec82c), so an older plan is measured as the new one
     * would plan it.
     */
    static final int WORDS_PER_PASSAGE = 70;

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    // ------------------------------------------------------------------------------------------------ settings

    /** What a run reads, how it writes and where the results go. */
    static final class Settings {
        File plan;
        File corpus;
        File sources;
        File out;
        final List<String> sections = new ArrayList<String>();
        String language = "";
        String legalSpace = "DE";
        int parallel = 2;
        int checkParallel = 2;
        int wordsPerCall = 450;
        int answerTokens = 1400;
        int passagesPerCall = 10;
        boolean rewrite = true;
        boolean topUp = true;
        boolean attributionTail = true;
        boolean resume;
        /** S5's bound: a section is measured against at most this many words per plan passage with text; 0: none. */
        int wordsPerPassage = WORDS_PER_PASSAGE;
    }

    static Settings settings(String plan, String corpus, String sources, String out) {
        Settings settings = new Settings();
        settings.plan = new File(plan);
        settings.corpus = new File(corpus);
        settings.sources = sources.isEmpty() ? null : new File(sources);
        settings.out = new File(out);
        for (String id : option("abschnitte", "").split(",")) {
            if (!id.trim().isEmpty()) {
                settings.sections.add(id.trim());
            }
        }
        settings.language = option("sprache", "");
        settings.legalSpace = option("rechtsraum", "DE").toUpperCase(Locale.ROOT);
        settings.parallel = Integer.parseInt(option("parallel", "2"));
        settings.checkParallel = Integer.parseInt(option("pruefParallel", "2"));
        settings.wordsPerCall = Integer.parseInt(option("woerterJeAufruf", "450"));
        settings.answerTokens = Integer.parseInt(option("antwortTokens", "1400"));
        settings.passagesPerCall = Integer.parseInt(option("passagenJeAufruf", "10"));
        settings.rewrite = Boolean.parseBoolean(option("neufassung", "true"));
        settings.topUp = Boolean.parseBoolean(option("auffuellen", "true"));
        settings.attributionTail = Boolean.parseBoolean(option("sprecherEinfuegen", "true"));
        settings.resume = Boolean.parseBoolean(option("weiter", "false"));
        settings.wordsPerPassage = Integer.parseInt(option("woerterJePassage", String.valueOf(WORDS_PER_PASSAGE)));
        return settings;
    }

    // ------------------------------------------------------------------------------------------------ the stock

    /** The stock: its passages with the kind and legal space of their source. */
    static final class Stock {
        final String language;
        final List<PassageLookup.Passage> passages = new ArrayList<PassageLookup.Passage>();
        int unrated;

        Stock(String language) {
            this.language = language;
        }
    }

    static Stock readStock(File corpus, File sources) throws IOException {
        Map<String, String[]> rated = new HashMap<String, String[]>();
        if (sources != null) {
            for (JsonElement element : array(JsonParser.parseString(read(sources)).getAsJsonObject(), "domains")) {
                JsonObject domain = element.getAsJsonObject();
                rated.put(string(domain, "domain").toLowerCase(Locale.ROOT),
                        new String[]{string(domain, "quellenart"), string(domain, "rechtsraum")});
            }
        }
        JsonObject json = JsonParser.parseString(read(corpus)).getAsJsonObject();
        Stock stock = new Stock(string(json, "language"));
        for (JsonElement element : array(json, "passages")) {
            JsonObject passage = element.getAsJsonObject();
            String id = string(passage, "id");
            if (id.isEmpty()) {
                continue;
            }
            String[] rating = rated.get(string(passage, "domain").toLowerCase(Locale.ROOT));
            stock.unrated += rating == null ? 1 : 0;
            stock.passages.add(new PassageLookup.Passage(id, string(passage, "sourceId"), string(passage, "domain"),
                    string(passage, "text"), rating == null ? "" : rating[0], rating == null ? "" : rating[1]));
        }
        return stock;
    }

    // ------------------------------------------------------------------------------------------------ the run

    /** Writes the plan's sections, then result.json, buch.md and log.txt; returns the result. */
    static JsonObject run(Settings settings, MainModelChat writer, MainModelChat checker) throws Exception {
        SectionPlan plan = SectionPlan.fromJson(JsonParser.parseString(read(settings.plan)).getAsJsonObject());
        Stock stock = readStock(settings.corpus, settings.sources);
        PassageLookup lookup = PassageLookup.of(stock.passages);
        File project = new File(settings.out, "projekt");
        String[] there = project.list();
        if (there != null && there.length > 0 && !settings.resume) {
            throw new IllegalStateException(project + " holds a project already: choose another out folder or set "
                    + "-D" + P + "weiter=true");
        }
        Files.createDirectories(project.toPath());
        SentenceVerdicts verdicts = new SentenceVerdicts(project);
        verdicts.open();
        WrittenSections store = new WrittenSections(project);
        store.open();
        String language = !settings.language.isEmpty() ? settings.language
                : stock.language.isEmpty() ? "de" : stock.language;
        PlanSectionWriter.Options options = PlanSectionWriter.Options.defaults().withLanguage(language)
                .withLegalSpace(settings.legalSpace).withWordsPerCall(settings.wordsPerCall)
                .withAnswerTokens(settings.answerTokens).withPassagesPerCall(settings.passagesPerCall)
                .withParallel(settings.parallel).withCheckParallel(settings.checkParallel)
                .withRewrite(settings.rewrite).withTopUp(settings.topUp)
                .withAttributionTail(settings.attributionTail);
        List<String> log = Collections.synchronizedList(new ArrayList<String>());
        PlanSectionWriter sectionWriter = new PlanSectionWriter(writer, checker, lookup, verdicts, store, options,
                Clock.systemUTC(), line -> {
            log.add(line);
            System.out.println("[writer] " + line);
        });
        long began = System.currentTimeMillis();
        List<WrittenSection> written = sectionWriter.writeAll(plan,
                settings.sections.isEmpty() ? null : settings.sections);
        long millis = System.currentTimeMillis() - began;

        JsonObject result = evaluate(plan, settings, options, lookup, stock, written);
        result.addProperty("pruefung", checker != null);
        result.addProperty("ms", millis);
        writeJson(new File(settings.out, "result.json"), result);
        write(new File(settings.out, "buch.md"), book(plan, written));
        synchronized (log) {
            write(new File(settings.out, "log.txt"), String.join("\n", log) + "\n");
        }
        return result;
    }

    /** What one section shows beyond its counts: questions carried, sentences with a source to attribute. */
    static final class Measures {
        int keyPoints;
        int carried;
        int attribution;
        int foreignRule;
        int foreign;
        int cited;
    }

    /**
     * S4: a question with direct evidence in the section counts as carried when one of its direct passages with a
     * text is cited; S6/S7: the sentences citing a passage the check asks attribution or the legal space for, as it
     * asks them; S8: citations of a passage the plan did not give the section.
     */
    static Measures measure(SectionPlan.Section section, WrittenSection written, PlanSectionWriter.Prepared prepared,
                            PassageLookup lookup, PlanSectionWriter.Options options) {
        Measures m = new Measures();
        Set<String> cited = new HashSet<String>();
        for (WrittenSection.Citation citation : written.getCitations().values()) {
            cited.add(citation.getPassageId());
        }
        m.cited = cited.size();
        Set<String> asked = new HashSet<String>();
        for (SectionPlan.Question question : section.getQuestions()) {
            if (question.getId().isEmpty() || !asked.add(question.getId())
                    || !QuestionEvidence.LABEL_DIRECT.equals(question.getStanding())) {
                continue;
            }
            boolean any = false;
            boolean hit = false;
            for (PlanSectionWriter.Entry entry : prepared.entries) {
                if (entry.direct && entry.evidence.getQuestionIds().contains(question.getId())) {
                    any = true;
                    hit |= cited.contains(entry.passage.getPassageId());
                }
            }
            m.keyPoints += any ? 1 : 0;
            m.carried += hit ? 1 : 0;
        }
        Set<String> given = new HashSet<String>();
        for (SectionPlan.Evidence evidence : section.getEvidence()) {
            given.add(evidence.getPassageId());
        }
        for (SectionPlan.Evidence evidence : section.getLeads()) {
            given.add(evidence.getPassageId());
        }
        for (String id : cited) {
            m.foreign += given.contains(id) ? 0 : 1;
        }
        for (CitationValidator.Sentence sentence : CitationValidator.sentencesOf(written.getText())) {
            boolean interest = false;
            boolean rule = false;
            for (Integer n : sentence.getCited()) {
                WrittenSection.Citation citation = written.getCitations().get(n);
                PassageLookup.Passage passage = citation == null ? null : lookup.find(citation.getPassageId());
                if (passage == null) {
                    continue;
                }
                interest |= PlanSectionWriter.OWN_INTEREST.contains(passage.getSourceKind());
                rule |= PlanSectionWriter.LEGAL_SPACES.contains(passage.getLegalSpace())
                        && !passage.getLegalSpace().equals(options.getLegalSpace());
            }
            m.attribution += interest ? 1 : 0;
            m.foreignRule += rule ? 1 : 0;
        }
        return m;
    }

    /** The words S5 measures a section against: its planned words, at most {@code perPassage} x its passages. */
    static int boundedTarget(int planned, int passagesWithText, int perPassage) {
        return perPassage <= 0 ? planned : (int) Math.min(planned, (long) perPassage * passagesWithText);
    }

    static JsonObject evaluate(SectionPlan plan, Settings settings, PlanSectionWriter.Options options,
                               PassageLookup lookup, Stock stock, List<WrittenSection> written) {
        JsonObject result = new JsonObject();
        result.addProperty("plan", settings.plan.getName());
        result.addProperty("korpus", settings.corpus.getName());
        result.addProperty("quellen", settings.sources == null ? "" : settings.sources.getName());
        result.addProperty("fassung", PlanSectionWriter.VERSION);
        result.addProperty("sprache", options.getLanguage());
        result.addProperty("rechtsraum", options.getLegalSpace());
        JsonObject tuning = new JsonObject();
        tuning.addProperty("parallel", options.getParallel());
        tuning.addProperty("pruefParallel", options.getCheckParallel());
        tuning.addProperty("woerterJeAufruf", options.getWordsPerCall());
        tuning.addProperty("antwortTokens", options.getAnswerTokens());
        tuning.addProperty("passagenJeAufruf", options.getPassagesPerCall());
        tuning.addProperty("neufassung", options.isRewrite());
        tuning.addProperty("auffuellen", options.isTopUp());
        tuning.addProperty("sprecherEinfuegen", options.isAttributionTail());
        tuning.addProperty("woerterJePassage", settings.wordsPerPassage);
        result.add("einstellungen", tuning);
        result.addProperty("passagenImBestand", stock.passages.size());
        result.addProperty("passagenOhneQuellenart", stock.unrated);

        List<SectionPlan.Section> asked = new ArrayList<SectionPlan.Section>();
        Set<String> known = new HashSet<String>();
        for (SectionPlan.Section section : plan.getSections()) {
            known.add(section.getId());
            if (settings.sections.isEmpty() || settings.sections.contains(section.getId())) {
                asked.add(section);
            }
        }
        JsonArray unknown = new JsonArray();
        for (String id : settings.sections) {
            if (!known.contains(id)) {
                unknown.add(id);
            }
        }
        result.addProperty("abschnitteGeplant", plan.getSections().size());
        result.addProperty("abschnitteVerlangt", asked.size());
        result.add("unbekannteAbschnitte", unknown);

        Map<String, WrittenSection> byId = new HashMap<String, WrittenSection>();
        for (WrittenSection section : written) {
            byId.put(section.getSectionId(), section);
        }
        Map<String, Long> sums = new LinkedHashMap<String, Long>();
        Map<String, Long> gapSums = new LinkedHashMap<String, Long>();
        Map<String, Long> markSums = new LinkedHashMap<String, Long>();
        Map<String, Long> statuses = new LinkedHashMap<String, Long>();
        long missing = 0;
        long okWords = 0;
        long okTarget = 0;
        long planned = 0;
        long reached = 0;
        JsonArray under = new JsonArray();
        long okPlanned = 0;
        long plannedUncapped = 0;
        long reachedUncapped = 0;
        JsonArray underUncapped = new JsonArray();
        long keyPoints = 0;
        long carried = 0;
        long attribution = 0;
        long foreignRule = 0;
        long foreign = 0;
        long cited = 0;
        long withoutText = 0;
        long rewrittenMarked = 0;
        JsonArray lines = new JsonArray();
        for (SectionPlan.Section section : asked) {
            JsonObject line = new JsonObject();
            line.addProperty("abschnittId", section.getId());
            line.addProperty("kapitelId", chapterIdOf(plan, section.getId()));
            WrittenSection w = byId.get(section.getId());
            if (w == null) {
                missing++;
                line.addProperty("status", "FEHLT");
                lines.add(line);
                continue;
            }
            add(statuses, w.getStatus().name(), 1);
            line.addProperty("status", w.getStatus().name());
            if (!w.getFailure().isEmpty()) {
                line.addProperty("fehler", clip(w.getFailure(), FAILURE_CHARS));
            }
            PlanSectionWriter.Prepared prepared = PlanSectionWriter.prepare(section,
                    PlanSectionWriter.chapterOf(plan, section.getId()), lookup, options);
            int target = boundedTarget(w.getTargetWords(), prepared.entries.size(), settings.wordsPerPassage);
            line.addProperty("zielwoerter", w.getTargetWords());
            line.addProperty("zielNachStoff", target);
            line.addProperty("passagenMitText", prepared.entries.size());
            line.addProperty("woerter", w.getWords());
            if (w.getStatus() == WrittenSection.Status.OK) {
                okWords += w.getWords();
                okTarget += target;
                okPlanned += w.getTargetWords();
                if (target > 0) {
                    planned++;
                    boolean enough = w.getWords() >= UNDER_TARGET * target - 1e-9;
                    reached += enough ? 1 : 0;
                    if (!enough) {
                        under.add(section.getId());
                    }
                }
                if (w.getTargetWords() > 0) {
                    plannedUncapped++;
                    boolean enough = w.getWords() >= UNDER_TARGET * w.getTargetWords() - 1e-9;
                    reachedUncapped += enough ? 1 : 0;
                    if (!enough) {
                        underUncapped.add(section.getId());
                    }
                }
            }
            JsonObject counts = new JsonObject();
            for (Map.Entry<String, Integer> count : w.getCounts().entrySet()) {
                counts.addProperty(count.getKey(), count.getValue());
                add(sums, count.getKey(), count.getValue());
            }
            line.add("zaehler", counts);
            Map<String, Long> gaps = new LinkedHashMap<String, Long>();
            for (WrittenSection.Gap gap : w.getGaps()) {
                add(gaps, gap.getKind().name(), 1);
                add(gapSums, gap.getKind().name(), 1);
            }
            line.add("lueckenNachArt", object(gaps));
            Map<String, Long> marks = new LinkedHashMap<String, Long>();
            for (WrittenSection.Mark mark : w.getMarks()) {
                add(marks, mark.getKind().name(), 1);
                add(markSums, mark.getKind().name(), 1);
                rewrittenMarked += mark.isRewritten() ? 1 : 0;
            }
            line.add("markiertNachArt", object(marks));
            line.addProperty("ohneText", w.getWithoutText().size());
            withoutText += w.getWithoutText().size();
            Measures m = measure(section, w, prepared, lookup, options);
            line.addProperty("kernpunkteMitBeleg", m.keyPoints);
            line.addProperty("getragen", m.carried);
            line.addProperty("zuschreibungFaelle", m.attribution);
            line.addProperty("rechtsraumFaelle", m.foreignRule);
            line.addProperty("fremdeZitate", m.foreign);
            line.addProperty("ms", w.getMillis());
            keyPoints += m.keyPoints;
            carried += m.carried;
            attribution += m.attribution;
            foreignRule += m.foreignRule;
            foreign += m.foreign;
            cited += m.cited;
            lines.add(line);
        }

        JsonObject totals = object(sums);
        totals.addProperty("woerter", sum(written, true));
        totals.addProperty("zielwoerter", sum(written, false));
        totals.add("status", object(statuses));
        totals.add("lueckenNachArt", object(gapSums));
        totals.add("markiertNachArt", object(markSums));
        totals.addProperty("ohneText", withoutText);
        totals.addProperty("kernpunkteMitBeleg", keyPoints);
        totals.addProperty("getragen", carried);
        totals.addProperty("zuschreibungFaelle", attribution);
        totals.addProperty("rechtsraumFaelle", foreignRule);
        totals.addProperty("fremdeZitate", foreign);
        result.add("summen", totals);

        long errors = value(statuses, WrittenSection.Status.FEHLER.name()) + missing;
        result.add("S1", criterion(errors, asked.size(), 0.0, true));
        long covered = value(sums, "gedeckt") + value(sums, "teilweise");
        long notCovered = value(sums, "nichtGedeckt");
        JsonObject s2 = criterion(notCovered, covered + notCovered, 0.10, true);
        JsonObject unchecked = criterion(value(sums, "ungeprueft"), value(sums, "mitBeleg"), 0.02, true);
        s2.add("ungeprueft", unchecked);
        s2.addProperty("bestanden", s2.get("bestanden").getAsBoolean() && unchecked.get("bestanden").getAsBoolean());
        result.add("S2", s2);
        result.add("S3", criterion(value(sums, "ohneBeleg"), value(sums, "saetze"), 0.15, true));
        result.add("S4", criterion(carried, keyPoints, 0.90, false));
        JsonObject s5 = criterion(reached, planned, 1.0, false);
        s5.add("gesamt", criterion(okWords, okTarget, UNDER_TARGET, false));
        s5.add("unter", under);
        JsonObject uncapped = criterion(reachedUncapped, plannedUncapped, 1.0, false);
        uncapped.add("gesamt", criterion(okWords, okPlanned, UNDER_TARGET, false));
        uncapped.add("unter", underUncapped);
        s5.add("ungedeckelt", uncapped);
        result.add("S5", s5);
        result.add("S6", criterion(value(sums, "alsTatsache"), attribution, 0.10, true));
        result.add("S7", criterion(value(sums, "rechtsraumOffen"), foreignRule, 0.10, true));
        result.add("S8", criterion(foreign, cited, 0.0, true));

        JsonObject report = new JsonObject();
        report.addProperty("zurueck", value(sums, "zurueck"));
        report.addProperty("ueberarbeitet", value(sums, "ueberarbeitet"));
        report.addProperty("zuschreibungImSatz", value(sums, "zuschreibungImSatz"));
        report.addProperty("sprecherEingefuegt", value(sums, "sprecherEingefuegt"));
        report.addProperty("quelleBuchstabe", value(sums, "quelleBuchstabe"));
        report.addProperty("neufassungBesteht", value(sums, "ueberarbeitet") - rewrittenMarked);
        report.addProperty("neufassungMarkiert", rewrittenMarked);
        report.addProperty("ohneStoff", value(statuses, WrittenSection.Status.OHNE_STOFF.name()));
        report.addProperty("unterZiel", value(gapSums, WrittenSection.Gap.Kind.UNTER_ZIEL.name()));
        report.addProperty("schreibAufrufe", value(sums, "schreibAufrufe"));
        report.addProperty("neuAufrufe", value(sums, "neuAufrufe"));
        report.addProperty("pruefPaare", value(sums, "pruefPaare"));
        report.addProperty("pruefGefragt", value(sums, "pruefGefragt"));
        result.add("bericht", report);
        result.add("abschnitte", lines);
        return result;
    }

    /** {wert, von, anteil, schwelle, bestanden}; nothing to measure passes. */
    static JsonObject criterion(long value, long of, double limit, boolean atMost) {
        JsonObject criterion = new JsonObject();
        criterion.addProperty("wert", value);
        criterion.addProperty("von", of);
        double share = of == 0 ? 0.0 : (double) value / of;
        criterion.addProperty("anteil", Math.round(share * 1000.0) / 1000.0);
        criterion.addProperty("schwelle", (atMost ? "<= " : ">= ") + limit);
        criterion.addProperty("bestanden", of == 0 || (atMost ? share <= limit + 1e-9 : share >= limit - 1e-9));
        return criterion;
    }

    /** The sections with their text, marks, gaps and the passage ids they cite; it holds the book's text. */
    static String book(SectionPlan plan, List<WrittenSection> written) {
        Map<String, WrittenSection> byId = new HashMap<String, WrittenSection>();
        for (WrittenSection section : written) {
            byId.put(section.getSectionId(), section);
        }
        StringBuilder out = new StringBuilder();
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            boolean any = false;
            for (SectionPlan.Section section : chapter.getSections()) {
                any |= byId.containsKey(section.getId());
            }
            if (!any) {
                continue;
            }
            out.append("# ").append(chapter.getTitle()).append("\n\n");
            for (SectionPlan.Section section : chapter.getSections()) {
                WrittenSection w = byId.get(section.getId());
                if (w == null) {
                    continue;
                }
                out.append("## ").append(w.getTitle()).append("\n\n_").append(w).append("_\n\n");
                if (!w.getFailure().isEmpty()) {
                    out.append("Fehler: ").append(w.getFailure()).append("\n\n");
                }
                if (!w.getText().isEmpty()) {
                    out.append(w.getText()).append("\n\n");
                }
                if (!w.getMarks().isEmpty()) {
                    out.append("Markiert:\n");
                    for (WrittenSection.Mark mark : w.getMarks()) {
                        out.append("- ").append(mark.getKind()).append(mark.isRewritten() ? " (neu gefasst)" : "")
                                .append(": ").append(mark.getSentence()).append(" | ").append(mark.getReason())
                                .append('\n');
                    }
                    out.append('\n');
                }
                if (!w.getGaps().isEmpty()) {
                    out.append("Lücken:\n");
                    for (WrittenSection.Gap gap : w.getGaps()) {
                        out.append("- ").append(gap.getKind()).append(gap.getQuestionId().isEmpty() ? ""
                                : " (" + gap.getQuestionId() + ")").append(": ").append(gap.getText()).append('\n');
                    }
                    out.append('\n');
                }
                if (!w.getCitations().isEmpty()) {
                    List<String> cited = new ArrayList<String>();
                    for (WrittenSection.Citation citation : w.getCitations().values()) {
                        cited.add("[" + citation.getMarker() + "] " + citation.getPassageId());
                    }
                    out.append("Belege: ").append(String.join(", ", cited)).append("\n\n");
                }
                if (!w.getWithoutText().isEmpty()) {
                    out.append("Ohne Text im Bestand: ").append(String.join(", ", w.getWithoutText())).append("\n\n");
                }
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ the tests

    @Test
    public void writesTheSectionsOfAStoredPlan() throws Exception {
        String plan = option("plan", "");
        String corpus = option("korpus", "");
        String out = option("out", "");
        Assume.assumeTrue("needs -D" + P + "plan, -D" + P + "korpus and -D" + P + "out",
                !plan.isEmpty() && !corpus.isEmpty() && !out.isEmpty());
        Settings settings = settings(plan, corpus, option("quellen", ""), out);
        String url = option("chatUrl", "http://127.0.0.1:11434");
        String model = option("chatModel", "gemma4:12b");
        String checkModel = option("pruefModell", model);
        String numCtx = option("numCtx", "8192");
        String think = option("think", "false");
        Integer context = "omit".equalsIgnoreCase(numCtx) ? null : Integer.valueOf(numCtx);
        Boolean thinking = "omit".equalsIgnoreCase(think) ? null : Boolean.valueOf(think);
        int timeout = Integer.parseInt(option("timeoutSekunden", "900")) * 1000;
        OllamaStockChat writer = new OllamaStockChat(url, model, context, thinking, timeout);
        OllamaStockChat checker = "aus".equalsIgnoreCase(checkModel) ? null
                : new OllamaStockChat(url, checkModel, context, thinking, timeout);

        JsonObject result = run(settings, writer, checker);

        result.addProperty("modell", model);
        result.addProperty("pruefModell", checker == null ? "" : checkModel);
        result.addProperty("numCtx", context == null ? "omit" : String.valueOf(context));
        JsonObject calls = new JsonObject();
        calls.add("schreiber", calls(writer));
        if (checker != null) {
            calls.add("pruefung", calls(checker));
        }
        result.add("aufrufe", calls);
        writeJson(new File(settings.out, "result.json"), result);
        StringBuilder summary = new StringBuilder("[writer] result:");
        for (String key : Arrays.asList("S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8")) {
            JsonObject criterion = result.getAsJsonObject(key);
            summary.append(' ').append(key).append(' ').append(criterion.get("wert").getAsLong()).append('/')
                    .append(criterion.get("von").getAsLong())
                    .append(criterion.get("bestanden").getAsBoolean() ? " ok" : " NICHT");
        }
        System.out.println(summary);
    }

    @Test
    public void theSampleRunWritesTheBookAndOnlyCountsIntoTheResult() throws Exception {
        File folder = scratch.newFolder("sample");
        Settings settings = sampleSettings(folder);
        Map<String, String> drafts = new LinkedHashMap<String, String>();
        drafts.put("Überwachung", "Tempo 30 senkt den Lärm in Wohnstraßen deutlich [1]. Die Blitzer erkennen jedes "
                + "Fahrzeug zuverlässig [2].");
        drafts.put("Wien", "In Wien gilt in Wohngebieten flächendeckend Tempo 30 [1]. In Tempo-30-Zonen gibt es "
                + "weniger schwere Unfälle [2]. Das zeigt viel.");
        Map<String, String> rewrites = new LinkedHashMap<String, String>();
        rewrites.put("Die Blitzer", "Nach Angaben des Herstellers erkennen seine Blitzer jedes Fahrzeug zuverlässig "
                + "[2].");
        Map<String, String[]> said = new LinkedHashMap<String, String[]>();
        said.put("Tempo 30 senkt", PlanSectionWriterTest.says("GEDECKT", "Tempo 30 senkt den Lärm in Wohnstraßen "
                + "deutlich", "Die Passage sagt das."));
        said.put("Die Blitzer", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ALS_TATSACHE", null});
        said.put("Nach Angaben", new String[]{"GEDECKT", "Blitzer erkennen jedes Fahrzeug zuverlässig",
                "Die Passage sagt das.", "ZUGESCHRIEBEN", null});
        said.put("In Wien", PlanSectionWriterTest.says("GEDECKT", "In Wien gilt in Wohngebieten flächendeckend "
                + "Tempo 30", "Die Passage sagt das."));
        said.put("In Tempo-30-Zonen", PlanSectionWriterTest.says("GEDECKT", "In Tempo-30-Zonen gibt es weniger "
                + "schwere Unfälle", "Die Passage sagt das."));
        PlanSectionWriterTest.Scripted checker = PlanSectionWriterTest.checker(said);

        JsonObject result = run(settings, PlanSectionWriterTest.writer(drafts, rewrites), checker);

        assertEquals("plan.json", result.get("plan").getAsString());
        assertEquals(5, result.get("passagenImBestand").getAsInt());
        assertEquals("stadt-b has no rating", 1, result.get("passagenOhneQuellenart").getAsInt());
        assertEquals(3, result.get("abschnitteVerlangt").getAsInt());
        JsonArray sections = result.getAsJsonArray("abschnitte");
        JsonObject watch = sections.get(0).getAsJsonObject();
        assertEquals("abschnitt-w", watch.get("abschnittId").getAsString());
        assertEquals("kapitel-1", watch.get("kapitelId").getAsString());
        assertEquals("OK", watch.get("status").getAsString());
        assertEquals(18, watch.get("woerter").getAsInt());
        assertEquals(1, watch.getAsJsonObject("zaehler").get("ueberarbeitet").getAsInt());
        assertEquals(2, watch.get("kernpunkteMitBeleg").getAsInt());
        assertEquals(2, watch.get("getragen").getAsInt());
        assertEquals("the maker's sentence", 1, watch.get("zuschreibungFaelle").getAsInt());
        assertEquals(0, watch.get("rechtsraumFaelle").getAsInt());
        assertEquals(1, watch.getAsJsonObject("lueckenNachArt").get("UNTER_ZIEL").getAsInt());
        JsonObject vienna = sections.get(1).getAsJsonObject();
        assertEquals("OK", vienna.get("status").getAsString());
        assertEquals(1, vienna.getAsJsonObject("markiertNachArt").get("OHNE_BELEG").getAsInt());
        assertEquals("q2 carried; q3 has leads only, q4 nothing", 1, vienna.get("kernpunkteMitBeleg").getAsInt());
        assertEquals(1, vienna.get("getragen").getAsInt());
        assertEquals("Vienna's rule", 1, vienna.get("rechtsraumFaelle").getAsInt());
        assertEquals(1, vienna.getAsJsonObject("lueckenNachArt").get("NUR_HINWEISE").getAsInt());
        assertEquals(1, vienna.getAsJsonObject("lueckenNachArt").get("FRAGE_OHNE_BELEG").getAsInt());
        JsonObject empty = sections.get(2).getAsJsonObject();
        assertEquals("OHNE_STOFF", empty.get("status").getAsString());
        assertEquals(1, empty.get("ohneText").getAsInt());

        assertCriterion(result, "S1", 0, 3, true);
        assertCriterion(result, "S2", 0, 4, true);
        assertCriterion(result, "S3", 1, 5, false);
        assertCriterion(result, "S4", 3, 3, true);
        assertCriterion(result, "S5", 0, 2, false);
        assertEquals(36, result.getAsJsonObject("S5").getAsJsonObject("gesamt").get("wert").getAsLong());
        assertEquals("Wien by its material: 3 passages with text x 70 instead of 300", 270,
                result.getAsJsonObject("S5").getAsJsonObject("gesamt").get("von").getAsLong());
        assertEquals(210, vienna.get("zielNachStoff").getAsInt());
        assertEquals(300, vienna.get("zielwoerter").getAsInt());
        assertEquals(3, vienna.get("passagenMitText").getAsInt());
        JsonObject uncapped = result.getAsJsonObject("S5").getAsJsonObject("ungedeckelt");
        assertEquals(0, uncapped.get("wert").getAsInt());
        assertEquals(2, uncapped.get("von").getAsInt());
        assertEquals(360, uncapped.getAsJsonObject("gesamt").get("von").getAsLong());
        assertEquals(70, result.getAsJsonObject("einstellungen").get("woerterJePassage").getAsInt());
        assertEquals(210, PlanSectionWriterStockTest.boundedTarget(300, 3, 70));
        assertEquals("within its material the plan stands", 60, PlanSectionWriterStockTest.boundedTarget(60, 2, 70));
        assertEquals("no bound", 300, PlanSectionWriterStockTest.boundedTarget(300, 3, 0));
        assertEquals("a section without text holds no words", 0, PlanSectionWriterStockTest.boundedTarget(300, 0, 70));
        assertEquals("[\"abschnitt-w\",\"abschnitt-wien\"]", result.getAsJsonObject("S5").getAsJsonArray("unter")
                .toString());
        assertCriterion(result, "S6", 0, 1, true);
        assertCriterion(result, "S7", 0, 1, true);
        assertCriterion(result, "S8", 0, 4, true);
        assertEquals(1, result.getAsJsonObject("bericht").get("neufassungBesteht").getAsInt());
        assertEquals(1, result.getAsJsonObject("bericht").get("ohneStoff").getAsInt());
        assertEquals("the count stays beside the kinds", 1, result.getAsJsonObject("summen").get("markiert")
                .getAsInt());
        assertEquals(2, result.getAsJsonObject("summen").getAsJsonObject("lueckenNachArt").get("UNTER_ZIEL")
                .getAsInt());

        String json = read(new File(settings.out, "result.json"));
        for (String text : Arrays.asList(PlanSectionWriterTest.P1, PlanSectionWriterTest.P2, PlanSectionWriterTest.P3,
                PlanSectionWriterTest.P4, PlanSectionWriterTest.P5, "Lärm", "Blitzer", "Wien", "Passage sagt",
                "zitiert keine", PlanSectionWriterTest.Q1)) {
            assertFalse("no text in result.json: " + text, json.contains(text));
        }
        String book = read(new File(settings.out, "buch.md"));
        assertTrue(book, book.startsWith("# Verkehr in der Stadt\n\n## Überwachung\n\n_abschnitt-w OK, 18 of 60 "));
        assertTrue(book, book.contains("Nach Angaben des Herstellers erkennen seine Blitzer jedes Fahrzeug "
                + "zuverlässig [2]."));
        assertTrue(book, book.contains("- OHNE_BELEG: Das zeigt viel. | zitiert keine Passage\n"));
        assertTrue(book, book.contains("Belege: [1] p1, [2] p3\n"));
        assertTrue(book, book.contains("Ohne Text im Bestand: p6\n"));
        assertTrue(read(new File(settings.out, "log.txt")).contains("section abschnitt-w: 18 of 60 words"));
        WrittenSections stored = new WrittenSections(new File(settings.out, "projekt"));
        assertEquals(3, stored.open());

        try {
            run(settings, PlanSectionWriterTest.writer(drafts, rewrites), checker);
            fail("the project is there already");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("weiter=true"));
        }
        int asked = checker.calls.get();
        settings.resume = true;
        settings.sections.add("abschnitt-w");
        settings.sections.add("abschnitt-x");
        JsonObject again = run(settings, PlanSectionWriterTest.writer(drafts, rewrites), checker);
        assertEquals(1, again.get("abschnitteVerlangt").getAsInt());
        assertEquals("abschnitt-x", again.getAsJsonArray("unbekannteAbschnitte").get(0).getAsString());
        assertEquals("the stored verdicts are used again", asked, checker.calls.get());
        assertEquals(4, new WrittenSections(new File(settings.out, "projekt")).open());
    }

    @Test
    public void theStockChatSendsProseWithoutAFormatAndKeepsACutOffProseAnswer() {
        OllamaStockChat chat = new OllamaStockChat("http://127.0.0.1:1/", "m", 8192, Boolean.FALSE, 1000);
        List<com.aresstack.askai.research.runtime.team.ChatMessage> messages = Collections.singletonList(
                com.aresstack.askai.research.runtime.team.ChatMessage.user("Schreib."));
        JsonObject prose = chat.body(messages, 0.2, 100, null, false);
        assertFalse(prose.has("format"));
        assertFalse(prose.get("truncate").getAsBoolean());
        assertEquals(8192, prose.getAsJsonObject("options").get("num_ctx").getAsInt());
        assertEquals(100, prose.getAsJsonObject("options").get("num_predict").getAsInt());
        assertEquals("user", prose.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("json", chat.body(messages, 0.0, 100, null, true).get("format").getAsString());
        assertEquals("object", chat.body(messages, 0.0, 100, PlanSectionWriter.REWRITE_SCHEMA, true)
                .getAsJsonObject("format").get("type").getAsString());

        JsonObject cut = JsonParser.parseString("{\"model\":\"m:latest\",\"done_reason\":\"length\","
                + "\"eval_count\":100,\"prompt_eval_count\":40,\"total_duration\":30000000000,"
                + "\"message\":{\"content\":\"Ein Satz. Und\"}}").getAsJsonObject();
        com.aresstack.askai.research.runtime.team.MainModelChatResult kept = chat.answer(cut, 100, false);
        assertTrue(kept.isOk());
        assertTrue(kept.isCutOff());
        assertEquals("Ein Satz. Und", kept.getText());
        assertEquals("m:latest", kept.getMetrics().getModelLabel());
        assertEquals(30_000L, kept.getMetrics().getDurationMillis());
        assertFalse("a cut-off JSON answer fails", chat.answer(cut, 100, true).isOk());
        assertEquals(2, chat.cutOff.get());
        assertEquals(1, chat.failures.get());
        assertEquals(200L, chat.evalTokens.get());
        assertFalse(chat.complete(messages, 0.2, 10).isOk());
        assertEquals("nothing listens there", 1, chat.calls.get());
    }

    private static void assertCriterion(JsonObject result, String key, long value, long of, boolean passed) {
        JsonObject criterion = result.getAsJsonObject(key);
        assertEquals(key + " " + criterion, value, criterion.get("wert").getAsLong());
        assertEquals(key + " " + criterion, of, criterion.get("von").getAsLong());
        assertEquals(key + " " + criterion, passed, criterion.get("bestanden").getAsBoolean());
    }

    /** A plan of three sections, a stock of five passages and their sources' ratings, all self-written. */
    static Settings sampleSettings(File folder) throws IOException {
        SectionPlan.Section vienna = PlanSectionWriterTest.section("abschnitt-wien", "Wien", 300,
                Arrays.asList(PlanSectionWriterTest.ev("p4", "wien", "q2"), PlanSectionWriterTest.ev("p2", "stadt-b",
                        "q2")),
                Collections.singletonList(PlanSectionWriterTest.ev("p5", "verband", "q3")),
                Arrays.asList(PlanSectionWriterTest.q("q2", PlanSectionWriterTest.Q2, QuestionEvidence.LABEL_DIRECT),
                        PlanSectionWriterTest.q("q3", PlanSectionWriterTest.Q3, QuestionEvidence.LABEL_LEAD),
                        PlanSectionWriterTest.q("q4", PlanSectionWriterTest.Q4, QuestionEvidence.LABEL_NONE)),
                Collections.<SectionPlan.Contradiction>emptyList());
        SectionPlan.Section empty = PlanSectionWriterTest.section("abschnitt-leer", "Kontrolle", 300,
                Collections.singletonList(PlanSectionWriterTest.ev("p6", "fehlt", "q6")),
                Collections.<SectionPlan.Evidence>emptyList(),
                Collections.singletonList(PlanSectionWriterTest.q("q6", PlanSectionWriterTest.Q6,
                        QuestionEvidence.LABEL_DIRECT)),
                Collections.<SectionPlan.Contradiction>emptyList());
        SectionPlan plan = PlanSectionWriterTest.plan(PlanSectionWriterTest.watch(), vienna, empty);
        File planFile = new File(folder, "plan.json");
        writeJson(planFile, plan.toJson());

        JsonObject corpus = new JsonObject();
        corpus.addProperty("language", "de");
        corpus.addProperty("mission", "Verkehr in der Stadt");
        JsonArray passages = new JsonArray();
        passages.add(passage("p1", "stadt-a", "stadt-a.example", PlanSectionWriterTest.P1));
        passages.add(passage("p2", "stadt-b", "stadt-b.example", PlanSectionWriterTest.P2));
        passages.add(passage("p3", "blitzer", "blitzer.example", PlanSectionWriterTest.P3));
        passages.add(passage("p4", "wien", "wien.example", PlanSectionWriterTest.P4));
        passages.add(passage("p5", "verband", "verband.example", PlanSectionWriterTest.P5));
        corpus.add("passages", passages);
        File corpusFile = new File(folder, "korpus.json");
        writeJson(corpusFile, corpus);

        JsonObject sources = new JsonObject();
        JsonArray domains = new JsonArray();
        domains.add(domain("stadt-a.example", "BEHOERDE_UNFALLVERSICHERUNG", "DE"));
        domains.add(domain("Blitzer.example", "HERSTELLER_HANDEL", "DE"));
        domains.add(domain("wien.example", "BEHOERDE_UNFALLVERSICHERUNG", "AT"));
        domains.add(domain("verband.example", "VERBAND", "DE"));
        sources.add("domains", domains);
        File sourcesFile = new File(folder, "quellen.json");
        writeJson(sourcesFile, sources);

        Settings settings = new Settings();
        settings.plan = planFile;
        settings.corpus = corpusFile;
        settings.sources = sourcesFile;
        settings.out = new File(folder, "out");
        settings.parallel = 2;
        settings.checkParallel = 1;
        settings.topUp = false;
        return settings;
    }

    private static JsonObject passage(String id, String source, String domain, String text) {
        JsonObject passage = new JsonObject();
        passage.addProperty("id", id);
        passage.addProperty("sourceId", source);
        passage.addProperty("domain", domain);
        passage.addProperty("text", text);
        return passage;
    }

    private static JsonObject domain(String domain, String kind, String space) {
        JsonObject json = new JsonObject();
        json.addProperty("domain", domain);
        json.addProperty("quellenart", kind);
        json.addProperty("rechtsraum", space);
        json.addProperty("eigeninteresse", "NEIN");
        return json;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static JsonObject calls(OllamaStockChat chat) {
        JsonObject json = new JsonObject();
        json.addProperty("aufrufe", chat.calls.get());
        json.addProperty("fehler", chat.failures.get());
        json.addProperty("abgeschnitten", chat.cutOff.get());
        json.addProperty("promptTokens", chat.promptTokens.get());
        json.addProperty("antwortTokens", chat.evalTokens.get());
        json.addProperty("denkZeichen", chat.thinkingChars.get());
        json.addProperty("ms", chat.millis.get());
        return json;
    }

    static String chapterIdOf(SectionPlan plan, String sectionId) {
        for (SectionPlan.Chapter chapter : plan.getChapters()) {
            for (SectionPlan.Section section : chapter.getSections()) {
                if (section.getId().equals(sectionId)) {
                    return chapter.getId();
                }
            }
        }
        return "";
    }

    private static long sum(List<WrittenSection> written, boolean words) {
        long n = 0;
        for (WrittenSection section : written) {
            n += words ? section.getWords() : section.getTargetWords();
        }
        return n;
    }

    private static void add(Map<String, Long> map, String key, long n) {
        Long had = map.get(key);
        map.put(key, (had == null ? 0L : had) + n);
    }

    private static long value(Map<String, Long> map, String key) {
        Long had = map.get(key);
        return had == null ? 0L : had;
    }

    private static JsonObject object(Map<String, Long> map) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static String clip(String text, int chars) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= chars ? flat : flat.substring(0, chars) + " …";
    }

    static String option(String name, String fallback) {
        return System.getProperty(P + name, fallback).trim();
    }

    private static JsonArray array(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString().trim();
    }

    static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        Files.createDirectories(file.getAbsoluteFile().getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJson(File file, JsonObject json) throws IOException {
        write(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n");
    }
}

package com.aresstack.askai.research.conceptrun;

import com.aresstack.askai.research.evidence.EvidenceBundleJudge;
import com.aresstack.askai.research.planwriter.OllamaStockChat;
import com.aresstack.askai.research.planwriter.PlanSectionWriter;
import com.aresstack.askai.research.runtime.sourcerating.SourceRater;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;

/**
 * The concept-led run headless on a real model (Ollama), outside CI; skipped without {@code -Daskai.stock.concept.model}.
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*ConceptRunStockTest*' \
 *   -Daskai.stock.concept.model=gemma4:12b -Daskai.stock.concept.out=&lt;folder&gt;
 * </pre>
 * Input, one of:
 * <ul>
 * <li>{@code bestand}: a test bestand file; without it the invented controlled bestand of the repository
 * ({@code concept-run/bestand-kontrolliert.json}: relevant, irrelevant, mixed, unclear, contradictory sources);</li>
 * <li>{@code korpus} ({@code replay-corpus.json}), {@code fragen} ({@code fragen-1711.json}) and {@code paare}
 * ({@code belegpaare-1711.jsonl}, the candidates per question): the book of 17 Nov.</li>
 * </ul>
 * Settings: {@code url} (http://127.0.0.1:11434), {@code model}, {@code writerModel} and {@code checkModel} (default
 * {@code model}, so nothing switches; {@code checkModel=aus}: no sentence check), {@code numCtx} (16384, also the
 * context the evidence bundles are sized for), {@code think} (false), {@code timeoutSekunden} (900),
 * {@code zielWoerter} (the bestand's), {@code parallel} (1: questions of the evidence check at a time),
 * {@code auffuellen} (false). Output in {@code out}: {@code ergebnis.json} (steps with calls, tokens and time, model
 * switches, every original question with where it is answered or why not, per expected class judged and cited, the
 * mission report), {@code buch.md}, {@code bericht.txt}. Nothing of a stock goes into the repository.
 */
public class ConceptRunStockTest {

    private static final String P = "askai.stock.concept.";

    @Test
    public void run() throws Exception {
        String model = System.getProperty(P + "model", "");
        Assume.assumeTrue("concept run skipped (no -D" + P + "model)", !model.isEmpty());
        String url = System.getProperty(P + "url", "http://127.0.0.1:11434");
        int numCtx = Integer.getInteger(P + "numCtx", 16384);
        Boolean think = Boolean.valueOf(System.getProperty(P + "think", "false"));
        int timeout = Integer.getInteger(P + "timeoutSekunden", 900) * 1000;
        File out = new File(System.getProperty(P + "out", "build/concept-run"));

        ConceptStock stock;
        String korpus = System.getProperty(P + "korpus", "");
        if (!korpus.isEmpty()) {
            String paare = System.getProperty(P + "paare", "");
            try (Reader corpus = Files.newBufferedReader(new File(korpus).toPath(), StandardCharsets.UTF_8);
                 Reader questions = Files.newBufferedReader(new File(System.getProperty(P + "fragen")).toPath(),
                         StandardCharsets.UTF_8);
                 BufferedReader pairs = paare.isEmpty() ? null : Files.newBufferedReader(new File(paare).toPath(),
                         StandardCharsets.UTF_8)) {
                stock = ConceptStock.of1711(corpus, questions, pairs, Integer.getInteger(P + "zielWoerter", 0));
            }
        } else {
            String bestand = System.getProperty(P + "bestand", "");
            try (Reader in = bestand.isEmpty() ? ConceptStock.resource("bestand-kontrolliert.json")
                    : Files.newBufferedReader(new File(bestand).toPath(), StandardCharsets.UTF_8)) {
                stock = ConceptStock.bestand(in);
            }
            stock.targetWords = Integer.getInteger(P + "zielWoerter", stock.targetWords);
        }

        OllamaStockChat research = new OllamaStockChat(url, model, numCtx, think, timeout);
        String writerModel = System.getProperty(P + "writerModel", model);
        OllamaStockChat writer = writerModel.equals(model) ? research
                : new OllamaStockChat(url, writerModel, numCtx, think, timeout);
        String checkModel = System.getProperty(P + "checkModel", model);
        OllamaStockChat check = "aus".equals(checkModel) ? null : checkModel.equals(model) ? research
                : checkModel.equals(writerModel) ? writer : new OllamaStockChat(url, checkModel, numCtx, think, timeout);

        EvidenceBundleJudge.Options judge = EvidenceBundleJudge.Options.defaults().withContextTokens(numCtx)
                .withParallel(Integer.getInteger(P + "parallel", 1));
        PlanSectionWriter.Options writing = PlanSectionWriter.Options.defaults().withLanguage(stock.language)
                .withTopUp(Boolean.parseBoolean(System.getProperty(P + "auffuellen", "false")));
        ConceptRun.Settings settings = new ConceptRun.Settings(stock.language, stock.targetWords,
                SourceRater.Options.defaults(), judge, writing);

        System.out.println("[concept] " + stock.title + ": " + stock.questions.size() + " questions, "
                + stock.passages.size() + " passages, model " + model);
        ConceptRun.Result result = new ConceptRun(research, writer, check, settings, Clock.systemUTC(),
                line -> System.out.println("[concept] " + line)).run(stock.fence, stock.cards, stock.questions,
                stock.passages, stock.candidates);

        JsonObject run = new JsonObject();
        run.addProperty("modell", model);
        run.addProperty("schreibModell", writerModel);
        run.addProperty("pruefModell", checkModel);
        run.addProperty("numCtx", numCtx);
        run.addProperty("zielWoerter", stock.targetWords);
        JsonObject json = stock.write(result, out, run);
        System.out.println("[concept] questions " + json.get("fragenAbdeckung") + ", calls " + json.get("aufrufe")
                + ", tokens " + json.get("tokens") + ", seconds " + json.get("sekunden") + ", model switches "
                + json.get("modellwechsel") + (json.has("klassen") ? ", classes " + json.get("klassen") : ""));
        System.out.println("[concept] written to " + out.getAbsolutePath());
    }
}

package com.aresstack.askai.research.bookrun;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.evidence.EvidenceBundleJudge;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.planwriter.OllamaStockChat;
import com.aresstack.askai.research.planwriter.PlanSectionWriter;
import com.aresstack.askai.research.runtime.sourcerating.SourceRater;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;

/**
 * Phase 4 headless on a real model (Ollama), outside CI; skipped without {@code -Daskai.stock.book.model}.
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*BookRunStockTest*' \
 *   -Daskai.stock.book.model=gemma4:12b -Daskai.stock.book.out=&lt;folder&gt;
 * </pre>
 * Input, one of: {@code korb} (a test basket file; without it the invented controlled basket of the repository,
 * {@code book-run/korb-kontrolliert.json}) or {@code korpus} ({@code replay-corpus.json} of 17 Nov, every source in
 * the basket, the mission as the book idea). Settings: {@code url} (http://127.0.0.1:11434), {@code model},
 * {@code writerModel} and {@code checkModel} (default {@code model}, so nothing switches; {@code checkModel=aus}: no
 * sentence check), {@code einbettung} (bge-m3) with {@code einbettungDimension} (1024, the dimension of bge-m3),
 * {@code gruppenAbstand} (the setting of the topic analysis), {@code numCtx} (16384, also the context the bundles are
 * sized for), {@code think} (false), {@code timeoutSekunden} (900), {@code zielWoerter} (the basket's),
 * {@code kapazitaet} (1: the concurrent calls the model takes; more only where the endpoint runs them, as Ollama with
 * {@code OLLAMA_NUM_PARALLEL} or a cloud service), {@code auffuellen} (false). Output in {@code out}: {@code fragen.json}, {@code fragen.md},
 * {@code ergebnis.json}, {@code buch.md}, {@code bericht.txt}. Nothing of a stock goes into the repository.
 */
public class BookRunStockTest {

    private static final String P = "askai.stock.book.";

    @Test
    public void run() throws Exception {
        String model = System.getProperty(P + "model", "");
        Assume.assumeTrue("book run skipped (no -D" + P + "model)", !model.isEmpty());
        String url = System.getProperty(P + "url", "http://127.0.0.1:11434");
        int numCtx = Integer.getInteger(P + "numCtx", 16384);
        Boolean think = Boolean.valueOf(System.getProperty(P + "think", "false"));
        int timeout = Integer.getInteger(P + "timeoutSekunden", 900) * 1000;
        File out = new File(System.getProperty(P + "out", "build/book-run"));

        BasketStock stock;
        String korpus = System.getProperty(P + "korpus", "");
        if (!korpus.isEmpty()) {
            try (Reader corpus = Files.newBufferedReader(new File(korpus).toPath(), StandardCharsets.UTF_8)) {
                stock = BasketStock.of1711(corpus, Integer.getInteger(P + "zielWoerter", 0));
            }
        } else {
            String korb = System.getProperty(P + "korb", "");
            try (Reader in = korb.isEmpty() ? BasketStock.resource("korb-kontrolliert.json")
                    : Files.newBufferedReader(new File(korb).toPath(), StandardCharsets.UTF_8)) {
                stock = BasketStock.korb(in);
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
        String embedModel = System.getProperty(P + "einbettung", "bge-m3");
        HttpEmbeddingPortAdapter embeddings = new HttpEmbeddingPortAdapter(new EmbeddingEndpointDescriptor(embedModel,
                url, "/api/embed", Integer.getInteger(P + "einbettungDimension", 1024), "none", "", timeout),
                new UrlConnectionEmbeddingHttpTransport(timeout));

        BookRun.Settings settings = new BookRun.Settings(stock.language, stock.targetWords,
                Double.parseDouble(System.getProperty(P + "gruppenAbstand", "0")),
                Integer.getInteger(P + "kapazitaet", 1), SourceRater.Options.defaults(),
                StockQuestions.Options.defaults().withContextTokens(numCtx),
                EvidenceBundleJudge.Options.defaults().withContextTokens(numCtx),
                PlanSectionWriter.Options.defaults().withLanguage(stock.language)
                        .withTopUp(Boolean.parseBoolean(System.getProperty(P + "auffuellen", "false"))));

        System.out.println("[book] " + stock.title + ": " + stock.basket.getSources().size() + " sources, "
                + stock.passageCount() + " passages, model " + model + ", embeddings " + embedModel);
        BookRun.Result result = new BookRun(research, writer, check, embeddings, settings, Clock.systemUTC(),
                line -> System.out.println("[book] " + line)).run(stock.fence, stock.basket, stock);

        JsonObject run = new JsonObject();
        run.addProperty("modell", model);
        run.addProperty("schreibModell", writerModel);
        run.addProperty("pruefModell", checkModel);
        run.addProperty("einbettung", embedModel);
        run.addProperty("numCtx", numCtx);
        run.addProperty("zielWoerter", stock.targetWords);
        JsonObject json = stock.write(result, out, run);
        System.out.println("[book] questions " + json.get("fragenAbdeckung") + ", calls " + json.get("aufrufe")
                + ", tokens " + json.get("tokens") + ", seconds " + json.get("sekunden") + ", model switches "
                + json.get("modellwechsel") + (json.has("klassen") ? ", classes " + json.get("klassen") : ""));
        System.out.println("[book] written to " + out.getAbsolutePath());
    }
}

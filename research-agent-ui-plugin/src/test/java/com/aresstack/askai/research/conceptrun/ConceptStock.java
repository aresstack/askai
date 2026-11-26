package com.aresstack.askai.research.conceptrun;

import com.aresstack.askai.research.domain.scope.ResearchFence;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.planwriter.WrittenSection;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test tooling: the input of a concept run from a file, and its result written to a folder. Two inputs:
 * <ul>
 * <li>a test bestand ({@code bestand-kontrolliert.json}): {@code weidezaun}, {@code karten}, {@code fragen},
 * {@code passagen} with {@code id}, {@code quelle}, {@code url}, {@code titel}, {@code ueberschrift}, {@code text} and
 * the expected class {@code art} (never sent to the model);</li>
 * <li>the stock of 17 Nov on the owner's machine: {@code replay-corpus.json} (mission, language, passages with
 * {@code id}, {@code sourceId}, {@code domain}, {@code heading}, {@code text}), {@code fragen-1711.json} (themes as
 * cards, questions) and, as candidates, the pairs of {@code belegpaare-1711.jsonl} ({@code frage}, {@code passage}).
 * </li>
 * </ul>
 */
final class ConceptStock {

    String title = "";
    String language = "de";
    int targetWords;
    ResearchFence fence;
    final List<ConceptRun.Card> cards = new ArrayList<ConceptRun.Card>();
    final List<ConceptRun.Question> questions = new ArrayList<ConceptRun.Question>();
    final List<ConceptRun.StockPassage> passages = new ArrayList<ConceptRun.StockPassage>();
    /** The expected class of a passage by its id (only a test bestand has them). */
    final Map<String, String> classes = new LinkedHashMap<String, String>();
    /** The candidates per question id; {@code null}: every passage. */
    Map<String, List<String>> candidates;

    static ConceptStock bestand(Reader in) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        ConceptStock stock = new ConceptStock();
        stock.title = text(root, "titel");
        stock.language = text(root, "sprache").isEmpty() ? "de" : text(root, "sprache");
        stock.targetWords = root.has("zielWoerter") ? root.get("zielWoerter").getAsInt() : 0;
        JsonObject fence = root.getAsJsonObject("weidezaun");
        List<String> cardNames = new ArrayList<String>();
        for (JsonElement element : root.getAsJsonArray("karten")) {
            JsonObject card = element.getAsJsonObject();
            stock.cards.add(new ConceptRun.Card(text(card, "id"), text(card, "name"),
                    card.has("anteil") ? card.get("anteil").getAsDouble() : 0));
            cardNames.add(text(card, "name"));
        }
        stock.fence = new ResearchFence(text(fence, "auftrag"), text(fence, "rand"), list(fence, "dazu"),
                list(fence, "ausgeschlossen"), text(fence, "raum"), text(fence, "zeit"), cardNames);
        for (JsonElement element : root.getAsJsonArray("fragen")) {
            JsonObject question = element.getAsJsonObject();
            stock.questions.add(new ConceptRun.Question(text(question, "id"), text(question, "text"),
                    text(question, "karte")));
        }
        for (JsonElement element : root.getAsJsonArray("passagen")) {
            JsonObject p = element.getAsJsonObject();
            String url = text(p, "url");
            stock.passages.add(new ConceptRun.StockPassage(text(p, "id"), text(p, "quelle"), url, host(url),
                    text(p, "titel"), text(p, "ueberschrift"), text(p, "text")));
            if (!text(p, "art").isEmpty()) {
                stock.classes.put(text(p, "id"), text(p, "art"));
            }
        }
        return stock;
    }

    /** The stock of 17 Nov: corpus, questions with themes, and the stored pairs as candidates. */
    static ConceptStock of1711(Reader corpus, Reader questions, BufferedReader pairs, int targetWords)
            throws IOException {
        ConceptStock stock = new ConceptStock();
        JsonObject root = JsonParser.parseReader(corpus).getAsJsonObject();
        stock.title = text(root, "title");
        stock.language = text(root, "language").isEmpty() ? "de" : text(root, "language");
        stock.targetWords = targetWords;
        for (JsonElement element : root.getAsJsonArray("passages")) {
            JsonObject p = element.getAsJsonObject();
            String domain = text(p, "domain");
            stock.passages.add(new ConceptRun.StockPassage(text(p, "id"), text(p, "sourceId"),
                    domain.isEmpty() ? "" : "https://" + domain + "/", domain, "", text(p, "heading"),
                    text(p, "text")));
        }
        JsonObject q = JsonParser.parseReader(questions).getAsJsonObject();
        List<String> cardNames = new ArrayList<String>();
        for (Map.Entry<String, JsonElement> theme : q.getAsJsonObject("themen").entrySet()) {
            stock.cards.add(new ConceptRun.Card("thema-" + theme.getKey(), theme.getValue().getAsString(), 0));
            cardNames.add(theme.getValue().getAsString());
        }
        for (JsonElement element : q.getAsJsonArray("fragen")) {
            JsonObject question = element.getAsJsonObject();
            stock.questions.add(new ConceptRun.Question(text(question, "id"), text(question, "text"),
                    "thema-" + question.get("thema").getAsString()));
        }
        stock.fence = ResearchFence.of(text(root, "mission"), cardNames);
        if (pairs != null) {
            Map<String, Set<String>> byQuestion = new LinkedHashMap<String, Set<String>>();
            String line;
            while ((line = pairs.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonObject pair = JsonParser.parseString(line).getAsJsonObject();
                Set<String> ids = byQuestion.get(text(pair, "frage"));
                if (ids == null) {
                    ids = new LinkedHashSet<String>();
                    byQuestion.put(text(pair, "frage"), ids);
                }
                ids.add(text(pair, "passage"));
            }
            stock.candidates = new LinkedHashMap<String, List<String>>();
            for (ConceptRun.Question question : stock.questions) {
                Set<String> ids = byQuestion.get(question.getId());
                stock.candidates.put(question.getId(), ids == null ? new ArrayList<String>()
                        : new ArrayList<String>(ids));
            }
        }
        return stock;
    }

    /** The result as {@code ergebnis.json}, {@code buch.md} and {@code bericht.txt} in {@code out}. */
    JsonObject write(ConceptRun.Result result, File out, JsonObject run) throws IOException {
        Files.createDirectories(out.toPath());
        JsonObject json = new JsonObject();
        json.addProperty("bestand", title);
        json.add("lauf", run);
        JsonArray steps = new JsonArray();
        long calls = 0;
        long tokens = 0;
        long millis = 0;
        for (ConceptRun.Step step : result.getSteps()) {
            JsonObject s = new JsonObject();
            s.addProperty("schritt", step.getName());
            s.addProperty("aufrufe", step.getCalls());
            s.addProperty("fehlgeschlagen", step.getFailures());
            s.addProperty("promptTokens", step.getPromptTokens());
            s.addProperty("antwortTokens", step.getAnswerTokens());
            s.addProperty("sekunden", step.getMillis() / 1000.0);
            steps.add(s);
            calls += step.getCalls();
            tokens += step.getPromptTokens() + step.getAnswerTokens();
            millis += step.getMillis();
        }
        json.add("schritte", steps);
        json.addProperty("aufrufe", calls);
        json.addProperty("tokens", tokens);
        json.addProperty("sekunden", millis / 1000.0);
        json.addProperty("modellwechsel", result.getModelSwitches());
        json.addProperty("zitiertAusserhalbWeidezaun", result.getCitedOutsideFence());
        JsonArray questionLines = new JsonArray();
        Map<String, Integer> states = new LinkedHashMap<String, Integer>();
        for (ConceptRun.QuestionLine line : result.getQuestions()) {
            JsonObject q = new JsonObject();
            q.addProperty("id", line.getQuestion().getId());
            q.addProperty("frage", line.getQuestion().getText());
            q.addProperty("stand", line.getState().name());
            q.addProperty("abschnitt", line.getSection());
            q.addProperty("grund", line.getReason());
            q.addProperty("belegt", line.getDirect());
            q.addProperty("hinweise", line.getLeads());
            q.addProperty("nichtAnwendbar", line.getNotApplicable());
            q.addProperty("anwendbarkeitUnklar", line.getUnclear());
            questionLines.add(q);
            states.merge(line.getState().name(), 1, Integer::sum);
        }
        json.add("fragen", questionLines);
        JsonObject coverage = new JsonObject();
        for (Map.Entry<String, Integer> state : states.entrySet()) {
            coverage.addProperty(state.getKey(), state.getValue());
        }
        json.add("fragenAbdeckung", coverage);
        Map<String, Integer> sectionCounts = new LinkedHashMap<String, Integer>();
        for (WrittenSection section : result.getSections()) {
            for (Map.Entry<String, Integer> count : section.getCounts().entrySet()) {
                sectionCounts.merge(count.getKey(), count.getValue(), Integer::sum);
            }
        }
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> count : sectionCounts.entrySet()) {
            counts.addProperty(count.getKey(), count.getValue());
        }
        json.add("abschnittsZaehler", counts);
        if (!classes.isEmpty()) {
            json.add("klassen", classes(result));
        }
        json.add("bericht", result.getReport().toJson());
        String pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json);
        Files.write(new File(out, "ergebnis.json").toPath(), pretty.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "buch.md").toPath(), result.book().getBytes(StandardCharsets.UTF_8));
        StringBuilder report = new StringBuilder(result.getReport().text(ZoneId.of("Europe/Berlin")));
        report.append("\n\nFragen des Auftrags\n");
        for (ConceptRun.QuestionLine line : result.getQuestions()) {
            report.append("- ").append(line.getQuestion().getId()).append(' ').append(line.getState()).append(": ")
                    .append(line.getQuestion().getText())
                    .append(line.getSection().isEmpty() ? "" : " → " + line.getSection())
                    .append(line.getReason().isEmpty() ? "" : " (" + line.getReason() + ")").append('\n');
        }
        Files.write(new File(out, "bericht.txt").toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        return json;
    }

    /** Per expected class: passages, judged BELEGT for any question, judged HINWEIS, cited in the book. */
    JsonObject classes(ConceptRun.Result result) {
        Set<String> direct = new LinkedHashSet<String>();
        Set<String> leads = new LinkedHashSet<String>();
        for (EvidenceVerdict verdict : result.getVerdicts()) {
            if (verdict.getKind() == EvidenceVerdict.Kind.BELEGT) {
                direct.add(verdict.getPassageId());
            } else if (verdict.getKind() == EvidenceVerdict.Kind.HINWEIS) {
                leads.add(verdict.getPassageId());
            }
        }
        Set<String> cited = new LinkedHashSet<String>();
        for (WrittenSection section : result.getSections()) {
            for (WrittenSection.Citation citation : section.getCitations().values()) {
                cited.add(citation.getPassageId());
            }
        }
        Map<String, int[]> byClass = new LinkedHashMap<String, int[]>();
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            int[] c = byClass.computeIfAbsent(entry.getValue(), k -> new int[4]);
            c[0]++;
            c[1] += direct.contains(entry.getKey()) ? 1 : 0;
            c[2] += leads.contains(entry.getKey()) && !direct.contains(entry.getKey()) ? 1 : 0;
            c[3] += cited.contains(entry.getKey()) ? 1 : 0;
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, int[]> entry : byClass.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("passagen", entry.getValue()[0]);
            c.addProperty("belegt", entry.getValue()[1]);
            c.addProperty("nurHinweis", entry.getValue()[2]);
            c.addProperty("zitiert", entry.getValue()[3]);
            out.add(entry.getKey(), c);
        }
        return out;
    }

    static Reader resource(String name) {
        InputStream in = ConceptStock.class.getResourceAsStream("/concept-run/" + name);
        if (in == null) {
            throw new IllegalStateException("missing test resource concept-run/" + name);
        }
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    private static String host(String url) {
        String rest = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json == null ? null : json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static List<String> list(JsonObject json, String field) {
        List<String> out = new ArrayList<String>();
        JsonElement value = json == null ? null : json.get(field);
        if (value != null && value.isJsonArray()) {
            for (JsonElement entry : value.getAsJsonArray()) {
                out.add(entry.getAsString());
            }
        }
        return out;
    }
}

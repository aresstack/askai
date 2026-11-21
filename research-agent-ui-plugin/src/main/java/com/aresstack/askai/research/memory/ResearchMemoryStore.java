package com.aresstack.askai.research.memory;

import com.aresstack.askai.research.transition.AtomicFiles;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component 7: the research memory on disk, {@code research-memory/log.jsonl} in the project directory (next to the
 * book manifest that holds the concept pin, and to {@code search-plan/}). One JSON object per line, UTF-8, with the
 * field names of the shared data model. The file is only ever appended to: every record is one line, written and
 * flushed to disk before the memory takes it in. Opening replays the lines in order, so the latest record of an id
 * (or of a question and a passage) is the one that holds.
 * <p>
 * A line that cannot be read fails closed ({@link CorruptProjectFileException}); nothing is repaired or skipped.
 * The one exception is a last line without its line end: that is a write the process did not finish, it never
 * reached the memory, and it is cut off when the memory is opened.
 */
public final class ResearchMemoryStore {

    static final int VERSION = 1;

    private static final Gson GSON = new Gson();

    private final File logFile;

    public ResearchMemoryStore(File projectDirectory) {
        this.logFile = new File(new File(projectDirectory, "research-memory"), "log.jsonl");
    }

    public File logFile() {
        return logFile;
    }

    /** The memory as stored, writing through to the file from now on. Empty before the first record. */
    public synchronized ResearchMemory open() throws IOException, CorruptProjectFileException {
        ResearchMemory memory = new ResearchMemory(new ResearchMemory.Log() {
            @Override
            public void topic(ResearchTopic topic) throws IOException {
                append(toJson(topic));
            }

            @Override
            public void question(ResearchQuestion question) throws IOException {
                append(toJson(question));
            }

            @Override
            public void verdict(EvidenceVerdict verdict) throws IOException {
                append(toJson(verdict));
            }

            @Override
            public void search(SearchRecord search) throws IOException {
                append(toJson(search));
            }

            @Override
            public void decision(LeadDecision decision) throws IOException {
                append(toJson(decision));
            }
        });
        if (!logFile.isFile()) {
            return memory;
        }
        String content = new String(Files.readAllBytes(logFile.toPath()), AtomicFiles.UTF8);
        int end = content.lastIndexOf('\n') + 1;
        if (end < content.length()) {
            System.err.println("[research-memory] dropping an unfinished last record of " + logFile);
            AtomicFiles.write(logFile, content.substring(0, end));
        }
        int lineNumber = 0;
        for (String line : content.substring(0, end).split("\n")) {
            lineNumber++;
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                replay(memory, JsonParser.parseString(line).getAsJsonObject());
            } catch (RuntimeException unreadable) {
                throw new CorruptProjectFileException(logFile, "The research memory cannot be read (line "
                        + lineNumber + "): " + unreadable.getMessage());
            }
        }
        return memory;
    }

    private synchronized void append(JsonObject record) throws IOException {
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(record) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    // ------------------------------------------------------------------------------------------- reading

    private static void replay(ResearchMemory memory, JsonObject record) {
        int version = record.get("v").getAsInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("unknown version " + version);
        }
        String kind = text(record, "art");
        if ("thema".equals(kind)) {
            memory.restore(topicOf(record));
        } else if ("frage".equals(kind)) {
            memory.restore(new ResearchQuestion(text(record, "frageId"), text(record, "text"),
                    text(record, "themaId"), ResearchQuestion.Origin.valueOf(text(record, "herkunft")),
                    list(record, "zusammengefuehrtAus"), ResearchQuestion.State.valueOf(text(record, "zustand")),
                    record.get("zeit").getAsLong()));
        } else if ("belegurteil".equals(kind)) {
            memory.restore(new EvidenceVerdict(text(record, "frageId"), text(record, "passageId"),
                    text(record, "quelleId"), text(record, "quelleUrl"), text(record, "textSchluessel"),
                    EvidenceVerdict.Kind.valueOf(text(record, "urteilModell")),
                    EvidenceVerdict.Kind.valueOf(text(record, "urteil")), text(record, "zitat"),
                    text(record, "grund"), text(record, "modell"), record.get("runde").getAsInt(),
                    record.get("zeit").getAsLong()));
        } else if ("suche".equals(kind)) {
            memory.restore(searchOf(record));
        } else if ("entscheidung".equals(kind)) {
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
            for (Map.Entry<String, JsonElement> count : record.getAsJsonObject("zaehlwerte").entrySet()) {
                counts.put(count.getKey(), count.getValue().getAsInt());
            }
            memory.restore(new LeadDecision(record.get("runde").getAsInt(),
                    LeadDecision.Target.valueOf(text(record, "ziel")), text(record, "zielId"),
                    LeadDecision.Action.valueOf(text(record, "handlung")), text(record, "grund"), counts,
                    text(record, "modell"), record.get("zeit").getAsLong()));
        } else {
            throw new IllegalArgumentException("unknown record kind " + kind);
        }
    }

    private static ResearchTopic topicOf(JsonObject record) {
        List<ResearchTopic.Finding> findings = new ArrayList<ResearchTopic.Finding>();
        if (record.has("fundstellen")) {
            for (JsonElement element : record.getAsJsonArray("fundstellen")) {
                JsonObject finding = element.getAsJsonObject();
                findings.add(new ResearchTopic.Finding(text(finding, "quelleId"), text(finding, "passageId"),
                        text(finding, "zitat")));
            }
        }
        return new ResearchTopic(text(record, "themaId"), text(record, "name"), text(record, "beschreibung"),
                ResearchTopic.Origin.valueOf(text(record, "herkunft")), text(record, "karte"),
                list(record, "zusammengefuehrtAus"), findings, ResearchTopic.Fence.valueOf(text(record, "zaun")),
                text(record, "zaunGrund"), record.get("gewicht").getAsDouble(), text(record, "modell"),
                record.get("zeit").getAsLong());
    }

    private static SearchRecord searchOf(JsonObject record) {
        SearchRecord.Yield yield = null;
        if (record.has("ertrag") && !record.get("ertrag").isJsonNull()) {
            JsonObject result = record.getAsJsonObject("ertrag");
            yield = new SearchRecord.Yield(result.get("quellen").getAsInt(), result.get("passagen").getAsInt(),
                    result.get("neueBelege").getAsInt(), result.get("neueThemen").getAsInt());
        }
        return new SearchRecord(text(record, "sucheId"), text(record, "anfrage"), list(record, "frageIds"),
                list(record, "themaIds"), text(record, "strategie"), text(record, "herkunft"),
                record.get("runde").getAsInt(), yield, record.get("zeit").getAsLong());
    }

    private static String text(JsonObject record, String field) {
        return record.has(field) && !record.get(field).isJsonNull() ? record.get(field).getAsString() : "";
    }

    private static List<String> list(JsonObject record, String field) {
        List<String> values = new ArrayList<String>();
        if (record.has(field) && record.get(field).isJsonArray()) {
            for (JsonElement element : record.getAsJsonArray(field)) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    // ------------------------------------------------------------------------------------------- writing

    private static JsonObject head(String kind) {
        JsonObject record = new JsonObject();
        record.addProperty("v", VERSION);
        record.addProperty("art", kind);
        return record;
    }

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    static JsonObject toJson(ResearchTopic topic) {
        JsonObject record = head("thema");
        record.addProperty("themaId", topic.getId());
        record.addProperty("name", topic.getName());
        record.addProperty("beschreibung", topic.getDescription());
        record.addProperty("herkunft", topic.getOrigin().name());
        record.addProperty("karte", topic.getCardAnchorId());
        record.add("zusammengefuehrtAus", array(topic.getMergedFrom()));
        JsonArray findings = new JsonArray();
        for (ResearchTopic.Finding finding : topic.getFindings()) {
            JsonObject place = new JsonObject();
            place.addProperty("quelleId", finding.getSourceId());
            place.addProperty("passageId", finding.getPassageId());
            place.addProperty("zitat", finding.getQuote());
            findings.add(place);
        }
        record.add("fundstellen", findings);
        record.addProperty("zaun", topic.getFence().name());
        record.addProperty("zaunGrund", topic.getFenceReason());
        record.addProperty("gewicht", topic.getWeight());
        record.addProperty("modell", topic.getModel());
        record.addProperty("zeit", topic.getTimeMillis());
        return record;
    }

    static JsonObject toJson(ResearchQuestion question) {
        JsonObject record = head("frage");
        record.addProperty("frageId", question.getId());
        record.addProperty("text", question.getText());
        record.addProperty("themaId", question.getTopicId());
        record.addProperty("herkunft", question.getOrigin().name());
        record.add("zusammengefuehrtAus", array(question.getMergedFrom()));
        record.addProperty("zustand", question.getState().name());
        record.addProperty("zeit", question.getTimeMillis());
        return record;
    }

    static JsonObject toJson(EvidenceVerdict verdict) {
        JsonObject record = head("belegurteil");
        record.addProperty("frageId", verdict.getQuestionId());
        record.addProperty("passageId", verdict.getPassageId());
        record.addProperty("quelleId", verdict.getSourceId());
        record.addProperty("quelleUrl", verdict.getSourceUrl());
        record.addProperty("textSchluessel", verdict.getTextKey());
        record.addProperty("urteilModell", verdict.getJudgedKind().name());
        record.addProperty("urteil", verdict.getKind().name());
        record.addProperty("zitat", verdict.getQuote());
        record.addProperty("grund", verdict.getReason());
        record.addProperty("modell", verdict.getModel());
        record.addProperty("runde", verdict.getRound());
        record.addProperty("zeit", verdict.getTimeMillis());
        return record;
    }

    static JsonObject toJson(SearchRecord search) {
        JsonObject record = head("suche");
        record.addProperty("sucheId", search.getId());
        record.addProperty("anfrage", search.getQuery());
        record.add("frageIds", array(search.getQuestionIds()));
        record.add("themaIds", array(search.getTopicIds()));
        record.addProperty("strategie", search.getStrategy());
        record.addProperty("herkunft", search.getOrigin());
        record.addProperty("runde", search.getRound());
        SearchRecord.Yield yield = search.getYield();
        if (yield != null) {
            JsonObject result = new JsonObject();
            result.addProperty("quellen", yield.getSources());
            result.addProperty("passagen", yield.getPassages());
            result.addProperty("neueBelege", yield.getNewEvidence());
            result.addProperty("neueThemen", yield.getNewTopics());
            record.add("ertrag", result);
        }
        record.addProperty("zeit", search.getTimeMillis());
        return record;
    }

    static JsonObject toJson(LeadDecision decision) {
        JsonObject record = head("entscheidung");
        record.addProperty("runde", decision.getRound());
        record.addProperty("ziel", decision.getTarget().name());
        record.addProperty("zielId", decision.getTargetId());
        record.addProperty("handlung", decision.getAction().name());
        record.addProperty("grund", decision.getReason());
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> count : decision.getCounts().entrySet()) {
            counts.addProperty(count.getKey(), count.getValue());
        }
        record.add("zaehlwerte", counts);
        record.addProperty("modell", decision.getModel());
        record.addProperty("zeit", decision.getTimeMillis());
        return record;
    }
}

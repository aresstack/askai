package com.aresstack.askai.research.store;

import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * File-backed manuscript (plan PR 26): per outline section id a list of immutable revisions and one state file.
 * <pre>
 *   sections/&lt;sectionId&gt;/0001.json      revision 1 (text, citation map, gaps, model label, ...), immutable
 *   sections/&lt;sectionId&gt;/section.json   status, reason, chapter id, current revision, hash of that revision
 * </pre>
 * The revision file is written first and the state swap last, so only the swap makes a revision current. Content
 * writes name the revision they were based on; a write based on an older revision is refused with the stored text
 * ({@link SectionConflictException}), so neither text is lost. Every file is UTF-8; a damaged file fails closed
 * ({@link CorruptManuscriptException}) and is never overwritten.
 * <p>
 * The store does not know phases: the write guard of D4 sits in front of it
 * ({@link com.aresstack.askai.research.manuscript.ManuscriptWorkspace}).
 */
public final class FileManuscriptStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A stored section is damaged; it is kept as it is. */
    public static final class CorruptManuscriptException extends IllegalStateException {
        CorruptManuscriptException(String message) {
            super(message);
        }
    }

    /** A content write based on an outdated revision; carries the stored text so both can be shown. */
    public static final class SectionConflictException extends IllegalStateException {
        private final int currentRevision;
        private final String currentText;

        SectionConflictException(String sectionId, int expected, int current, String currentText) {
            super("The section " + sectionId + " changed (revision " + current + ", your copy is revision "
                    + expected + "). Your text was kept; compare it with the saved text and save again.");
            this.currentRevision = current;
            this.currentText = currentText;
        }

        public int getCurrentRevision() {
            return currentRevision;
        }

        public String getCurrentText() {
            return currentText;
        }
    }

    /** The outcome of a content write. */
    public static final class Write {
        public final ManuscriptSection section;
        public final SectionRevision revision;

        Write(ManuscriptSection section, SectionRevision revision) {
            this.section = section;
            this.revision = revision;
        }
    }

    private final File dir;

    public FileManuscriptStore(File manuscriptDir) {
        this.dir = manuscriptDir;
    }

    public File directory() {
        return dir;
    }

    public static boolean isValidSectionId(String id) {
        return id != null && SAFE_ID.matcher(id).matches() && !id.startsWith(".");
    }

    /** Every stored section, ordered by id. */
    public synchronized List<ManuscriptSection> sections() {
        File[] dirs = sectionsDir().listFiles();
        List<ManuscriptSection> list = new ArrayList<ManuscriptSection>();
        if (dirs == null) {
            return list;
        }
        Arrays.sort(dirs);
        for (File section : dirs) {
            if (section.isDirectory() && isValidSectionId(section.getName())) {
                ManuscriptSection state = section(section.getName());
                if (state != null) {
                    list.add(state);
                }
            }
        }
        return list;
    }

    /** The stored state of a section, or {@code null} when it has none. */
    public synchronized ManuscriptSection section(String sectionId) {
        File file = stateFile(checkId(sectionId));
        if (!file.isFile()) {
            if (revisionFile(sectionId, 1).exists()) {
                throw corrupt(sectionId, "its state file is missing although revisions exist");
            }
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(StoreIo.readUtf8(file)).getAsJsonObject();
            ManuscriptSection state = new ManuscriptSection(sectionId, string(json, "chapterId"),
                    SectionStatus.valueOf(string(json, "status")), string(json, "reason"),
                    json.get("revision").getAsInt(), json.get("updatedAt").getAsLong());
            if (state.getRevision() > 0) {
                String content = readOrNull(revisionFile(sectionId, state.getRevision()));
                if (content == null || !StoreIo.sha256(content).equals(string(json, "revisionHash"))) {
                    throw corrupt(sectionId, "its revision " + state.getRevision() + " does not match its state");
                }
            }
            return state;
        } catch (CorruptManuscriptException corrupt) {
            throw corrupt;
        } catch (IOException | RuntimeException broken) {
            throw corrupt(sectionId, "its state cannot be read (" + broken.getMessage() + ")");
        }
    }

    /** Revision {@code number} of a section, or {@code null}. */
    public synchronized SectionRevision revision(String sectionId, int number) {
        String content = number <= 0 ? null : readOrNull(revisionFile(checkId(sectionId), number));
        if (content == null) {
            return null;
        }
        try {
            return fromJson(JsonParser.parseString(content).getAsJsonObject());
        } catch (RuntimeException broken) {
            throw corrupt(sectionId, "its revision " + number + " cannot be read (" + broken.getMessage() + ")");
        }
    }

    /** The current revision of a section, or {@code null} while it has no text. */
    public synchronized SectionRevision current(String sectionId) {
        ManuscriptSection state = section(sectionId);
        return state == null || !state.hasText() ? null : revision(sectionId, state.getRevision());
    }

    /** Every revision of a section, oldest first (the current one may be older than the newest after a mark). */
    public synchronized List<SectionRevision> revisions(String sectionId) {
        List<SectionRevision> list = new ArrayList<SectionRevision>();
        for (int number = 1; ; number++) {
            SectionRevision revision = revision(sectionId, number);
            if (revision == null) {
                return list;
            }
            list.add(revision);
        }
    }

    /**
     * Save a new revision based on {@code expectedRevision}; the section becomes written.
     * @throws SectionConflictException  when the section moved on meanwhile
     * @throws IllegalArgumentException  for an orphan section or an invalid id
     */
    public synchronized Write save(String sectionId, String chapterId, int expectedRevision,
                                   SectionRevision.Builder content, long nowMillis) {
        ManuscriptSection state = section(sectionId);
        int current = state == null ? 0 : state.getRevision();
        if (state != null && state.getStatus() == SectionStatus.ORPHAN) {
            throw new IllegalArgumentException("The section " + sectionId
                    + " was removed from the outline; its text is kept but not written any more.");
        }
        if (expectedRevision != current) {
            SectionRevision stored = current == 0 ? null : revision(sectionId, current);
            throw new SectionConflictException(sectionId, expectedRevision, current,
                    stored == null ? "" : stored.getText());
        }
        int number = Math.max(current, newestNumber(sectionId)) + 1;
        SectionRevision revision = content.build(number, nowMillis);
        String json = GSON.toJson(toJson(revision));
        String chapter = chapterId == null || chapterId.isEmpty()
                ? (state == null ? "" : state.getChapterId()) : chapterId;
        ManuscriptSection next = new ManuscriptSection(sectionId, chapter, SectionStatus.WRITTEN, "", number,
                nowMillis);
        try {
            StoreIo.atomicWrite(revisionFile(sectionId, number), json);
            writeState(next, StoreIo.sha256(json));
        } catch (IOException failed) {
            throw new IllegalStateException("Could not save the section: " + failed.getMessage(), failed);
        }
        return new Write(next, revision);
    }

    /** Restore revision {@code number} as a new revision based on {@code expectedRevision}. */
    public synchronized Write restore(String sectionId, int number, int expectedRevision, long nowMillis) {
        SectionRevision old = revision(sectionId, number);
        if (old == null) {
            throw new IllegalArgumentException("The section " + sectionId + " has no revision " + number + ".");
        }
        return save(sectionId, null, expectedRevision, old.toBuilder().author(SectionRevision.AUTHOR_USER)
                .restoredFromNumber(number), nowMillis);
    }

    /**
     * Set the status of a section (creating its state when absent) without an expected revision: the stale and
     * orphan marks of the transition hooks, failed, skipped and pending. An unchanged state writes nothing.
     * @return the state after the mark
     */
    public synchronized ManuscriptSection mark(String sectionId, String chapterId, SectionStatus status,
                                               String reason, long nowMillis) {
        ManuscriptSection state = section(sectionId);
        String chapter = chapterId == null ? (state == null ? "" : state.getChapterId()) : chapterId;
        String why = reason == null ? "" : reason.trim();
        if (state != null && state.getStatus() == status && state.getReason().equals(why)
                && state.getChapterId().equals(chapter)) {
            return state;
        }
        int revision = state == null ? 0 : state.getRevision();
        ManuscriptSection next = new ManuscriptSection(sectionId, chapter, status, why, revision, nowMillis);
        try {
            String hash = "";
            if (revision > 0) {
                hash = StoreIo.sha256(StoreIo.readUtf8(revisionFile(sectionId, revision)));
            }
            writeState(next, hash);
        } catch (IOException failed) {
            throw new IllegalStateException("Could not mark the section: " + failed.getMessage(), failed);
        }
        return next;
    }

    // ------------------------------------------------------------------ files

    private void writeState(ManuscriptSection state, String revisionHash) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("formatVersion", 1);
        json.addProperty("sectionId", state.getSectionId());
        json.addProperty("chapterId", state.getChapterId());
        json.addProperty("status", state.getStatus().name());
        json.addProperty("reason", state.getReason());
        json.addProperty("revision", state.getRevision());
        json.addProperty("revisionHash", revisionHash);
        json.addProperty("updatedAt", state.getUpdatedAt());
        StoreIo.atomicWrite(stateFile(state.getSectionId()), GSON.toJson(json));
    }

    private int newestNumber(String sectionId) {
        int number = 0;
        while (revisionFile(sectionId, number + 1).exists()) {
            number++;
        }
        return number;
    }

    private static JsonObject toJson(SectionRevision revision) {
        JsonObject json = new JsonObject();
        json.addProperty("number", revision.getNumber());
        json.addProperty("text", revision.getText());
        JsonArray citations = new JsonArray();
        for (ManuscriptCitation citation : revision.getCitations()) {
            JsonObject c = new JsonObject();
            c.addProperty("marker", citation.getMarker());
            c.addProperty("sourceId", citation.getSourceId());
            c.addProperty("sourceChecksum", citation.getSourceChecksum());
            c.addProperty("citedText", citation.getCitedText());
            c.addProperty("citedTextHash", citation.getCitedTextHash());
            c.addProperty("passageId", citation.getPassageId());
            c.add("flags", strings(citation.getFlags()));
            citations.add(c);
        }
        json.add("citations", citations);
        json.add("gaps", strings(revision.getGaps()));
        json.addProperty("author", revision.getAuthor());
        json.addProperty("modelLabel", revision.getModelLabel());
        json.addProperty("thinkingLevel", revision.getThinkingLevel());
        json.addProperty("promptHash", revision.getPromptHash());
        json.add("evidenceIds", strings(revision.getEvidenceIds()));
        json.addProperty("sectionFingerprint", revision.getSectionFingerprint());
        json.addProperty("chapterFingerprint", revision.getChapterFingerprint());
        json.addProperty("restoredFrom", revision.getRestoredFrom());
        json.addProperty("createdAt", revision.getCreatedAt());
        return json;
    }

    private static SectionRevision fromJson(JsonObject json) {
        List<ManuscriptCitation> citations = new ArrayList<ManuscriptCitation>();
        for (JsonElement element : json.getAsJsonArray("citations")) {
            JsonObject c = element.getAsJsonObject();
            ManuscriptCitation citation = new ManuscriptCitation(string(c, "marker"), string(c, "sourceId"),
                    string(c, "sourceChecksum"), string(c, "citedText"), string(c, "passageId"),
                    list(c.getAsJsonArray("flags")));
            if (!citation.getCitedTextHash().equals(string(c, "citedTextHash"))) {
                throw new IllegalStateException("the cited text of [" + citation.getMarker()
                        + "] does not match its hash");
            }
            citations.add(citation);
        }
        return SectionRevision.builder().text(string(json, "text")).citations(citations)
                .gaps(list(json.getAsJsonArray("gaps"))).author(string(json, "author"))
                .modelLabel(string(json, "modelLabel")).thinkingLevel(string(json, "thinkingLevel"))
                .promptHash(string(json, "promptHash")).evidenceIds(list(json.getAsJsonArray("evidenceIds")))
                .sectionFingerprint(string(json, "sectionFingerprint"))
                .chapterFingerprint(string(json, "chapterFingerprint"))
                .restoredFromNumber(json.has("restoredFrom") ? json.get("restoredFrom").getAsInt() : 0)
                .build(json.get("number").getAsInt(), json.get("createdAt").getAsLong());
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> list(JsonArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private String checkId(String sectionId) {
        if (!isValidSectionId(sectionId)) {
            throw new IllegalArgumentException("Invalid section id: " + sectionId);
        }
        return sectionId;
    }

    private CorruptManuscriptException corrupt(String sectionId, String what) {
        return new CorruptManuscriptException("The manuscript section " + sectionId + " in " + dir.getPath()
                + " is damaged: " + what + ". It was kept as it is; repair or remove it before you continue.");
    }

    private static String readOrNull(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return StoreIo.readUtf8(file);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private File sectionsDir() {
        return new File(dir, "sections");
    }

    private File stateFile(String sectionId) {
        return new File(new File(sectionsDir(), sectionId), "section.json");
    }

    private File revisionFile(String sectionId, int number) {
        return new File(new File(sectionsDir(), sectionId), String.format("%04d", number) + ".json");
    }
}

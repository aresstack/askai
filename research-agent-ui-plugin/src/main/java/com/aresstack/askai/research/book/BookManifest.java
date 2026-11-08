package com.aresstack.askai.research.book;

import com.aresstack.askai.research.transition.AtomicFiles;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The book's approval records (D4): {@code book.json} in the project directory, one pin per approval and
 * notes such as "Concept reopened". Pins and notes are only appended, never rewritten; every write goes
 * through {@link AtomicFiles#write} (UTF-8, flushed before the rename). A manifest that cannot be read
 * fails closed: it is kept as it is, every read reports it and no write overwrites it.
 * <p>
 * The valid pin of a phase is its newest pin whose transition the memento committed (D3), so a pin
 * written by a transition that crashed before its commit is never read.
 */
public final class BookManifest {

    public static final String FILE_NAME = "book.json";
    public static final int SCHEMA = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Answers whether the memento committed a transition id. */
    public interface CommitCheck {
        boolean isCommitted(String transitionId);
    }

    private final File file;

    public BookManifest(File projectDirectory) {
        this.file = new File(projectDirectory, FILE_NAME);
    }

    public File file() {
        return file;
    }

    /** @return {@code null} while the manifest is readable, otherwise why it is not. */
    public synchronized String corruptReason() {
        try {
            read();
            return null;
        } catch (CorruptProjectFileException corrupt) {
            return corrupt.getMessage();
        }
    }

    /** Append one concept pin; refused when the manifest is damaged. */
    public synchronized void appendConceptPin(ConceptPin pin) throws IOException {
        JsonObject root = writableRoot();
        root.getAsJsonArray("pins").add(pin.toJson());
        AtomicFiles.write(file, GSON.toJson(root));
    }

    /** Append one outline pin; refused when the manifest is damaged. */
    public synchronized void appendOutlinePin(OutlinePin pin) throws IOException {
        JsonObject root = writableRoot();
        root.getAsJsonArray("pins").add(pin.toJson());
        AtomicFiles.write(file, GSON.toJson(root));
    }

    /** Every outline pin in the order written. */
    public synchronized List<OutlinePin> outlinePins() throws CorruptProjectFileException {
        List<OutlinePin> pins = new ArrayList<OutlinePin>();
        for (JsonElement element : read().getAsJsonArray("pins")) {
            JsonObject pin = element.getAsJsonObject();
            if (OutlinePin.PHASE.equals(string(pin, "phase"))) {
                pins.add(OutlinePin.fromJson(pin));
            }
        }
        return pins;
    }

    /** The newest outline pin whose transition is committed, or {@code null}. */
    public synchronized OutlinePin validOutlinePin(CommitCheck commits) throws CorruptProjectFileException {
        List<OutlinePin> pins = outlinePins();
        for (int index = pins.size() - 1; index >= 0; index--) {
            if (commits.isCommitted(pins.get(index).getTransitionId())) {
                return pins.get(index);
            }
        }
        return null;
    }

    /** The outline pin a transition wrote, committed or not, or {@code null}. */
    public synchronized OutlinePin outlinePinOf(String transitionId) throws CorruptProjectFileException {
        for (OutlinePin pin : outlinePins()) {
            if (pin.getTransitionId().equals(transitionId)) {
                return pin;
            }
        }
        return null;
    }

    /**
     * The committed concept pin that was valid before the one {@code transitionId} wrote, or {@code null} when
     * that transition wrote the first one (or none).
     */
    public synchronized ConceptPin conceptPinBefore(String transitionId, CommitCheck commits)
            throws CorruptProjectFileException {
        List<ConceptPin> pins = conceptPins();
        int index = -1;
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).getTransitionId().equals(transitionId)) {
                index = i;
            }
        }
        for (int i = index - 1; i >= 0; i--) {
            if (commits.isCommitted(pins.get(i).getTransitionId())) {
                return pins.get(i);
            }
        }
        return null;
    }

    /** Append a note to the later pins (e.g. "Concept reopened"); refused when the manifest is damaged. */
    public synchronized void appendNote(String transitionId, String phase, String text, long timeMillis)
            throws IOException {
        JsonObject root = writableRoot();
        JsonObject note = new JsonObject();
        note.addProperty("transitionId", transitionId);
        note.addProperty("phase", phase);
        note.addProperty("text", text);
        note.addProperty("time", timeMillis);
        root.getAsJsonArray("notes").add(note);
        AtomicFiles.write(file, GSON.toJson(root));
    }

    /** Every concept pin in the order written. */
    public synchronized List<ConceptPin> conceptPins() throws CorruptProjectFileException {
        List<ConceptPin> pins = new ArrayList<ConceptPin>();
        for (JsonElement element : read().getAsJsonArray("pins")) {
            JsonObject pin = element.getAsJsonObject();
            if (ConceptPin.PHASE.equals(string(pin, "phase"))) {
                pins.add(ConceptPin.fromJson(pin));
            }
        }
        return pins;
    }

    /** The newest concept pin whose transition is committed, or {@code null}. */
    public synchronized ConceptPin validConceptPin(CommitCheck commits) throws CorruptProjectFileException {
        List<ConceptPin> pins = conceptPins();
        for (int index = pins.size() - 1; index >= 0; index--) {
            if (commits.isCommitted(pins.get(index).getTransitionId())) {
                return pins.get(index);
            }
        }
        return null;
    }

    /** The concept pin a transition wrote, committed or not, or {@code null}. */
    public synchronized ConceptPin conceptPinOf(String transitionId) throws CorruptProjectFileException {
        for (ConceptPin pin : conceptPins()) {
            if (pin.getTransitionId().equals(transitionId)) {
                return pin;
            }
        }
        return null;
    }

    /** The note texts in the order written. */
    public synchronized List<String> notes() throws CorruptProjectFileException {
        List<String> texts = new ArrayList<String>();
        for (JsonElement element : read().getAsJsonArray("notes")) {
            texts.add(string(element.getAsJsonObject(), "text"));
        }
        return Collections.unmodifiableList(texts);
    }

    private JsonObject writableRoot() throws IOException {
        try {
            return read();
        } catch (CorruptProjectFileException corrupt) {
            throw new IOException(corrupt.getMessage());
        }
    }

    private JsonObject read() throws CorruptProjectFileException {
        if (!file.exists()) {
            JsonObject fresh = new JsonObject();
            fresh.addProperty("schema", SCHEMA);
            fresh.add("pins", new JsonArray());
            fresh.add("notes", new JsonArray());
            return fresh;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), AtomicFiles.UTF8);
        } catch (IOException unreadable) {
            throw corrupt("cannot be read (" + unreadable.getMessage() + ")");
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA
                    || !root.has("pins") || !root.get("pins").isJsonArray()
                    || !root.has("notes") || !root.get("notes").isJsonArray()) {
                throw corrupt("has an unknown layout");
            }
            for (JsonElement pin : root.getAsJsonArray("pins")) {
                if (ConceptPin.PHASE.equals(string(pin.getAsJsonObject(), "phase"))) {
                    ConceptPin.fromJson(pin.getAsJsonObject()); // validates the fields
                } else if (OutlinePin.PHASE.equals(string(pin.getAsJsonObject(), "phase"))) {
                    OutlinePin.fromJson(pin.getAsJsonObject());
                }
            }
            return root;
        } catch (CorruptProjectFileException corrupt) {
            throw corrupt;
        } catch (RuntimeException broken) {
            throw corrupt("is damaged (" + broken.getMessage() + ")");
        }
    }

    private CorruptProjectFileException corrupt(String what) {
        return new CorruptProjectFileException(file, "The book manifest " + file.getName() + " " + what
                + ". It was kept as it is; repair or remove it before approving again.");
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}

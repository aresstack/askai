package com.aresstack.askai.research.book;

import com.aresstack.askai.research.store.FileConceptStore;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The only way later phases see Phase 1 (D4): the concept approved at {@code SUBMIT_SCOPE} minus the
 * blacklist recorded with it, and mission, deliverable and working language from the Phase-1 record —
 * never from the live scope draft or the toolbar language.
 */
public final class FrozenConcept {

    private final ConceptPin pin;
    private final String approvedDocument;
    private final String effectiveDocument;

    private FrozenConcept(ConceptPin pin, String approvedDocument, String effectiveDocument) {
        this.pin = pin;
        this.approvedDocument = approvedDocument;
        this.effectiveDocument = effectiveDocument;
    }

    /**
     * Read the valid Phase-1 record.
     * @return {@code null} when no committed concept pin exists yet
     * @throws CorruptProjectFileException when the manifest is damaged (fail closed)
     */
    public static FrozenConcept read(BookManifest manifest, FileConceptStore store,
                                     BookManifest.CommitCheck commits) throws CorruptProjectFileException {
        ConceptPin pin = manifest.validConceptPin(commits);
        if (pin == null || store == null) {
            return null;
        }
        String approved = store.approvedContent(pin.getApprovedRevision());
        if (approved == null || !FileConceptStore.sha256(approved).equals(pin.getContentHash())) {
            throw new CorruptProjectFileException(manifest.file(), "The approved concept revision "
                    + pin.getApprovedRevision() + " does not match the book manifest.");
        }
        return of(pin, approved);
    }

    /** The frozen concept of a verified pin and its approved document. */
    static FrozenConcept of(ConceptPin pin, String approved) {
        return new FrozenConcept(pin, approved, withoutTerms(approved, pin.getBlacklist()));
    }

    public ConceptPin getPin() {
        return pin;
    }

    public int getRevision() {
        return pin.getApprovedRevision();
    }

    /** The approved concept as it was frozen. */
    public String getApprovedDocument() {
        return approvedDocument;
    }

    /** The approved concept minus the recorded blacklist. */
    public String getEffectiveDocument() {
        return effectiveDocument;
    }

    public String getMission() {
        return pin.getMission();
    }

    public String getDeliverable() {
        return pin.getDeliverable();
    }

    public String getLanguage() {
        return pin.getLanguage();
    }

    /** Every card name of the frozen concept (all levels). */
    public Set<String> cardNames() {
        Set<String> names = new HashSet<String>();
        collectNames(parseConcept(approvedDocument), names);
        return names;
    }

    /** The first-level card names of a concept document (empty for an unreadable one). */
    public static List<String> firstLevelCards(String document) {
        List<String> names = new ArrayList<String>();
        for (JsonElement element : parseConcept(document)) {
            if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonArray() && !entry.getKey().trim().isEmpty()) {
                        names.add(entry.getKey());
                    }
                }
            }
        }
        return names;
    }

    /** Every card name of a concept document, all levels, as typed. */
    public static Set<String> allCardNames(String document) {
        Set<String> names = new HashSet<String>();
        collectNames(parseConcept(document), names);
        return names;
    }

    static String withoutTerms(String document, List<String> terms) {
        if (terms.isEmpty()) {
            return document;
        }
        Set<String> lowered = new HashSet<String>();
        for (String term : terms) {
            lowered.add(term.trim().toLowerCase(Locale.ROOT));
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(document).getAsJsonObject();
        } catch (RuntimeException unreadable) {
            return document;
        }
        JsonElement concept = root.get("concept");
        if (concept != null && concept.isJsonArray()) {
            prune(concept.getAsJsonArray(), lowered);
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    private static void prune(JsonArray level, Set<String> lowered) {
        Iterator<JsonElement> elements = level.iterator();
        while (elements.hasNext()) {
            JsonElement element = elements.next();
            if (element.isJsonPrimitive()
                    && lowered.contains(element.getAsString().trim().toLowerCase(Locale.ROOT))) {
                elements.remove();
            } else if (element.isJsonObject()) {
                JsonObject cards = element.getAsJsonObject();
                for (String key : new ArrayList<String>(cards.keySet())) {
                    if (lowered.contains(key.trim().toLowerCase(Locale.ROOT))) {
                        cards.remove(key);
                    } else if (cards.get(key).isJsonArray()) {
                        prune(cards.get(key).getAsJsonArray(), lowered);
                    }
                }
            }
        }
    }

    private static void collectNames(JsonArray level, Set<String> names) {
        for (JsonElement element : level) {
            if (element.isJsonPrimitive()) {
                names.add(element.getAsString());
            } else if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    names.add(entry.getKey());
                    if (entry.getValue().isJsonArray()) {
                        collectNames(entry.getValue().getAsJsonArray(), names);
                    }
                }
            }
        }
    }

    private static JsonArray parseConcept(String document) {
        try {
            JsonElement concept = JsonParser.parseString(document == null ? "" : document)
                    .getAsJsonObject().get("concept");
            return concept != null && concept.isJsonArray() ? concept.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException unreadable) {
            return new JsonArray();
        }
    }


    /**
     * The read-only concept summary every phase after Concept gets (plan PR 16): mission, deliverable, the
     * approved cards of the effective concept and the blacklist recorded with it. Rendered from the Phase-1
     * record only, never from the live scope draft.
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Approved concept revision ").append(getRevision()).append('\n');
        if (!getMission().trim().isEmpty()) {
            sb.append("Mission: ").append(getMission().trim()).append('\n');
        }
        if (!getDeliverable().trim().isEmpty()) {
            sb.append("Deliverable: ").append(getDeliverable().trim()).append('\n');
        }
        sb.append("Cards:\n");
        int before = sb.length();
        appendCards(parseConcept(effectiveDocument), "- ", sb);
        if (sb.length() == before) {
            sb.append("(none)\n");
        }
        List<String> blacklist = pin.getBlacklist();
        sb.append("Recorded blacklist: ");
        if (blacklist.isEmpty()) {
            sb.append("(none)");
        } else {
            for (int i = 0; i < blacklist.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(blacklist.get(i).trim());
            }
        }
        return sb.append('\n').toString();
    }

    private static void appendCards(JsonArray level, String indent, StringBuilder sb) {
        for (JsonElement element : level) {
            if (element.isJsonPrimitive()) {
                String name = element.getAsString().trim();
                if (!name.isEmpty()) {
                    sb.append(indent).append(name).append('\n');
                }
            } else if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    String name = entry.getKey().trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    sb.append(indent).append(name).append('\n');
                    if (entry.getValue().isJsonArray()) {
                        appendCards(entry.getValue().getAsJsonArray(), "  " + indent, sb);
                    }
                }
            }
        }
    }
}

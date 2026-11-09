package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.concept.ConceptIdentity;
import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.store.FileConceptStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The concept nodes of the search plan (plan PR 14, D5): every card of the effective frozen concept (the approved
 * revision minus the recorded blacklist), in concept order, with its stable id and its deterministic base query.
 * <ul>
 * <li>The id of a card comes from the concept's ID sidecar of the working revision the Phase-1 record names, so a
 * renamed card keeps its id. A plain string item has no sidecar id; it gets one from its parent id and its label.
 * Without a readable sidecar every node gets a label-path id.</li>
 * <li>The base query is the node label, the parent label and up to three mission keywords, at most
 * {@value ConceptQueryPlan#MAX_QUERY_LENGTH} characters, with every blacklist term removed.</li>
 * </ul>
 */
public final class ConceptPlanNodes {

    /** How many mission keywords a base query takes at most. */
    static final int MISSION_KEYWORDS = 3;

    private static final Set<String> STOPWORDS = new HashSet<String>(Arrays.asList(
            // English
            "about", "after", "also", "and", "are", "because", "been", "being", "between", "book", "both", "can",
            "does", "each", "for", "from", "have", "how", "into", "its", "more", "most", "other", "over", "should",
            "such", "than", "that", "their", "them", "then", "there", "these", "they", "this", "those", "through",
            "under", "using", "what", "when", "where", "which", "while", "who", "why", "will", "with", "within",
            "would", "your", "readers", "reader", "explain", "explains", "show", "shows", "describe", "describes",
            // German
            "aber", "alle", "allem", "allen", "aller", "alles", "als", "also", "auch", "auf", "aus", "bei", "beim",
            "bis", "buch", "dabei", "damit", "dann", "dass", "dem", "den", "denen", "der", "deren", "des", "die",
            "dies", "diese", "diesem", "diesen", "dieser", "dieses", "doch", "durch", "eine", "einem", "einen",
            "einer", "eines", "für", "gegen", "hat", "haben", "ihre", "ihren", "ist", "jede", "jeder", "kann",
            "können", "leser", "mehr", "mit", "nach", "nicht", "noch", "nur", "oder", "ohne", "sich", "sie", "sind",
            "soll", "sollen", "sowie", "über", "um", "und", "unter", "vom", "von", "vor", "was", "welche", "wenn",
            "werden", "wie", "wird", "zum", "zur", "zwischen", "erklärt", "erklären", "zeigt", "zeigen"));

    private ConceptPlanNodes() {
    }

    /**
     * The plan nodes of a frozen concept.
     *
     * @param approvedDocument  the approved concept document (the sidecar's shape)
     * @param effectiveDocument the approved document minus the recorded blacklist
     * @param identityJson      the ID sidecar of the recorded working revision, or {@code null}
     * @param mission           the mission of the Phase-1 record
     * @param blacklist         the blacklist terms to keep out of every query
     */
    public static List<ConceptQueryPlan.Node> of(String approvedDocument, String effectiveDocument,
                                                 String identityJson, String mission, List<String> blacklist) {
        JsonElement approvedRoot = parse(approvedDocument);
        ConceptIdentity identity = null;
        if (identityJson != null && approvedRoot != null) {
            try {
                ConceptIdentity parsed = ConceptIdentity.parse(identityJson);
                identity = parsed.matchesShape(approvedRoot) ? parsed : null;
            } catch (RuntimeException unreadable) {
                identity = null;
            }
        }
        List<String> keywords = missionKeywords(mission, blacklist);
        List<ConceptQueryPlan.Node> nodes = new ArrayList<ConceptQueryPlan.Node>();
        JsonElement effectiveRoot = parse(effectiveDocument);
        if (effectiveRoot == null || !effectiveRoot.isJsonObject()) {
            return nodes;
        }
        JsonElement concept = effectiveRoot.getAsJsonObject().get("concept");
        if (concept != null && concept.isJsonArray()) {
            walk(concept.getAsJsonArray(), new ArrayList<String>(), "", approvedRoot, identity, keywords,
                    blacklist == null ? new ArrayList<String>() : blacklist, nodes);
        }
        return nodes;
    }

    private static void walk(JsonArray level, List<String> path, String parentId, JsonElement approvedRoot,
                             ConceptIdentity identity, List<String> keywords, List<String> blacklist,
                             List<ConceptQueryPlan.Node> out) {
        String parentLabel = path.isEmpty() ? "" : path.get(path.size() - 1);
        for (JsonElement element : level) {
            if (element.isJsonPrimitive()) {
                String label = element.getAsString().trim();
                if (label.isEmpty() || blacklisted(label, blacklist)) {
                    continue;
                }
                List<String> nodePath = append(path, label);
                String id = "item:" + FileConceptStore.sha256(parentId + "/" + label.toLowerCase(Locale.ROOT))
                        .substring(0, 16);
                out.add(new ConceptQueryPlan.Node(id, label, nodePath, parentId,
                        baseQuery(label, parentLabel, keywords, blacklist)));
            } else if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    String label = entry.getKey().trim();
                    if (label.isEmpty() || !entry.getValue().isJsonArray() || blacklisted(label, blacklist)) {
                        continue;
                    }
                    List<String> nodePath = append(path, entry.getKey());
                    String id = identity == null ? null : identity.idAtPath(approvedRoot, nodePath);
                    if (id == null) {
                        id = "path:" + FileConceptStore.sha256(joinLower(nodePath)).substring(0, 16);
                    }
                    out.add(new ConceptQueryPlan.Node(id, label, trimmed(nodePath), parentId,
                            baseQuery(label, parentLabel, keywords, blacklist)));
                    walk(entry.getValue().getAsJsonArray(), nodePath, id, approvedRoot, identity, keywords,
                            blacklist, out);
                }
            }
        }
    }

    /** A card named exactly like a blacklist term is left out with its subtree (the current blacklist, D5). */
    private static boolean blacklisted(String label, List<String> blacklist) {
        for (String term : blacklist) {
            if (term != null && term.trim().equalsIgnoreCase(label.trim())) {
                return true;
            }
        }
        return false;
    }

    /** The deterministic base query of a node; empty when nothing usable is left. */
    public static String baseQuery(String label, String parentLabel, List<String> missionKeywords,
                                   List<String> blacklist) {
        LinkedHashSet<String> words = new LinkedHashSet<String>();
        Set<String> seen = new HashSet<String>();
        addWords(label, words, seen);
        addWords(parentLabel, words, seen);
        int added = 0;
        for (String keyword : missionKeywords) {
            if (added >= MISSION_KEYWORDS) {
                break;
            }
            if (seen.add(keyword.toLowerCase(Locale.ROOT))) {
                words.add(keyword);
                added++;
            }
        }
        String query = removeTerms(join(words), blacklist);
        if (query.length() > ConceptQueryPlan.MAX_QUERY_LENGTH) {
            int cut = query.lastIndexOf(' ', ConceptQueryPlan.MAX_QUERY_LENGTH);
            query = query.substring(0, cut > 0 ? cut : ConceptQueryPlan.MAX_QUERY_LENGTH).trim();
        }
        return query.length() < ConceptQueryPlan.MIN_QUERY_LENGTH ? "" : query;
    }

    /** Up to the first content words of the mission, in order, without stopwords and blacklist terms. */
    static List<String> missionKeywords(String mission, List<String> blacklist) {
        List<String> keywords = new ArrayList<String>();
        if (mission == null) {
            return keywords;
        }
        Set<String> seen = new HashSet<String>();
        String cleaned = removeTerms(mission, blacklist);
        for (String raw : cleaned.split("[^\\p{L}\\p{N}\\-]+")) {
            String word = raw.replaceAll("^-+|-+$", "");
            String lower = word.toLowerCase(Locale.ROOT);
            if (word.length() < 4 || STOPWORDS.contains(lower) || !seen.add(lower)) {
                continue;
            }
            keywords.add(word);
        }
        return keywords;
    }

    /** Remove every blacklist term (whole words, case-insensitive) and squeeze the spaces. */
    static String removeTerms(String text, List<String> blacklist) {
        String result = text == null ? "" : text;
        if (blacklist != null) {
            for (String term : blacklist) {
                String t = term == null ? "" : term.trim();
                if (t.isEmpty()) {
                    continue;
                }
                result = result.replaceAll("(?iu)(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(t)
                        + "(?![\\p{L}\\p{N}])", " ");
            }
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    private static void addWords(String text, LinkedHashSet<String> words, Set<String> seen) {
        if (text == null) {
            return;
        }
        for (String word : text.trim().split("\\s+")) {
            if (!word.isEmpty() && seen.add(word.toLowerCase(Locale.ROOT))) {
                words.add(word);
            }
        }
    }

    private static String join(Iterable<String> words) {
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(word);
        }
        return sb.toString();
    }

    private static String joinLower(List<String> path) {
        StringBuilder sb = new StringBuilder();
        for (String name : path) {
            sb.append('/').append(name.trim().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static List<String> append(List<String> path, String name) {
        List<String> next = new ArrayList<String>(path);
        next.add(name);
        return next;
    }

    private static List<String> trimmed(List<String> path) {
        List<String> out = new ArrayList<String>();
        for (String name : path) {
            out.add(name.trim());
        }
        return out;
    }

    private static JsonElement parse(String document) {
        try {
            JsonElement root = JsonParser.parseString(document == null ? "" : document);
            return root != null && root.isJsonObject() ? root : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}

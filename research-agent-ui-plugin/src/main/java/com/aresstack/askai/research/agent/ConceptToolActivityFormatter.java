package com.aresstack.askai.research.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Display adapter for the yellow concept tool bubble (issue #20): reduces one turn's technical
 * concept/scope log lines to a few readable, localized progress lines and an honest summary.
 *
 * <p>It is a pure, deterministic projection — it never changes, drops or reorders the technical
 * log (that stays complete in "Technical details" and the AskAI-Test {@code technical_log}); it
 * only reads it. Two line families feed it:</p>
 * <ul>
 *   <li>runtime trace lines ({@code "concept round 1: add_cards …"}, {@code "concept round 1 ->
 *       APPLIED revision=1"}) — they announce intent and relay the tool outcome;</li>
 *   <li>host receipts ({@code "concept_add_cards -> ADDED: …"}, {@code "offer_searches -> OFFERED 4
 *       tags"}, {@code "exclude_topic -> EXCLUDED …"}, …) — the host's own confirmation.</li>
 * </ul>
 *
 * <p>Success is only shown on a confirmed receipt; an announced action stays "in progress" until
 * then, and a refused/rejected/unconfirmed one never reads as stored. Every action kind keeps ONE
 * status line that is updated in place, so a repeated line can neither duplicate a message nor be
 * counted twice. Unknown lines are ignored. Not thread-safe: feed and render on one thread (the
 * EDT in the session).</p>
 */
final class ConceptToolActivityFormatter {

    /** At most this many status lines are visible in the bubble (the newest kinds win). */
    static final int MAX_VISIBLE_LINES = 4;
    private static final int MAX_NAME_CHARS = 32;
    private static final int MAX_LISTED_NAMES = 3;

    private static final String RUNTIME_PREFIX = "concept ";
    private static final Pattern ROUND_ACTION = Pattern.compile("^round (\\d+): ([a-z_]+)\\b(.*)$");
    private static final Pattern ROUND_OUTCOME = Pattern.compile("^round (\\d+) -> ([A-Z_]+)\\b.*$");
    private static final Pattern QUOTED_TOPIC = Pattern.compile("topic=\"(.*)\"\\s*$");
    private static final Pattern OFFERED_COUNT = Pattern.compile("^offer_searches -> OFFERED (\\d+)\\b.*$");
    private static final Pattern EXCLUDED_LABEL = Pattern.compile("^exclude_topic -> EXCLUDED\\b.*label=\"(.*)\"\\s*$");
    private static final Pattern RESOLVE_OUTCOME = Pattern.compile("^resolve_concept_conflict \\S+ -> ([A-Z_]+)\\b.*$");

    private enum Kind { CARDS, OFFER, EXCLUDE, MOVE, EDIT, RESOLVE, READ, PROBE, ENDPOINT }

    /**
     * One action kind within the turn. {@code pending}/{@code noChange}/{@code rejected} describe the
     * LATEST round of that kind; {@code confirmed}, the names and the count accumulate the results.
     */
    private static final class Step {
        boolean pending;
        boolean confirmed;
        boolean noChange;
        boolean rejected;
        String requestedLabel;
        int count = -1;
        /** The host receipt batch the next ADDED/MOVED lines belong to (its head line). */
        String batch = "";
        /** Distinct labels, for display. */
        final Set<String> names = new LinkedHashSet<String>();
        /** batch|label: equal labels under different parents count apart, a replayed line does not. */
        final Set<String> receiptKeys = new LinkedHashSet<String>();
        final Set<String> alreadyPresent = new LinkedHashSet<String>();
        final Set<String> suppressed = new LinkedHashSet<String>();
    }

    /** Insertion order = first appearance; a kind moves to the end when it changes again. */
    private final LinkedHashMap<Kind, Step> steps = new LinkedHashMap<Kind, Step>();
    private final Map<Integer, Kind> openRounds = new HashMap<Integer, Kind>();

    /** True for the line families this formatter interprets (everything else is never fed). */
    static boolean isRelevant(String line) {
        return line != null && (line.startsWith(RUNTIME_PREFIX) || isHostReceipt(line));
    }

    /** Host-side receipt lines (written by the plugin itself, never by the runtime). */
    static boolean isHostReceipt(String line) {
        return line != null && (line.startsWith("concept_add_cards -> ")
                || line.startsWith("concept_move_leaf -> ")
                || line.startsWith("offer_searches -> ")
                || line.startsWith("exclude_topic -> ")
                || line.startsWith("resolve_concept_conflict "));
    }

    void reset() {
        steps.clear();
        openRounds.clear();
    }

    /** Feed one technical line; returns {@code true} when the visible projection changed. */
    boolean accept(String line) {
        if (line == null) {
            return false;
        }
        String before = signature();
        String trimmed = line.trim();
        if (trimmed.startsWith(RUNTIME_PREFIX)) {
            acceptRuntime(trimmed.substring(RUNTIME_PREFIX.length()).trim());
        } else if (isHostReceipt(trimmed)) {
            acceptHostReceipt(trimmed);
        }
        return !before.equals(signature());
    }

    boolean hasVisibleLines() {
        return !visibleLines(true).isEmpty();
    }

    String title(boolean german) {
        return german ? "Arbeite an Konzept & Themenraum …" : "Working on concept & scope …";
    }

    /** The bubble body: the newest {@link #MAX_VISIBLE_LINES} status lines, one per action kind. */
    String body(boolean german) {
        List<String> lines = visibleLines(german);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - MAX_VISIBLE_LINES); i < lines.size(); i++) {
            sb.append(sb.length() == 0 ? "" : "\n").append("• ").append(lines.get(i));
        }
        return sb.toString();
    }

    /** The closing summary: only confirmed results — never a count of log lines. */
    String summary(boolean german) {
        List<String> parts = new ArrayList<String>();
        boolean anyRejected = false;
        for (Map.Entry<Kind, Step> entry : steps.entrySet()) {
            Step step = entry.getValue();
            anyRejected |= step.rejected;
            String part = summaryPart(entry.getKey(), step, german);
            if (part != null) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            if (anyRejected) {
                return german ? "Keine Änderung übernommen" : "No change applied";
            }
            return german ? "Keine Änderung am Konzept" : "No concept change";
        }
        return String.join(" · ", parts);
    }

    // ------------------------------------------------------------------ runtime trace lines

    private void acceptRuntime(String text) {
        Matcher action = ROUND_ACTION.matcher(text);
        if (action.matches()) {
            Kind kind = kindOfAction(action.group(2));
            if (kind == null) {
                return; // "invalid conceptAction", "none", future types: nothing to show
            }
            int round = Integer.parseInt(action.group(1));
            openRounds.put(round, kind);
            Step step = touch(kind);
            step.pending = true;
            step.noChange = false;
            step.rejected = false;
            if (kind == Kind.EXCLUDE) {
                Matcher topic = QUOTED_TOPIC.matcher(action.group(3));
                if (topic.find()) {
                    step.requestedLabel = topic.group(1);
                }
            }
            return;
        }
        Matcher outcome = ROUND_OUTCOME.matcher(text);
        if (outcome.matches()) {
            int round = Integer.parseInt(outcome.group(1));
            String result = outcome.group(2);
            Kind kind = openRounds.remove(round);
            if (kind == null && "EXCLUDED".equals(result)) {
                kind = Kind.EXCLUDE;
            } else if (kind == null && "RESOLVED".equals(result)) {
                kind = Kind.RESOLVE;
            }
            if (kind != null) {
                applyRuntimeOutcome(kind, result);
            }
            return;
        }
        if (text.startsWith("wrap-up offer executed")) {
            touch(Kind.OFFER).pending = !steps.get(Kind.OFFER).confirmed; // awaits the host receipt
        } else if (text.startsWith("wrap-up offer REJECTED") || text.startsWith("wrap-up offer lost")) {
            Step step = touch(Kind.OFFER);
            step.pending = false;
            step.rejected = true;
        } else if (text.startsWith("dedicated move -> APPLIED")) {
            applyRuntimeOutcome(Kind.MOVE, "APPLIED");
        } else if (text.startsWith("dedicated move -> REJECTED")) {
            applyRuntimeOutcome(Kind.MOVE, "REJECTED");
        } else if (text.startsWith("concept endpoint unavailable")) {
            for (Step step : steps.values()) {
                step.pending = false;
            }
            openRounds.clear();
            touch(Kind.ENDPOINT).rejected = true;
        }
    }

    private void applyRuntimeOutcome(Kind kind, String result) {
        Step step = steps.containsKey(kind) ? steps.get(kind) : null;
        if ("REFUSED".equals(result)) {
            // A guard repair round: internal, never shown — and certainly never a success.
            if (step != null) {
                step.pending = false;
                dropIfEmpty(kind, step);
            }
            return;
        }
        step = touch(kind);
        if ("APPLIED".equals(result) || "RESULT".equals(result) || "PROBED".equals(result)) {
            step.pending = false;
            step.confirmed = true;
        } else if ("NO_CHANGE".equals(result)) {
            step.pending = false;
            step.noChange = true;
        } else if ("REJECTED".equals(result)) {
            step.pending = false;
            step.rejected = true;
        } else if ("OFFERED".equals(result) || "EXCLUDED".equals(result) || "RESOLVED".equals(result)) {
            // Relayed "the tool returned" — success only on the host's own receipt.
            step.pending = !step.confirmed && !step.rejected;
        }
    }

    // ------------------------------------------------------------------ host receipts

    private void acceptHostReceipt(String line) {
        if (line.startsWith("concept_add_cards -> ")) {
            String receipt = line.substring("concept_add_cards -> ".length());
            Step step = touch(Kind.CARDS);
            if (receipt.startsWith("ADDED: ")) {
                addReceipt(step, receipt.substring("ADDED: ".length()));
                step.rejected = false;
            } else if (receipt.startsWith("ALREADY_PRESENT: ")) {
                step.alreadyPresent.add(receipt.substring("ALREADY_PRESENT: ".length()).trim());
            } else if (receipt.startsWith("SUPPRESSED_BY_SCOPE: ")) {
                step.suppressed.add(receipt.substring("SUPPRESSED_BY_SCOPE: ".length()).trim());
            } else if (receipt.startsWith("APPLIED")) {
                step.batch = receipt;
                step.confirmed = true;
                step.pending = false;
                step.rejected = false;
            } else if (receipt.startsWith("NO_CHANGE")) {
                step.batch = receipt;
                step.noChange = true;
                step.pending = false;
            }
            return;
        }
        if (line.startsWith("concept_move_leaf -> ")) {
            String receipt = line.substring("concept_move_leaf -> ".length());
            Step step = touch(Kind.MOVE);
            if (receipt.startsWith("MOVED: ")) {
                addReceipt(step, receipt.substring("MOVED: ".length()));
                step.rejected = false;
            } else if (receipt.startsWith("APPLIED")) {
                step.batch = receipt;
            } else if (receipt.startsWith("NO_CHANGE")) {
                step.noChange = true;
                step.pending = false;
            } else if (receipt.startsWith("REFUSED")) {
                step.rejected = true;
                step.pending = false;
            }
            return;
        }
        Matcher offered = OFFERED_COUNT.matcher(line);
        if (offered.matches()) {
            Step step = touch(Kind.OFFER);
            step.count = Integer.parseInt(offered.group(1)); // a later offer replaces the tags
            step.confirmed = step.count > 0;
            step.pending = false;
            return;
        }
        Matcher excluded = EXCLUDED_LABEL.matcher(line);
        if (excluded.matches()) {
            Step step = touch(Kind.EXCLUDE);
            step.names.add(excluded.group(1));
            step.confirmed = true;
            step.pending = false;
            return;
        }
        if (line.startsWith("exclude_topic -> REJECTED")) {
            Step step = touch(Kind.EXCLUDE);
            step.rejected = true;
            step.pending = false;
            return;
        }
        Matcher resolved = RESOLVE_OUTCOME.matcher(line);
        if (resolved.matches()) {
            Step step = touch(Kind.RESOLVE);
            step.pending = false;
            if ("REFUSED".equals(resolved.group(1))) {
                step.rejected = true;
            } else {
                step.confirmed = true;
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private List<String> visibleLines(boolean german) {
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<Kind, Step> entry : steps.entrySet()) {
            String line = statusLine(entry.getKey(), entry.getValue(), german);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String statusLine(Kind kind, Step step, boolean german) {
        String line = resultOrStatusLine(kind, step, german);
        boolean hasResult = step.confirmed || !step.names.isEmpty();
        if (line == null || !hasResult || kind == Kind.READ || kind == Kind.PROBE) {
            return line;
        }
        // An earlier success must not hide the latest round of the same kind.
        if (step.pending) {
            return line + (german ? " (weiterer Schritt läuft …)" : " (another step running …)");
        }
        if (step.rejected) {
            return line + (german ? " (weiterer Schritt nicht übernommen)" : " (a further step was not applied)");
        }
        return line;
    }

    private static String resultOrStatusLine(Kind kind, Step step, boolean german) {
        switch (kind) {
            case CARDS:
                if (!step.names.isEmpty()) {
                    int n = step.receiptKeys.size();
                    String text = german
                            ? "Konzept erweitert – " + n + (n == 1 ? " Themenbereich" : " Themenbereiche")
                                    + " hinzugefügt: " + nameList(step.names)
                            : "Concept extended – " + n + (n == 1 ? " topic area" : " topic areas")
                                    + " added: " + nameList(step.names);
                    return text + cardsSideNote(step, german);
                }
                if (step.confirmed) {
                    return german ? "Konzept erweitert" : "Concept extended";
                }
                if (step.pending) {
                    return german ? "Themenbereiche werden vorbereitet …" : "Preparing topic areas …";
                }
                if (step.noChange || !step.alreadyPresent.isEmpty() || !step.suppressed.isEmpty()) {
                    return (german ? "Keine neuen Themenbereiche" : "No new topic areas")
                            + cardsSideNote(step, german);
                }
                return step.rejected ? (german ? "Themenbereiche wurden nicht übernommen"
                        : "Topic areas were not applied") : null;
            case OFFER:
                if (step.confirmed) {
                    return german
                            ? step.count + (step.count == 1 ? " Suchvorschlag" : " Suchvorschläge") + " vorbereitet"
                            : step.count + (step.count == 1 ? " search suggestion" : " search suggestions")
                                    + " prepared";
                }
                if (step.pending) {
                    return german ? "Suchvorschläge werden vorbereitet …" : "Preparing search suggestions …";
                }
                return step.rejected || step.count == 0 ? (german ? "Suchvorschläge wurden nicht übernommen"
                        : "Search suggestions were not applied") : null;
            case EXCLUDE:
                if (step.confirmed && !step.names.isEmpty()) {
                    return german
                            ? (step.names.size() == 1 ? "Thema " : "Themen ") + quotedList(step.names, true)
                                    + " ausgeschlossen"
                            : (step.names.size() == 1 ? "Topic " : "Topics ") + quotedList(step.names, false)
                                    + " excluded";
                }
                if (step.pending) {
                    String label = step.requestedLabel == null ? null : shorten(step.requestedLabel);
                    if (label != null && !label.isEmpty()) {
                        return german ? "Thema „" + label + "“ wird ausgeschlossen …"
                                : "Excluding topic “" + label + "” …";
                    }
                    return german ? "Thema wird ausgeschlossen …" : "Excluding topic …";
                }
                return step.rejected ? (german ? "Ausschluss wurde nicht übernommen"
                        : "Exclusion was not applied") : null;
            case MOVE:
                if (!step.names.isEmpty()) {
                    return quotedList(step.names, german) + (german ? " verschoben" : " moved");
                }
                if (step.confirmed) {
                    return german ? "Karte verschoben" : "Card moved";
                }
                if (step.pending) {
                    return german ? "Karte wird verschoben …" : "Moving card …";
                }
                if (step.noChange) {
                    return german ? "Karte liegt bereits am Ziel" : "Card already in place";
                }
                return step.rejected ? (german ? "Verschieben wurde nicht übernommen"
                        : "Move was not applied") : null;
            case EDIT:
                if (step.confirmed) {
                    return german ? "Konzept angepasst" : "Concept updated";
                }
                if (step.pending) {
                    return german ? "Konzept wird angepasst …" : "Updating concept …";
                }
                if (step.noChange) {
                    return german ? "Keine Änderung nötig" : "No change needed";
                }
                return step.rejected ? (german ? "Änderung wurde nicht übernommen"
                        : "Change was not applied") : null;
            case RESOLVE:
                if (step.confirmed) {
                    return german ? "Konfliktentscheidung übernommen" : "Conflict decision applied";
                }
                if (step.pending) {
                    return german ? "Konfliktentscheidung wird übernommen …" : "Applying conflict decision …";
                }
                return step.rejected ? (german ? "Konfliktentscheidung wurde nicht übernommen"
                        : "Conflict decision was not applied") : null;
            case READ:
                if (step.confirmed) {
                    return german ? "Konzept gelesen" : "Concept read";
                }
                return step.pending ? (german ? "Konzept wird gelesen …" : "Reading concept …") : null;
            case PROBE:
                if (step.confirmed) {
                    return german ? "Themenraum geprüft" : "Topic space checked";
                }
                return step.pending ? (german ? "Themenraum wird geprüft …" : "Checking topic space …") : null;
            default:
                return step.rejected ? (german ? "Konzept-Werkzeug nicht erreichbar"
                        : "Concept tool unreachable") : null;
        }
    }

    private static String summaryPart(Kind kind, Step step, boolean german) {
        switch (kind) {
            case CARDS:
                if (!step.names.isEmpty()) {
                    int n = step.receiptKeys.size();
                    return german ? n + (n == 1 ? " Themenbereich" : " Themenbereiche") + " hinzugefügt"
                            : n + (n == 1 ? " topic area" : " topic areas") + " added";
                }
                return step.confirmed ? (german ? "Konzept erweitert" : "Concept extended") : null;
            case OFFER:
                return step.confirmed ? (german
                        ? step.count + (step.count == 1 ? " Suchvorschlag" : " Suchvorschläge")
                        : step.count + (step.count == 1 ? " search suggestion" : " search suggestions")) : null;
            case EXCLUDE:
                return step.confirmed && !step.names.isEmpty()
                        ? quotedList(step.names, german) + (german ? " ausgeschlossen" : " excluded") : null;
            case MOVE:
                if (!step.names.isEmpty()) {
                    return quotedList(step.names, german) + (german ? " verschoben" : " moved");
                }
                return step.confirmed ? (german ? "Karte verschoben" : "Card moved") : null;
            case EDIT:
                return step.confirmed ? (german ? "Konzept angepasst" : "Concept updated") : null;
            case RESOLVE:
                return step.confirmed ? (german ? "Konflikt entschieden" : "Conflict resolved") : null;
            case PROBE:
                return step.confirmed ? (german ? "Themenraum geprüft" : "Topic space checked") : null;
            default:
                return null; // reading the concept is not a result
        }
    }

    private static String cardsSideNote(Step step, boolean german) {
        StringBuilder sb = new StringBuilder();
        if (!step.alreadyPresent.isEmpty()) {
            sb.append(german ? " (" + step.alreadyPresent.size() + " bereits vorhanden)"
                    : " (" + step.alreadyPresent.size() + " already present)");
        }
        if (!step.suppressed.isEmpty()) {
            sb.append(german ? " (" + step.suppressed.size() + " außerhalb des Themenraums)"
                    : " (" + step.suppressed.size() + " outside the topic space)");
        }
        return sb.toString();
    }

    private static String nameList(Set<String> names) {
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (String name : names) {
            if (listed == MAX_LISTED_NAMES) {
                return sb.append(" …").toString();
            }
            sb.append(listed++ == 0 ? "" : ", ").append(shorten(name));
        }
        return sb.toString();
    }

    private static String quotedList(Set<String> names, boolean german) {
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (String name : names) {
            if (listed == MAX_LISTED_NAMES) {
                return sb.append(" …").toString();
            }
            sb.append(listed++ == 0 ? "" : ", ")
                    .append(german ? "„" : "“").append(shorten(name)).append(german ? "“" : "”");
        }
        return sb.toString();
    }

    static String shorten(String name) {
        String flat = name == null ? "" : name.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_NAME_CHARS ? flat : flat.substring(0, MAX_NAME_CHARS - 1).trim() + "…";
    }

    // ------------------------------------------------------------------ bookkeeping

    private static Kind kindOfAction(String type) {
        if ("add_cards".equals(type) || "add".equals(type)) {
            return Kind.CARDS;
        } else if ("offer".equals(type)) {
            return Kind.OFFER;
        } else if ("exclude".equals(type)) {
            return Kind.EXCLUDE;
        } else if ("move".equals(type)) {
            return Kind.MOVE;
        } else if ("rename".equals(type) || "rewrite".equals(type) || "remove".equals(type)) {
            return Kind.EDIT;
        } else if ("resolve".equals(type)) {
            return Kind.RESOLVE;
        } else if ("read".equals(type)) {
            return Kind.READ;
        } else if ("probe".equals(type)) {
            return Kind.PROBE;
        }
        return null;
    }

    private static void addReceipt(Step step, String label) {
        String name = label.trim();
        step.names.add(name);
        step.receiptKeys.add(step.batch + "|" + name);
        step.confirmed = true;
        step.pending = false;
    }

    private Step touch(Kind kind) {
        Step step = steps.remove(kind);
        steps.put(kind, step == null ? new Step() : step);
        return steps.get(kind);
    }

    private void dropIfEmpty(Kind kind, Step step) {
        if (!step.pending && !step.confirmed && !step.noChange && !step.rejected
                && step.names.isEmpty() && step.alreadyPresent.isEmpty() && step.suppressed.isEmpty()) {
            steps.remove(kind);
        }
    }

    /** The rendered state (language does not change WHAT is shown, only its wording). */
    private String signature() {
        return body(true) + "\u0000" + summary(true);
    }
}

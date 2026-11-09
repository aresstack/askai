package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.PortMainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The section writer of the Document phase (plan D8, PR 28). It runs on the {@code phase.draft.writer} slot (which
 * inherits the model of {@code phase.draft}), asks for plain text with {@code [n]} markers and checks the answer in
 * code: {@link CitationValidator} strips unknown markers, flags uncited sentences and paragraphs and parses gaps;
 * {@link ClaimSupportCheck} flags numbers and quotations the cited passages do not contain.
 *
 * <p>A cut-off answer gets exactly one continuation from its last full sentence; an answer with uncited sentences or
 * unsupported numbers or quotes gets exactly one repair, which sends the error list and never the failed answer. The
 * repaired answer is stored with whatever flags remain. A section longer than the word target of one call is split
 * by its key points, one call per part. {@code WriterWorker} stays for the orchestrated path until PR 37.</p>
 */
public final class SectionWriter {

    static final double TEMPERATURE = 0.3;
    private static final String CLOSERS = "\"'\u201C\u201D\u2019\u00BB\u00AB)";

    private final MainModelChat chat;
    private final CitationValidator validator = new CitationValidator();
    private final ClaimSupportCheck claims = new ClaimSupportCheck();
    private final Consumer<String> log;

    /**
     * @param chat the transport of the {@code phase.draft.writer} slot ({@code ResearchModelSlots.PHASE_DRAFT_WRITER})
     * @param log  receives one line per stripped marker and per extra call; never source text
     */
    public SectionWriter(MainModelChat chat, Consumer<String> log) {
        if (chat == null) {
            throw new IllegalArgumentException("chat must not be null");
        }
        this.chat = chat;
        this.log = log == null ? new Consumer<String>() {
            public void accept(String line) {
            }
        } : log;
    }

    /** A writer on the host's model port, bound to {@code phase.draft.writer} (falls back to {@code phase.draft}). */
    public static SectionWriter onPort(ModelPort port, Duration timeout, Consumer<String> log) {
        return new SectionWriter(new PortMainModelChat(port, ResearchModelSlots.PHASE_DRAFT_WRITER, timeout), log);
    }

    /** Writes the whole section; more words than one call's target are split by key points. */
    public SectionDraft write(SectionAssignment assignment) {
        List<SectionAssignment> parts = parts(assignment);
        if (parts.size() == 1) {
            return writePart(parts.get(0));
        }
        StringBuilder text = new StringBuilder();
        Map<Integer, EvidencePassage> citations = new LinkedHashMap<Integer, EvidencePassage>();
        List<ClaimFlag> flags = new ArrayList<ClaimFlag>();
        List<String> gaps = new ArrayList<String>();
        List<String> stripped = new ArrayList<String>();
        String hash = "";
        String model = "";
        int calls = 0;
        int words = 0;
        int paragraphOffset = 0;
        String digest = assignment.getPreviousDigest();
        for (SectionAssignment part : parts) {
            SectionDraft draft = writePart(part.part(part.getKeyPoints(), part.getWordsPerCall(), digest));
            calls += draft.getCalls();
            if (hash.isEmpty()) {
                hash = draft.getPromptHash();
            }
            if (!draft.isOk()) {
                return SectionDraft.failed(draft.getFailure(), hash, calls);
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(draft.getText());
            citations.putAll(draft.getCitations());
            for (ClaimFlag f : draft.getFlags()) {
                flags.add(new ClaimFlag(f.getKind(), f.getParagraph() + paragraphOffset, f.getSentence(),
                        f.getDetail()));
            }
            paragraphOffset += draft.getText().isEmpty() ? 0 : draft.getText().split("\n\n").length;
            gaps.addAll(draft.getGaps());
            stripped.addAll(draft.getStrippedMarkers());
            model = draft.getModelLabel().isEmpty() ? model : draft.getModelLabel();
            words += draft.getWordCount();
            digest = lastSentences(draft.getText());
        }
        return new SectionDraft(true, "", text.toString(), new java.util.TreeMap<Integer, EvidencePassage>(citations),
                flags, gaps, stripped, model, hash, calls, words);
    }

    /** One call's worth: first answer, at most one continuation, at most one repair (with its own continuation). */
    SectionDraft writePart(SectionAssignment a) {
        List<ChatMessage> first = SectionWriterPrompt.first(a);
        String hash = SectionWriterPrompt.hash(first);
        int[] calls = {0};
        String[] model = {""};
        String answer = answer(a, first, calls, model);
        if (answer == null) {
            return SectionDraft.failed(lastFailure, hash, calls[0]);
        }
        Checked checked = check(answer, a);
        List<ClaimFlag> errors = checked.repairable();
        if (!errors.isEmpty()) {
            log.accept("section " + a.getSectionId() + ": one repair for " + errors.size() + " citation errors");
            String repaired = answer(a, SectionWriterPrompt.repair(a, errors), calls, model);
            if (repaired != null) {
                checked = check(repaired, a);
            }
        }
        for (String marker : checked.result.getStrippedMarkers()) {
            log.accept("section " + a.getSectionId() + ": stripped unknown marker " + marker + " (k = "
                    + a.getPassages().size() + ")");
        }
        Map<Integer, EvidencePassage> citations = new java.util.TreeMap<Integer, EvidencePassage>();
        for (Integer n : checked.result.citedNumbers()) {
            EvidencePassage passage = byNumber(a, n);
            if (passage != null) {
                citations.put(n, passage);
            }
        }
        return new SectionDraft(true, "", checked.result.text(), citations, checked.flags(),
                checked.result.getGaps(), checked.result.getStrippedMarkers(), model[0], hash, calls[0],
                checked.result.wordCount());
    }

    private String lastFailure = "";

    /** One answer: a call and, when it was cut off, exactly one continuation. Null on a failed call. */
    private String answer(SectionAssignment a, List<ChatMessage> messages, int[] calls, String[] model) {
        MainModelChatResult result = chat.complete(messages, TEMPERATURE, a.getAnswerTokens());
        calls[0]++;
        if (!result.isOk()) {
            lastFailure = result.getDetail();
            return null;
        }
        remember(result, model);
        String text = result.getText() == null ? "" : result.getText();
        if (!result.isCutOff()) {
            return text;
        }
        String cut = upToLastFullSentence(text);
        log.accept("section " + a.getSectionId() + ": answer cut off at " + a.getAnswerTokens()
                + " tokens, one continuation");
        MainModelChatResult more = chat.complete(SectionWriterPrompt.continuation(a, cut), TEMPERATURE,
                a.getAnswerTokens());
        calls[0]++;
        if (!more.isOk()) {
            return cut; // the cut text up to its last full sentence stays usable
        }
        remember(more, model);
        String rest = more.getText() == null ? "" : more.getText();
        if (more.isCutOff()) {
            rest = upToLastFullSentence(rest); // no second continuation: the fragment is dropped
        }
        return join(cut, rest);
    }

    private static void remember(MainModelChatResult result, String[] model) {
        String label = result.getMetrics() == null ? "" : result.getMetrics().getModelLabel();
        if (label != null && !label.isEmpty()) {
            model[0] = label;
        }
    }

    /** The text up to and including its last sentence end (or its last complete line). */
    static String upToLastFullSentence(String text) {
        int end = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                int after = i + 1;
                while (after < text.length() && CLOSERS.indexOf(text.charAt(after)) >= 0) {
                    after++; // a closing quote or bracket belongs to the sentence: "rose.” [1]"
                }
                if (after == text.length() || Character.isWhitespace(text.charAt(after))
                        || text.charAt(after) == '[') {
                    end = after;
                    break;
                }
            }
        }
        if (end < 0) {
            return "";
        }
        // keep markers written right after the full stop: "... rose. [2]"
        java.util.regex.Matcher tail = java.util.regex.Pattern.compile("^(\\s*\\[[\\d,; ]+\\])+")
                .matcher(text.substring(end));
        if (tail.find()) {
            end += tail.end();
        }
        return text.substring(0, end).trim();
    }

    /** Joins a continuation to the cut text, dropping sentences the continuation repeats. */
    static String join(String cut, String continuation) {
        String more = continuation.trim();
        List<CitationValidator.Sentence> written = CitationValidator.sentences(cut.replace('\n', ' '));
        List<CitationValidator.Sentence> next = CitationValidator.sentences(more.split("\n", 2)[0]);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (CitationValidator.Sentence s : written) {
            seen.add(ClaimSupportCheck.normaliseText(s.plainText()));
        }
        int skip = 0;
        for (CitationValidator.Sentence s : next) {
            if (!seen.contains(ClaimSupportCheck.normaliseText(s.plainText()))) {
                break;
            }
            skip += 1;
        }
        if (skip > 0) {
            String firstLine = more.split("\n", 2)[0];
            StringBuilder kept = new StringBuilder();
            for (int i = skip; i < next.size(); i++) {
                kept.append(kept.length() == 0 ? "" : " ").append(next.get(i).getText());
            }
            more = kept + more.substring(firstLine.length());
            more = more.trim();
        }
        if (more.isEmpty()) {
            return cut;
        }
        boolean newParagraph = continuation.startsWith("\n");
        if (more.regionMatches(true, 0, "GAP", 0, 3) && more.substring(3).trim().startsWith(":")) {
            return cut + "\n" + more; // a gap line must start its own line to be parsed
        }
        return cut + (newParagraph ? "\n\n" : " ") + more;
    }

    private Checked check(String answer, SectionAssignment a) {
        CitationValidator.Result result = validator.validate(answer, a.getPassages().size());
        return new Checked(result, claims.check(result, a.getPassages()));
    }

    private static EvidencePassage byNumber(SectionAssignment a, int n) {
        for (EvidencePassage p : a.getPassages()) {
            if (p.getNumber() == n) {
                return p;
            }
        }
        return null;
    }

    /** Splits a section into key-point parts when it needs more words than one call writes. */
    static List<SectionAssignment> parts(SectionAssignment a) {
        List<SectionAssignment> parts = new ArrayList<SectionAssignment>();
        int per = a.getWordsPerCall();
        int count = (a.getSectionWords() + per - 1) / per;
        List<String> points = a.getKeyPoints();
        count = Math.max(1, Math.min(count, points.size()));
        if (count == 1) {
            parts.add(a.part(points, Math.min(a.getSectionWords(), per), a.getPreviousDigest()));
            return parts;
        }
        int words = Math.min(per, (a.getSectionWords() + count - 1) / count);
        for (int i = 0; i < count; i++) {
            int from = i * points.size() / count;
            int to = (i + 1) * points.size() / count;
            parts.add(a.part(new ArrayList<String>(points.subList(from, to)), words, a.getPreviousDigest()));
        }
        return parts;
    }

    private static String lastSentences(String text) {
        List<CitationValidator.Sentence> s = CitationValidator.sentences(text.replace('\n', ' '));
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, s.size() - 3); i < s.size(); i++) {
            sb.append(sb.length() == 0 ? "" : " ").append(s.get(i).plainText());
        }
        return sb.toString();
    }

    private static final class Checked {
        final CitationValidator.Result result;
        final List<ClaimFlag> unsupported;

        Checked(CitationValidator.Result result, List<ClaimFlag> unsupported) {
            this.result = result;
            this.unsupported = unsupported;
        }

        List<ClaimFlag> flags() {
            List<ClaimFlag> all = new ArrayList<ClaimFlag>(result.getFlags());
            all.addAll(unsupported);
            return all;
        }

        /** What the repair lists: uncited sentences and unsupported numbers and quotes. */
        List<ClaimFlag> repairable() {
            List<ClaimFlag> out = new ArrayList<ClaimFlag>();
            for (ClaimFlag f : flags()) {
                if (f.getKind() != ClaimFlag.Kind.UNCITED_PARAGRAPH) {
                    out.add(f);
                }
            }
            return out;
        }
    }
}

package com.aresstack.askai.research.sentencecheck;

import com.aresstack.askai.research.memory.PassageText;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sentence check's verdict on one sentence of the book and one passage it cites (component 11): whether the
 * passage covers the sentence, the quote that carries it, what kind of text the passage is, and, where it was asked,
 * whether the sentence attributes a statement of a source with an interest of its own and marks a rule of another
 * legal space. Immutable.
 *
 * <p>Two verdicts are kept, as with the evidence judge (component 6): the model's and the one after the quote check.
 * A GEDECKT holds only when the quote stands verbatim in the passage ({@link PassageText#holdsQuote}) and every number
 * of the sentence stands in the quote; otherwise it holds as TEILWEISE. A failed check is FEHLER, never NICHT_GEDECKT,
 * and is offered again.</p>
 */
public final class SentenceVerdict {

    /** Whether the passage covers the sentence. */
    public enum Verdict { GEDECKT, TEILWEISE, NICHT_GEDECKT }

    /** What kind of text the cited passage is; only {@link #TEXT} carries statements. */
    public enum Kind { TEXT, MUSTERKLAUSEL, LITERATURLISTE, LINKLISTE, INHALTSVERZEICHNIS, SONSTIGES }

    /** For a source with an interest of its own: whether the sentence attributes its statement. */
    public enum Attribution { NICHT_GEFRAGT, ZUGESCHRIEBEN, ALS_TATSACHE }

    /** For a source of another legal space: whether the sentence says where its rule holds. */
    public enum Jurisdiction { NICHT_GEFRAGT, GEKENNZEICHNET, NICHT_GEKENNZEICHNET, KEINE_RECHTSAUSSAGE }

    public enum Status { OK, FEHLER }

    /** A citation marker of the book's text: {@code [12]} or {@code [3, 5]}. */
    static final Pattern MARKER = Pattern.compile("\\s*\\[\\d+(?:\\s*[,;]\\s*\\d+)*\\]");
    /** The same numbers as the evidence judge's check (component 6): digits with their separators. */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,\\u00A0\\u202F]\\d+)*");

    private final String sentenceId;
    private final String sentenceKey;
    private final String passageId;
    private final String sourceId;
    private final String textKey;
    private final Verdict said;
    private final Verdict holds;
    private final String quote;
    private final String reason;
    private final Kind kind;
    private final Attribution attribution;
    private final Jurisdiction jurisdiction;
    private final Status status;
    private final String problem;
    private final String model;
    private final int version;
    private final long timeMillis;
    private final long millis;

    SentenceVerdict(String sentenceId, String sentenceKey, String passageId, String sourceId, String textKey,
                    Verdict said, Verdict holds, String quote, String reason, Kind kind, Attribution attribution,
                    Jurisdiction jurisdiction, Status status, String problem, String model, int version,
                    long timeMillis, long millis) {
        this.sentenceId = clean(sentenceId);
        this.sentenceKey = clean(sentenceKey);
        this.passageId = clean(passageId);
        this.sourceId = clean(sourceId);
        this.textKey = clean(textKey);
        this.status = status == null ? Status.FEHLER : status;
        this.said = this.status == Status.OK ? said : null;
        this.holds = this.status == Status.OK ? holds : null;
        this.quote = quote == null ? "" : quote;
        this.reason = reason == null ? "" : reason;
        this.kind = this.status == Status.OK ? (kind == null ? Kind.SONSTIGES : kind) : null;
        this.attribution = attribution == null ? Attribution.NICHT_GEFRAGT : attribution;
        this.jurisdiction = jurisdiction == null ? Jurisdiction.NICHT_GEFRAGT : jurisdiction;
        this.problem = problem == null ? "" : problem;
        this.model = model == null ? "" : model;
        this.version = version;
        this.timeMillis = timeMillis;
        this.millis = Math.max(0, millis);
        if (this.sentenceKey.isEmpty() || this.passageId.isEmpty() || this.textKey.isEmpty()) {
            throw new IllegalArgumentException("a verdict needs a sentence key, a passage id and a text key");
        }
        if (this.status == Status.OK && (this.said == null || this.holds == null)) {
            throw new IllegalArgumentException("a verdict that holds needs the model's verdict and the checked one");
        }
    }

    /**
     * The model's answer, checked against the passage it read and the sentence: a GEDECKT holds only when the quote
     * stands verbatim in the passage and every number of the sentence (without its citation markers) stands in the
     * quote; otherwise it holds as TEILWEISE.
     */
    public static SentenceVerdict checked(String sentenceId, String sentence, String passageId, String sourceId,
                                          String passageText, Verdict said, Kind kind, String quote, String reason,
                                          Attribution attribution, Jurisdiction jurisdiction, String model,
                                          int version, long timeMillis, long millis) {
        if (said == null) {
            throw new IllegalArgumentException("a verdict that holds needs the model's verdict");
        }
        Verdict holds = said == Verdict.GEDECKT && !carries(sentence, passageText, quote) ? Verdict.TEILWEISE : said;
        return new SentenceVerdict(sentenceId, sentenceKey(sentenceId, sentence), passageId, sourceId,
                textKey(passageId, passageText), said, holds, quote, reason, kind, attribution, jurisdiction,
                Status.OK, "", model, version, timeMillis, millis);
    }

    /** The check failed on this pair; {@code problem} says why. The pair is offered again later. */
    public static SentenceVerdict failed(String sentenceId, String sentence, String passageId, String sourceId,
                                         String passageText, String problem, String model, int version,
                                         long timeMillis, long millis) {
        return new SentenceVerdict(sentenceId, sentenceKey(sentenceId, sentence), passageId, sourceId,
                textKey(passageId, passageText), null, null, "", "", null, null, null, Status.FEHLER, problem, model,
                version, timeMillis, millis);
    }

    /** Whether the quote stands in the passage and holds every number of the sentence. */
    static boolean carries(String sentence, String passageText, String quote) {
        return PassageText.holdsQuote(passageText, quote) && numbersHeld(plain(sentence), quote);
    }

    /** The sentence without its citation markers, blanks single. */
    public static String plain(String sentence) {
        String text = sentence == null ? "" : MARKER.matcher(sentence).replaceAll("");
        return text.replaceAll("\\s+", " ").replaceAll(" ([.,;:!?])", "$1").trim();
    }

    /** The key a sentence is kept by: {@link PassageText#key} of its plain text, {@code satz:<id>} when empty. */
    public static String sentenceKey(String sentenceId, String sentence) {
        String key = PassageText.key(plain(sentence));
        return key.isEmpty() ? "satz:" + clean(sentenceId) : key;
    }

    /** The key a passage text is kept by: {@link PassageText#key}, {@code passage:<id>} when empty. */
    public static String textKey(String passageId, String passageText) {
        String key = PassageText.key(passageText);
        return key.isEmpty() ? "passage:" + clean(passageId) : key;
    }

    /**
     * Whether every number a statement names stands in the quote: the rule of the evidence judge's check (component 6,
     * {@code PassageText.numbersHeld} there); one of the two copies goes when both are merged.
     */
    static boolean numbersHeld(String statement, String quote) {
        Set<String> wanted = numbers(statement);
        return wanted.isEmpty() || numbers(quote).containsAll(wanted);
    }

    /** The numbers of a text in one form: thousands separators dropped, any other separator a point. */
    static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<String>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String[] groups = m.group().split("[.,\\u00A0\\u202F]");
            boolean thousands = groups.length > 1;
            for (int i = 1; i < groups.length; i++) {
                thousands &= groups[i].length() == 3;
            }
            out.add(String.join(thousands ? "" : ".", groups));
        }
        return out;
    }

    /**
     * The verdict of a sentence over the passages it cites, each checked alone: GEDECKT when one covers it, TEILWEISE
     * when one covers a part (the others may cover the rest; that is not judged together), NICHT_GEDECKT when every
     * one was checked and none carries anything, {@code null} while a check failed or none was made.
     */
    public static Verdict ofSentence(Collection<SentenceVerdict> verdicts) {
        boolean partial = false;
        boolean failed = verdicts == null || verdicts.isEmpty();
        for (SentenceVerdict verdict : verdicts == null ? java.util.Collections.<SentenceVerdict>emptyList()
                : verdicts) {
            if (verdict.getStatus() != Status.OK) {
                failed = true;
            } else if (verdict.getVerdict() == Verdict.GEDECKT) {
                return Verdict.GEDECKT;
            } else if (verdict.getVerdict() == Verdict.TEILWEISE) {
                partial = true;
            }
        }
        if (partial) {
            return Verdict.TEILWEISE;
        }
        return failed ? null : Verdict.NICHT_GEDECKT;
    }

    /**
     * Whether the writer gets the sentence back (once, build plan): the passage does not carry it, or it states a
     * statement of a source with an interest of its own as a fact, or a rule of another legal space without saying so.
     */
    public boolean needsRewrite() {
        return status == Status.OK && (holds == Verdict.NICHT_GEDECKT || attribution == Attribution.ALS_TATSACHE
                || jurisdiction == Jurisdiction.NICHT_GEKENNZEICHNET);
    }

    /** Whether the quote check took a GEDECKT of the model down to TEILWEISE. */
    public boolean isDowngraded() {
        return status == Status.OK && said != holds;
    }

    public String getSentenceId() {
        return sentenceId;
    }

    public String getSentenceKey() {
        return sentenceKey;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTextKey() {
        return textKey;
    }

    /** The model's verdict; {@code null} on FEHLER. */
    public Verdict getSaid() {
        return said;
    }

    /** The verdict after the quote check, the one that counts; {@code null} on FEHLER. */
    public Verdict getVerdict() {
        return holds;
    }

    public String getQuote() {
        return quote;
    }

    public String getReason() {
        return reason;
    }

    /** What kind of text the passage is; {@code null} on FEHLER. */
    public Kind getKind() {
        return kind;
    }

    public Attribution getAttribution() {
        return attribution;
    }

    public Jurisdiction getJurisdiction() {
        return jurisdiction;
    }

    public Status getStatus() {
        return status;
    }

    public String getProblem() {
        return problem;
    }

    public String getModel() {
        return model;
    }

    /** The check's version ({@link SentenceCheck#VERSION}) the verdict was given under. */
    public int getVersion() {
        return version;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    /** How long the check took. */
    public long getMillis() {
        return millis;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return sentenceId + "/" + passageId + " " + (status == Status.OK ? holds + (isDowngraded() ? " (model "
                + said + ")" : "") : "FEHLER " + problem);
    }
}

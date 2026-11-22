package com.aresstack.askai.research.pagenoise;

/**
 * The page-noise check's mark on one passage text: content (INHALT) or a page leftover (RAUSCHEN), with the kind of
 * leftover and the stage that decided; marks of version 1 may also say both (MISCH). A mark is kept by text key, so
 * one text has one mark wherever it stands. Only a mark that holds (status OK) and says RAUSCHEN makes a passage a
 * leftover; MISCH holds content, and FEHLER never says anything. Immutable.
 */
public final class NoiseMark {

    /** What the passage is. */
    public enum Verdict {
        /** A statement on the matter: a fact, a rule, a number, a result, an explanation, an advice. */
        INHALT,
        /** All or almost all of it belongs to the page, not to the matter. */
        RAUSCHEN,
        /**
         * Content and leftover together; it is no leftover, because it holds content. Only version 1 of the check gave
         * it; since version 2 the model names the kind of the biggest part and the verdict follows from it.
         */
        MISCH
    }

    /** The kind of leftover; {@link #KEINE} for content. {@link #STRUKTUR} comes from the prose rules only. */
    public enum Kind {
        /** Advertising and self-promotion: offers, prices, coupons, calls to buy, book or sign up. */
        WERBUNG,
        /** Table of contents, index, list of titles or links. */
        VERZEICHNIS,
        /** Literature references and lists of sources. */
        LITERATUR,
        /** Captions, picture credits, and a lone short sentence on a page of almost no prose. */
        BILDUNTERSCHRIFT,
        /** Menus, breadcrumbs, next and back links. */
        NAVIGATION,
        /** A bare heading. */
        UEBERSCHRIFT,
        /** A description of the site or of its provider itself. */
        SEITENBESCHREIBUNG,
        /** Imprint and contact data. */
        IMPRESSUM,
        /** Notices of the site: cookies, data protection settings, licences, newsletters. */
        SEITENHINWEIS,
        /** Too few words or sentences, or a long run without punctuation (prose rules only). */
        STRUKTUR,
        /** Another leftover. */
        SONSTIGES,
        /** No leftover. */
        KEINE
    }

    /** Whether the mark holds. */
    public enum Status {
        OK,
        /** No verdict: the call failed or the answer was unreadable. The text is checked again. */
        FEHLER
    }

    /** Stage 1: the prose rules, no model. */
    public static final int RULES = 1;
    /** Stage 2: the small model, only on what the rules let through. */
    public static final int MODEL = 2;

    private final String textKey;
    private final String passageId;
    private final String sourceId;
    private final Verdict verdict;
    private final Kind kind;
    private final int stage;
    private final String reason;
    private final Status status;
    private final String problem;
    private final String model;
    private final int version;
    private final long timeMillis;
    private final long millis;

    NoiseMark(String textKey, String passageId, String sourceId, Verdict verdict, Kind kind, int stage, String reason,
              Status status, String problem, String model, int version, long timeMillis, long millis) {
        this.textKey = clean(textKey);
        this.passageId = clean(passageId);
        this.sourceId = clean(sourceId);
        this.status = status == null ? Status.FEHLER : status;
        this.verdict = this.status == Status.OK ? verdict : null;
        if (this.textKey.isEmpty()) {
            throw new IllegalArgumentException("a mark needs a text key");
        }
        if (stage != RULES && stage != MODEL) {
            throw new IllegalArgumentException("a mark comes from stage 1 or 2, not " + stage);
        }
        if (this.status == Status.OK && this.verdict == null) {
            throw new IllegalArgumentException("a mark that holds needs a verdict");
        }
        Kind given = kind == null ? Kind.SONSTIGES : kind;
        if (this.verdict == null || this.verdict == Verdict.INHALT) {
            this.kind = Kind.KEINE;
        } else {
            this.kind = given == Kind.KEINE ? Kind.SONSTIGES : given;
        }
        this.stage = stage;
        this.reason = clean(reason);
        this.problem = clean(problem);
        this.model = clean(model);
        this.version = version;
        this.timeMillis = timeMillis;
        this.millis = Math.max(0, millis);
    }

    /** A mark that holds. */
    static NoiseMark of(String textKey, String passageId, String sourceId, Verdict verdict, Kind kind, int stage,
                        String reason, String model, int version, long timeMillis, long millis) {
        return new NoiseMark(textKey, passageId, sourceId, verdict, kind, stage, reason, Status.OK, "", model, version,
                timeMillis, millis);
    }

    /** No verdict: the call failed or its answer could not be read. Never a leftover. */
    static NoiseMark failed(String textKey, String passageId, String sourceId, String problem, String model,
                            int version, long timeMillis, long millis) {
        return new NoiseMark(textKey, passageId, sourceId, null, Kind.KEINE, MODEL, "", Status.FEHLER, problem, model,
                version, timeMillis, millis);
    }

    /** Whether the passage is a page leftover the other components skip: the mark holds and says RAUSCHEN. */
    public boolean isNoise() {
        return status == Status.OK && verdict == Verdict.RAUSCHEN;
    }

    /** The text key ({@code PassageText.key} of the passage text, {@code passage:<id>} for an empty one). */
    public String getTextKey() {
        return textKey;
    }

    /** The first passage the text was checked in. */
    public String getPassageId() {
        return passageId;
    }

    public String getSourceId() {
        return sourceId;
    }

    /** {@code null} when the mark is FEHLER. */
    public Verdict getVerdict() {
        return verdict;
    }

    public Kind getKind() {
        return kind;
    }

    /** {@link #RULES} or {@link #MODEL}. */
    public int getStage() {
        return stage;
    }

    /** The rule a passage broke (stage 1) or the model's reason (stage 2). */
    public String getReason() {
        return reason;
    }

    public Status getStatus() {
        return status;
    }

    /** Why a FEHLER mark holds no verdict; empty otherwise. */
    public String getProblem() {
        return problem;
    }

    /** The model of stage 2; empty for stage 1. */
    public String getModel() {
        return model;
    }

    /** The check's version ({@link PageNoiseCheck#VERSION}) the mark was given under. */
    public int getVersion() {
        return version;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    /** How long the mark took (the model's calls for stage 2). */
    public long getMillis() {
        return millis;
    }

    @Override
    public String toString() {
        return "NoiseMark{" + textKey + ", " + (status == Status.OK ? verdict + "/" + kind : "FEHLER") + ", stage "
                + stage + "}";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the section writer gets for one section (plan PR 28): the section of the approved outline with its brief and
 * key points, the evidence pack of PR 27 (numbered passages, book synopsis, previous-section digest, glossary), the
 * book language of the Phase-1 record and the writer settings of the Document row (word target and answer budget
 * per call). Immutable; build it with {@link #builder(String)}.
 */
public final class SectionAssignment {

    private final String sectionId;
    private final String title;
    private final String brief;
    private final List<String> keyPoints;
    private final List<EvidencePassage> passages;
    private final String synopsis;
    private final String previousDigest;
    private final List<String> glossary;
    private final String bookLanguage;
    private final int sectionWords;
    private final int wordsPerCall;
    private final int answerTokens;
    private final String rewriteComment;
    private final String currentText;

    private SectionAssignment(Builder b) {
        this.sectionId = b.sectionId;
        this.title = b.title;
        this.brief = b.brief;
        this.keyPoints = Collections.unmodifiableList(new ArrayList<String>(b.keyPoints));
        this.passages = Collections.unmodifiableList(new ArrayList<EvidencePassage>(b.passages));
        this.synopsis = b.synopsis;
        this.previousDigest = b.previousDigest;
        this.glossary = Collections.unmodifiableList(new ArrayList<String>(b.glossary));
        this.bookLanguage = b.bookLanguage;
        this.wordsPerCall = Math.max(50, b.wordsPerCall);
        this.sectionWords = b.sectionWords > 0 ? b.sectionWords : this.wordsPerCall;
        this.answerTokens = Math.max(0, b.answerTokens);
        this.rewriteComment = b.rewriteComment;
        this.currentText = b.currentText;
    }

    public static Builder builder(String sectionId) {
        return new Builder(sectionId);
    }

    public String getSectionId() {
        return sectionId;
    }

    public String getTitle() {
        return title;
    }

    public String getBrief() {
        return brief;
    }

    public List<String> getKeyPoints() {
        return keyPoints;
    }

    public List<EvidencePassage> getPassages() {
        return passages;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public String getPreviousDigest() {
        return previousDigest;
    }

    public List<String> getGlossary() {
        return glossary;
    }

    public String getBookLanguage() {
        return bookLanguage;
    }

    /** Words the whole section aims for; more than {@link #getWordsPerCall()} splits it by key points. */
    public int getSectionWords() {
        return sectionWords;
    }

    /** The word target of one writer call (writer settings, D8). */
    public int getWordsPerCall() {
        return wordsPerCall;
    }

    /** The answer budget of one writer call in tokens (writer settings, D8). */
    public int getAnswerTokens() {
        return answerTokens;
    }

    /** The user's comment of "Rewrite section" or "Rewrite chapter"; empty for a first draft. */
    public String getRewriteComment() {
        return rewriteComment;
    }

    /** The current text of the part being rewritten; empty for a first draft. Never sent with a continuation. */
    public String getCurrentText() {
        return currentText;
    }

    /** True for "Rewrite section" or "Rewrite chapter" (a comment or a current text is set). */
    public boolean isRewrite() {
        return !rewriteComment.isEmpty() || !currentText.isEmpty();
    }

    /** A copy for one key-point part of the section, with that part's key points, words and digest. */
    SectionAssignment part(List<String> partKeyPoints, int words, String digest) {
        Builder b = new Builder(sectionId);
        b.title = title;
        b.brief = brief;
        b.keyPoints = partKeyPoints;
        b.passages = passages;
        b.synopsis = synopsis;
        b.previousDigest = digest;
        b.glossary = glossary;
        b.bookLanguage = bookLanguage;
        b.wordsPerCall = words;
        b.sectionWords = words;
        b.answerTokens = answerTokens;
        b.rewriteComment = rewriteComment;
        b.currentText = currentText;
        return new SectionAssignment(b);
    }

    public static final class Builder {
        private final String sectionId;
        private String title = "";
        private String brief = "";
        private List<String> keyPoints = new ArrayList<String>();
        private List<EvidencePassage> passages = new ArrayList<EvidencePassage>();
        private String synopsis = "";
        private String previousDigest = "";
        private List<String> glossary = new ArrayList<String>();
        private String bookLanguage = "English";
        private int sectionWords;
        private int wordsPerCall = 450;
        private int answerTokens = 1200;
        private String rewriteComment = "";
        private String currentText = "";

        private Builder(String sectionId) {
            if (sectionId == null || sectionId.trim().isEmpty()) {
                throw new IllegalArgumentException("sectionId must not be blank");
            }
            this.sectionId = sectionId.trim();
        }

        public Builder title(String value) {
            this.title = value == null ? "" : value.trim();
            return this;
        }

        public Builder brief(String value) {
            this.brief = value == null ? "" : value.trim();
            return this;
        }

        public Builder keyPoints(List<String> value) {
            this.keyPoints = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
            return this;
        }

        public Builder passages(List<EvidencePassage> value) {
            this.passages = value == null ? new ArrayList<EvidencePassage>() : new ArrayList<EvidencePassage>(value);
            return this;
        }

        public Builder synopsis(String value) {
            this.synopsis = value == null ? "" : value.trim();
            return this;
        }

        public Builder previousDigest(String value) {
            this.previousDigest = value == null ? "" : value.trim();
            return this;
        }

        public Builder glossary(List<String> value) {
            this.glossary = value == null ? new ArrayList<String>() : new ArrayList<String>(value);
            return this;
        }

        public Builder bookLanguage(String value) {
            this.bookLanguage = value == null || value.trim().isEmpty() ? "English" : value.trim();
            return this;
        }

        public Builder sectionWords(int value) {
            this.sectionWords = value;
            return this;
        }

        public Builder wordsPerCall(int value) {
            this.wordsPerCall = value;
            return this;
        }

        public Builder answerTokens(int value) {
            this.answerTokens = value;
            return this;
        }

        public Builder rewriteComment(String value) {
            this.rewriteComment = value == null ? "" : value.trim();
            return this;
        }

        public Builder currentText(String value) {
            this.currentText = value == null ? "" : value.trim();
            return this;
        }

        public SectionAssignment build() {
            return new SectionAssignment(this);
        }
    }
}

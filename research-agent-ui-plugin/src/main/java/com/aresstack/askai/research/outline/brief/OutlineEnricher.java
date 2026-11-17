package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineCandidate;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Plan PR 24 inside "Generate outline": after the model-free proposal of PR 22, every untitled section and every
 * candidate cluster gets a label call and every chapter a brief call. A failed call leaves the default title or
 * brief in place; the generation never fails because of it. Cancelling stops between calls and keeps what is done.
 */
public final class OutlineEnricher {

    /** The pass's outcome: the enriched outline and the chapters whose brief stayed the default. */
    public static final class Result {
        private final Outline outline;
        private final List<String> briefFailures;

        Result(Outline outline, List<String> briefFailures) {
            this.outline = outline;
            this.briefFailures = briefFailures;
        }

        public Outline getOutline() {
            return outline;
        }

        /** Ids (as generated; may be empty for new chapters) of chapters showing "Brief not generated". */
        public List<String> getBriefFailures() {
            return briefFailures;
        }
    }

    private final OutlineBriefWorker worker;
    private final OutlinePassages passages;

    /**
     * Default number of key terms a fallback title is made of when the label call gives no title. A section title
     * never comes from a passage sentence (live retest of #243: 19 sections kept cut raw sentences).
     */
    public static final int DEFAULT_FALLBACK_TITLE_TERMS = 3;

    private final int fallbackTitleTerms;

    public OutlineEnricher(OutlineBriefWorker worker, OutlinePassages passages) {
        this(worker, passages, DEFAULT_FALLBACK_TITLE_TERMS);
    }

    /** @param fallbackTitleTerms key terms of a title made when the label call fails (setting) */
    public OutlineEnricher(OutlineBriefWorker worker, OutlinePassages passages, int fallbackTitleTerms) {
        if (fallbackTitleTerms < 1) {
            throw new IllegalArgumentException("fallbackTitleTerms must be >= 1");
        }
        this.worker = worker;
        this.passages = passages;
        this.fallbackTitleTerms = fallbackTitleTerms;
    }

    public Result enrich(Outline generated, BriefContext context, BooleanSupplier cancelled) {
        OutlinePassages view = passages.snapshot();
        List<String> failures = new ArrayList<String>();
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        // Per chapter the titles its sections already carry; the book's chapter titles count for every chapter.
        java.util.Map<OutlineChapter, List<String>> takenIn = new java.util.IdentityHashMap<OutlineChapter,
                List<String>>();
        for (OutlineChapter each : generated.getChapters()) {
            List<String> own = new ArrayList<String>();
            for (OutlineSection section : each.getSections()) {
                if (!section.isUntitled()) {
                    own.add(section.getTitle());
                }
            }
            takenIn.put(each, own);
        }
        int chapterNumber = 0;
        for (OutlineChapter chapter : generated.getChapters()) {
            chapterNumber++;
            if (cancelled.getAsBoolean()) {
                chapters.add(chapter);
                continue;
            }
            List<String> taken = takenIn.get(chapter);
            List<String> bookTaken = new ArrayList<String>();
            for (OutlineChapter other : generated.getChapters()) {
                if (other != chapter) {
                    bookTaken.add(other.getTitle());
                    bookTaken.addAll(takenIn.get(other));
                }
            }
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            for (OutlineSection section : chapter.getSections()) {
                OutlineSection labelled = section;
                // A generated section gets its id when the outline is saved; until then the telemetry names it by
                // its place (live run 4: "LABEL  on slot ..." with an empty target).
                String target = section.getId().isEmpty()
                        ? "section " + chapterNumber + "." + (sections.size() + 1) : section.getId();
                if (section.isUntitled() && !section.isUserEdited() && !cancelled.getAsBoolean()) {
                    List<String> ids = section.getProvenance().getPassageIds();
                    OutlineLabel label = worker.label(context, target, chapter.getTitle(),
                            ChapterRegenerator.texts(view, ids.subList(0,
                                    Math.min(LabelContract.MAX_EXCERPTS, ids.size()))), "", taken, bookTaken);
                    if (label != null) {
                        labelled = section.withTitle(label.getTitle()).withUntitled(false);
                        taken.add(label.getTitle());
                    } else {
                        String fallback = KeyTermTitle.of(ChapterRegenerator.texts(view, ids), chapter.getTitle(),
                                fallbackTitleTerms);
                        if (!fallback.isEmpty()) {
                            labelled = section.withTitle(fallback);
                            taken.add(fallback);
                        }
                    }
                }
                sections.add(labelled);
            }
            OutlineChapter withLabels = chapter.withSections(sections);
            if (cancelled.getAsBoolean()) {
                chapters.add(withLabels);
                continue;
            }
            OutlineBriefWorker.ChapterBriefs briefs = worker.brief(context, withLabels, "");
            if (!briefs.isGenerated()) {
                failures.add(chapter.getId());
            }
            chapters.add(briefs.getChapter());
        }
        List<OutlineCandidate> candidates = new ArrayList<OutlineCandidate>();
        for (OutlineCandidate candidate : generated.getCandidates()) {
            OutlineLabel label = cancelled.getAsBoolean() ? null : worker.label(context, candidate.getClusterId(),
                    "", ChapterRegenerator.texts(view, candidate.getRepresentativePassageIds()), "");
            candidates.add(label == null ? candidate : new OutlineCandidate(candidate.getClusterId(),
                    label.getTitle(), candidate.getPassageIds(), candidate.getRepresentativePassageIds(),
                    candidate.getSourceCount()));
        }
        return new Result(generated.withChapters(chapters).withCandidates(candidates), failures);
    }
}

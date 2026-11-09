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

    public OutlineEnricher(OutlineBriefWorker worker, OutlinePassages passages) {
        this.worker = worker;
        this.passages = passages;
    }

    public Result enrich(Outline generated, BriefContext context, BooleanSupplier cancelled) {
        OutlinePassages view = passages.snapshot();
        List<String> failures = new ArrayList<String>();
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        for (OutlineChapter chapter : generated.getChapters()) {
            if (cancelled.getAsBoolean()) {
                chapters.add(chapter);
                continue;
            }
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            for (OutlineSection section : chapter.getSections()) {
                OutlineSection labelled = section;
                if (section.isUntitled() && !section.isUserEdited() && !cancelled.getAsBoolean()) {
                    List<String> ids = section.getProvenance().getPassageIds();
                    OutlineLabel label = worker.label(context, section.getId(), chapter.getTitle(),
                            ChapterRegenerator.texts(view, ids.subList(0,
                                    Math.min(LabelContract.MAX_EXCERPTS, ids.size()))), "");
                    if (label != null) {
                        labelled = section.withTitle(label.getTitle()).withUntitled(false);
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

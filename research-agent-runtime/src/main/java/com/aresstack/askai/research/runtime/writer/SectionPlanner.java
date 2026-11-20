package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plans a section before it is written ({@link SectionPlanCriteria.Mode#SEMANTIC}), without a model call: every
 * passage of the pack goes to the key point it is closest to (sentence-embedding slot), each key point gets the words
 * its passages carry, and consecutive key points are packed into parts of one writer call each. So no two parts see
 * the same passage, a part writes about the key points its passages belong to, and a key point without a passage is an
 * open point before a word is written (writer A/B of 20 Nov 2026: three parts shared one pack, six passages were
 * retold in three or more paragraphs while eight of 22 were never used, and the parts reported seven to nine open
 * points per section). Deterministic for the same vectors.
 */
final class SectionPlanner {

    /** A part may exceed the word target of one call by this share before the section gets a further part. */
    static final double PART_TOLERANCE = 1.1;

    /** The plan of one section. */
    static final class Plan {
        /** Every key point of the section in outline order. */
        final List<String> keyPoints;
        /** The first and the last part that writes each key point; -1 for a key point without a passage. */
        final int[] firstPart;
        final int[] lastPart;
        /** One assignment per writer call, with the pack's passage numbers. */
        final List<SectionAssignment> parts;
        /** For each part, the key point of each of its passages, in the part's passage order. */
        final List<List<String>> passagePoints;
        /** The words the evidence carries, at most the section's target. */
        final int words;

        Plan(List<String> keyPoints, int[] firstPart, int[] lastPart, List<SectionAssignment> parts,
             List<List<String>> passagePoints, int words) {
            this.keyPoints = keyPoints;
            this.firstPart = firstPart;
            this.lastPart = lastPart;
            this.parts = parts;
            this.passagePoints = passagePoints;
            this.words = words;
        }

        /** The key points no passage of the pack belongs to: open points found before writing. */
        List<String> uncovered() {
            List<String> out = new ArrayList<String>();
            for (int k = 0; k < keyPoints.size(); k++) {
                if (firstPart[k] < 0) {
                    out.add(keyPoints.get(k));
                }
            }
            return out;
        }
    }

    /** One key point, or a slice of one that is larger than a call, with its passages and words. */
    private static final class Unit {
        final int keyPoint;
        final List<EvidencePassage> passages;
        final int evidenceWords;
        int words;

        Unit(int keyPoint, List<EvidencePassage> passages) {
            this.keyPoint = keyPoint;
            this.passages = passages;
            int count = 0;
            for (EvidencePassage passage : passages) {
                count += words(passage.getText());
            }
            this.evidenceWords = count;
        }
    }

    private SectionPlanner() {
    }

    /**
     * @return the plan, or {@code null} when the section has fewer than two key points, no passages or no sentence
     *         model: the writer then splits the section as before
     */
    static Plan plan(SectionAssignment a, SentenceSimilarity similarity, Consumer<String> log) {
        List<String> points = a.getKeyPoints();
        List<EvidencePassage> pack = a.getPassages();
        SectionPlanCriteria criteria = a.getPlan();
        if (points.size() < 2 || pack.isEmpty() || similarity == null || !similarity.isAvailable()) {
            return null;
        }
        List<String> texts = new ArrayList<String>();
        for (String point : points) {
            texts.add(a.getTitle().isEmpty() ? point : a.getTitle() + ": " + point);
        }
        for (EvidencePassage passage : pack) {
            texts.add(passage.getText());
        }
        List<float[]> vectors = similarity.vectors(texts);
        if (vectors == null) {
            return null;
        }
        int k = points.size();
        int p = pack.size();
        double[][] sim = new double[k][p];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < p; j++) {
                sim[i][j] = SentenceSimilarity.cosine(vectors.get(i), vectors.get(k + j));
            }
        }
        // 1. every key point first takes its closest passages in turn, so that one broad key point cannot take all
        int[] owner = new int[p];
        java.util.Arrays.fill(owner, -1);
        int rounds = Math.max(1, p / (2 * k));
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < k; i++) {
                int best = -1;
                for (int j = 0; j < p; j++) {
                    if (owner[j] < 0 && sim[i][j] >= criteria.getMinSimilarity()
                            && (best < 0 || sim[i][j] > sim[i][best])) {
                        best = j;
                    }
                }
                if (best >= 0) {
                    owner[best] = i;
                }
            }
        }
        // 2. every other passage goes to the key point it is closest to
        for (int j = 0; j < p; j++) {
            if (owner[j] < 0) {
                int best = 0;
                for (int i = 1; i < k; i++) {
                    if (sim[i][j] > sim[best][j]) {
                        best = i;
                    }
                }
                owner[j] = best;
            }
        }
        // 3. the passages of a key point, the closest first, then each next to the one most like it
        List<Unit> whole = new ArrayList<Unit>();
        long evidence = 0;
        for (int i = 0; i < k; i++) {
            List<Integer> own = new ArrayList<Integer>();
            for (int j = 0; j < p; j++) {
                if (owner[j] == i) {
                    own.add(j);
                }
            }
            List<EvidencePassage> ordered = new ArrayList<EvidencePassage>();
            int last = -1;
            while (!own.isEmpty()) {
                int pick = 0;
                for (int n = 1; n < own.size(); n++) {
                    double candidate = last < 0 ? sim[i][own.get(n)]
                            : SentenceSimilarity.cosine(vectors.get(k + last), vectors.get(k + own.get(n)));
                    double current = last < 0 ? sim[i][own.get(pick)]
                            : SentenceSimilarity.cosine(vectors.get(k + last), vectors.get(k + own.get(pick)));
                    if (candidate > current) {
                        pick = n;
                    }
                }
                last = own.remove(pick);
                ordered.add(pack.get(last));
            }
            Unit unit = new Unit(i, ordered);
            evidence += unit.evidenceWords;
            whole.add(unit);
        }
        // 4. words per key point: what its evidence carries, together at most the section's target
        int target = a.getSectionWords();
        double yield = criteria.getWordsPerEvidenceWord();
        double carried = yield > 0 ? yield * evidence : target;
        double scale = evidence == 0 ? 0 : Math.min(target, carried) / evidence;
        int total = 0;
        for (Unit unit : whole) {
            unit.words = (int) Math.round(unit.evidenceWords * scale);
            total += unit.words;
        }
        // 5. a key point larger than one call is written in slices of its passages
        int per = a.getWordsPerCall();
        List<Unit> units = new ArrayList<Unit>();
        for (Unit unit : whole) {
            if (unit.passages.isEmpty()) {
                continue;
            }
            int slices = Math.min(unit.passages.size(), (int) Math.ceil(unit.words / (per * PART_TOLERANCE)));
            if (slices <= 1) {
                units.add(unit);
                continue;
            }
            for (int s = 0; s < slices; s++) {
                int from = s * unit.passages.size() / slices;
                int to = (s + 1) * unit.passages.size() / slices;
                Unit slice = new Unit(unit.keyPoint, new ArrayList<EvidencePassage>(unit.passages.subList(from, to)));
                slice.words = (int) Math.round(unit.words * (double) slice.evidenceWords
                        / Math.max(1, unit.evidenceWords));
                units.add(slice);
            }
        }
        if (units.isEmpty()) {
            return null;
        }
        // 6. consecutive units share a part; each goes to the part in whose n-th of the words its middle lies
        int count = Math.max(1, Math.min(units.size(), (int) Math.ceil(total / (per * PART_TOLERANCE))));
        List<List<Unit>> grouped = new ArrayList<List<Unit>>();
        for (int n = 0; n < count; n++) {
            grouped.add(new ArrayList<Unit>());
        }
        long before = 0;
        for (Unit unit : units) {
            int part = (int) Math.min(count - 1, (2L * before + unit.words) * count / (2L * Math.max(1, total)));
            before += unit.words;
            grouped.get(part).add(unit);
        }
        List<List<Unit>> filled = new ArrayList<List<Unit>>();
        for (List<Unit> group : grouped) {
            if (!group.isEmpty()) {
                filled.add(group);
            }
        }
        int[] first = new int[k];
        int[] lastPart = new int[k];
        java.util.Arrays.fill(first, -1);
        java.util.Arrays.fill(lastPart, -1);
        List<String> shares = SectionWriter.currentTextShares(a.getCurrentText(), filled.size(),
                Math.max(SectionAssignment.MIN_WORDS_PER_CALL, per));
        List<SectionAssignment> parts = new ArrayList<SectionAssignment>();
        List<List<String>> passagePoints = new ArrayList<List<String>>();
        StringBuilder shape = new StringBuilder();
        for (int n = 0; n < filled.size(); n++) {
            List<String> partPoints = new ArrayList<String>();
            List<EvidencePassage> partPassages = new ArrayList<EvidencePassage>();
            List<String> pointOf = new ArrayList<String>();
            int words = 0;
            for (Unit unit : filled.get(n)) {
                String point = points.get(unit.keyPoint);
                if (!partPoints.contains(point)) {
                    partPoints.add(point);
                }
                for (EvidencePassage passage : unit.passages) {
                    partPassages.add(passage);
                    pointOf.add(point);
                }
                words += unit.words;
                first[unit.keyPoint] = first[unit.keyPoint] < 0 ? n : first[unit.keyPoint];
                lastPart[unit.keyPoint] = n;
            }
            parts.add(a.part(partPoints, partPassages, words, a.getPreviousDigest(), shares.get(n)));
            passagePoints.add(pointOf);
            shape.append(n == 0 ? "" : ", ").append(partPoints.size()).append(" key points/")
                    .append(partPassages.size()).append(" passages/").append(words).append(" words");
        }
        Plan plan = new Plan(Collections.unmodifiableList(new ArrayList<String>(points)), first, lastPart, parts,
                passagePoints, total);
        if (log != null) {
            log.accept("section " + a.getSectionId() + ": plan of " + parts.size() + " parts (" + shape + "); the "
                    + "evidence of " + evidence + " words carries " + total + " of " + target + " words; "
                    + plan.uncovered().size() + " key points without a passage");
        }
        return plan;
    }

    static int words(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }
}

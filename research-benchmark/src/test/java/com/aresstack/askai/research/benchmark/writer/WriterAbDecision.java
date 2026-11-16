package com.aresstack.askai.research.benchmark.writer;

import java.util.List;
import java.util.Map;

/**
 * The decision rule of plan section 5.4: {@code llama3.1:8b} stays the Document preset if it beats
 * {@code gemma4:e2b} in the sample chapter and in at least two of the three sections; {@code gpt-oss:20b} only if it
 * clearly beats both, read as: ranked first in the sample chapter and in every section. Otherwise the preset falls
 * back to {@code gemma4:e2b}.
 */
public final class WriterAbDecision {

    public static final String CHAPTER = "chapter";

    private WriterAbDecision() {
    }

    /**
     * @param rankings item -> models best first; the item {@value #CHAPTER} is the sample chapter, every other item a
     *                 section
     * @return the model {@code phase.draft} is set to
     */
    public static String decide(Map<String, List<String>> rankings) {
        List<String> chapter = rankings.get(CHAPTER);
        if (chapter == null) {
            throw new IllegalArgumentException("the sample chapter has no ranking");
        }
        int sections = rankings.size() - 1;
        if (sections < 1) {
            throw new IllegalArgumentException("no section has a ranking");
        }
        boolean gptOssClearly = true;
        int llamaSectionWins = 0;
        for (Map.Entry<String, List<String>> entry : rankings.entrySet()) {
            List<String> order = entry.getValue();
            if (order.isEmpty() || !WriterAbArm.GPT_OSS.equals(order.get(0))) {
                gptOssClearly = false;
            }
            if (!CHAPTER.equals(entry.getKey()) && beats(order, WriterAbArm.LLAMA, WriterAbArm.GEMMA)) {
                llamaSectionWins++;
            }
        }
        if (gptOssClearly && chapter.contains(WriterAbArm.GPT_OSS)) {
            return WriterAbArm.GPT_OSS;
        }
        if (beats(chapter, WriterAbArm.LLAMA, WriterAbArm.GEMMA) && llamaSectionWins >= 2) {
            return WriterAbArm.LLAMA;
        }
        return WriterAbArm.GEMMA;
    }

    static boolean beats(List<String> order, String a, String b) {
        int ia = order.indexOf(a);
        int ib = order.indexOf(b);
        return ia >= 0 && (ib < 0 || ia < ib);
    }
}

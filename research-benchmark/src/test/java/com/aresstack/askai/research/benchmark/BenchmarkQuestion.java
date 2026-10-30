package com.aresstack.askai.research.benchmark;

import java.util.ArrayList;
import java.util.List;

/**
 * One benchmark question with its fixture corpus and its expected outcome. Loaded from
 * {@code benchmark/questions.json} by {@link BenchmarkQuestionSet}; the field names are the file format.
 *
 * <p>The corpus is invented on purpose (fictional products, places and alloys): a statement in the subject's
 * output can then only be "supported" by a corpus page, never by what the model happens to know.</p>
 */
public final class BenchmarkQuestion {

    /** Stable id, used in reports and in {@code askai.benchmark.questions}. */
    String id;
    /** What the benchmark measures with this question, one line for the report. */
    String purpose;
    /** The research question as a user would ask it. */
    String question;
    /** The query the subject is started with ({@code research: <query>} for the baseline). */
    String query;
    /** The outline sections the research is expected to cover (gap detection is measured against them). */
    List<String> outline = new ArrayList<String>();
    /** The pages of the fixture world; every page lives on one logical host. */
    List<Page> pages = new ArrayList<Page>();
    Expected expected = new Expected();

    public String getId() {
        return id;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getQuestion() {
        return question;
    }

    public String getQuery() {
        return query;
    }

    public List<String> getOutline() {
        return outline;
    }

    public List<Page> getPages() {
        return pages;
    }

    public Expected getExpected() {
        return expected;
    }

    public Page page(String pageId) {
        for (Page page : pages) {
            if (page.id.equals(pageId)) {
                return page;
            }
        }
        return null;
    }

    /** A page of the fixture world. */
    public static final class Page {
        String id;
        /** Logical host key; every distinct key becomes its own loopback server (its own domain family). */
        String host;
        String path;
        String title;
        /** Paragraphs of the page body. */
        List<String> paragraphs = new ArrayList<String>();
        /** Ids of pages this page links to. */
        List<String> links = new ArrayList<String>();

        public String getId() {
            return id;
        }

        public String getHost() {
            return host;
        }

        public String getPath() {
            return path;
        }

        public String getTitle() {
            return title;
        }

        public List<String> getParagraphs() {
            return paragraphs;
        }

        public List<String> getLinks() {
            return links;
        }

        public String text() {
            StringBuilder out = new StringBuilder(title == null ? "" : title);
            for (String paragraph : paragraphs) {
                out.append('\n').append(paragraph);
            }
            return out.toString();
        }
    }

    /** What a good research run produces for this question. */
    public static final class Expected {
        /** Pages a good run accepts as sources. */
        List<String> relevantPages = new ArrayList<String>();
        /** Off-topic or low-quality pages a good run does not accept. */
        List<String> distractorPages = new ArrayList<String>();
        /** The run budget's sufficiency rule for this question. */
        int minimumSources = 3;
        int minimumDistinctHosts = 2;
        /** The stop reason of a good run. */
        String stopReason = "SUFFICIENT_EVIDENCE";
        List<Fact> facts = new ArrayList<Fact>();
        List<Contradiction> contradictions = new ArrayList<Contradiction>();
        List<Gap> gaps = new ArrayList<Gap>();

        public List<String> getRelevantPages() {
            return relevantPages;
        }

        public List<String> getDistractorPages() {
            return distractorPages;
        }

        public int getMinimumSources() {
            return minimumSources;
        }

        public int getMinimumDistinctHosts() {
            return minimumDistinctHosts;
        }

        public String getStopReason() {
            return stopReason;
        }

        public List<Fact> getFacts() {
            return facts;
        }

        public List<Contradiction> getContradictions() {
            return contradictions;
        }

        public List<Gap> getGaps() {
            return gaps;
        }
    }

    /** A fact the accepted sources must contain; covered when ALL keywords occur in one accepted source. */
    public static final class Fact {
        String id;
        List<String> keywords = new ArrayList<String>();
        /** Pages that state the fact. */
        List<String> supportedBy = new ArrayList<String>();

        public String getId() {
            return id;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public List<String> getSupportedBy() {
            return supportedBy;
        }
    }

    /** Two pages that state different values for the same thing; a good run reports the conflict. */
    public static final class Contradiction {
        String id;
        String subject;
        String pageA;
        String valueA;
        String pageB;
        String valueB;

        public String getId() {
            return id;
        }

        public String getSubject() {
            return subject;
        }

        public String getPageA() {
            return pageA;
        }

        public String getValueA() {
            return valueA;
        }

        public String getPageB() {
            return pageB;
        }

        public String getValueB() {
            return valueB;
        }
    }

    /** An outline section no corpus page covers; a good run reports it as open instead of inventing it. */
    public static final class Gap {
        String id;
        String section;
        /** Words that name the gap; detection needs one of them near a gap marker. */
        List<String> keywords = new ArrayList<String>();

        public String getId() {
            return id;
        }

        public String getSection() {
            return section;
        }

        public List<String> getKeywords() {
            return keywords;
        }
    }
}

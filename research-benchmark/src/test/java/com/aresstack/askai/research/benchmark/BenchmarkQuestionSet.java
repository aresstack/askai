package com.aresstack.askai.research.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The versioned question set ({@code benchmark/questions.json} on the test classpath). Loading validates the
 * file completely, so a broken fixture fails the model-free unit test instead of a two-hour benchmark run.
 */
public final class BenchmarkQuestionSet {

    public static final String RESOURCE = "/benchmark/questions.json";

    private final int version;
    private final List<BenchmarkQuestion> questions;

    private BenchmarkQuestionSet(int version, List<BenchmarkQuestion> questions) {
        this.version = version;
        this.questions = Collections.unmodifiableList(questions);
    }

    /** The question set shipped with this module. */
    public static BenchmarkQuestionSet load() {
        InputStream in = BenchmarkQuestionSet.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("question set " + RESOURCE + " is not on the classpath");
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + RESOURCE + ": " + ex.getMessage(), ex);
        }
    }

    static BenchmarkQuestionSet parse(Reader reader) {
        FileFormat file;
        try {
            file = new Gson().fromJson(reader, FileFormat.class);
        } catch (JsonParseException ex) {
            throw new IllegalArgumentException("question set is not valid JSON: " + ex.getMessage(), ex);
        }
        if (file == null || file.questions == null || file.questions.isEmpty()) {
            throw new IllegalArgumentException("question set has no questions");
        }
        List<String> problems = new ArrayList<String>();
        Set<String> ids = new HashSet<String>();
        for (BenchmarkQuestion question : file.questions) {
            validate(question, ids, problems);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("question set is invalid: " + problems);
        }
        return new BenchmarkQuestionSet(file.version, new ArrayList<BenchmarkQuestion>(file.questions));
    }

    public int getVersion() {
        return version;
    }

    public List<BenchmarkQuestion> getQuestions() {
        return questions;
    }

    /** The questions named by id, in set order; an empty selection means all. Unknown ids fail. */
    public List<BenchmarkQuestion> select(List<String> questionIds) {
        if (questionIds.isEmpty()) {
            return questions;
        }
        List<BenchmarkQuestion> out = new ArrayList<BenchmarkQuestion>();
        Set<String> known = new HashSet<String>();
        for (BenchmarkQuestion question : questions) {
            known.add(question.getId());
            if (questionIds.contains(question.getId())) {
                out.add(question);
            }
        }
        for (String id : questionIds) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("unknown benchmark question: " + id + " (known: " + known
                        + ")");
            }
        }
        return out;
    }

    private static void validate(BenchmarkQuestion question, Set<String> ids, List<String> problems) {
        String where = "question " + question.id;
        if (blank(question.id) || !ids.add(question.id)) {
            problems.add("missing or duplicate question id: " + question.id);
        }
        if (blank(question.question) || blank(question.query) || blank(question.purpose)) {
            problems.add(where + ": question, query and purpose are required");
        }
        Set<String> pageIds = new HashSet<String>();
        Set<String> locations = new HashSet<String>();
        Set<String> hosts = new HashSet<String>();
        for (BenchmarkQuestion.Page page : question.pages) {
            if (blank(page.id) || !pageIds.add(page.id)) {
                problems.add(where + ": missing or duplicate page id " + page.id);
            }
            if (blank(page.host) || blank(page.path) || !page.path.startsWith("/") || blank(page.title)
                    || page.paragraphs.isEmpty()) {
                problems.add(where + ": page " + page.id + " needs host, an absolute path, title and text");
            } else if (!locations.add(page.host + page.path)) {
                problems.add(where + ": two pages at " + page.host + page.path);
            }
            hosts.add(page.host);
        }
        for (BenchmarkQuestion.Page page : question.pages) {
            for (String link : page.links) {
                requirePage(where + ", link of " + page.id, link, pageIds, problems);
            }
        }
        BenchmarkQuestion.Expected expected = question.expected;
        if (expected == null) {
            problems.add(where + ": expected outcome is missing");
            return;
        }
        for (String page : expected.relevantPages) {
            requirePage(where + ", relevant", page, pageIds, problems);
            if (expected.distractorPages.contains(page)) {
                problems.add(where + ": page " + page + " is both relevant and a distractor");
            }
        }
        for (String page : expected.distractorPages) {
            requirePage(where + ", distractor", page, pageIds, problems);
        }
        if (expected.relevantPages.size() < expected.minimumSources) {
            problems.add(where + ": fewer relevant pages than the minimum source count");
        }
        if (hosts.size() < expected.minimumDistinctHosts) {
            problems.add(where + ": fewer hosts than the minimum distinct host count");
        }
        for (BenchmarkQuestion.Fact fact : expected.facts) {
            if (blank(fact.id) || fact.keywords.isEmpty() || fact.supportedBy.isEmpty()) {
                problems.add(where + ": fact " + fact.id + " needs keywords and supporting pages");
                continue;
            }
            for (String page : fact.supportedBy) {
                requirePage(where + ", fact " + fact.id, page, pageIds, problems);
                BenchmarkQuestion.Page supporting = question.page(page);
                if (supporting != null && !containsAll(supporting.text(), fact.keywords)) {
                    problems.add(where + ": page " + page + " does not contain all keywords of fact " + fact.id);
                }
            }
        }
        for (BenchmarkQuestion.Contradiction contradiction : expected.contradictions) {
            requirePage(where + ", contradiction " + contradiction.id, contradiction.pageA, pageIds, problems);
            requirePage(where + ", contradiction " + contradiction.id, contradiction.pageB, pageIds, problems);
            BenchmarkQuestion.Page a = question.page(contradiction.pageA);
            BenchmarkQuestion.Page b = question.page(contradiction.pageB);
            if (blank(contradiction.valueA) || blank(contradiction.valueB)
                    || contradiction.valueA.equalsIgnoreCase(contradiction.valueB)) {
                problems.add(where + ": contradiction " + contradiction.id + " needs two different values");
            } else if (a != null && b != null && (!a.text().contains(contradiction.valueA)
                    || !b.text().contains(contradiction.valueB))) {
                problems.add(where + ": contradiction " + contradiction.id + " values are not on their pages");
            }
        }
        for (BenchmarkQuestion.Gap gap : expected.gaps) {
            if (blank(gap.id) || gap.keywords.isEmpty() || !question.outline.contains(gap.section)) {
                problems.add(where + ": gap " + gap.id + " needs keywords and an outline section");
                continue;
            }
            for (BenchmarkQuestion.Page page : question.pages) {
                if (containsAll(page.text(), gap.keywords)) {
                    problems.add(where + ": gap " + gap.id + " is covered by page " + page.id);
                }
            }
        }
    }

    private static void requirePage(String where, String page, Set<String> pageIds, List<String> problems) {
        if (!pageIds.contains(page)) {
            problems.add(where + ": unknown page " + page);
        }
    }

    static boolean containsAll(String text, List<String> keywords) {
        String haystack = text.toLowerCase(java.util.Locale.ROOT);
        for (String keyword : keywords) {
            if (!haystack.contains(keyword.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** The file layout of {@code questions.json}. */
    private static final class FileFormat {
        int version;
        List<BenchmarkQuestion> questions;
    }
}

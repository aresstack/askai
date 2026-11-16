package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.OutlineChapter;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The label contract of plan PR 24: one call per candidate cluster or untitled section returns a title (a short
 * noun phrase in the book language) and a one-sentence summary, from a few representative passages.
 */
final class LabelContract {

    static final int MAX_TITLE_WORDS = 8;
    static final int MAX_TITLE_CHARS = 80;
    static final int MAX_EXCERPT_CHARS = 400;
    static final int MAX_EXCERPTS = 5;
    static final int BUDGET = 120;

    static final String SYSTEM = "You name one group of source passages for a book outline. The title is a short "
            + "noun phrase (at most " + MAX_TITLE_WORDS + " words, no sentence, no final punctuation); the summary "
            + "is one sentence. Answer with the JSON object only.";

    static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"title\",\"summary\"],\"properties\":{"
            + "\"title\":{\"type\":\"string\"},\"summary\":{\"type\":\"string\"}}}";

    private LabelContract() {
    }

    static String prompt(BriefContext context, String chapterTitle, List<String> excerpts, String comment) {
        StringBuilder text = new StringBuilder(context.header());
        if (chapterTitle != null && !chapterTitle.trim().isEmpty()) {
            text.append("Chapter: ").append(chapterTitle.trim())
                    .append(" (the title names this group's part of the chapter, not the chapter itself)\n");
        }
        if (comment != null && !comment.trim().isEmpty()) {
            text.append("Author's request: ").append(comment.trim()).append('\n');
        }
        text.append("Passages:\n");
        int count = 0;
        for (String excerpt : excerpts) {
            if (count == MAX_EXCERPTS) {
                break;
            }
            String value = excerpt == null ? "" : excerpt.replaceAll("\\s+", " ").trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_EXCERPT_CHARS) {
                value = value.substring(0, MAX_EXCERPT_CHARS) + "...";
            }
            count++;
            text.append("- ").append(value).append('\n');
        }
        text.append("Answer: {\"title\":\"...\",\"summary\":\"...\"}");
        return text.toString();
    }

    static OutlineLabel read(String answer) throws BriefAnswerException {
        return read(answer, "");
    }

    /** @param chapterTitle the chapter of the labelled section; the title must not repeat it ("" for none) */
    static OutlineLabel read(String answer, String chapterTitle) throws BriefAnswerException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!parsed.isJsonObject()) {
                throw new BriefAnswerException("the answer is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new BriefAnswerException("the answer is not valid JSON");
        }
        List<String> errors = new ArrayList<String>();
        String title = BriefContract.string(root.get("title")).replaceAll("\\s+", " ");
        String summary = BriefContract.string(root.get("summary"));
        if (title.isEmpty()) {
            errors.add("the title is empty");
        } else {
            if (title.split(" ").length > MAX_TITLE_WORDS || title.length() > MAX_TITLE_CHARS) {
                errors.add("the title must be a short noun phrase of at most " + MAX_TITLE_WORDS + " words");
            }
            char last = title.charAt(title.length() - 1);
            if (last == '.' || last == '!' || last == '?' || last == ':' || last == ';') {
                errors.add("the title must not end with punctuation");
            }
            if (OutlineChapter.repeatsChapterTitle(title, chapterTitle)) {
                errors.add("the title repeats the chapter title; name this group's part of the chapter");
            }
        }
        if (summary.isEmpty()) {
            errors.add("the summary is empty");
        }
        if (!errors.isEmpty()) {
            throw new BriefAnswerException(errors);
        }
        return new OutlineLabel(title, summary);
    }
}

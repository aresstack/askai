package com.aresstack.askai.research.benchmark.writer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The writer's log in the writer A/B: every line goes to a file per run, and the lines of the current sample are
 * counted (top-ups, the words they brought, top-ups by key points without an uncited passage, dropped top-ups,
 * removed repeated sentences, repairs). A writer that loses its sentence vectors ("sentence embedding unavailable")
 * keeps only its lexical checks for the rest of the run without any other sign; {@link #check()} stops the run then,
 * because every later sample would be measured under other conditions.
 */
final class WriterAbLog implements Consumer<String> {

    static final String LOST_VECTORS = "sentence embedding unavailable";

    /** The counts, in report order. */
    static final java.util.List<String> KEYS = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
            "top-ups", "top-up words", "top-ups without new passages", "top-ups dropped", "repeats removed",
            "repairs"));

    private static final Pattern TOP_UP = Pattern.compile("top-up \\d+ for \\d+ missing words \\((\\d+) of \\d+\\), "
            + "(\\d+) uncited passages(, by key points)?");
    private static final Pattern TOPPED_UP = Pattern.compile("topped up to (\\d+) words");
    private static final Pattern REMOVED = Pattern.compile("removed \\d+ label lines and (\\d+) repeated sentences");

    private final File file;
    private final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
    private int lastHave = -1;
    private String lost = "";

    WriterAbLog(File file) {
        this.file = file;
        reset();
    }

    /** Starts the counts of a new sample and writes its heading to the file. */
    synchronized void begin(String heading) {
        reset();
        write("\n## " + heading);
    }

    /** The counts of the sample since {@link #begin}. */
    synchronized Map<String, Integer> counts() {
        return new LinkedHashMap<String, Integer>(counts);
    }

    /** Stops the run when the writer lost its sentence vectors. */
    synchronized void check() {
        if (!lost.isEmpty()) {
            throw new IllegalStateException("the writer lost its sentence vectors, every later sample would run with "
                    + "lexical checks only" + (file == null ? "" : "; see " + file.getAbsolutePath()) + ": " + lost);
        }
    }

    public synchronized void accept(String line) {
        write(line);
        if (line.contains(LOST_VECTORS)) {
            lost = line;
        }
        Matcher topUp = TOP_UP.matcher(line);
        if (topUp.find()) {
            add("top-ups", 1);
            lastHave = Integer.parseInt(topUp.group(1));
            if (topUp.group(3) != null && Integer.parseInt(topUp.group(2)) == 0) {
                add("top-ups without new passages", 1);
            }
        }
        Matcher topped = TOPPED_UP.matcher(line);
        if (topped.find() && lastHave >= 0) {
            add("top-up words", Math.max(0, Integer.parseInt(topped.group(1)) - lastHave));
            lastHave = -1;
        }
        if (line.contains("top-up dropped")) {
            add("top-ups dropped", 1);
        }
        Matcher removed = REMOVED.matcher(line);
        if (removed.find()) {
            add("repeats removed", Integer.parseInt(removed.group(1)));
        }
        if (line.contains(": one repair for ")) {
            add("repairs", 1);
        }
    }

    private void reset() {
        counts.clear();
        for (String key : KEYS) {
            counts.put(key, 0);
        }
        lastHave = -1;
    }

    private void add(String key, int n) {
        counts.put(key, counts.get(key) + n);
    }

    private void write(String line) {
        if (file == null) {
            return;
        }
        try {
            Files.write(file.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the log is a record, never a reason to stop a run
        }
    }
}

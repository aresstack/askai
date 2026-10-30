package com.aresstack.askai.research.benchmark;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The settings of one benchmark run. Every limit, budget and timeout is a setting with a default (ADR driver
 * 5), read from {@code askai.benchmark.*} system properties; the Gradle {@code benchmark} task forwards them
 * from the command line, for example {@code -Daskai.benchmark.models=gemma4:e2b,gpt-oss:20b}.
 */
public final class BenchmarkSettings {

    public static final String PREFIX = "askai.benchmark.";

    /** Base URL of the Ollama the subject's main model runs on. */
    public final String ollamaUrl;
    /** Models to benchmark, in order; empty means the first installed chat model. */
    public final List<String> models;
    /** Runs per question and model; the spread across them is the stability measurement. */
    public final int repetitions;
    /** Upper bound for one research run, from prompt to structured outcome. */
    public final int runTimeoutSeconds;
    /** Whether the resume-after-interruption scenario runs (once per question and model). */
    public final boolean resumeScenario;
    /** Upper bound for the interrupted run to reach its first accepted source before it is cancelled. */
    public final int interruptTimeoutSeconds;
    /**
     * How long a cancelled run may take to report its outcome. A run that does not settle in time fails the
     * resume scenario: continuing next to a still-running run would measure two runs at once.
     */
    public final int cancelSettleSeconds;
    /** Timeout of one model call, written into the inference descriptor the subject receives. */
    public final long inferenceTimeoutMillis;
    /** Question ids to run; empty means the whole set. */
    public final List<String> questionIds;
    /**
     * Subject names to run (e.g. {@code single-agent-baseline,orchestrated+writer}); empty means every subject.
     * Lane G measures each new worker role as its own subject, so a role can be compared alone.
     */
    public final List<String> subjects;
    /** Where the Markdown reports are written (relative paths resolve against the working directory). */
    public final File reportDirectory;
    /** Browser channel of the subject's in-process browser ("chrome", "camoufox", ...). */
    public final String browserChannel;
    /** Top-N selection of the baseline's reranker snapshot (the end-to-end test uses 10). */
    public final int rerankTopN;
    /** Root of the installed local models (the reranker); default is the host's {@code %APPDATA%} location. */
    public final File localModelRoot;
    /** Maximum result blocks the fixture SERP engine returns for one query. */
    public final int serpResults;
    /** Share of a statement's content words a source must contain for the statement to count as supported. */
    public final double claimSupportThreshold;
    /** Statements with fewer content words than this are not counted as claims (status lines, greetings). */
    public final int claimMinimumContentWords;

    private final Map<String, String> effective;

    private BenchmarkSettings(Properties properties, Map<String, String> environment) {
        Map<String, String> used = new LinkedHashMap<String, String>();
        this.ollamaUrl = stripTrailingSlash(string(properties, used, "ollamaUrl", "http://127.0.0.1:11434"));
        this.models = list(string(properties, used, "models", ""));
        this.repetitions = Math.max(1, integer(properties, used, "repetitions", 3));
        this.runTimeoutSeconds = Math.max(10, integer(properties, used, "runTimeoutSeconds", 300));
        this.resumeScenario = Boolean.parseBoolean(string(properties, used, "resume", "true"));
        this.interruptTimeoutSeconds = Math.max(10, integer(properties, used, "interruptTimeoutSeconds", 180));
        this.cancelSettleSeconds = Math.max(1, integer(properties, used, "cancelSettleSeconds", 360));
        this.inferenceTimeoutMillis = Math.max(1000L, longValue(properties, used, "inferenceTimeoutMillis",
                300_000L));
        this.questionIds = list(string(properties, used, "questions", ""));
        this.subjects = list(string(properties, used, "subjects", ""));
        this.reportDirectory = new File(string(properties, used, "reportDir",
                "build/reports/research-benchmark"));
        String channelDefault = environment.get("ASKAI_TEST_BROWSER_CHANNEL");
        this.browserChannel = string(properties, used, "browserChannel",
                channelDefault == null || channelDefault.trim().isEmpty() ? "chrome" : channelDefault.trim());
        this.rerankTopN = Math.max(1, integer(properties, used, "rerankTopN", 10));
        this.localModelRoot = new File(string(properties, used, "localModelRoot",
                LocalRerankerRuntime.defaultModelRoot().getPath()));
        this.serpResults = Math.max(1, integer(properties, used, "serpResults", 8));
        this.claimSupportThreshold = Math.min(1.0, Math.max(0.0,
                doubleValue(properties, used, "claimSupportThreshold", 0.6)));
        this.claimMinimumContentWords = Math.max(1, integer(properties, used, "claimMinimumContentWords", 5));
        this.effective = Collections.unmodifiableMap(used);
    }

    /** The settings of this JVM's system properties and environment. */
    public static BenchmarkSettings fromSystem() {
        return new BenchmarkSettings(System.getProperties(), System.getenv());
    }

    /** Settings from explicit values (unit tests); absent keys take their defaults. */
    public static BenchmarkSettings of(Map<String, String> values) {
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            properties.setProperty(PREFIX + entry.getKey(), entry.getValue());
        }
        return new BenchmarkSettings(properties, Collections.<String, String>emptyMap());
    }

    /** Every setting with the value this run used, for the report header. */
    public Map<String, String> effectiveValues() {
        return effective;
    }

    private static String string(Properties properties, Map<String, String> used, String key, String fallback) {
        String raw = properties.getProperty(PREFIX + key);
        String value = raw == null || raw.trim().isEmpty() ? fallback : raw.trim();
        used.put(key, value);
        return value;
    }

    private static int integer(Properties properties, Map<String, String> used, String key, int fallback) {
        String value = string(properties, used, key, String.valueOf(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PREFIX + key + " is not a whole number: " + value);
        }
    }

    private static long longValue(Properties properties, Map<String, String> used, String key, long fallback) {
        String value = string(properties, used, key, String.valueOf(fallback));
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PREFIX + key + " is not a whole number: " + value);
        }
    }

    private static double doubleValue(Properties properties, Map<String, String> used, String key,
                                      double fallback) {
        String value = string(properties, used, key, String.valueOf(fallback));
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PREFIX + key + " is not a number: " + value);
        }
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<String>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

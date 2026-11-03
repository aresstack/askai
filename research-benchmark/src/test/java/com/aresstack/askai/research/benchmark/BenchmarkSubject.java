package com.aresstack.askai.research.benchmark;

/**
 * A research system under measurement. Today there is one: {@link SingleAgentBaselineSubject}, the single
 * in-process agent as it runs on {@code develop}. The orchestrator of lane F and every new worker role of
 * lane G is added as a further subject and measured against that baseline on the same questions (ADR 0001,
 * section 6: H before F).
 */
public interface BenchmarkSubject {

    /** Short name for the report, e.g. {@code single-agent-baseline}. */
    String name();

    /** One sentence on what the subject is, for the report. */
    String description();

    /**
     * Research {@code question} once inside {@code world} with {@code model} as the main model.
     *
     * @param modelBaseUrl the base URL the subject must send its model calls to (the token meter)
     * @return the observations; a failed run is a record with {@code finished == false}, not an exception
     */
    BenchmarkRunRecord run(BenchmarkQuestion question, FixtureWorld world, String model, String modelBaseUrl,
                           int repetition, BenchmarkSettings settings) throws Exception;

    /**
     * The resume-after-interruption scenario: start the research, interrupt it after the first accepted
     * source, continue it, and record what the continuation did with the work already done.
     */
    BenchmarkRunRecord runInterrupted(BenchmarkQuestion question, FixtureWorld world, String model,
                                      String modelBaseUrl, BenchmarkSettings settings) throws Exception;
}

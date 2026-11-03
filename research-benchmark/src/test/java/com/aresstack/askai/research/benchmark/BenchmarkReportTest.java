package com.aresstack.askai.research.benchmark;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BenchmarkReportTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theReportCarriesEverySectionAndMarksUnmeasuredValues() throws Exception {
        Map<String, String> values = new HashMap<String, String>();
        values.put("reportDir", folder.getRoot().getAbsolutePath());
        values.put("repetitions", "2");
        BenchmarkSettings settings = BenchmarkSettings.of(values);
        BenchmarkQuestion question = BenchmarkQuestionSet.load().select(
                Collections.singletonList("q1-sufficiency")).get(0);
        BenchmarkReport report = new BenchmarkReport(Instant.parse("2026-10-30T03:00:00Z"), settings, 1);
        report.environment("Ollama", "http://127.0.0.1:11434 (version 0.12.6)");
        report.subject(new SingleAgentBaselineSubject(null));
        report.question(question);
        BenchmarkScorer scorer = new BenchmarkScorer(settings);

        BenchmarkRunRecord good = new BenchmarkRunRecord(SingleAgentBaselineSubject.NAME, "gemma4:e2b",
                question.getId(), 1);
        good.finished = true;
        good.stopReason = "SUFFICIENT_EVIDENCE";
        good.wallMillis = 42_000L;
        good.usage = new OllamaTokenMeter.Usage(4, 0, 4000, 400, 9000);
        BenchmarkRunRecord failed = new BenchmarkRunRecord(SingleAgentBaselineSubject.NAME, "gemma4:e2b",
                question.getId(), 2);
        failed.failure = "no structured run outcome within 300 s | timeout";
        report.add(scorer.score(question, good));
        report.add(scorer.score(question, failed));
        BenchmarkRunRecord interrupted = new BenchmarkRunRecord(SingleAgentBaselineSubject.NAME, "gemma4:e2b",
                question.getId(), 0);
        interrupted.resume = new BenchmarkRunRecord.ResumeOutcome(1, true, "SUFFICIENT_EVIDENCE", true, 0, 2,
                61_000L, "continued");
        report.addResume(scorer.score(question, interrupted));

        String md = report.render();
        for (String section : new String[]{"# Research benchmark report", "## Subjects", "## Settings",
                "## Questions", "## Summary per subject, model and question", "## Single runs",
                "## Resume after interruption", "## Method"}) {
            assertTrue("section " + section, md.contains(section));
        }
        assertTrue(md.contains("`single-agent-baseline`"));
        assertTrue("summary groups the repetitions", md.contains("| 2 | 0/2 |"));
        assertTrue("mean tokens over the group", md.contains("2000 / 200"));
        assertTrue("no model call means n/a, not zero", md.contains("| 0 (0) | n/a |"));
        assertTrue("table cells are escaped", md.contains("300 s \\| timeout"));
        assertTrue(md.contains("| `askai.benchmark.repetitions` | 2 |"));
        assertTrue(md.contains("| 1 | yes | SUFFICIENT_EVIDENCE | yes | 0 | 2 | 61.0 | continued |"));

        File file = report.write();
        assertEquals("research-benchmark-20261030-030000.md", file.getName());
        assertEquals(md, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    /** Lane G: the role table appears only for subjects with worker roles. */
    @Test
    public void theRoleTableShowsWhatEachRoleDid() {
        BenchmarkSettings settings = BenchmarkSettings.of(Collections.<String, String>emptyMap());
        BenchmarkQuestion question = BenchmarkQuestionSet.load().select(
                Collections.singletonList("q1-sufficiency")).get(0);
        BenchmarkScorer scorer = new BenchmarkScorer(settings);
        BenchmarkReport report = new BenchmarkReport(Instant.parse("2026-10-30T03:00:00Z"), settings, 1);
        report.add(scorer.score(question, new BenchmarkRunRecord(SingleAgentBaselineSubject.NAME, "m",
                question.getId(), 1)));
        assertFalse(report.render().contains("## Worker roles (lane G)"));

        BenchmarkRunRecord verifier = new BenchmarkRunRecord("orchestrated+source-verifier", "m", question.getId(), 1);
        verifier.roles = new BenchmarkRunRecord.RoleObservation();
        verifier.roles.verifierPackages = 2;
        verifier.roles.verifiedSources = 5;
        verifier.roles.rejectedUrls.add("http://127.0.0.1:1/x");
        report.add(scorer.score(question, verifier));
        String md = report.render();
        assertTrue(md.contains("## Worker roles (lane G)"));
        assertTrue(md, md.contains("| `orchestrated+source-verifier` | m | `q1-sufficiency` | 1 | 0 (0) | 2 (5) | 1 |"));
    }
}

package com.aresstack.askai.research.acceptance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The result of {@link BookIntegrityCheck}: how many records each check examined and every miss. Immutable.
 */
public final class BookIntegrityReport {

    private final Map<IntegrityFinding.Check, Integer> examined;
    private final List<IntegrityFinding> findings;

    BookIntegrityReport(Map<IntegrityFinding.Check, Integer> examined, List<IntegrityFinding> findings) {
        EnumMap<IntegrityFinding.Check, Integer> copy =
                new EnumMap<IntegrityFinding.Check, Integer>(IntegrityFinding.Check.class);
        for (IntegrityFinding.Check check : IntegrityFinding.Check.values()) {
            Integer count = examined.get(check);
            copy.put(check, count == null ? 0 : count);
        }
        this.examined = Collections.unmodifiableMap(copy);
        this.findings = Collections.unmodifiableList(new ArrayList<IntegrityFinding>(findings));
    }

    public boolean passed() {
        return findings.isEmpty();
    }

    public int examined(IntegrityFinding.Check check) {
        return examined.get(check);
    }

    public List<IntegrityFinding> findings() {
        return findings;
    }

    public List<IntegrityFinding> findings(IntegrityFinding.Check check) {
        List<IntegrityFinding> out = new ArrayList<IntegrityFinding>();
        for (IntegrityFinding finding : findings) {
            if (finding.getCheck() == check) {
                out.add(finding);
            }
        }
        return out;
    }

    /** The data-integrity section of {@code docs/first-book-acceptance.md}, as Markdown. */
    public String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("| Check | Examined | Misses |\n|---|---:|---:|\n");
        for (IntegrityFinding.Check check : IntegrityFinding.Check.values()) {
            md.append("| ").append(check.label()).append(" | ").append(examined(check)).append(" | ")
                    .append(findings(check).size()).append(" |\n");
        }
        md.append('\n');
        if (findings.isEmpty()) {
            md.append("Result: **met**, no miss.\n");
        } else {
            md.append("Result: **not met**, ").append(findings.size()).append(" miss(es):\n\n");
            for (IntegrityFinding finding : findings) {
                md.append("- ").append(finding.getCheck().label()).append(", `").append(finding.getSubject())
                        .append("`: ").append(finding.getMessage()).append('\n');
            }
        }
        return md.toString();
    }
}

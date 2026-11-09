package com.aresstack.askai.research.writer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The sources an evidence pack may draw from (plan D4, PR 27): the approved source set of the sources pin plus
 * sources accepted later, minus excluded sources and minus sources hit by the blacklist recorded at
 * {@code SUBMIT_SCOPE}. Entries added to the blacklist after {@code SUBMIT_SCOPE} never remove a source here.
 * Every admitted source carries its content checksum.
 */
public final class EvidenceSourceSet {

    private final Map<String, String> checksums;
    private final Set<String> excluded;
    private final Set<String> recordedBlacklistHits;

    /**
     * @param acceptedChecksums     source id to content checksum of the approved set and of sources accepted later
     * @param excluded              source ids the user excluded
     * @param recordedBlacklistHits source ids hit by the blacklist recorded at {@code SUBMIT_SCOPE}
     */
    public EvidenceSourceSet(Map<String, String> acceptedChecksums, Collection<String> excluded,
                             Collection<String> recordedBlacklistHits) {
        this.checksums = Collections.unmodifiableMap(new HashMap<String, String>(
                acceptedChecksums == null ? Collections.<String, String>emptyMap() : acceptedChecksums));
        this.excluded = copy(excluded);
        this.recordedBlacklistHits = copy(recordedBlacklistHits);
    }

    private static Set<String> copy(Collection<String> values) {
        return Collections.unmodifiableSet(new HashSet<String>(
                values == null ? Collections.<String>emptySet() : values));
    }

    public boolean admits(String sourceId) {
        return sourceId != null && checksums.containsKey(sourceId) && !excluded.contains(sourceId)
                && !recordedBlacklistHits.contains(sourceId);
    }

    /** Every admitted source id, sorted (for example the eligible set of the PR-38 glossary). */
    public java.util.List<String> admittedSourceIds() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String id : checksums.keySet()) {
            if (admits(id)) {
                out.add(id);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** The content checksum of an admitted source; "" when the source is not admitted. */
    public String checksum(String sourceId) {
        if (!admits(sourceId)) {
            return "";
        }
        String checksum = checksums.get(sourceId);
        return checksum == null ? "" : checksum;
    }
}

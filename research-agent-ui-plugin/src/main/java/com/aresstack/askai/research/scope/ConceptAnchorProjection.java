package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.concept.ConceptTopicScanner;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFacet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Zielbild slice 1 — the fence as a PROJECTION of the two artifacts: IN posts come from the
 * EFFECTIVE mindmap (stored concept minus exact blacklist matches), OUT posts stay the draft's
 * EXCLUDED facets. The concept is the positive working space; positive scope facets no longer
 * duplicate its terms.
 * <p>
 * Suppression here is EXACT (case-insensitive card name vs. excluded facet label/id or exclusion
 * string) and subtree-deep — a suppressed card never becomes an IN post, and neither do its
 * children ("Physische Konsistenz darf nachlaufen, funktionale Suppression niemals"). Semantic
 * similarity is deliberately NOT applied — a neighbourhood match must never silently widen the
 * negative boundary (the live drift-guard already showed why).
 */
public final class ConceptAnchorProjection {

    private ConceptAnchorProjection() {
    }

    /**
     * The mindmap's IN posts. Stable ids ({@code anchor-concept-<path-slug>}) keep the derived
     * vector cache warm across checks; the semantic text is the CARD NAME only (v1 — the same
     * rule facet anchors follow: never decisions, weights or negations).
     */
    public static List<ScopeAnchor> anchorsOf(String conceptDocumentJson, ResearchScopeDraft draft) {
        List<ScopeAnchor> anchors = new ArrayList<ScopeAnchor>();
        if (conceptDocumentJson == null || draft == null) {
            return anchors;
        }
        Set<String> suppressed = suppressedNames(draft);
        Set<String> seenIds = new HashSet<String>();
        for (List<String> path : ConceptTopicScanner.collectCardPaths(conceptDocumentJson)) {
            if (isSuppressed(path, suppressed)) {
                continue;
            }
            String pathSlug = slugOf(join(path));
            if (pathSlug.isEmpty() || !seenIds.add(pathSlug)) {
                continue;
            }
            anchors.add(new ScopeAnchor("anchor-concept-" + pathSlug, "concept-" + pathSlug,
                    path.get(path.size() - 1), ScopeAnchor.Membership.IN));
        }
        return anchors;
    }

    /**
     * The anchor id {@link #anchorsOf} gives the card at {@code path} (first-level card: a path of one name), so
     * other records can point at a card by the same id.
     */
    public static String anchorIdOf(List<String> path) {
        return path == null || path.isEmpty() ? "" : "anchor-concept-" + slugOf(join(path));
    }

    /** Exact suppression truth: EXCLUDED facet labels + ids and the plain exclusion strings. */
    private static Set<String> suppressedNames(ResearchScopeDraft draft) {
        Set<String> suppressed = new HashSet<String>();
        for (ScopeFacet facet : draft.excludedFacets()) {
            suppressed.add(normalize(facet.getLabel()));
            suppressed.add(normalize(facet.getFacetId()));
        }
        for (String exclusion : draft.getExclusions()) {
            suppressed.add(normalize(exclusion));
        }
        suppressed.remove("");
        return suppressed;
    }

    /** A path is out as soon as ANY of its cards is suppressed — subtrees follow their parent. */
    private static boolean isSuppressed(List<String> path, Set<String> suppressed) {
        for (String card : path) {
            if (suppressed.contains(normalize(card))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String join(List<String> path) {
        StringBuilder sb = new StringBuilder();
        for (String card : path) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(card);
        }
        return sb.toString();
    }

    /** Stable lowercase-ascii id material from the full path (plugin keeps its own copy). */
    private static String slugOf(String label) {
        String lower = label.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        StringBuilder sb = new StringBuilder();
        boolean gap = false;
        for (int index = 0; index < lower.length() && sb.length() < 80; index++) {
            char character = lower.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                if (gap && sb.length() > 0) {
                    sb.append('-');
                }
                sb.append(character);
                gap = false;
            } else {
                gap = true;
            }
        }
        return sb.toString();
    }
}

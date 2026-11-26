package com.aresstack.askai.research.bookrun;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Architecture (Angelo 27.11.2026): the concept (the mind map) only steers the search of Phase 3. Phase 4, from the
 * basket to the book, has no dependency on it: its input is mission, Weidezaun, basket and writing settings. The
 * packages below may neither import the concept packages nor name the concept's types. A package joins the list as
 * soon as it is free of the concept.
 */
public class WritingWithoutConceptArchitectureTest {

    private static final String ROOT = "src/main/java/com/aresstack/askai/research/";
    /** The packages of Phase 4 that are free of the concept. */
    private static final List<String> PHASE_4 = Arrays.asList("bookrun", "evidence", "planwriter", "sentencecheck",
            "missionreport");
    /** The basket and the fence of the domain: the contract between research and writing. */
    private static final List<String> CONTRACT = Arrays.asList(
            "../research-domain/src/main/java/com/aresstack/askai/research/domain/basket/",
            "../research-domain/src/main/java/com/aresstack/askai/research/domain/scope/ResearchFence.java");
    private static final Pattern FORBIDDEN = Pattern.compile("import\\s+com\\.aresstack\\.askai\\.research\\.concept\\."
            + "|\\b(ConceptCoverage|ConceptAnchor|ConceptOutlineNode|ConceptFrame|ConceptFreezeHook|ConceptPin"
            + "|ConceptStaleMarker|FrozenConcept|ConceptProjection|ConceptIdentity|ConceptBranchService"
            + "|MissionQuestionDrafter)\\b");

    @Test
    public void phaseFourKnowsNoConcept() throws IOException {
        List<String> found = new ArrayList<String>();
        for (String name : PHASE_4) {
            scan(new File(ROOT + name), found);
        }
        for (String path : CONTRACT) {
            scan(new File(path), found);
        }
        assertTrue("Phase 4 depends on the concept:\n" + String.join("\n", found), found.isEmpty());
    }

    @Test
    public void theBookRunTakesNoConceptAsInput() throws IOException {
        String source = new String(Files.readAllBytes(new File(ROOT + "bookrun/BookRun.java").toPath()),
                StandardCharsets.UTF_8);
        assertTrue("no card, no concept in the run", !source.contains("ofCard(")
                && !source.contains("Origin.KONZEPT") && !source.contains("getCards()"));
    }

    private static void scan(File file, List<String> found) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            assertTrue("cannot list " + file, children != null);
            for (File child : children) {
                scan(child, found);
            }
            return;
        }
        assertTrue("missing " + file.getPath(), file.isFile());
        if (!file.getName().endsWith(".java")) {
            return;
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = FORBIDDEN.matcher(lines.get(i));
            if (matcher.find()) {
                found.add(file.getPath() + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
    }
}

package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The normalizer quotes clearly-repairable unquoted flowchart rectangle labels and leaves every other
 * Mermaid construct byte-for-byte unchanged. It is the reproduction guard for mermaid-js issue #7590
 * (LLM-generated unquoted special characters) against the embedded Mermaid 11.13.0 grammar.
 */
public class MermaidRenderingSourceNormalizerTest {

    private final MermaidRenderingSourceNormalizer normalizer = new MermaidRenderingSourceNormalizer();

    private static final String REPRO =
            "graph TD\n"
            + "    A[Tierreich (Animalia)] --> B[Wirbellose (Invertebrata)]\n"
            + "    A --> C[Wirbeltiere (Vertebrata)]\n"
            + "    C --> D[Fische]\n"
            + "    C --> E[Amphibien]\n"
            + "    C --> F[Reptilien]\n"
            + "    C --> G[Vögel]\n"
            + "    C --> H[Säugetiere]\n"
            + "\n"
            + "    B --> B1[Insekten]\n"
            + "    B --> B2[Würmer/Gliederfüßer]\n"
            + "    B --> B3[Krebse/Garnelen]\n"
            + "    B --> B4[Schildpferse]";

    // --- success cases: repairable rectangle labels get quoted ---

    @Test
    public void parenthesesInARectangleLabelAreQuoted() {
        assertEquals("graph TD\n    A[\"Tierreich (Animalia)\"]",
                normalizer.normalize("graph TD\n    A[Tierreich (Animalia)]"));
    }

    @Test
    public void anotherParenthesisedLabelIsQuoted() {
        assertEquals("graph TD\n    A[\"Wirbellose (Invertebrata)\"]",
                normalizer.normalize("graph TD\n    A[Wirbellose (Invertebrata)]"));
    }

    @Test
    public void slashesInARectangleLabelAreQuoted() {
        assertEquals("graph TD\n    B2[\"Würmer/Gliederfüßer\"]",
                normalizer.normalize("graph TD\n    B2[Würmer/Gliederfüßer]"));
    }

    @Test
    public void aPlainRectangleLabelIsLeftUntouched() {
        String source = "graph TD\n    H[Säugetiere]";
        assertSame("no special characters -> nothing to fix", source, normalizer.normalize(source));
    }

    @Test
    public void theReproducingGraphIsFullyNormalizedForRendering() {
        String out = normalizer.normalize(REPRO);
        assertTrue(out.contains("A[\"Tierreich (Animalia)\"] --> B[\"Wirbellose (Invertebrata)\"]"));
        assertTrue(out.contains("C[\"Wirbeltiere (Vertebrata)\"]"));
        assertTrue(out.contains("B2[\"Würmer/Gliederfüßer\"]"));
        assertTrue(out.contains("B3[\"Krebse/Garnelen\"]"));
        assertTrue("plain labels stay unquoted", out.contains("C --> D[Fische]"));
        assertTrue("plain labels stay unquoted", out.contains("H[Säugetiere]"));
        assertFalse("no unquoted parenthesised label remains", out.contains("A[Tierreich (Animalia)]"));
    }

    // --- already-valid syntax must not change (assertSame proves the original reference is returned) ---

    @Test
    public void alreadyQuotedLabelsAreNotDoubleQuoted() {
        String source = "graph TD\n    A[\"Tierreich (Animalia)\"]";
        assertSame(source, normalizer.normalize(source));
    }

    @Test
    public void subroutineNodesAreUntouched() {
        String source = "graph TD\n    A[[Subroutine]]";
        assertSame(source, normalizer.normalize(source));
    }

    @Test
    public void databaseCylinderNodesAreUntouched() {
        String source = "graph TD\n    A[(Database)]";
        assertSame(source, normalizer.normalize(source));
    }

    @Test
    public void circleNodesAreUntouched() {
        String source = "graph TD\n    A((Circle))";
        assertSame(source, normalizer.normalize(source));
    }

    @Test
    public void decisionNodesAreUntouched() {
        String source = "graph TD\n    A{Decision}";
        assertSame(source, normalizer.normalize(source));
    }

    @Test
    public void hexagonAsymmetricAndParallelogramNodesAreUntouched() {
        assertSame("graph TD\n    A{{Hexagon}}", normalizer.normalize("graph TD\n    A{{Hexagon}}"));
        assertSame("graph TD\n    A>Asymmetric]", normalizer.normalize("graph TD\n    A>Asymmetric]"));
        assertSame("graph TD\n    A[/Parallelogram/]", normalizer.normalize("graph TD\n    A[/Parallelogram/]"));
        assertSame("graph TD\n    A[\\Alternative\\]", normalizer.normalize("graph TD\n    A[\\Alternative\\]"));
    }

    // --- stability ---

    @Test
    public void nullIsReturnedAsNull() {
        assertNull(normalizer.normalize(null));
    }

    @Test
    public void emptyOrBlankSourceIsReturnedUnchanged() {
        assertSame("", normalizer.normalize(""));
        String blank = "   \n  ";
        assertSame(blank, normalizer.normalize(blank));
    }

    @Test
    public void multipleNodesOnOneLineAreAllQuoted() {
        assertEquals("graph TD\n    A[\"Tierreich (Animalia)\"] --> B[\"Wirbellose (Invertebrata)\"]",
                normalizer.normalize(
                        "graph TD\n    A[Tierreich (Animalia)] --> B[Wirbellose (Invertebrata)]"));
    }

    @Test
    public void nodeIdsWithDigitsUnderscoresAndHyphensAreHandled() {
        assertEquals("graph TD\n    node_1[\"foo (x)\"] --> node-2[\"bar/baz\"]",
                normalizer.normalize("graph TD\n    node_1[foo (x)] --> node-2[bar/baz]"));
    }

    @Test
    public void normalizationIsIdempotent() {
        String once = normalizer.normalize(REPRO);
        assertEquals(once, normalizer.normalize(once));
    }

    @Test
    public void nonFlowchartDiagramsAreLeftUntouched() {
        String sequence = "sequenceDiagram\n    Alice->>Bob: hello (world)";
        assertSame(sequence, normalizer.normalize(sequence));
        String classDiagram = "classDiagram\n    class Foo(bar)";
        assertSame(classDiagram, normalizer.normalize(classDiagram));
    }

    @Test
    public void commentLinesAreNeverRewritten() {
        String source = "graph TD\n    %% keep A[foo (x)] as documentation\n    A[foo (x)]";
        String out = normalizer.normalize(source);
        assertTrue("the comment stays verbatim", out.contains("%% keep A[foo (x)] as documentation"));
        assertTrue("the real node is quoted", out.contains("    A[\"foo (x)\"]"));
    }

    @Test
    public void flowchartKeywordVariantIsRecognised() {
        assertEquals("flowchart LR\n    A[\"foo (x)\"]",
                normalizer.normalize("flowchart LR\n    A[foo (x)]"));
    }
}

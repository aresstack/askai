package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.store.FileManuscriptStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** Live run 4, book 6: the writer's repeat check reads what the other written sections of the book say. */
public class ProductiveSectionContextsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static OutlineSection section(String id) {
        return new OutlineSection(id, "ch-1", 0, "Section " + id, new OutlineBrief("on " + id,
                Collections.<String>emptyList(), 200), OutlineProvenance.NONE, false, "");
    }

    @Test
    public void writtenSectionsAreTheOtherSectionsThatStayInTheBook() {
        FileManuscriptStore store = new FileManuscriptStore(new File(folder.getRoot(), "manuscript"));
        store.save("s1", "ch-1", 0, SectionRevision.builder().text("Erster Abschnitt [1]."), 1L);
        store.save("s2", "ch-1", 0, SectionRevision.builder().text("Eigener alter Text [1]."), 1L);
        store.save("s3", "ch-1", 0, SectionRevision.builder().text("Übersprungen [1]."), 1L);
        store.mark("s3", "ch-1", SectionStatus.SKIPPED, "", 2L);
        store.save("s4", "ch-1", 0, SectionRevision.builder().text("Veraltet, aber im Buch [1]."), 1L);
        store.mark("s4", "ch-1", SectionStatus.STALE, "outline changed", 2L);
        store.mark("s5", "ch-1", SectionStatus.FAILED, "no answer", 2L);
        ProductiveSectionContexts contexts = new ProductiveSectionContexts("proj", "fp", null, null, store, null);
        OutlineSection own = section("s2");
        OutlineChapter chapter = new OutlineChapter("ch-1", 0, "Chapter", OutlineBrief.EMPTY, OutlineProvenance.NONE,
                false, "", Arrays.asList(section("s1"), own, section("s3"), section("s4"), section("s5")));
        assertEquals(Arrays.asList("Erster Abschnitt [1].", "Veraltet, aber im Buch [1]."),
                contexts.writtenSections(SectionWriteRequest.draft(chapter, own)));
    }
}

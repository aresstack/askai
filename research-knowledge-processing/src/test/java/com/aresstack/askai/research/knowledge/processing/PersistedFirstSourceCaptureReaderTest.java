package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A re-read page refreshes its source record in place, so the source's full text is the NEWEST capture's text. An
 * older capture that must be derived again (re-embedding after a model switch, a retry after a restart) has to come
 * from the knowledge project's persisted original, never be rebuilt from the refreshed source record.
 */
public class PersistedFirstSourceCaptureReaderTest {

    private static SourceCapture capture(String captureId, String text, String checksum) {
        return new SourceCapture(captureId, "source-61", "https://x/page", 1000L, checksum, "T", "",
                Collections.singletonList(new SourceCapture.StructuralBlock("b1",
                        SourceCapture.BlockKind.PARAGRAPH, "", text)));
    }

    /** Stands in for the capture store + durable source record, which now hold the refreshed text. */
    private static final class RefreshedSource implements SourceCaptureReader {
        int reads;

        public SourceCapture read(String captureId) {
            reads++;
            return "unknown".equals(captureId) ? null : capture(captureId, "Tuesday edition.", "h-new");
        }
    }

    @Test
    public void anAlreadyPersistedCaptureIsReadAsTheImmutableOriginal() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("askai-persisted-first").toFile();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        ResearchProject project = new ResearchProject("p1", IdSequence.counting());
        project.recordSourceCapture(capture("cap-34", "Monday edition.", "h-old"));
        project.recordSentences(Arrays.asList(new Sentence("cap-34#s0", "cap-34", "b1", 0, "Monday edition.")));
        project.recordPassages(Arrays.asList(new Passage("cap-34#p0", "cap-34", Arrays.asList("cap-34#s0"), "",
                "Monday edition.", "fpA", "seg-v1")));
        repo.save(project);

        RefreshedSource fallback = new RefreshedSource();
        PersistedFirstSourceCaptureReader reader = new PersistedFirstSourceCaptureReader(repo, fallback);

        SourceCapture original = reader.read("cap-34", "source-61");
        assertEquals("h-old", original.getChecksum());
        assertEquals("Monday edition.", original.getBlocks().get(0).getText());
        assertEquals("the refreshed source record is not asked", 0, fallback.reads);

        assertEquals("a capture the project never stored comes from the delegate", "h-new",
                reader.read("cap-8", "source-61").getChecksum());
        assertNull(reader.read("unknown", ""));
    }
}

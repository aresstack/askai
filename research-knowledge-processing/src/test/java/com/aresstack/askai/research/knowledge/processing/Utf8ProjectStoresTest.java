package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjection;
import com.aresstack.askai.research.knowledge.live.LiveOutlineSection;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;
import com.aresstack.askai.research.knowledge.processing.live.FileLiveOutlineProjectionStore;
import com.aresstack.askai.research.knowledge.processing.live.FileTopicSnapshotStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

/** PR 02: non-ASCII text survives three read-modify-write cycles in every knowledge-processing store. */
public class Utf8ProjectStoresTest {

    /** German umlauts, sharp s, typographic quotes, en dash and an emoji (a surrogate pair). */
    public static final String TEXT = "Grüße „Zitat“ – ß 😀";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void helperReadsUtf8BytesNotIso88591() throws Exception {
        File f = folder.newFile("x.properties");
        Files.write(f.toPath(), ("title=" + TEXT + "\n").getBytes(StandardCharsets.UTF_8));
        Properties p = Utf8Properties.load(f);
        assertEquals(TEXT, p.getProperty("title"));
    }

    @Test
    public void projectRepositoryKeepsText() throws Exception {
        File dir = folder.newFolder("rproj");
        for (int cycle = 1; cycle <= 3; cycle++) {
            String previous = cycle == 1 ? ""
                    : new FileResearchProjectRepository(dir).load("p1").sentences().get("cap-1#s0").getText();
            new FileResearchProjectRepository(dir).save(project(previous + TEXT + cycle));
        }
        ResearchProject back = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(TEXT + "1" + TEXT + "2" + TEXT + "3", back.sentences().get("cap-1#s0").getText());
        assertEquals(TEXT, back.captures().get("cap-1").getTitle());
        assertEquals(TEXT, back.captures().get("cap-1").getBlocks().get(0).getText());
    }

    private static ResearchProject project(String sentence) {
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, TEXT, TEXT);
        ResearchProject p = new ResearchProject("p1", IdSequence.counting());
        p.recordSourceCapture(new SourceCapture("cap-1", "src-1", "https://example.org/a", 7L, "h1", TEXT, TEXT,
                Collections.singletonList(b)));
        p.recordSentences(Arrays.asList(new Sentence("cap-1#s0", "cap-1", "b1", 0, sentence)));
        p.recordPassages(Arrays.asList(new Passage("cap-1#p0@seg-fp", "cap-1",
                Arrays.asList("cap-1#s0"), TEXT, sentence, "fp", "seg")));
        return p;
    }

    @Test
    public void topicSnapshotKeepsText() throws Exception {
        File dir = folder.newFolder("snap");
        String title = "";
        for (int cycle = 1; cycle <= 3; cycle++) {
            FileTopicSnapshotStore.TopicSnapshot previous = new FileTopicSnapshotStore(dir).load();
            title = (previous == null ? "" : previous.topics.get(0).getTitle()) + TEXT;
            new FileTopicSnapshotStore(dir).save(new FileTopicSnapshotStore.TopicSnapshot(cycle, "c", "fp", 1L,
                    Collections.singletonList(topic(title))));
        }
        assertEquals(TEXT + TEXT + TEXT, new FileTopicSnapshotStore(dir).load().topics.get(0).getTitle());
    }

    @Test
    public void liveOutlineKeepsText() throws Exception {
        File dir = folder.newFolder("outline");
        for (int cycle = 1; cycle <= 3; cycle++) {
            LiveOutlineProjection previous = new FileLiveOutlineProjectionStore(dir).load();
            String title = (previous == null ? "" : previous.getSections().get(0).getTitle()) + TEXT;
            LiveTopicProjection topic = topic(title);
            LiveOutlineSection section = new LiveOutlineSection("sec-1", title, "",
                    Collections.singletonList(topic.getClusterId()), Arrays.asList("cap-1#p0"),
                    Arrays.asList(title + "?"));
            new FileLiveOutlineProjectionStore(dir).save(new LiveOutlineProjection(cycle,
                    LiveOutlineProjection.corpusFingerprintOf(Arrays.asList("cap-1#p0")), "fp", 1L,
                    Collections.singletonList(topic), Collections.singletonList(section)));
        }
        LiveOutlineProjection back = new FileLiveOutlineProjectionStore(dir).load();
        assertEquals(TEXT + TEXT + TEXT, back.getSections().get(0).getTitle());
        assertEquals(TEXT + TEXT + TEXT, back.getTopics().get(0).getTitle());
        assertEquals(TEXT + TEXT + TEXT + "?", back.getSections().get(0).getUncoveredQuestions().get(0));
    }

    private static LiveTopicProjection topic(String title) {
        return new LiveTopicProjection(Arrays.asList("cap-1#p0"), Collections.singletonList("cap-1#p0"), title, 0.5);
    }
}

package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Eligibility = EMBEDDING capability + RUNNABLE, resolving ONLY the requested id; never picks/guesses. */
public class LocalEmbeddingModelCatalogTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static File root() throws IOException {
        return Files.createTempDirectory("askai-emb-catalog").toFile();
    }

    private static void model(File root, String dir, String manifestJson) throws IOException {
        File d = new File(root, dir);
        d.mkdirs();
        Files.write(new File(d, "askai-local-model.json").toPath(), manifestJson.getBytes(UTF8));
    }

    @Test
    public void resolvesAnEmbeddingRunnableModelWithItsRevision() throws Exception {
        File root = root();
        model(root, "e5", "{\"virtualName\":\"local/e5:latest\",\"capabilities\":[\"embedding\",\"chat\"],"
                + "\"state\":\"RUNNABLE\",\"resolvedRevision\":\"rev-abc\"}");
        EmbeddingModelResolver.ResolvedEmbeddingModel r =
                LocalEmbeddingModelCatalog.resolveIn(root, "local/e5:latest");
        assertEquals("local/e5:latest", r.virtualModelId);
        assertEquals("rev-abc", r.resolvedRevision);
    }

    @Test
    public void rejectsAModelWithoutEmbeddingCapability() throws Exception {
        File root = root();
        model(root, "chat", "{\"virtualName\":\"local/chat:latest\",\"capabilities\":[\"chat\",\"rerank\"],"
                + "\"state\":\"RUNNABLE\",\"resolvedRevision\":\"rev\"}");
        assertReason(EmbeddingConfigurationException.Reason.MODEL_NOT_EMBEDDING_CAPABLE,
                root, "local/chat:latest");
    }

    @Test
    public void rejectsANotRunnableEmbeddingModel() throws Exception {
        File root = root();
        model(root, "e5", "{\"virtualName\":\"local/e5:latest\",\"capabilities\":[\"embedding\"],"
                + "\"state\":\"INSTALLING\",\"resolvedRevision\":\"rev\"}");
        assertReason(EmbeddingConfigurationException.Reason.MODEL_NOT_RUNNABLE, root, "local/e5:latest");
    }

    @Test
    public void rejectsAnUnknownModelId() throws Exception {
        File root = root();
        model(root, "e5", "{\"virtualName\":\"local/e5:latest\",\"capabilities\":[\"embedding\"],"
                + "\"state\":\"RUNNABLE\",\"resolvedRevision\":\"rev\"}");
        assertReason(EmbeddingConfigurationException.Reason.MODEL_NOT_FOUND, root, "local/other:latest");
    }

    @Test
    public void aCorruptManifestIsNotAUsableModel() throws Exception {
        File root = root();
        model(root, "broken", "{ this is not json ");
        assertReason(EmbeddingConfigurationException.Reason.MODEL_NOT_FOUND, root, "local/e5:latest");
    }

    private static void assertReason(EmbeddingConfigurationException.Reason expected, File root, String id) {
        try {
            LocalEmbeddingModelCatalog.resolveIn(root, id);
            fail("expected " + expected);
        } catch (EmbeddingConfigurationException e) {
            assertEquals(expected, e.getReason());
        }
    }
}

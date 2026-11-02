package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationValidationResult;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A5b: the host publishes an atomic, self-validated reranker start snapshot; a reader always sees a
 * complete, strictly-valid document and never a {@code .tmp} leftover.
 */
public class LocalRerankerConfigurationSnapshotWriterTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private RerankerConfigurationDocument document() {
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL, "http://127.0.0.1:51877",
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest",
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT, 15_000L,
                RerankerSelectionConfiguration.topN(10));
        return RerankerConfigurationDocument.current(3L, descriptor);
    }

    @Test
    public void writesAValidSnapshotThatDecodesBack() throws Exception {
        File target = new File(folder.newFolder("cfg"), "reranker.json");
        LocalRerankerConfigurationSnapshotWriter.write(document(), target);

        assertTrue(target.isFile());
        assertFalse("no temp file left behind", new File(target.getParentFile(),
                target.getName() + ".tmp").exists());
        String json = new String(Files.readAllBytes(target.toPath()), Charset.forName("UTF-8"));
        RerankerConfigurationValidationResult decoded =
                RerankerEndpointDescriptorCodec.parse(json);
        assertTrue(decoded.describe(), decoded.valid);
        assertEquals("http://127.0.0.1:51877", decoded.document.descriptor.baseUrl);
        assertEquals(3L, decoded.document.configurationRevision);
    }

    @Test
    public void overwritesAnExistingSnapshotAtomically() throws Exception {
        File target = new File(folder.newFolder("cfg2"), "reranker.json");
        Files.write(target.toPath(), "stale".getBytes(Charset.forName("UTF-8")));
        LocalRerankerConfigurationSnapshotWriter.write(document(), target);

        String json = new String(Files.readAllBytes(target.toPath()), Charset.forName("UTF-8"));
        assertTrue(RerankerEndpointDescriptorCodec.parse(json).valid);
    }

    @Test
    public void createsMissingParentDirectories() throws Exception {
        File target = new File(folder.getRoot(), "nested/deeper/reranker.json");
        LocalRerankerConfigurationSnapshotWriter.write(document(), target);
        assertTrue(target.isFile());
    }
}

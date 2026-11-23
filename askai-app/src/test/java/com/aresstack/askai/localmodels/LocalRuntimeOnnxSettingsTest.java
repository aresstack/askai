package com.aresstack.askai.localmodels;

import com.aresstack.askai.localruntime.OnnxManifests;
import com.aresstack.askai.localruntime.onnx.OnnxModelCatalog;
import com.aresstack.askai.localruntime.onnx.OnnxModelDescriptor;
import com.aresstack.askai.localruntime.onnx.OnnxRuntimeOptions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class LocalRuntimeOnnxSettingsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void absentFileGivesDefaultsAndSavedValuesRoundTrip() throws Exception {
        LocalRuntimeSettingsRepository repository = new LocalRuntimeSettingsRepository(
                new File(temp.getRoot(), "local-runtime.properties"));
        LocalRuntimeSettings defaults = repository.load();
        assertEquals(OnnxRuntimeOptions.ExecutionProvider.CPU, defaults.getOnnx().executionProvider());
        assertEquals(LocalRuntimeSettings.DEFAULT_SENTENCE_EMBEDDING_MODEL, defaults.getSentenceEmbeddingModel());

        repository.save(new LocalRuntimeSettings(OnnxRuntimeOptions.builder().intraOpThreads(6).preferQuantized(false)
                .batchSize(8).maxTokens(1024).executionProvider(OnnxRuntimeOptions.ExecutionProvider.DIRECTML)
                .build(), "local/BAAI/bge-m3:latest"));
        LocalRuntimeSettings loaded = new LocalRuntimeSettingsRepository(repository.getFile()).load();
        assertEquals(6, loaded.getOnnx().intraOpThreads());
        assertFalse(loaded.getOnnx().preferQuantized());
        assertEquals(8, loaded.getOnnx().batchSize());
        assertEquals(1024, loaded.getOnnx().maxTokens());
        assertEquals(OnnxRuntimeOptions.ExecutionProvider.DIRECTML, loaded.getOnnx().executionProvider());
        assertEquals("local/BAAI/bge-m3:latest", loaded.getSentenceEmbeddingModel());
    }

    @Test
    public void sentenceSlotFallsBackToTheMainEmbeddingUntilItsModelIsInstalled() throws Exception {
        File root = temp.newFolder("models");
        LocalRuntimeSettingsRepository repository = new LocalRuntimeSettingsRepository(
                new File(temp.getRoot(), "local-runtime.properties"));
        SentenceEmbeddingModelResolver resolver = new SentenceEmbeddingModelResolver(repository, root);
        assertEquals("bge-m3", resolver.resolve("bge-m3"));

        repository.save(new LocalRuntimeSettings(OnnxRuntimeOptions.defaults(), "local/BAAI/bge-m3:latest"));
        assertEquals("bge-m3", resolver.resolve("bge-m3"));
        install(root, OnnxModelCatalog.findByRepositoryId("BAAI/bge-m3"));
        assertEquals("local/BAAI/bge-m3:latest", resolver.resolve("bge-m3"));

        repository.save(new LocalRuntimeSettings(OnnxRuntimeOptions.defaults(), "mxbai-embed-large"));
        assertEquals("mxbai-embed-large", resolver.resolve("bge-m3"));
        repository.save(new LocalRuntimeSettings(OnnxRuntimeOptions.defaults(), ""));
        assertEquals("bge-m3", resolver.resolve("bge-m3"));
    }

    @Test
    public void hostReaderAcceptsAnInstalledOnnxModel() throws Exception {
        File root = temp.newFolder("models2");
        install(root, OnnxModelCatalog.findByRepositoryId("Xenova/bge-m3"));
        assertNotNull(LocalInstalledModels.readByVirtualName(root, "local/Xenova/bge-m3:latest"));
    }

    private static void install(File root, OnnxModelDescriptor descriptor) throws Exception {
        File dir = new File(root, descriptor.directoryName());
        dir.mkdirs();
        Files.write(new File(dir, "model.onnx").toPath(), new byte[]{1});
        Files.write(new File(dir, "tokenizer.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "askai-local-model.json").toPath(), LocalModelManifestCodec.toJson(
                OnnxManifests.forInstall(descriptor, "cpu", "rev", 1L)).getBytes(StandardCharsets.UTF_8));
    }
}

package com.aresstack.askai.research.benchmark;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LocalRerankerRuntimeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void picksTheFirstRunnableModelThatPublishesTheRerankCapability() throws Exception {
        File root = folder.getRoot();
        manifest(root, "a-embed", "{\"state\":\"RUNNABLE\",\"virtualName\":\"embed\",\"capabilities\":[\"embed\"]}");
        manifest(root, "b-broken", "{\"state\":\"RUNNABLE\",\"virtualName\":\"broken\",\"capabilities\":[\"rerank\"");
        manifest(root, "c-pending", "{\"state\":\"DOWNLOADING\",\"virtualName\":\"pending\","
                + "\"capabilities\":[\"rerank\"]}");
        assertNull(LocalRerankerRuntime.rerankModelOrNull(root));
        manifest(root, "d-rerank", "{\"state\":\"RUNNABLE\",\"virtualName\":\"bge-reranker\","
                + "\"capabilities\":[\"embed\",\"rerank\"]}");
        assertEquals("bge-reranker", LocalRerankerRuntime.rerankModelOrNull(root));
        assertNull(LocalRerankerRuntime.rerankModelOrNull(new File(root, "missing")));
    }

    private static void manifest(File root, String directory, String json) throws Exception {
        File dir = new File(root, directory);
        dir.mkdirs();
        Files.write(new File(dir, "askai-local-model.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }
}

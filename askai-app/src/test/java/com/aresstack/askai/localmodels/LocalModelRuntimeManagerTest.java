package com.aresstack.askai.localmodels;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The restart-on-boot signal of the local runtime manager. */
public class LocalModelRuntimeManagerTest {

    @Test
    public void hasInstalledModelsLooksForManifestDirectories() throws Exception {
        File root = Files.createTempDirectory("askai-local-mgr").toFile();
        LocalModelRuntimeManager manager = new LocalModelRuntimeManager(root);
        assertFalse(manager.hasInstalledModels());

        File model = new File(root, "cross-encoder-ms-marco-MiniLM-L-6-v2");
        assertTrue(model.mkdirs());
        assertFalse("a directory without manifest is not an installed model",
                manager.hasInstalledModels());
        Files.write(new File(model, "askai-local-model.json").toPath(),
                "{\"state\":\"RUNNABLE\"}".getBytes(Charset.forName("UTF-8")));
        assertTrue(manager.hasInstalledModels());
    }
}

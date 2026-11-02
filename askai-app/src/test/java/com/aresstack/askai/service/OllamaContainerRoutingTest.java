package com.aresstack.askai.service;

import com.aresstack.askai.localmodels.LocalModelNames;
import com.aresstack.askai.localmodels.LocalModelRuntimeManager;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ownership routing of the virtual container: the {@code local/} namespace belongs EXCLUSIVELY to
 * the local runtime, everything else to the remote server — no overlap, so an operation can never
 * be sent to the wrong container.
 */
public class OllamaContainerRoutingTest {

    private final LocalAskAiContainerSource local = new LocalAskAiContainerSource(
            new LocalModelRuntimeManager(new File("build/tmp/no-local-models")));

    @Test
    public void localNamespaceBelongsExclusivelyToTheLocalContainer() {
        String localName = LocalModelNames.virtualName("cross-encoder/ms-marco-MiniLM-L6-v2");
        assertEquals("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest", localName);
        assertTrue(local.ownsModel(localName));
        assertFalse(local.ownsModel("gpt-oss:20b"));
        assertFalse("a remote model named like a local one still stays remote",
                local.ownsModel("cross-encoder/ms-marco-MiniLM-L6-v2:latest"));
        assertTrue(local.isLocal());
        assertEquals("askai-local", local.getContainerId());
    }

    @Test
    public void localSourceStaysSilentWithoutInstalledModels() {
        // Without installed local models the source contributes NOTHING to lists — it neither
        // errors nor starts a process just for an empty list.
        assertFalse(local.hasAnythingToServe());
        assertEquals("", local.getBaseUrl());
    }
}

package com.aresstack.askai.hf;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** The recovery index persists a lost install contract so a later install is not silently degraded. */
public class DownloadMetadataRecoveryIndexTest {

    private static File tempGguf(long bytes) throws Exception {
        File gguf = File.createTempFile("askai-recovery-", ".gguf");
        gguf.deleteOnExit();
        FileOutputStream out = new FileOutputStream(gguf);
        out.write(new byte[(int) bytes]);
        out.close();
        return gguf;
    }

    private static File indexFile() throws Exception {
        File dir = File.createTempFile("askai-recovery-idx", "");
        dir.delete();
        dir.mkdirs();
        File index = new File(dir, "download-metadata-recovery.json");
        index.deleteOnExit();
        return index;
    }

    private static HuggingFaceInstallPlan plan() {
        return new HuggingFaceInstallPlan("owner/model", "main", "sha123", "m:q4",
                Arrays.asList("TEXT", "TOOLS"), Arrays.asList("completion", "tools"), "qwen3");
    }

    @Test
    public void recordThenFindReconstructsThePlan() throws Exception {
        DownloadMetadataRecoveryIndex index = new DownloadMetadataRecoveryIndex(indexFile());
        File gguf = tempGguf(10);
        index.record(gguf, "abc-sha256", plan());

        DownloadMetadataRecoveryIndex.Entry entry = index.find(gguf);
        assertNotNull(entry);
        assertEquals("SIDECAR_WRITE_FAILED", entry.getStatus());
        HuggingFaceInstallPlan recovered = entry.toPlan();
        assertEquals("owner/model", recovered.getRepositoryId());
        assertEquals("sha123", recovered.getResolvedRevisionSha());
        assertEquals("qwen3", recovered.getModelType());
        assertEquals(Arrays.asList("completion", "tools"), recovered.getRequiredOllamaCapabilities());
    }

    @Test
    public void survivesAcrossReopen() throws Exception {
        File file = indexFile();
        File gguf = tempGguf(10);
        new DownloadMetadataRecoveryIndex(file).record(gguf, "s", plan());
        // A brand-new index instance reads the same on-disk file (simulating a restart).
        assertNotNull(new DownloadMetadataRecoveryIndex(file).find(gguf));
    }

    @Test
    public void removeDropsTheEntry() throws Exception {
        DownloadMetadataRecoveryIndex index = new DownloadMetadataRecoveryIndex(indexFile());
        File gguf = tempGguf(10);
        index.record(gguf, "s", plan());
        index.remove(gguf);
        assertNull(index.find(gguf));
    }

    @Test
    public void sizeMismatchIsTreatedAsStale() throws Exception {
        DownloadMetadataRecoveryIndex index = new DownloadMetadataRecoveryIndex(indexFile());
        File gguf = tempGguf(10);
        index.record(gguf, "s", plan());
        // A different file now sits at the same path.
        FileOutputStream out = new FileOutputStream(gguf);
        out.write(new byte[20]);
        out.close();
        assertNull(index.find(gguf));
    }

    @Test
    public void missingEntryIsNull() throws Exception {
        assertNull(new DownloadMetadataRecoveryIndex(indexFile()).find(tempGguf(5)));
    }
}

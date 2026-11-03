package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * Installs a CURATED {@link NlpModelCatalogEntry} on EXPLICIT user action from the Model Browser: download →
 * verify exact size → verify pinned SHA-256 → write into the {@link LocalNlpModelStore}. Nothing is written
 * before BOTH checks pass, so an HTTP error, an interrupted download, a size mismatch or a hash mismatch leaves
 * NO installation (and no stray temp). A model already installed with the pinned hash is idempotently
 * {@code ALREADY_INSTALLED} — no re-download.
 *
 * <p>This is a user/Model-Browser action ONLY; the research agent and the snapshot provider NEVER download — a
 * missing model stays a typed "not installed" that degrades to the regex fallback.</p>
 */
public final class NlpModelInstaller {

    public enum Outcome {
        INSTALLED, ALREADY_INSTALLED
    }

    private final NlpDownloadClient downloadClient;
    private final LocalNlpModelStore store;

    public NlpModelInstaller(NlpDownloadClient downloadClient, LocalNlpModelStore store) {
        this.downloadClient = downloadClient;
        this.store = store;
    }

    public Outcome install(NlpModelCatalogEntry entry) throws IOException {
        return install(entry, false);
    }

    /** @param reinstall download again even when the verified model is installed (it is replaced after the checks) */
    public Outcome install(NlpModelCatalogEntry entry, boolean reinstall) throws IOException {
        NlpModelDescriptor existing = store.find(entry.getModelId());
        if (!reinstall && existing != null && new File(existing.getArtifactPath()).isFile()
                && existing.getSha256().equalsIgnoreCase(entry.getExpectedSha256())
                && !entry.getExpectedSha256().isEmpty()) {
            return Outcome.ALREADY_INSTALLED; // idempotent — no re-download
        }

        byte[] bytes = downloadClient.fetch(entry.getSourceUrl()); // follows redirects; throws on HTTP error
        if (bytes.length != entry.getExpectedSize()) {
            throw new IOException("NLP model '" + entry.getModelId() + "' size mismatch: expected "
                    + entry.getExpectedSize() + " bytes, got " + bytes.length + " — refusing to install");
        }
        String actualSha = LocalNlpModelStore.sha256(bytes);
        if (!actualSha.equalsIgnoreCase(entry.getExpectedSha256())) {
            throw new IOException("NLP model '" + entry.getModelId() + "' SHA-256 mismatch: expected "
                    + entry.getExpectedSha256() + ", got " + actualSha + " — refusing to install");
        }
        // Only now, with size AND hash verified, is anything written.
        store.install(entry, bytes);
        return Outcome.INSTALLED;
    }
}

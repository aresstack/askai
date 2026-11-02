package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;

/**
 * One CURATED, installable NLP model — an entry the Model Browser offers for explicit user install. Unlike a
 * generic web download it pins the logical source, the exact expected size and SHA-256 (verified once at curation
 * time), so the installer accepts ONLY that exact artifact from ONLY that URL.
 */
public final class NlpModelCatalogEntry {

    private final String modelId;
    private final NlpCapability capability;
    private final String languageCode;
    private final String implementation;
    private final String version;
    private final String compatibleRuntime;
    private final String sourceUrl;
    private final String artifactFileName;
    private final String expectedSha256;
    private final long expectedSize;

    public NlpModelCatalogEntry(String modelId, NlpCapability capability, String languageCode,
                                String implementation, String version, String compatibleRuntime,
                                String sourceUrl, String artifactFileName, String expectedSha256,
                                long expectedSize) {
        this.modelId = modelId;
        this.capability = capability;
        this.languageCode = languageCode == null ? "" : languageCode.trim().toLowerCase();
        this.implementation = implementation;
        this.version = version;
        this.compatibleRuntime = compatibleRuntime;
        this.sourceUrl = sourceUrl;
        this.artifactFileName = artifactFileName;
        this.expectedSha256 = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase();
        this.expectedSize = expectedSize;
    }

    public String getModelId() {
        return modelId;
    }

    public NlpCapability getCapability() {
        return capability;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public String getImplementation() {
        return implementation;
    }

    public String getVersion() {
        return version;
    }

    public String getCompatibleRuntime() {
        return compatibleRuntime;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getArtifactFileName() {
        return artifactFileName;
    }

    public String getExpectedSha256() {
        return expectedSha256;
    }

    public long getExpectedSize() {
        return expectedSize;
    }
}

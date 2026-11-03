package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;

import java.nio.file.Path;

/**
 * One catalog-validated installed model: the neutral {@link InstalledModelManifest} plus the directory it
 * lives in. The sidecar publishes and runs ONLY {@link LocalModel}s whose manifest passed
 * {@link InstalledModelManifest#validate(int)} against the shared catalog — a manifest can never invent a
 * capability the catalog does not grant.
 */
record LocalModel(InstalledModelManifest manifest, Path directory) {

    String virtualName() {
        return manifest.getVirtualName();
    }

    String runtimeModelId() {
        return manifest.getRuntimeModelId();
    }

    /** The runtime family token; a v1 reranker manifest carries none, so fall back to the catalog. */
    String runtimeFamily() {
        String family = manifest.getRuntimeFamily();
        if (!family.isEmpty()) {
            return family;
        }
        LocalRuntimeModelDescriptor descriptor =
                LocalModelCatalog.findByRepositoryId(manifest.getHuggingFaceRepository());
        return descriptor == null ? "" : descriptor.runtimeFamily().token();
    }

    boolean hasCapability(ModelCapability capability) {
        return manifest.hasCapability(capability);
    }

    boolean isEmbedding() {
        return hasCapability(ModelCapability.EMBEDDING);
    }

    boolean isReranker() {
        return hasCapability(ModelCapability.RERANK);
    }

    boolean isE5() {
        return "e5".equals(runtimeFamily());
    }
}

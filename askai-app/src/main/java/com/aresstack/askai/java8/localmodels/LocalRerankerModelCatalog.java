package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerModelCatalog;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The productive {@link RerankerModelCatalog}: scans the local model root for manifests that declare the
 * {@code RERANK} capability AND a usable state, and returns their virtual model ids sorted for a stable
 * dropdown. Corrupt or capability-less manifests are simply not usable rerank models — the catalog only
 * LISTS, it never selects.
 */
public final class LocalRerankerModelCatalog implements RerankerModelCatalog {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** The only state the installer ever writes for a completed install. */
    private static final String STATE_RUNNABLE = "RUNNABLE";

    private final LocalModelRuntimeManager manager;

    public LocalRerankerModelCatalog(LocalModelRuntimeManager manager) {
        this.manager = manager;
    }

    @Override
    public List<String> listInstalledRerankModels() {
        List<String> rerankModels = new ArrayList<String>();
        File root = manager.getModelRoot();
        File[] children = root == null ? null : root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                String virtualName = usableRerankModelName(new File(child, "askai-local-model.json"));
                if (virtualName != null) {
                    rerankModels.add(virtualName);
                }
            }
        }
        Collections.sort(rerankModels);
        return rerankModels;
    }

    /**
     * The manifest's virtual name if it declares the RERANK capability and a usable installed state
     * ({@code RUNNABLE}; an absent state field predates the field and stays usable), else {@code null}.
     */
    @SuppressWarnings("unchecked")
    static String usableRerankModelName(File manifestFile) {
        if (!manifestFile.isFile()) {
            return null;
        }
        Object parsed;
        try {
            parsed = SimpleJson.parse(new String(Files.readAllBytes(manifestFile.toPath()), UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return null; // a corrupt manifest is simply not a usable rerank model
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<String, Object> manifest = (Map<String, Object>) parsed;
        Object virtualName = manifest.get("virtualName");
        Object capabilities = manifest.get("capabilities");
        Object state = manifest.get("state");
        if (!(virtualName instanceof String) || !(capabilities instanceof List)) {
            return null;
        }
        if (state != null && !STATE_RUNNABLE.equals(state)) {
            return null; // explicitly not installed/usable
        }
        for (Object capability : (List<Object>) capabilities) {
            if (LocalRuntimeCapability.RERANK.getOllamaTag().equals(capability)) {
                return (String) virtualName;
            }
        }
        return null;
    }
}

package com.aresstack.askai.localmodels;

import java.io.File;

/**
 * Naming and path conventions of AskAI's local model runtime. Local models travel through all
 * existing UI paths as normal model-name strings in their own namespace —
 * {@code local/<repository>:latest} — so they never collide with a same-named remote model.
 */
public final class LocalModelNames {

    /** Prefix of every virtual local model name. */
    public static final String LOCAL_PREFIX = "local/";

    private LocalModelNames() {
    }

    public static String virtualName(String huggingFaceRepository) {
        return LOCAL_PREFIX + huggingFaceRepository + ":latest";
    }

    public static boolean isLocalModelName(String modelName) {
        return modelName != null && modelName.startsWith(LOCAL_PREFIX);
    }

    /**
     * The local model root: {@code %APPDATA%\.askai\models\local} (falls back to the user home on
     * non-Windows systems). Model directories below it use the runtime's canonical directory names
     * because the runtime's {@code RerankerModelId} validates the final path component strictly.
     */
    public static File localModelRoot() {
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.trim().isEmpty()
                ? new File(appData) : new File(System.getProperty("user.home"), "AppData/Roaming");
        return new File(new File(new File(base, ".askai"), "models"), "local");
    }
}

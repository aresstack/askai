package com.aresstack.askai.java8.modelport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Persists {@link ModelPortSettings} in their OWN properties file next to the central configuration
 * ({@code model-port.properties}): the central file is rewritten from its typed model on every save, so
 * keys it does not know would not survive there. Reads are cached by file timestamp, so the port can ask
 * on every call and a save in the settings panel applies to the next call.
 */
public final class ModelPortSettingsRepository {

    static final String FILE_NAME = "model-port.properties";

    private static final String DEFAULT_CAPACITY = "capacity.default";
    private static final String MODEL_CAPACITY = "capacity.model.";
    private static final String ENDPOINT_BUDGET = "endpoint.";
    private static final String ENDPOINT_BUDGET_SUFFIX = ".memoryBudgetMb";
    private static final String SLOT = "slot.";
    private static final String SLOT_MODEL_SUFFIX = ".model";
    private static final String SLOT_CONTEXT_SUFFIX = ".contextTokens";
    private static final String KEEP_ALIVE = "keepAlive";
    private static final String IMPLICIT_WAIT = "implicitWaitSeconds";
    private static final String RESIDENCY_REFRESH = "residencyRefreshSeconds";

    private static ModelPortSettingsRepository shared;

    private final File file;
    private long cachedStamp = Long.MIN_VALUE;
    private ModelPortSettings cached;

    public ModelPortSettingsRepository(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
    }

    /**
     * The shared repository at the default location (the AskAI configuration directory). One instance for
     * the app, so the settings panel and the port see the same cache.
     */
    public static synchronized ModelPortSettingsRepository inConfigurationDirectory() {
        if (shared == null) {
            String appData = System.getenv("APPDATA");
            File directory = appData != null && appData.trim().length() > 0
                    ? new File(appData, ".askai-java8")
                    : new File(System.getProperty("user.home"), ".askai-java8");
            shared = new ModelPortSettingsRepository(new File(directory, FILE_NAME));
        }
        return shared;
    }

    public File getFile() {
        return file;
    }

    /** The current settings; defaults when the file is absent or unreadable. Never throws. */
    public synchronized ModelPortSettings load() {
        long stamp = file.isFile() ? file.lastModified() ^ file.length() : -1L;
        if (cached != null && stamp == cachedStamp) {
            return cached;
        }
        ModelPortSettings loaded = stamp == -1L ? ModelPortSettings.defaults() : read();
        cached = loaded;
        cachedStamp = stamp;
        return loaded;
    }

    public synchronized void save(ModelPortSettings settings) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(DEFAULT_CAPACITY, String.valueOf(settings.getDefaultCapacity()));
        for (Map.Entry<String, Integer> entry : settings.getModelCapacities().entrySet()) {
            properties.setProperty(MODEL_CAPACITY + entry.getKey(), String.valueOf(entry.getValue()));
        }
        for (Map.Entry<String, Long> entry : settings.getEndpointMemoryBudgetsMb().entrySet()) {
            properties.setProperty(ENDPOINT_BUDGET + entry.getKey() + ENDPOINT_BUDGET_SUFFIX,
                    String.valueOf(entry.getValue()));
        }
        for (Map.Entry<String, String> entry : settings.getSlotModels().entrySet()) {
            properties.setProperty(SLOT + entry.getKey() + SLOT_MODEL_SUFFIX, entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : settings.getSlotContextTokens().entrySet()) {
            properties.setProperty(SLOT + entry.getKey() + SLOT_CONTEXT_SUFFIX, String.valueOf(entry.getValue()));
        }
        properties.setProperty(KEEP_ALIVE, settings.getKeepAlive());
        properties.setProperty(IMPLICIT_WAIT, String.valueOf(settings.getImplicitWaitSeconds()));
        properties.setProperty(RESIDENCY_REFRESH, String.valueOf(settings.getResidencyRefreshSeconds()));
        File directory = file.getAbsoluteFile().getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        OutputStream out = new FileOutputStream(file);
        try {
            properties.store(out, "AskAI model port: capacities, memory budgets, slots (ADR 0001, 4.5)");
        } finally {
            out.close();
        }
        cached = null;
    }

    private ModelPortSettings read() {
        Properties properties = new Properties();
        try {
            InputStream in = new FileInputStream(file);
            try {
                properties.load(in);
            } finally {
                in.close();
            }
        } catch (IOException unreadable) {
            System.err.println("[model-port] settings unreadable, using defaults: " + unreadable.getMessage());
            return ModelPortSettings.defaults();
        }
        Map<String, Integer> capacities = new LinkedHashMap<String, Integer>();
        Map<String, Long> budgets = new LinkedHashMap<String, Long>();
        Map<String, String> slotModels = new LinkedHashMap<String, String>();
        Map<String, Integer> slotContexts = new LinkedHashMap<String, Integer>();
        for (String key : new TreeMap<Object, Object>(properties).keySet().toArray(new String[0])) {
            String value = properties.getProperty(key, "").trim();
            if (key.startsWith(MODEL_CAPACITY)) {
                capacities.put(key.substring(MODEL_CAPACITY.length()), intOr(value, 0));
            } else if (key.startsWith(ENDPOINT_BUDGET) && key.endsWith(ENDPOINT_BUDGET_SUFFIX)) {
                budgets.put(key.substring(ENDPOINT_BUDGET.length(), key.length() - ENDPOINT_BUDGET_SUFFIX.length()),
                        longOr(value, -1L));
            } else if (key.startsWith(SLOT) && key.endsWith(SLOT_MODEL_SUFFIX)) {
                slotModels.put(key.substring(SLOT.length(), key.length() - SLOT_MODEL_SUFFIX.length()), value);
            } else if (key.startsWith(SLOT) && key.endsWith(SLOT_CONTEXT_SUFFIX)) {
                slotContexts.put(key.substring(SLOT.length(), key.length() - SLOT_CONTEXT_SUFFIX.length()),
                        intOr(value, 0));
            }
        }
        return new ModelPortSettings(
                intOr(properties.getProperty(DEFAULT_CAPACITY), ModelPortSettings.DEFAULT_CAPACITY),
                capacities, budgets, slotModels, slotContexts,
                properties.getProperty(KEEP_ALIVE, ModelPortSettings.DEFAULT_KEEP_ALIVE),
                intOr(properties.getProperty(IMPLICIT_WAIT), ModelPortSettings.DEFAULT_IMPLICIT_WAIT_SECONDS),
                intOr(properties.getProperty(RESIDENCY_REFRESH),
                        ModelPortSettings.DEFAULT_RESIDENCY_REFRESH_SECONDS));
    }

    private static int intOr(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static long longOr(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}

package com.aresstack.askai.batch.ui;

import com.aresstack.askai.batch.service.BatchProfileCatalogLoadedEvent;

import java.util.function.Consumer;

/**
 * Local batch refresh: reloads only the audio-processing profiles (a purely local source). Models come
 * exclusively from the global catalog refresh, so the batch panel no longer loads them itself.
 */
public interface BatchProfileRefresher {

    void loadProfiles(Consumer<BatchProfileCatalogLoadedEvent> callback);
}

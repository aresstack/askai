package com.aresstack.askai.service;

import java.util.List;

/**
 * Boundary for future Ollama capabilities. Current implementation is a click dummy
 * so the UI can be completed without binding API behavior into Swing classes.
 */
public interface FeatureActionService {

    /**
     * Returns the actions the UI should render. The Swing panel does not hard-code
     * future capabilities; Opus can later replace this implementation with one
     * backed by real services/API calls.
     */
    List<FeatureAction> actions();

    void execute(String actionId, FeatureActionListener listener);

    interface FeatureActionListener {
        void onAccepted(String title, String message);
    }
}

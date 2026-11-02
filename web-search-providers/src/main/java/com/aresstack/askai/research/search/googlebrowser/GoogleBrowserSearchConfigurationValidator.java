package com.aresstack.askai.research.search.googlebrowser;

import com.aresstack.askai.research.search.config.ConfigurationValidation;

public final class GoogleBrowserSearchConfigurationValidator {

    public void validate(GoogleBrowserSearchConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        ConfigurationValidation.requireRange(configuration.getFormatVersion(), 1, Integer.MAX_VALUE,
                "formatVersion");
        String type = configuration.getBrowserType();
        if (type != null && !type.trim().isEmpty()
                && !"chromium".equalsIgnoreCase(type.trim()) && !"firefox".equalsIgnoreCase(type.trim())) {
            throw new IllegalArgumentException("browserType must be chromium or firefox");
        }
        ConfigurationValidation.requireRange(configuration.getNavigationTimeoutMillis(), 0, Integer.MAX_VALUE,
                "navigationTimeoutMillis");
        ConfigurationValidation.requireRange(configuration.getMaxConcurrentSessions(), 0, 64,
                "maxConcurrentSessions");
        ConfigurationValidation.validateTransport(configuration.getTransport());
    }
}

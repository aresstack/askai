package com.aresstack.askai.research.runtime.search.service;

/** A registry write could not be made durable; the registry is unchanged. */
public final class UrlRegistryException extends Exception {

    public UrlRegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}

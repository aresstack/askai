package com.aresstack.askai.research.runtime.search.service;

/** What {@link SearchService#resolveForVisit} made of one queued page right before a worker visits it. */
public final class PageResolution {

    public enum Status {
        /** {@link #getPage()} carries a real page address a worker may open. */
        READY,
        /** The wrapper led to a page that is already registered (visited or queued before): skip it. */
        ALREADY_KNOWN,
        /** The engines are paused after a challenge: put the page back and try later. */
        ENGINE_PAUSED,
        /** The engine answered this hop with a challenge; the engines are now paused: put the page back. */
        ENGINE_BLOCKED,
        /** The wrapper could not be resolved (timeout, back to the engine): drop the page. */
        UNRESOLVED,
        /** The resolved address could not be registered durably: put the page back and try later. */
        REGISTRY_FAILED
    }

    private final Status status;
    private final PageCandidate page;
    private final String detail;

    PageResolution(Status status, PageCandidate page, String detail) {
        this.status = status;
        this.page = page;
        this.detail = detail == null ? "" : detail;
    }

    public Status getStatus() {
        return status;
    }

    /** The page (resolved when READY, the original candidate otherwise). */
    public PageCandidate getPage() {
        return page;
    }

    public String getDetail() {
        return detail;
    }
}

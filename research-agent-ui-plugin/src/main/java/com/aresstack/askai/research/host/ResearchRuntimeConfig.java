package com.aresstack.askai.research.host;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit configuration of the productive research runtime — every setting is passed here, never through
 * hidden system properties or globals: the browser channel, the display mode and the URL policy. Both the
 * research agent and the browser run in-process now, so there are no runtime paths left to configure;
 * {@link #validate()} stays the hook a generation switch runs BEFORE publishing.
 */
public final class ResearchRuntimeConfig {

    private final String browserChannel;
    private final boolean headless;
    private final boolean allowPrivateNetworks;
    /** The EXPLICITLY selected virtual reranker model id ("" = none — the session start fails). */
    private final String selectedRerankerModel;

    public ResearchRuntimeConfig(String browserChannel, boolean headless, boolean allowPrivateNetworks) {
        this(browserChannel, headless, allowPrivateNetworks, "");
    }

    /** @param selectedRerankerModel the explicitly selected virtual reranker model id ("" = none). */
    public ResearchRuntimeConfig(String browserChannel, boolean headless, boolean allowPrivateNetworks,
                                 String selectedRerankerModel) {
        this.browserChannel = browserChannel == null || browserChannel.isEmpty()
                ? ResearchRuntimeSettings.CAMOUFOX_CHANNEL : browserChannel;
        this.headless = headless;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.selectedRerankerModel = selectedRerankerModel == null ? "" : selectedRerankerModel.trim();
    }

    public String getBrowserChannel() { return browserChannel; }
    public boolean isHeadless() { return headless; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public String getSelectedRerankerModel() { return selectedRerankerModel; }

    /** @return all problems (empty = usable). Never throws; the CALLER decides (prepare vs. report). */
    public List<String> validate() {
        return new ArrayList<String>();
    }
}

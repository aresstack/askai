package com.aresstack.askai.research.host;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit configuration of the productive research runtime — every external path/setting is passed here,
 * never through hidden system properties or globals: the Java-8 launcher + jar of the external ACP research
 * agent, the Java-21 launcher + thin jar of the Playwright browser sidecar and the browser channel.
 * {@link #validate()} reports every missing prerequisite readably; a
 * generation switch validates BEFORE publishing (a broken config never displaces a working generation).
 */
public final class ResearchRuntimeConfig {

    private final String agentJavaExecutable;
    private final String agentJar;
    private final String sidecarJavaExecutable;
    private final String sidecarJar;
    private final String browserChannel;
    private final boolean headless;
    private final boolean allowPrivateNetworks;
    /** The EXPLICITLY selected virtual reranker model id ("" = none — the session start fails). */
    private final String selectedRerankerModel;

    public ResearchRuntimeConfig(String agentJavaExecutable, String agentJar,
                                 String sidecarJavaExecutable, String sidecarJar,
                                 String browserChannel, boolean headless,
                                 boolean allowPrivateNetworks) {
        this(agentJavaExecutable, agentJar, sidecarJavaExecutable, sidecarJar, browserChannel,
                headless, allowPrivateNetworks, "");
    }

    /** @param selectedRerankerModel the explicitly selected virtual reranker model id ("" = none). */
    public ResearchRuntimeConfig(String agentJavaExecutable, String agentJar,
                                 String sidecarJavaExecutable, String sidecarJar,
                                 String browserChannel, boolean headless,
                                 boolean allowPrivateNetworks,
                                 String selectedRerankerModel) {
        this.agentJavaExecutable = agentJavaExecutable;
        this.agentJar = agentJar;
        this.sidecarJavaExecutable = sidecarJavaExecutable;
        this.sidecarJar = sidecarJar;
        this.browserChannel = browserChannel == null || browserChannel.isEmpty()
                ? ResearchRuntimeSettings.CAMOUFOX_CHANNEL : browserChannel;
        this.headless = headless;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.selectedRerankerModel = selectedRerankerModel == null ? "" : selectedRerankerModel.trim();
    }

    public String getAgentJavaExecutable() { return agentJavaExecutable; }
    public String getAgentJar() { return agentJar; }
    public String getSidecarJavaExecutable() { return sidecarJavaExecutable; }
    public String getSidecarJar() { return sidecarJar; }
    public String getBrowserChannel() { return browserChannel; }
    public boolean isHeadless() { return headless; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public String getSelectedRerankerModel() { return selectedRerankerModel; }

    /** @return all problems (empty = usable). Never throws; the CALLER decides (prepare vs. report). */
    public List<String> validate() {
        List<String> problems = new ArrayList<String>();
        requireFile(problems, "agent java executable", agentJavaExecutable);
        requireFile(problems, "agent jar", agentJar);
        // The browser runs inside AskAI (askai #31): no sidecar java or jar is required any more.
        return problems;
    }

    private static void requireFile(List<String> problems, String what, String path) {
        if (path == null || path.trim().isEmpty()) {
            problems.add(what + " is not configured");
        } else if (!new File(path).isFile()) {
            problems.add(what + " does not exist: " + path);
        }
    }
}

package com.aresstack.askai.research.host;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicit configuration of the productive research runtime — every external path/setting is passed here,
 * never through hidden system properties or globals: the Java launcher + jar of the external ACP research
 * agent and the browser channel. The browser itself runs in-process (the research plugin starts the
 * Playwright runtime) and needs no path configuration. {@link #validate()} reports every missing
 * prerequisite readably; a generation switch validates BEFORE publishing (a broken config never displaces
 * a working generation).
 */
public final class ResearchRuntimeConfig {

    private final String agentJavaExecutable;
    private final String agentJar;
    private final String browserChannel;
    private final boolean headless;
    private final boolean allowPrivateNetworks;
    /** The EXPLICITLY selected virtual reranker model id ("" = none — the session start fails). */
    private final String selectedRerankerModel;

    public ResearchRuntimeConfig(String agentJavaExecutable, String agentJar,
                                 String browserChannel, boolean headless,
                                 boolean allowPrivateNetworks) {
        this(agentJavaExecutable, agentJar, browserChannel, headless, allowPrivateNetworks, "");
    }

    /** @param selectedRerankerModel the explicitly selected virtual reranker model id ("" = none). */
    public ResearchRuntimeConfig(String agentJavaExecutable, String agentJar,
                                 String browserChannel, boolean headless,
                                 boolean allowPrivateNetworks,
                                 String selectedRerankerModel) {
        this.agentJavaExecutable = agentJavaExecutable;
        this.agentJar = agentJar;
        this.browserChannel = browserChannel == null || browserChannel.isEmpty()
                ? ResearchRuntimeSettings.CAMOUFOX_CHANNEL : browserChannel;
        this.headless = headless;
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.selectedRerankerModel = selectedRerankerModel == null ? "" : selectedRerankerModel.trim();
    }

    public String getAgentJavaExecutable() { return agentJavaExecutable; }
    public String getAgentJar() { return agentJar; }
    public String getBrowserChannel() { return browserChannel; }
    public boolean isHeadless() { return headless; }
    public boolean isAllowPrivateNetworks() { return allowPrivateNetworks; }
    public String getSelectedRerankerModel() { return selectedRerankerModel; }

    /** @return all problems (empty = usable). Never throws; the CALLER decides (prepare vs. report). */
    public List<String> validate() {
        List<String> problems = new ArrayList<String>();
        requireFile(problems, "agent java executable", agentJavaExecutable);
        requireFile(problems, "agent jar", agentJar);
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

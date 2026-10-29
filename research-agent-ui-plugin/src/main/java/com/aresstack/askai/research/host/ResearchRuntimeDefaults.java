package com.aresstack.askai.research.host;

import java.io.File;

/**
 * Automatic defaults for the productive research runtime so a normal development start has NO empty
 * mandatory fields: the agent's Java comes from the running JVM ({@code java.home}) and the agent jar from
 * the assembled distribution directory (documented hand-off {@code askai.research.runtime.dir}, set by
 * {@code runWithDevPlugins} / an installer). Only EMPTY fields are completed — explicit user values always
 * win, and nothing is ever downloaded or installed. The single exception is the browser channel: when the
 * configured browser is provably absent but the other channel's browser is installed, the installed one is
 * used (otherwise every session would fail although a working browser exists). The browser itself runs
 * in-process and needs no runtime of its own.
 */
public final class ResearchRuntimeDefaults {

    /** Documented dev/installer hand-off for the assembled distribution directory. */
    public static final String RUNTIME_DIR_PROPERTY = "askai.research.runtime.dir";

    private ResearchRuntimeDefaults() {
    }

    /** Complete only the EMPTY fields of the settings from the detectable environment. */
    public static ResearchRuntimeSettings complete(ResearchRuntimeSettings settings) {
        String agentJava = settings.getAgentJavaExecutable();
        if (agentJava.isEmpty()) {
            agentJava = currentJvmJava();
        }
        String agentJar = settings.getAgentJar();
        File dist = distributionDirectory();
        if (dist != null && agentJar.isEmpty()) {
            agentJar = existingPath(new File(dist, "research-agent-runtime.jar"));
        }
        return new ResearchRuntimeSettings(settings.getMode(), agentJava, agentJar,
                installedBrowserChannel(settings.getBrowserChannel()), settings.isHeadless(),
                settings.isAllowPrivateNetworks(), settings.getSelectedRerankerModel());
    }

    /**
     * The browser channel to actually use: the configured one when its browser is installed; otherwise the
     * OTHER channel when that one is provably present (e.g. a machine with Edge but no Chrome keeps
     * working after Chrome is uninstalled). When neither is found, the configured channel stays and the
     * browser's readiness probe reports BROWSER_NOT_INSTALLED readably. Discovery only — never installs.
     */
    public static String installedBrowserChannel(String configured) {
        if (ResearchRuntimeSettings.CAMOUFOX_CHANNEL.equals(configured)) {
            return configured; // managed: the browser runtime downloads and verifies its own Camoufox
        }
        String channel = "msedge".equals(configured) ? "msedge" : "chrome";
        String other = "msedge".equals(channel) ? "chrome" : "msedge";
        return pickChannel(channel, browserInstalled(channel), browserInstalled(other), other);
    }

    /** Pure channel decision, separated from the file-system probe for testability. */
    static String pickChannel(String configured, boolean configuredInstalled, boolean otherInstalled,
                              String other) {
        return configuredInstalled || !otherInstalled ? configured : other;
    }

    /** Standard install locations of the channel's browser (mirrors the browser runtime's discovery paths). */
    static boolean browserInstalled(String channel) {
        boolean chrome = !"msedge".equals(channel);
        java.util.List<String> paths = new java.util.ArrayList<String>();
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            for (String root : new String[]{System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
                if (root != null) {
                    paths.add(root + (chrome ? "\\Google\\Chrome\\Application\\chrome.exe"
                            : "\\Microsoft\\Edge\\Application\\msedge.exe"));
                }
            }
        } else if (os.contains("mac")) {
            paths.add(chrome ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                    : "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            if (chrome) {
                paths.add("/usr/bin/google-chrome");
                paths.add("/usr/bin/google-chrome-stable");
                paths.add("/opt/google/chrome/chrome");
            } else {
                paths.add("/usr/bin/microsoft-edge");
                paths.add("/opt/microsoft/msedge/msedge");
            }
        }
        for (String candidate : paths) {
            if (new File(candidate).isFile()) {
                return true;
            }
        }
        return false;
    }

    /** The running JVM's launcher (the agent runs on AskAI's own Java). */
    public static String currentJvmJava() {
        String home = System.getProperty("java.home", "");
        if (home.isEmpty()) {
            return "";
        }
        File java = new File(home, "bin" + File.separator + (isWindows() ? "java.exe" : "java"));
        return existingPath(java);
    }

    /** The assembled distribution directory, or null when not provided/absent. */
    public static File distributionDirectory() {
        String dir = System.getProperty(RUNTIME_DIR_PROPERTY, "");
        if (dir.isEmpty()) {
            return null;
        }
        File file = new File(dir);
        return file.isDirectory() ? file : null;
    }

    private static String existingPath(File file) {
        return file.isFile() ? file.getAbsolutePath() : "";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}

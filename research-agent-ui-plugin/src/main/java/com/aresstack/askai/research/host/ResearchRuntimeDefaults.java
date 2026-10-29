package com.aresstack.askai.research.host;

import java.io.File;

/**
 * Automatic defaults for the productive research runtime. Both the research agent and the browser run
 * in-process, so nothing has to be located any more; the one completion left is the browser channel: when
 * the configured browser is provably absent but the other channel's browser is installed, the installed one
 * is used (otherwise every session would fail although a working browser exists). Explicit user values
 * always win, and nothing is ever downloaded or installed here.
 */
public final class ResearchRuntimeDefaults {

    private ResearchRuntimeDefaults() {
    }

    /** Complete the settings from the detectable environment (the installed browsers). */
    public static ResearchRuntimeSettings complete(ResearchRuntimeSettings settings) {
        return new ResearchRuntimeSettings(settings.getMode(),
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

}

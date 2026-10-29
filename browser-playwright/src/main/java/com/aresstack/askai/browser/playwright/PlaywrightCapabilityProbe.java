package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.impl.driver.Driver;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real capability probing before the sidecar reports the Playwright backend as usable. Checks, in order:
 * (1) the effective {@code com.microsoft.playwright.impl.driver.Driver} is playwright4j's replacement (its
 * child command runs {@code GraalDriverMain} — the upstream one would try to install/spawn Node),
 * (2) the Playwright Core JS driver package is on the classpath (embedded in the playwright4j jar),
 * (3) an executable for the configured browser channel exists locally — except for the managed Camoufox,
 * which the launch installs and verifies itself ({@link CamoufoxProvisioning}).
 * Launchability itself is verified by the actual launch at session start ({@code BROWSER_START_FAILED}).
 * The probe itself NEVER downloads or installs anything.
 */
final class PlaywrightCapabilityProbe {

    /** The three environment checks, injectable for classification tests. */
    interface Checks {
        /** @return the driver child-process command line, or null when no Driver class is usable. */
        List<String> driverCommand();

        boolean driverBundlePresent();

        /** @return the resolved browser executable path, or null when none exists. */
        String browserExecutable(String channel);
    }

    private final Checks checks;

    PlaywrightCapabilityProbe() {
        this(new RealChecks());
    }

    PlaywrightCapabilityProbe(Checks checks) {
        this.checks = checks;
    }

    PlaywrightReadiness probe(String channel) {
        List<String> command;
        try {
            command = checks.driverCommand();
        } catch (Throwable ex) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.INCOMPATIBLE_DRIVER,
                    "Driver probe failed: " + ex.getMessage());
        }
        boolean graal = false;
        if (command != null) {
            for (String part : command) {
                if (part != null && part.contains("GraalDriverMain")) {
                    graal = true;
                    break;
                }
            }
        }
        if (!graal) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.INCOMPATIBLE_DRIVER,
                    "The effective Playwright Driver is not the playwright4j GraalJS replacement; "
                            + "check that com.microsoft.playwright:driver is excluded from the classpath.");
        }
        if (!checks.driverBundlePresent()) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.DRIVER_BUNDLE_NOT_FOUND,
                    "Playwright Core driver package (embedded in com.aresstack:playwright4j) is not on the classpath.");
        }
        if (CamoufoxProvisioning.CHANNEL.equals(channel)) {
            // Managed: not looked up on the machine — the launch installs (once) and verifies it.
            return new PlaywrightReadiness(PlaywrightReadiness.Status.READY,
                    "channel=camoufox executable=managed under " + CamoufoxProvisioning.browserRoot());
        }
        String executable = checks.browserExecutable(channel);
        if (executable == null) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.BROWSER_NOT_INSTALLED,
                    "No installed browser found for channel '" + channel
                            + "'. Install Google Chrome or Microsoft Edge; the sidecar never downloads browsers.");
        }
        return new PlaywrightReadiness(PlaywrightReadiness.Status.READY,
                "channel=" + channel + " executable=" + executable);
    }

    /** Production checks against the real classpath and file system. */
    static final class RealChecks implements Checks {

        public List<String> driverCommand() {
            Map<String, String> env = new LinkedHashMap<String, String>();
            return Driver.createAndInstall(env, false).createProcessBuilder().command();
        }

        public boolean driverBundlePresent() {
            // playwright4j >= 0.3.0 embeds the Core JS package platform-neutrally under driver/package/.
            return PlaywrightCapabilityProbe.class.getClassLoader().getResource("driver/package/cli.js") != null;
        }

        public String browserExecutable(String channel) {
            for (String candidate : candidatePaths(channel)) {
                File file = new File(candidate);
                if (file.isFile()) {
                    return file.getAbsolutePath();
                }
            }
            return null;
        }

        /** Standard install locations only — discovery, never installation. */
        private static List<String> candidatePaths(String channel) {
            List<String> paths = new ArrayList<String>();
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            boolean chrome = !"msedge".equals(channel);
            if (os.contains("win")) {
                String programFiles = System.getenv("ProgramFiles");
                String programFilesX86 = System.getenv("ProgramFiles(x86)");
                String localAppData = System.getenv("LOCALAPPDATA");
                for (String root : new String[]{programFiles, programFilesX86, localAppData}) {
                    if (root == null) {
                        continue;
                    }
                    if (chrome) {
                        paths.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
                    } else {
                        paths.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
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
            return paths;
        }
    }
}

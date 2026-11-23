package com.aresstack.askai.launcher;

import java.util.Arrays;

/**
 * Stands in for AskAiApp in {@link LauncherJarTest}: says which Java runs it, whether it is the restart and
 * which arguments arrived, and ends with exit code 3, so that the test sees the code passed on.
 */
public final class LauncherStubApp {

    private LauncherStubApp() {
    }

    public static void main(String[] args) {
        System.out.println("stub-app java=" + System.getProperty("java.specification.version")
                + " relaunched=" + System.getProperty(Launcher.RELAUNCHED_PROPERTY)
                + " args=" + Arrays.toString(args));
        System.out.flush();
        System.exit(3);
    }
}

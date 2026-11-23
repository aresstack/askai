package com.aresstack.askai.launcher;

/**
 * Starts the launcher as if this JVM were a Java 8 (for {@link LauncherJarTest}, on machines without a real
 * Java 8): {@code java.specification.version} cannot be overridden on the command line, but it can be set in
 * the process before the launcher reads it. The restart then runs the real launcher on the real Java.
 */
public final class LauncherAsIfOldJava {

    private LauncherAsIfOldJava() {
    }

    public static void main(String[] args) {
        System.setProperty("java.specification.version", "1.8");
        Launcher.main(args);
    }
}

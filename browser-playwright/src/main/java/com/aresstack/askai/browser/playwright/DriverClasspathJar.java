package com.aresstack.askai.browser.playwright;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * Keeps the command line of the playwright4j driver JVM short.
 *
 * <p>playwright4j starts the Playwright Core JS driver in a child JVM with {@code -cp <every jar it can see>}:
 * the plugin class loader's URLs plus the host's {@code java.class.path}. In a Gradle test run or with many
 * plugin jars under a long Windows profile path that string passes the 32 767-character limit of
 * {@code CreateProcess}, and the driver never starts ({@code CreateProcess error=206, filename or extension is
 * too long}). This class resolves the same classpath, writes it into the {@code Class-Path} manifest of a small
 * "pathing" jar and points playwright4j at that jar via {@code -Dplaywright4j.driver.classpath}. The child JVM
 * then gets exactly the classes it got before, through one short {@code -cp} entry — in tests and in the app.
 *
 * <p>An explicit {@code -Dplaywright4j.driver.classpath} set by the user is left untouched.
 */
final class DriverClasspathJar {

    static final String CLASSPATH_PROPERTY = "playwright4j.driver.classpath";

    private static final String DRIVER_CLASSPATH_CLASS = "com.microsoft.playwright.impl.driver.DriverClasspath";
    private static final String DRIVER_CLASS = "com.microsoft.playwright.impl.driver.Driver";

    /** The value this class last put into the property (null = never, or the user's value is in charge). */
    private static String installed;

    private DriverClasspathJar() {
    }

    /** Points the next driver start at a pathing jar; failures leave playwright4j's own detection in place. */
    static synchronized void install() {
        String current = System.getProperty(CLASSPATH_PROPERTY);
        boolean ours = current != null && current.equals(installed);
        if (current != null && !current.trim().isEmpty() && !ours) {
            return; // configured by the user
        }
        try {
            if (ours) {
                System.clearProperty(CLASSPATH_PROPERTY); // re-detect: the plugin class loader may have changed
            }
            Path cache = Paths.get(System.getProperty("java.io.tmpdir"), "playwright4j-driver");
            String classpath = resolve(cache.resolve("classpath"));
            Path jar = write(classpath, cache.resolve("askai-pathing"));
            installed = jar.toString();
            System.setProperty(CLASSPATH_PROPERTY, installed);
        } catch (Exception | LinkageError failed) {
            if (ours) {
                System.setProperty(CLASSPATH_PROPERTY, installed); // keep the last working jar
            }
            System.err.println("[browser-mcp] driver classpath jar not prepared, using the full -cp: " + failed);
        }
    }

    /** playwright4j's own classpath detection (package-private there), so both stay identical. */
    private static String resolve(Path nestedJarCache) throws ReflectiveOperationException {
        Class<?> driver = Class.forName(DRIVER_CLASS, false, DriverClasspathJar.class.getClassLoader());
        Class<?> detection = Class.forName(DRIVER_CLASSPATH_CLASS, true, driver.getClassLoader());
        Method resolve = detection.getDeclaredMethod("resolve", ClassLoader.class, Path.class);
        resolve.setAccessible(true);
        return (String) resolve.invoke(null, driver.getClassLoader(), nestedJarCache);
    }

    /**
     * Writes (or reuses) a jar whose manifest {@code Class-Path} lists every entry of {@code classpath}, named by
     * the hash of that list. @return the jar
     */
    static Path write(String classpath, Path directory) throws IOException {
        StringBuilder manifestPath = new StringBuilder();
        for (String element : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (element.trim().isEmpty()) {
                continue;
            }
            if (manifestPath.length() > 0) {
                manifestPath.append(' ');
            }
            // Absolute file: URIs (directories end in '/'), spaces and non-ASCII percent-encoded.
            manifestPath.append(Paths.get(element).toAbsolutePath().toUri().toASCIIString());
        }
        Files.createDirectories(directory);
        Path jar = directory.resolve("driver-classpath-" + sha256(manifestPath.toString()) + ".jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, manifestPath.toString());
        Path temp = Files.createTempFile(directory, "driver-classpath-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
                // the manifest is the whole content
            }
            try {
                Files.move(temp, jar, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                if (!Files.isRegularFile(jar)) {
                    throw raced;
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return jar;
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

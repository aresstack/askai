package com.aresstack.askai.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a 64-bit Java installation of at least one feature version on this computer, for a launcher that was
 * started with an older one. Compiled for Java 8 (see the {@code launcher} source set in
 * {@code askai-app/build.gradle}); it uses nothing of the app.
 *
 * <p>Ported from the launcher of Space Harvester (aresstack/space-harvester, {@code space-harvester-app/src/launcher}),
 * with these differences: every version from the minimum upwards counts (Java 25 is as good as Java 21), the
 * Windows registry is asked as well (where the installers of Oracle, Azul Zulu, Eclipse Adoptium, Microsoft,
 * BellSoft and Amazon record their installation folders), and a 32-bit Java is never taken, because AskAI's
 * native parts (ONNX Runtime, Camoufox, FFmpeg) exist for 64 bit only.</p>
 *
 * <p>Order of the search:</p>
 * <ol>
 *   <li>what was named explicitly ({@code -Daskai.java}, then {@code ASKAI_JAVA}): it wins over everything;</li>
 *   <li>the installation remembered from the last start;</li>
 *   <li>{@code JAVA_HOME} and every {@code java} on the {@code PATH};</li>
 *   <li>the Windows registry (all 64-bit views before the 32-bit ones);</li>
 *   <li>the folders installers usually use ({@code C:\Program Files\Zulu}, {@code ~/.jdks}, ...).</li>
 * </ol>
 * <p>The first suitable installation wins and is remembered. A candidate whose {@code release} file names an
 * older version or a 32-bit build is skipped without starting it; every other candidate is asked with
 * {@code java -XshowSettings:properties -version} (ten seconds at most).</p>
 */
final class JavaRuntimeFinder {

    private static final long PROBE_TIMEOUT_MILLIS = 10000L;
    private static final long REGISTRY_TIMEOUT_MILLIS = 5000L;
    private static final Pattern VERSION_PROPERTY = Pattern.compile("(?m)^\\s*java\\.version\\s*=\\s*(\\S+)\\s*$");
    private static final Pattern DATA_MODEL_PROPERTY = Pattern.compile("(?m)^\\s*sun\\.arch\\.data\\.model\\s*=\\s*(\\S+)\\s*$");
    private static final Pattern ARCH_PROPERTY = Pattern.compile("(?m)^\\s*os\\.arch\\s*=\\s*(\\S+)\\s*$");
    private static final Pattern QUOTED_VERSION = Pattern.compile("\"([0-9][0-9._\\-a-zA-Z+]*)\"");
    private static final Pattern RELEASE_VERSION = Pattern.compile("(?m)^\\s*JAVA_VERSION\\s*=\\s*\"?([^\"\\s]+)\"?\\s*$");
    private static final Pattern RELEASE_ARCH = Pattern.compile("(?m)^\\s*OS_ARCH\\s*=\\s*\"?([^\"\\s]+)\"?\\s*$");
    /** A value line of {@code reg query}: four spaces, name, type, data. */
    private static final Pattern REGISTRY_VALUE = Pattern.compile("^\\s+(.+?)\\s{2,}(REG_(?:EXPAND_)?SZ)\\s{2,}(.+?)\\s*$");
    /** The registry values that name an installation folder. */
    private static final List<String> REGISTRY_FOLDER_VALUES = Arrays.asList(
            "javahome", "installationpath", "path", "installlocation");
    /** Registry keys below HKLM and HKCU that installers fill (each searched with all sub keys). */
    private static final String[] REGISTRY_KEYS = {
            "SOFTWARE\\JavaSoft",
            "SOFTWARE\\Azul Systems",
            "SOFTWARE\\Eclipse Adoptium",
            "SOFTWARE\\Eclipse Foundation",
            "SOFTWARE\\Microsoft\\JDK",
            "SOFTWARE\\BellSoft\\Liberica",
            "SOFTWARE\\Amazon Corretto"};
    /** Vendor folders below Program Files; JetBrains last, its runtimes belong to the IDEs. */
    private static final String[] VENDORS = {"Java", "Zulu", "Azul", "Azul Systems", "Eclipse Adoptium",
            "Eclipse Foundation", "Microsoft", "Amazon Corretto", "BellSoft", "RedHat", "Red Hat", "SapMachine",
            "Semeru", "Oracle", "OpenJDK", "JetBrains"};
    private static final int STAGES = 3;

    /** What asking an installation found out. */
    static final class Probe {
        /** The feature version (8, 17, 21 ...), or -1 if it cannot be found out. */
        final int major;
        /** 32 or 64, or 0 if unknown. */
        final int bits;

        Probe(int major, int bits) {
            this.major = major;
            this.bits = bits;
        }
    }

    private final int minimum;
    /** Where the installation found last time is remembered; {@code null} to remember nothing. */
    private final File memory;

    JavaRuntimeFinder(int minimum, File memory) {
        this.minimum = minimum;
        this.memory = memory;
    }

    /** The {@code java} executable of a suitable installation (minimum version or newer, not 32-bit), or {@code null}. */
    File find() {
        Map<String, File> asked = new LinkedHashMap<String, File>();
        // Named explicitly: wins over the memory, so that naming another Java takes effect on the next start.
        Map<String, File> named = new LinkedHashMap<String, File>();
        for (String text : named()) {
            add(named, file(text));
        }
        File hit = firstSuitable(named, asked);
        if (hit != null) {
            return hit;
        }
        File remembered = executable(remembered());
        if (remembered != null && suitable(remembered)) {
            return remembered; // checked again, but nothing else is searched
        }
        // Stage by stage, so the registry and the folders are only searched when the cheap places had none.
        for (int stage = 0; stage < STAGES; stage++) {
            Map<String, File> found = new LinkedHashMap<String, File>();
            collect(stage, found);
            hit = firstSuitable(found, asked);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private File firstSuitable(Map<String, File> found, Map<String, File> asked) {
        for (Map.Entry<String, File> candidate : found.entrySet()) {
            if (asked.put(candidate.getKey(), candidate.getValue()) == null && suitable(candidate.getValue())) {
                remember(candidate.getValue());
                return candidate.getValue();
            }
        }
        return null;
    }

    /** The places named explicitly: {@code -Daskai.java}, then {@code ASKAI_JAVA}; empty ones left out. */
    static List<String> named() {
        List<String> named = new ArrayList<String>();
        for (String text : new String[]{System.getProperty("askai.java"), System.getenv("ASKAI_JAVA")}) {
            if (file(text) != null) {
                named.add(text.trim());
            }
        }
        return named;
    }

    /** Every {@code java} executable worth asking after the named ones, the likeliest first, each one once. */
    List<File> candidates() {
        Map<String, File> found = new LinkedHashMap<String, File>();
        for (int stage = 0; stage < STAGES; stage++) {
            collect(stage, found);
        }
        return new ArrayList<File>(found.values());
    }

    /** Stage 0: JAVA_HOME and PATH; stage 1: the Windows registry; stage 2: the usual installation folders. */
    private void collect(int stage, Map<String, File> found) {
        if (stage == 0) {
            for (int version = minimum; version <= minimum + 8; version++) {
                add(found, file(System.getenv("JAVA_HOME_" + version + "_X64"))); // set by GitHub's runners
            }
            add(found, file(System.getenv("JAVA_HOME")));
            String path = System.getenv("PATH");
            if (path != null) {
                // The PATH names bin folders (or Oracle's javapath), not installations.
                for (String entry : path.split(File.pathSeparator)) {
                    File folder = file(entry);
                    if (folder != null) {
                        add(found, new File(folder, "java.exe"));
                        add(found, new File(folder, "java"));
                    }
                }
            }
        } else if (stage == 1) {
            if (isWindows()) {
                for (File folder : registryFolders()) {
                    add(found, folder);
                }
            }
        } else {
            for (File root : searchRoots()) {
                for (File child : directories(root)) {
                    add(found, child);
                    for (File nested : directories(child)) {
                        add(found, nested); // scoop/apps/zulu21-jdk/current, JavaVirtualMachines/x/Contents
                        add(found, new File(nested, "Home"));
                    }
                }
            }
        }
    }

    /**
     * Installation folders the Windows registry names: machine and user, every 64-bit view before the 32-bit
     * one of the machine (32-bit installers write there; such a Java is rejected later, but should not be
     * asked first). {@code reg.exe} is started by its full path, never by name: Windows would look for a bare
     * name in the folder of the jar first.
     */
    private static List<File> registryFolders() {
        List<File> folders = new ArrayList<File>();
        String reg = registryTool();
        if (reg == null) {
            return folders;
        }
        String[][] views = {{"HKLM", "/reg:64"}, {"HKCU", "/reg:64"}, {"HKLM", "/reg:32"}};
        for (String[] view : views) {
            for (String key : REGISTRY_KEYS) {
                String output = run(REGISTRY_TIMEOUT_MILLIS, reg, "query", view[0] + "\\" + key, "/s", view[1]);
                if (output != null) {
                    for (File folder : parseRegistry(output)) {
                        if (!folders.contains(folder)) {
                            folders.add(folder);
                        }
                    }
                }
            }
        }
        return folders;
    }

    /** {@code %SystemRoot%\System32\reg.exe}, or {@code null} if it is not there. */
    private static String registryTool() {
        File systemRoot = file(System.getenv("SystemRoot"));
        if (systemRoot == null) {
            return null;
        }
        File reg = new File(new File(systemRoot, "System32"), "reg.exe");
        return reg.isFile() ? reg.getPath() : null;
    }

    /**
     * The installation folders in the output of {@code reg query <key> /s}: the data of every string value
     * called JavaHome (Oracle, Microsoft, BellSoft), InstallationPath (Azul Zulu), Path (Eclipse Adoptium) or
     * InstallLocation. Whether a folder holds a Java is checked later.
     */
    static List<File> parseRegistry(String output) {
        List<File> folders = new ArrayList<File>();
        for (String line : output.split("\\r?\\n")) {
            Matcher value = REGISTRY_VALUE.matcher(line);
            if (value.matches() && REGISTRY_FOLDER_VALUES.contains(value.group(1).trim().toLowerCase(Locale.ROOT))) {
                File folder = file(value.group(3));
                if (folder != null && !folders.contains(folder)) {
                    folders.add(folder);
                }
            }
        }
        return folders;
    }

    private static List<File> searchRoots() {
        List<File> roots = new ArrayList<File>();
        // ProgramW6432 first: in a 32-bit JVM (the java.com Java 8 often is one) ProgramFiles is "Program Files (x86)".
        for (String programFiles : new String[]{System.getenv("ProgramW6432"), System.getenv("ProgramFiles")}) {
            File base = file(programFiles);
            if (base != null) {
                for (String vendor : VENDORS) {
                    roots.add(new File(base, vendor));
                }
            }
        }
        File localAppData = file(System.getenv("LOCALAPPDATA"));
        if (localAppData != null) {
            roots.add(new File(new File(localAppData, "Programs"), "Eclipse Adoptium"));
            roots.add(new File(new File(localAppData, "Programs"), "Zulu"));
            roots.add(new File(new File(localAppData, "Programs"), "Microsoft"));
        }
        File home = file(System.getProperty("user.home"));
        if (home != null) {
            roots.add(new File(home, ".jdks")); // IntelliJ
            roots.add(new File(new File(home, "scoop"), "apps"));
            roots.add(new File(new File(new File(home, ".sdkman"), "candidates"), "java"));
            roots.add(new File(new File(home, ".gradle"), "jdks")); // Gradle toolchains (foojay)
        }
        roots.add(new File("/usr/lib/jvm"));
        roots.add(new File("/Library/Java/JavaVirtualMachines"));
        return roots;
    }

    private static void add(Map<String, File> found, File location) {
        File executable = executable(location);
        if (executable != null) {
            // Links (/usr/bin/java, alternatives) are asked once; the first place stays.
            String key;
            try {
                key = executable.getCanonicalPath();
            } catch (IOException e) {
                key = executable.getAbsolutePath();
            }
            key = key.toLowerCase(Locale.ROOT);
            if (!found.containsKey(key)) {
                found.put(key, executable);
            }
        }
    }

    /**
     * The {@code java} executable of a location: the file itself if it is one, else {@code bin/java(.exe)} of an
     * installation folder, else {@code java(.exe)} of a bin folder.
     */
    static File executable(File location) {
        if (location == null) {
            return null;
        }
        if (location.isFile()) {
            return location.getAbsoluteFile();
        }
        if (location.isDirectory()) {
            for (File folder : new File[]{new File(location, "bin"), location}) {
                for (String name : new String[]{"java.exe", "java"}) {
                    File candidate = new File(folder, name);
                    if (candidate.isFile()) {
                        return candidate.getAbsoluteFile();
                    }
                }
            }
        }
        return null;
    }

    /** Whether an installation is the minimum version or newer and not a 32-bit build. */
    boolean suitable(File executable) {
        Probe probe = probe(executable);
        return probe.major >= minimum && probe.bits != 32;
    }

    /** The feature version of an installation (8, 17, 21 ...), or -1 if it cannot be found out. */
    int majorVersion(File executable) {
        return probe(executable).major;
    }

    /**
     * Asks an installation for its version and bitness. Its {@code release} file names both: an older or a
     * 32-bit one is out without being started. A suitable-looking one (or one without the file, like a shim
     * on the PATH) is started and asked, so that a broken one is not used.
     */
    Probe probe(File executable) {
        File bin = executable.getParentFile();
        File release = bin == null || bin.getParentFile() == null ? null : new File(bin.getParentFile(), "release");
        if (release != null && release.isFile()) {
            try {
                String text = new String(Files.readAllBytes(release.toPath()), "UTF-8");
                Matcher version = RELEASE_VERSION.matcher(text);
                int named = version.find() ? majorOf(version.group(1)) : -1;
                Matcher arch = RELEASE_ARCH.matcher(text);
                int bits = arch.find() ? bitsOfArch(arch.group(1)) : 0;
                if ((named > 0 && named < minimum) || bits == 32) {
                    return new Probe(named, bits);
                }
            } catch (IOException | RuntimeException ignored) {
                // then the installation is asked
            }
        }
        String output = run(PROBE_TIMEOUT_MILLIS, executable.getPath(), "-XshowSettings:properties", "-version");
        return output == null ? new Probe(-1, 0) : parse(output);
    }

    /** Runs a command and returns what it printed, or {@code null} if it failed, hung or exited non-zero. */
    private static String run(long timeoutMillis, String... command) {
        File output = null;
        Process process = null;
        try {
            output = File.createTempFile("askai-launcher", ".txt");
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return null;
            }
            return new String(Files.readAllBytes(output.toPath()), Charset.defaultCharset());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null && !output.delete()) {
                output.deleteOnExit();
            }
        }
    }

    /** Version and bitness in what {@code java -XshowSettings:properties -version} printed. */
    static Probe parse(String output) {
        int major;
        Matcher property = VERSION_PROPERTY.matcher(output);
        if (property.find()) {
            major = majorOf(property.group(1));
        } else {
            Matcher quoted = QUOTED_VERSION.matcher(output);
            major = quoted.find() ? majorOf(quoted.group(1)) : -1;
        }
        int bits = 0;
        Matcher dataModel = DATA_MODEL_PROPERTY.matcher(output);
        if (dataModel.find()) {
            bits = "32".equals(dataModel.group(1)) ? 32 : "64".equals(dataModel.group(1)) ? 64 : 0;
        }
        if (bits == 0) {
            Matcher arch = ARCH_PROPERTY.matcher(output);
            bits = arch.find() ? bitsOfArch(arch.group(1)) : 0;
        }
        return new Probe(major, bits);
    }

    /** 32 for x86/i386/i586/i686, 64 for amd64/x86_64/aarch64, 0 for anything else. */
    static int bitsOfArch(String arch) {
        String a = arch == null ? "" : arch.trim().toLowerCase(Locale.ROOT);
        if (a.equals("x86") || a.equals("i386") || a.equals("i486") || a.equals("i586") || a.equals("i686")) {
            return 32;
        }
        if (a.equals("amd64") || a.equals("x86_64") || a.equals("x64") || a.equals("aarch64") || a.equals("arm64")) {
            return 64;
        }
        return 0;
    }

    /** "1.8.0_392" is 8, "21.0.5" is 21, "25" is 25, "22-ea" is 22; -1 for anything else. */
    static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        String[] parts = version.trim().split("[^0-9]+");
        try {
            if (parts.length > 1 && parts[0].equals("1")) {
                return Integer.parseInt(parts[1]);
            }
            return parts.length > 0 && !parts[0].isEmpty() && parts[0].length() < 5 ? Integer.parseInt(parts[0]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private File remembered() {
        if (memory == null || !memory.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(memory)) {
            properties.load(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return file(properties.getProperty("java"));
    }

    /** A failure only means that the search runs again next time. */
    private void remember(File executable) {
        if (memory == null) {
            return;
        }
        Properties properties = new Properties();
        try {
            if (memory.isFile()) {
                try (InputStream in = new FileInputStream(memory)) {
                    properties.load(in);
                }
            }
            properties.setProperty("java", executable.getPath());
            File folder = memory.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                return;
            }
            try (OutputStream out = new FileOutputStream(memory)) {
                properties.store(out, "AskAI launcher: the Java " + minimum + "+ found last time");
            }
        } catch (IOException | RuntimeException ignored) {
            // not remembered
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<File> directories(File root) {
        List<File> result = new ArrayList<File>();
        File[] children = root == null ? null : root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    result.add(child);
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    /**
     * A path from a property, a variable or the registry; {@code null} if empty. One pair of surrounding double
     * quotes is dropped: the Windows variable editor keeps them literally ({@code "C:\Program Files\Zulu\zulu-21"}).
     */
    static File file(String text) {
        if (text == null) {
            return null;
        }
        String path = text.trim();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1).trim();
        }
        return path.isEmpty() ? null : new File(path);
    }
}

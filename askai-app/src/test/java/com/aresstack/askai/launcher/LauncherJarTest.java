package com.aresstack.askai.launcher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Starts a small jar like a user does ({@code java -jar}): the launcher classes as built for Java 8, and
 * {@link LauncherStubApp} as Start-Class in place of AskAI. Proves the decisions of {@code Launcher.run} on real
 * JVMs: on Java 21+ the app runs in the same JVM without any search; on Java 8 the launcher restarts the jar
 * with a Java 21+ and passes the exit code on. The real Java 8 case runs where {@code JAVA_HOME_8_X64} names a
 * Java 8 (GitHub's Ubuntu runners have one) and is skipped elsewhere; the same path also runs everywhere with a
 * launcher that is made to see Java 8 ({@link LauncherAsIfOldJava}).
 */
public class LauncherJarTest {

    private static final String STUB = LauncherStubApp.class.getName();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void onJava21OrNewerTheAppRunsInTheSameJvmAndNothingIsSearched() throws Exception {
        File home = folder.newFolder("home");
        File java = JavaRuntimeFinder.executable(new File(System.getProperty("java.home")));
        Result result = start(java, home, new ArrayList<String>());
        assertEquals(result.output, 3, result.exitCode);
        assertTrue(result.output, result.output.contains("stub-app java=" + System.getProperty("java.specification.version")
                + " relaunched=null args=[one, two words]"));
        assertFalse(result.output, result.output.contains("restarting with"));
        assertFalse("nothing searched, nothing remembered", new File(new File(home, ".askai"), "launcher.properties").exists());
    }

    @Test
    public void onJava8TheJarRestartsWithJava21AndPassesTheExitCodeOn() throws Exception {
        String java8Home = System.getenv("JAVA_HOME_8_X64");
        File java8 = java8Home == null ? null : JavaRuntimeFinder.executable(new File(java8Home));
        assumeTrue("no Java 8 named by JAVA_HOME_8_X64", java8 != null);
        assumeTrue("JAVA_HOME_8_X64 is no Java 8", new JavaRuntimeFinder(21, null).majorVersion(java8) == 8);

        File home = folder.newFolder("home");
        List<String> jvmArgs = new ArrayList<String>();
        jvmArgs.add("-Daskai.java=" + System.getProperty("java.home"));
        Result result = start(java8, home, jvmArgs);
        assertEquals(result.output, 3, result.exitCode);
        assertTrue(result.output, result.output.contains("started with Java 8, restarting with"));
        assertTrue(result.output, result.output.contains("stub-app java=" + System.getProperty("java.specification.version")
                + " relaunched=true args=[one, two words]"));
        File memory = new File(new File(home, ".askai"), "launcher.properties");
        assertTrue("the Java found is remembered", memory.isFile());
        assertTrue(new String(Files.readAllBytes(memory.toPath()), StandardCharsets.ISO_8859_1).contains("java="));
    }

    @Test
    public void whenTheJavaIsOlderThan21TheJarRestartsWithAJava21AndPassesTheExitCodeOn() throws Exception {
        // The same path as the Java 8 case above, on any machine: the launcher is made to see Java 8.
        File home = folder.newFolder("home");
        File java = JavaRuntimeFinder.executable(new File(System.getProperty("java.home")));
        List<String> jvmArgs = new ArrayList<String>();
        jvmArgs.add("-Daskai.java=" + System.getProperty("java.home"));
        Result result = start(java, home, jvmArgs, LauncherAsIfOldJava.class.getName());
        assertEquals(result.output, 3, result.exitCode);
        assertTrue(result.output, result.output.contains("started with Java 8, restarting with " + java.getPath()));
        assertTrue(result.output, result.output.contains("stub-app java=" + System.getProperty("java.specification.version")
                + " relaunched=true args=[one, two words]"));
        File memory = new File(new File(home, ".askai"), "launcher.properties");
        assertTrue("the Java found is remembered", memory.isFile());
    }

    /** Runs {@code java [jvmArgs] -Duser.home=<home> -jar launcher-test.jar one "two words"} and waits for it. */
    private Result start(File java, File home, List<String> jvmArgs) throws Exception {
        return start(java, home, jvmArgs, null);
    }

    /** As above; with a main class, the jar is put on the class path and that class is started instead. */
    private Result start(File java, File home, List<String> jvmArgs, String mainClass) throws Exception {
        File jar = buildJar();
        List<String> command = new ArrayList<String>();
        command.add(java.getPath());
        command.addAll(jvmArgs);
        command.add("-Duser.home=" + home.getPath());
        command.add("-Djava.awt.headless=true");
        if (mainClass == null) {
            command.addAll(Arrays.asList("-jar", jar.getPath()));
        } else {
            command.addAll(Arrays.asList("-cp", jar.getPath(), mainClass));
        }
        command.addAll(Arrays.asList("one", "two words"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // The memory goes to <user.home>/.askai only without APPDATA; nothing named from outside the test.
        builder.environment().remove("APPDATA");
        builder.environment().remove("ASKAI_JAVA");
        File log = new File(folder.getRoot(), "output-" + System.nanoTime() + ".txt");
        Process process = builder.redirectOutput(log).start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the jar did not end within 120 s:\n" + read(log));
        }
        return new Result(process.exitValue(), read(log));
    }

    /** The launcher classes (Java 8 class files) plus the stub app, with Main-Class and Start-Class. */
    private File buildJar() throws Exception {
        File launcherClasses = new File(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File stubClass = new File(new File(LauncherStubApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                STUB.replace('.', '/') + ".class");
        File launcherPackage = new File(launcherClasses, "com/aresstack/askai/launcher");
        File[] classes = launcherPackage.listFiles();
        assertTrue("launcher classes in " + launcherPackage, classes != null && classes.length > 0);

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Launcher.class.getName());
        manifest.getMainAttributes().putValue("Start-Class", STUB);
        File jar = new File(folder.getRoot(), "launcher-test.jar");
        if (jar.isFile()) {
            return jar;
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            for (File file : classes) {
                if (file.getName().endsWith(".class")) {
                    put(out, "com/aresstack/askai/launcher/" + file.getName(), file);
                }
            }
            put(out, STUB.replace('.', '/') + ".class", stubClass);
            String asIfOld = LauncherAsIfOldJava.class.getName().replace('.', '/') + ".class";
            put(out, asIfOld, new File(stubClass.getParentFile(), new File(asIfOld).getName()));
        }
        return jar;
    }

    private static void put(JarOutputStream out, String name, File file) throws IOException {
        out.putNextEntry(new JarEntry(name));
        Files.copy(file.toPath(), out);
        out.closeEntry();
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}

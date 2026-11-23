package com.aresstack.askai.launcher;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class JavaRuntimeFinderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void forgetTheNamedJava() {
        System.clearProperty("askai.java");
    }

    @Test
    public void versionsAreReadTheOldWayAndTheNew() {
        assertEquals(8, JavaRuntimeFinder.majorOf("1.8.0_392"));
        assertEquals(8, JavaRuntimeFinder.majorOf("1.8"));
        assertEquals(21, JavaRuntimeFinder.majorOf("21.0.5"));
        assertEquals(21, JavaRuntimeFinder.majorOf("21"));
        assertEquals(25, JavaRuntimeFinder.majorOf("25.0.1"));
        assertEquals(22, JavaRuntimeFinder.majorOf("22-ea"));
        assertEquals(-1, JavaRuntimeFinder.majorOf("unknown"));
        assertEquals(-1, JavaRuntimeFinder.majorOf(null));
    }

    @Test
    public void theAnswerOfAJavaIsUnderstood() {
        JavaRuntimeFinder.Probe zulu = JavaRuntimeFinder.parse("Property settings:\n"
                + "    java.vendor = Azul Systems, Inc.\n    java.version = 21.0.5\n"
                + "    os.arch = amd64\n    sun.arch.data.model = 64\n");
        assertEquals(21, zulu.major);
        assertEquals(64, zulu.bits);
        JavaRuntimeFinder.Probe javaCom = JavaRuntimeFinder.parse("Property settings:\n    java.version = 1.8.0_431\n"
                + "    os.arch = x86\n    sun.arch.data.model = 32\n");
        assertEquals(8, javaCom.major);
        assertEquals(32, javaCom.bits);
        JavaRuntimeFinder.Probe archOnly = JavaRuntimeFinder.parse("    java.version = 21.0.2\n    os.arch = x86\n");
        assertEquals(32, archOnly.bits);
        JavaRuntimeFinder.Probe old = JavaRuntimeFinder.parse("openjdk version \"1.8.0_392\"\nOpenJDK Runtime Environment\n");
        assertEquals(8, old.major);
        assertEquals(0, old.bits);
        assertEquals(-1, JavaRuntimeFinder.parse("not a java at all").major);
    }

    @Test
    public void bitnessIsReadFromTheArchitecture() {
        assertEquals(32, JavaRuntimeFinder.bitsOfArch("x86"));
        assertEquals(32, JavaRuntimeFinder.bitsOfArch("i686"));
        assertEquals(64, JavaRuntimeFinder.bitsOfArch("amd64"));
        assertEquals(64, JavaRuntimeFinder.bitsOfArch("x86_64"));
        assertEquals(64, JavaRuntimeFinder.bitsOfArch("aarch64"));
        assertEquals(0, JavaRuntimeFinder.bitsOfArch("sparcv9"));
        assertEquals(0, JavaRuntimeFinder.bitsOfArch(null));
    }

    @Test
    public void theRegistryNamesTheFoldersOfOracleZuluAdoptiumAndMicrosoft() {
        String output = "\r\n"
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK\r\n"
                + "    CurrentVersion    REG_SZ    21\r\n"
                + "\r\n"
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK\\21\r\n"
                + "    JavaHome    REG_SZ    C:\\Program Files\\Java\\jdk-21\r\n"
                + "    RuntimeLib    REG_SZ    C:\\Program Files\\Java\\jdk-21\\bin\\server\\jvm.dll\r\n"
                + "\r\n"
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\Azul Systems\\Zulu\\zulu-21\r\n"
                + "    InstallationPath    REG_SZ    C:\\Program Files\\Zulu\\zulu-21\\\r\n"
                + "    MajorVersion    REG_DWORD    0x15\r\n"
                + "\r\n"
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\Eclipse Adoptium\\JDK\\21.0.5.11\\hotspot\\MSI\r\n"
                + "    Path    REG_SZ    C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.5.11-hotspot\\\r\n"
                + "\r\n"
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\JDK\\21.0.5.11\\hotspot\\MSI\r\n"
                + "    Path    REG_EXPAND_SZ    C:\\Program Files\\Microsoft\\jdk-21.0.5.11-hotspot\\\r\n"
                + "    Main    REG_DWORD    0x1\r\n";
        List<File> folders = JavaRuntimeFinder.parseRegistry(output);
        assertEquals(Arrays.asList(
                new File("C:\\Program Files\\Java\\jdk-21"),
                new File("C:\\Program Files\\Zulu\\zulu-21\\"),
                new File("C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.5.11-hotspot\\"),
                new File("C:\\Program Files\\Microsoft\\jdk-21.0.5.11-hotspot\\")), folders);
        assertTrue(JavaRuntimeFinder.parseRegistry("ERROR: The system was unable to find the specified registry key or value.")
                .isEmpty());
    }

    @Test
    public void quotesAroundAPathAreDropped() {
        assertEquals(new File("C:\\Program Files\\Zulu\\zulu-21"),
                JavaRuntimeFinder.file(" \"C:\\Program Files\\Zulu\\zulu-21\" "));
        assertEquals(new File("/opt/jdk"), JavaRuntimeFinder.file("/opt/jdk"));
        assertNull(JavaRuntimeFinder.file("\"\""));
        assertNull(JavaRuntimeFinder.file("  "));
        assertNull(JavaRuntimeFinder.file(null));
    }

    @Test
    public void anInstallationIsFoundByItsFolderItsBinFolderOrItsJavaFile() throws Exception {
        File home = fakeInstallation("jdk-21", "21.0.5", "x86_64", 21, 64);
        File java = new File(new File(home, "bin"), "java");
        assertEquals(java.getAbsoluteFile(), JavaRuntimeFinder.executable(home));
        assertEquals(java.getAbsoluteFile(), JavaRuntimeFinder.executable(new File(home, "bin")));
        assertEquals(java.getAbsoluteFile(), JavaRuntimeFinder.executable(java));
        assertNull(JavaRuntimeFinder.executable(new File(folder.getRoot(), "nothing")));
    }

    @Test
    public void anInstallationThatNamesAnOlderVersionIsNotStarted() throws Exception {
        File home = folder.newFolder("jdk-17");
        File java = notAProgram(home);
        Files.write(new File(home, "release").toPath(),
                "IMPLEMENTOR=\"Somebody\"\nJAVA_VERSION=\"17.0.9\"\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(17, new JavaRuntimeFinder(21, null).majorVersion(java));
    }

    @Test
    public void anInstallationThatNamesA32BitBuildIsNotStarted() throws Exception {
        File home = folder.newFolder("jdk-21-x86");
        File java = notAProgram(home);
        Files.write(new File(home, "release").toPath(),
                "JAVA_VERSION=\"21.0.5\"\nOS_ARCH=\"x86\"\n".getBytes(StandardCharsets.UTF_8));
        JavaRuntimeFinder.Probe probe = new JavaRuntimeFinder(21, null).probe(java);
        assertEquals(32, probe.bits);
        assertFalse(new JavaRuntimeFinder(21, null).suitable(java));
    }

    @Test
    public void aNewerInstallationThatCannotStartIsNotTaken() throws Exception {
        // The release file says 25, but the "java" in it is no program: asked, it answers nothing.
        File home = folder.newFolder("jdk-25");
        File java = notAProgram(home);
        Files.write(new File(home, "release").toPath(), "JAVA_VERSION=\"25.0.1\"\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(-1, new JavaRuntimeFinder(21, null).majorVersion(java));
        assertFalse(new JavaRuntimeFinder(21, null).suitable(java));
    }

    @Test
    public void a32BitJavaThatSaysSoWhenAskedIsNotTaken() throws Exception {
        assumeFalse("fake installations are shell scripts", JavaRuntimeFinder.isWindows());
        File home = fakeInstallation("jdk-21-i686", null, null, 21, 32);
        File java = JavaRuntimeFinder.executable(home);
        assertEquals(21, new JavaRuntimeFinder(21, null).majorVersion(java));
        assertFalse(new JavaRuntimeFinder(21, null).suitable(java));
    }

    @Test
    public void aNamedJavaWinsOverTheRememberedOne() throws Exception {
        assumeFalse("fake installations are shell scripts", JavaRuntimeFinder.isWindows());
        File zulu21 = fakeInstallation("zulu-21", null, null, 21, 64);
        File jdk25 = fakeInstallation("jdk-25", null, null, 25, 64);
        File memory = new File(folder.getRoot(), "launcher.properties");
        System.setProperty("askai.java", zulu21.getPath());
        assertEquals(JavaRuntimeFinder.executable(zulu21), new JavaRuntimeFinder(21, memory).find());
        System.clearProperty("askai.java");
        assertEquals("remembered", JavaRuntimeFinder.executable(zulu21), new JavaRuntimeFinder(21, memory).find());

        // Named in quotes, as the Windows variable editor keeps them, and as the bin folder.
        System.setProperty("askai.java", "\"" + new File(jdk25, "bin").getPath() + "\"");
        assertEquals("the named Java wins", JavaRuntimeFinder.executable(jdk25), new JavaRuntimeFinder(21, memory).find());
        System.clearProperty("askai.java");
        assertEquals("and is remembered", JavaRuntimeFinder.executable(jdk25), new JavaRuntimeFinder(21, memory).find());
    }

    @Test
    public void aNamedJavaThatIsTooOldIsPassedOver() throws Exception {
        assumeFalse("fake installations are shell scripts", JavaRuntimeFinder.isWindows());
        File jdk17 = fakeInstallation("jdk-17", null, null, 17, 64);
        File zulu21 = fakeInstallation("zulu-21", null, null, 21, 64);
        File memory = new File(folder.getRoot(), "launcher.properties");
        Files.write(memory.toPath(), ("java=" + JavaRuntimeFinder.executable(zulu21).getPath().replace("\\", "\\\\"))
                .getBytes(StandardCharsets.ISO_8859_1));
        System.setProperty("askai.java", jdk17.getPath());
        assertEquals(JavaRuntimeFinder.executable(zulu21), new JavaRuntimeFinder(21, memory).find());
    }

    @Test
    public void theJavaTheTestsRunOnIsFoundAndRemembered() throws Exception {
        int own = Runtime.version().feature();
        File memory = new File(folder.getRoot(), "launcher.properties");
        System.setProperty("askai.java", System.getProperty("java.home"));
        File found = new JavaRuntimeFinder(own, memory).find();
        assertTrue("found through askai.java", found != null && found.isFile());
        String remembered = new String(Files.readAllBytes(memory.toPath()), StandardCharsets.ISO_8859_1);
        assertTrue("remembered for next time", remembered.contains("java="));
        System.clearProperty("askai.java");
        assertEquals("taken from the memory", found, new JavaRuntimeFinder(own, memory).find());
    }

    @Test
    public void aNewerJavaThanTheMinimumCounts() {
        // The tests run on Java 21 (or newer); a finder that needs at least 17 takes it, as one for 21 takes a 25.
        System.setProperty("askai.java", System.getProperty("java.home"));
        File found = new JavaRuntimeFinder(17, null).find();
        assertNotNull(found);
        assertTrue(new JavaRuntimeFinder(17, null).majorVersion(found) >= 21);
    }

    @Test
    public void aJavaOlderThanTheMinimumIsNotTaken() {
        int own = Runtime.version().feature();
        File ownJava = JavaRuntimeFinder.executable(new File(System.getProperty("java.home")));
        assertEquals(own, new JavaRuntimeFinder(own + 1, null).majorVersion(ownJava));
        assertFalse(new JavaRuntimeFinder(own + 1, null).suitable(ownJava));
        System.setProperty("askai.java", System.getProperty("java.home"));
        assertNull("no Java " + (own + 100) + " anywhere", new JavaRuntimeFinder(own + 100, null).find());
    }

    /** A folder with a bin/java that is no program. */
    private static File notAProgram(File home) throws IOException {
        File bin = new File(home, "bin");
        assertTrue(bin.mkdirs());
        File java = new File(bin, "java");
        Files.write(java.toPath(), "not executable".getBytes(StandardCharsets.UTF_8));
        return java;
    }

    /**
     * An installation whose bin/java is a shell script that answers like a real one. With a version for the
     * release file, the file is written too.
     */
    private File fakeInstallation(String name, String releaseVersion, String releaseArch, int major, int bits)
            throws IOException {
        File home = folder.newFolder(name);
        File bin = new File(home, "bin");
        assertTrue(bin.mkdirs());
        File java = new File(bin, "java");
        String script = "#!/bin/sh\n"
                + "cat >&2 <<'EOF'\n"
                + "Property settings:\n"
                + "    java.version = " + major + ".0.1\n"
                + "    os.arch = " + (bits == 32 ? "i386" : "amd64") + "\n"
                + "    sun.arch.data.model = " + bits + "\n"
                + "\n"
                + "openjdk version \"" + major + ".0.1\"\n"
                + "EOF\n";
        Files.write(java.toPath(), script.getBytes(StandardCharsets.UTF_8));
        assertTrue(java.setExecutable(true));
        if (releaseVersion != null) {
            Files.write(new File(home, "release").toPath(), ("JAVA_VERSION=\"" + releaseVersion + "\"\n"
                    + (releaseArch == null ? "" : "OS_ARCH=\"" + releaseArch + "\"\n")).getBytes(StandardCharsets.UTF_8));
        }
        return home;
    }
}

package com.aresstack.askai.launcher;

import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.WindowConstants;

/**
 * Entry point of the runnable AskAI fat jar ({@code Main-Class} in its manifest).
 *
 * <p>The app is compiled for Java 21. This class and {@link JavaRuntimeFinder} are compiled <em>for Java 8</em>
 * and depend on nothing in the app at compile time: a class file carries its own version, and a JVM only checks
 * the version of the classes it loads. So a double-click on the jar works with whatever Java the computer opens
 * jars with:</p>
 * <ol>
 *   <li>Started with Java 21 or newer (25 is fine), the launcher calls the app ({@code Start-Class} of the
 *       manifest, {@code com.aresstack.askai.AskAiApp}) in the same JVM. Nothing is searched, nothing restarts.</li>
 *   <li>Started with an older Java, it looks for an installed 64-bit Java 21 or newer ({@link JavaRuntimeFinder})
 *       and starts the same jar with it, with the same arguments; the old JVM waits and passes on the exit code.</li>
 *   <li>If there is none, it says so in a dialog that offers the download page of Azul Zulu (free of licence
 *       costs) and exits.</li>
 * </ol>
 *
 * <p>Which Java to use can be named with {@code -Daskai.java=...} or the environment variable {@code ASKAI_JAVA}
 * (its folder, its bin folder or its {@code java} file); that wins over the search. The installation found is
 * remembered in {@code %APPDATA%\.askai\launcher.properties}.</p>
 *
 * <p>Ported from the launcher of Space Harvester (aresstack/space-harvester), which in turn comes from Audio
 * Studio (aresstack/audio) and 3d4j; at runtime the programs share nothing.</p>
 */
public final class Launcher {
    /** The Java version the app is compiled for; this one or any newer one runs it. */
    static final int REQUIRED_JAVA = 21;
    /** The app's main class when the manifest names none (started from an IDE or a class folder). */
    static final String DEFAULT_START_CLASS = "com.aresstack.askai.AskAiApp";
    static final String RELAUNCHED_PROPERTY = "askai.launcher.relaunched";
    private static final String TITLE = "AskAI";

    /** What the launcher does with the Java it was started with. */
    enum Decision {
        /** Java 21 or newer: start the app in this JVM, search nothing. */
        RUN_HERE,
        /** Older: look for an installed Java 21+ and restart with it. */
        SEARCH_AND_RESTART,
        /** Older although this JVM is already the restart: stop instead of restarting in a loop. */
        STOP_LOOP
    }

    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            fail("AskAI could not start:\n" + t, true);
        }
    }

    private static void run(String[] args) throws Throwable {
        int running = runningJava();
        Decision decision = decide(running, Boolean.getBoolean(RELAUNCHED_PROPERTY));
        if (decision == Decision.STOP_LOOP) {
            throw new IllegalStateException("the Java chosen for the restart is Java " + running
                    + ", AskAI needs Java " + REQUIRED_JAVA + " or newer");
        }
        if (decision == Decision.SEARCH_AND_RESTART) {
            File found = new JavaRuntimeFinder(REQUIRED_JAVA, memoryFile()).find();
            if (found == null) {
                javaMissing(running);
                return;
            }
            System.err.println("[askai] started with Java " + running + ", restarting with " + found);
            // The second JVM reports its own errors; only its exit code is passed on.
            System.exit(relaunch(sameKind(found), args));
        }
        Class<?> mainClass = Class.forName(startClass(), true, Launcher.class.getClassLoader());
        Method main = mainClass.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The first thing the launcher checks: only an older Java than 21 leads to a search. */
    static Decision decide(int runningJava, boolean relaunched) {
        if (runningJava >= REQUIRED_JAVA) {
            return Decision.RUN_HERE;
        }
        return relaunched ? Decision.STOP_LOOP : Decision.SEARCH_AND_RESTART;
    }

    /** The feature version of this JVM: 8 for "1.8", 21 for "21". */
    static int runningJava() {
        return JavaRuntimeFinder.majorOf(System.getProperty("java.specification.version"));
    }

    /** Where the Java found is remembered: next to the app's settings ({@code %APPDATA%\.askai}). */
    static File memoryFile() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        return new File(new File(appData, ".askai"), "launcher.properties");
    }

    /** {@code Start-Class} of the jar this launcher was loaded from, or the app's main class. */
    private static String startClass() {
        try {
            URL location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(location.toURI());
            if (jar.isFile()) {
                try (JarFile file = new JarFile(jar)) {
                    return startClass(file.getManifest());
                }
            }
        } catch (Exception ignored) {
            // not in a jar, or not readable: the default
        }
        return DEFAULT_START_CLASS;
    }

    static String startClass(Manifest manifest) {
        String named = manifest == null ? null : manifest.getMainAttributes().getValue("Start-Class");
        return named == null || named.trim().isEmpty() ? DEFAULT_START_CLASS : named.trim();
    }

    /** No Java 21+ here: says so, offers the download page and exits with 1. */
    private static void javaMissing(int running) {
        String url = downloadUrl(System.getProperty("os.name", ""));
        String message = missingJavaMessage(running, url, JavaRuntimeFinder.named());
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                Object[] options = {"Open download page", "Close"};
                JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION,
                        null, options, options[0]);
                if (options[0].equals(show(pane, TITLE + " needs Java " + REQUIRED_JAVA))) {
                    openInBrowser(url);
                }
            } catch (Throwable noScreen) {
                // The message on stderr has to do.
            }
        }
        System.exit(1);
    }

    static String missingJavaMessage(int running, String url, List<String> named) {
        StringBuilder message = new StringBuilder();
        message.append("AskAI needs a 64-bit Java ").append(REQUIRED_JAVA).append(" or newer. It was started with Java ")
                .append(running).append(",\nand no Java ").append(REQUIRED_JAVA)
                .append(" or newer was found on this computer.\n\n");
        for (String place : named) {
            message.append("Named with ASKAI_JAVA / -Daskai.java, but no 64-bit Java ").append(REQUIRED_JAVA)
                    .append("+ there:\n").append(place).append("\n\n");
        }
        message.append("Searched: JAVA_HOME, the PATH, the Windows registry and the usual installation folders.\n\n")
                .append("Azul Zulu is free of licence costs: install a JDK ").append(REQUIRED_JAVA)
                .append(" (or newer) from\n").append(url).append("\nand start AskAI again.\n\n")
                .append("A Java ").append(REQUIRED_JAVA)
                .append("+ in another place can be named with the environment variable\n")
                .append("ASKAI_JAVA (or -Daskai.java=...), as its folder or its java file.");
        return message.toString();
    }

    /** The Azul Zulu download page, already filtered to JDK 21 for this operating system. */
    static String downloadUrl(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        String filter = os.contains("win") ? "&os=windows&architecture=x86-64-bit"
                : os.contains("mac") ? "&os=macos"
                : os.contains("linux") ? "&os=linux&architecture=x86-64-bit" : "";
        return "https://www.azul.com/downloads/?version=java-" + REQUIRED_JAVA + "-lts" + filter + "&package=jdk#zulu";
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) {
            // falls through to the hint
        }
        try {
            show(new JOptionPane("Please open this address in a browser:\n" + url, JOptionPane.INFORMATION_MESSAGE),
                    TITLE);
        } catch (Throwable ignored) {
            // stderr has the address already
        }
    }

    /**
     * Shows a message in a dialog of its own and returns the value chosen. The dialog has no owner, so Windows
     * gives it a taskbar button, and it stays on top: a search of several seconds may have passed since the
     * double-click, and a dialog owned by Swing's hidden frame could open unseen behind other windows.
     */
    private static Object show(final JOptionPane pane, String title) {
        final JDialog dialog = new JDialog((Window) null, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(pane);
        pane.addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent event) {
                if (JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName()) && dialog.isVisible()) {
                    dialog.setVisible(false);
                }
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true); // modal: returns when a button or the close box was used
        dialog.dispose();
        return pane.getValue();
    }

    /**
     * Reports a fatal error and exits. A jar started by a double-click runs on {@code javaw} without a console,
     * so the message also goes into a dialog when there is a screen.
     */
    private static void fail(String message, boolean alreadyPrinted) {
        if (!alreadyPrinted) {
            System.err.println(message);
        }
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                show(new JOptionPane(message, JOptionPane.ERROR_MESSAGE), TITLE);
            } catch (Throwable ignored) {
                // no screen: stderr has to do
            }
        }
        System.exit(1);
    }

    /** Starts another Java with this jar and the same arguments and waits for it to end. */
    private static int relaunch(String executable, String[] args) throws Exception {
        List<String> command = relaunchCommand(executable, ManagementFactory.getRuntimeMXBean().getInputArguments(),
                System.getProperty("java.class.path"), args);
        final Process child = new ProcessBuilder(command).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                child.destroy();
            }
        }, "askai-launcher-cleanup"));
        return child.waitFor();
    }

    /**
     * The command of the restart: the found Java, the system properties and heap sizes of this JVM (another
     * Java may not know its other options), the restart marker, this class path, this class, the arguments.
     */
    static List<String> relaunchCommand(String executable, List<String> jvmArgs, String classPath, String[] args) {
        List<String> command = new ArrayList<String>();
        command.add(executable);
        for (String jvmArg : jvmArgs) {
            if ((jvmArg.startsWith("-D") && !jvmArg.startsWith("-D" + RELAUNCHED_PROPERTY + "="))
                    || jvmArg.startsWith("-Xmx") || jvmArg.startsWith("-Xms") || jvmArg.startsWith("-Xss")) {
                command.add(jvmArg);
            }
        }
        command.add("-D" + RELAUNCHED_PROPERTY + "=true");
        command.add("-cp");
        command.add(classPath);
        command.add(Launcher.class.getName());
        command.addAll(Arrays.asList(args));
        return command;
    }

    /** For a found Java: javaw.exe when this JVM runs without a console, so that no console window opens. */
    private static String sameKind(File java) {
        if (windowless()) {
            File javaw = new File(java.getParentFile(), "javaw.exe");
            if (javaw.isFile()) {
                return javaw.getPath();
            }
        }
        return java.getPath();
    }

    /**
     * Whether this JVM was started without a console, as a double-click on a jar does. Java 8 cannot tell which
     * executable started it; on Windows a missing console is the sign.
     */
    private static boolean windowless() {
        return JavaRuntimeFinder.isWindows() && System.console() == null;
    }
}

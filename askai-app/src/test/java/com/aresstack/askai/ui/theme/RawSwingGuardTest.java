package com.aresstack.askai.ui.theme;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * E4: no raw Swing control is instantiated in the app or the research plugin — every one is a comic control
 * ({@code ComicButton}, {@code ComicControls.*}, ...). Scans the main sources of both modules; a hit outside the
 * allowlist fails the test with file and line.
 */
public class RawSwingGuardTest {

    static final Pattern RAW_CONTROL = Pattern.compile(
            "new (javax\\.swing\\.)?(JButton|JCheckBox|JComboBox|JSpinner|JTextField|JPasswordField|JTextArea"
                    + "|JTabbedPane|JList|JProgressBar|JSlider|JToggleButton)(<[^>]*>)?\\(");
    static final Pattern RAW_TITLED_BORDER = Pattern.compile("createTitledBorder\\(|new TitledBorder\\(");

    /** Explicit exceptions, "path suffix" → reason. Deliberately empty. */
    static final Map<String, String> ALLOWLIST = Collections.emptyMap();

    @Test
    public void noRawSwingControlsInAppOrPlugin() throws IOException {
        List<String> hits = new ArrayList<String>();
        File root = projectRoot();
        scan(new File(root, "askai-app/src/main"), hits);
        scan(new File(root, "research-agent-ui-plugin/src/main"), hits);
        assertTrue("raw Swing controls found (use ComicButton / ComicControls):\n" + String.join("\n", hits),
                hits.isEmpty());
    }

    @Test
    public void theScanReallySeesTheSources() {
        File root = projectRoot();
        assertTrue(new File(root, "askai-app/src/main/java").isDirectory());
        assertTrue(new File(root, "research-agent-ui-plugin/src/main/java").isDirectory());
        assertTrue(RAW_CONTROL.matcher("x = new JButton(\"a\") {").find());
        assertTrue(RAW_CONTROL.matcher("new javax.swing.JComboBox<String>(items)").find());
        assertTrue(RAW_TITLED_BORDER.matcher("BorderFactory.createTitledBorder(\"x\")").find());
    }

    /** The repository root, whether the tests run from the module directory or from the root. */
    static File projectRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File candidate = dir; candidate != null; candidate = candidate.getParentFile()) {
            if (new File(candidate, "askai-app").isDirectory()
                    && new File(candidate, "research-agent-ui-plugin").isDirectory()) {
                return candidate;
            }
        }
        throw new IllegalStateException("repository root not found from " + dir);
    }

    private static void scan(File dir, List<String> hits) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scan(child, hits);
            } else if (child.getName().endsWith(".java") && !allowed(child)) {
                List<String> lines = Files.readAllLines(child.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    Matcher control = RAW_CONTROL.matcher(line);
                    if (control.find() || RAW_TITLED_BORDER.matcher(line).find()) {
                        hits.add(child.getPath() + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }
    }

    private static boolean allowed(File file) {
        String path = file.getPath().replace('\\', '/');
        for (String suffix : ALLOWLIST.keySet()) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}

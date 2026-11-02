package com.aresstack.askai.ui.theme;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * T7: no color literal is left in the app or the research plugin — every color is a theme token
 * ({@code ThemeColor}). Scans the main sources of both modules for {@code new Color(}, {@code new java.awt.Color(}
 * and the {@code Color} constants, and for {@code #RRGGBB}/{@code #RGB} colors inside string literals. A hit outside
 * the allowlist fails with file and line.
 */
public class RawColorGuardTest {

    static final Pattern RAW_COLOR = Pattern.compile(
            "new (java\\.awt\\.)?Color\\(|Color\\.(WHITE|BLACK|GRAY|LIGHT_GRAY|DARK_GRAY|RED|GREEN|BLUE|YELLOW|ORANGE"
                    + "|PINK|MAGENTA|CYAN|white|black|gray|lightGray|darkGray|red|green|blue|yellow|orange|pink"
                    + "|magenta|cyan)\\b");
    static final Pattern HEX_IN_STRING = Pattern.compile("\"[^\"]*#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?\\b[^\"]*\"");

    /** Explicit exceptions, "path suffix:line content fragment" → reason. */
    static final Map<String, String> ALLOWLIST;

    static {
        Map<String, String> allow = new LinkedHashMap<String, String>();
        allow.put("config/ChatColorSettings.java:new Color(Integer.parseInt(trimmed, 16)",
                "parses a USER-CHOSEN chat color from the settings file (spec T7 rule 6: user colors are no literals)");
        ALLOWLIST = Collections.unmodifiableMap(allow);
    }

    @Test
    public void noColorLiteralsInAppOrPlugin() throws IOException {
        List<String> hits = new ArrayList<String>();
        File root = RawSwingGuardTest.projectRoot();
        scan(new File(root, "askai-app/src/main"), hits);
        scan(new File(root, "research-agent-ui-plugin/src/main"), hits);
        assertTrue("color literals found (use ThemeColor tokens from AskAiTheme):\n" + String.join("\n", hits),
                hits.isEmpty());
    }

    @Test
    public void thePatternsCatchTheLiteralForms() {
        assertTrue(RAW_COLOR.matcher("x.setBackground(new Color(0xFFFFFF));").find());
        assertTrue(RAW_COLOR.matcher("g.setColor(new java.awt.Color(1, 2, 3, 4));").find());
        assertTrue(RAW_COLOR.matcher("label.setForeground(Color.GRAY);").find());
        assertTrue(HEX_IN_STRING.matcher("\"<b style='color:#2e8b57;'>\"").find());
        assertTrue(HEX_IN_STRING.matcher("\"color:#666\"").find());
    }

    private static void scan(File dir, List<String> hits) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scan(child, hits);
            } else if (child.getName().endsWith(".java")) {
                String path = child.getPath().replace('\\', '/');
                List<String> lines = Files.readAllLines(child.toPath(), StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    boolean hit = RAW_COLOR.matcher(line).find() || HEX_IN_STRING.matcher(line).find();
                    if (hit && !allowed(path, line)) {
                        hits.add(path + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }
    }

    private static boolean allowed(String path, String line) {
        for (String entry : ALLOWLIST.keySet()) {
            int colon = entry.indexOf(':');
            if (path.endsWith(entry.substring(0, colon)) && line.contains(entry.substring(colon + 1))) {
                return true;
            }
        }
        return false;
    }
}

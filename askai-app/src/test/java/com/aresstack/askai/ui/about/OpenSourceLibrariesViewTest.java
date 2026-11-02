package com.aresstack.askai.ui.about;

import org.junit.Test;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Help > About: the library list reads the build report; the license column is exactly the logo's width. */
public class OpenSourceLibrariesViewTest {

    @Test
    public void theReportIsParsedWithTheModule() throws IOException {
        List<OssLicenseEntry> entries = new ClasspathOssLicenseRepository().parseEntries("[\n"
                + "{\"group\":\"org.example\",\"artifact\":\"lib\",\"version\":\"1.2.3\",\"license\":\"Apache-2.0\",\"module\":\"askai-app\"},\n"
                + "{\"group\":\"org.example\",\"artifact\":\"with-\\\"quote\\\"\",\"version\":\"2.0\",\"license\":\"MIT\"}\n]");
        assertEquals(2, entries.size());
        assertEquals("org.example:lib", entries.get(0).getName());
        assertEquals("askai-app", entries.get(0).getModule());
        assertEquals("with-\"quote\"", entries.get(1).getArtifact());
        assertEquals("", entries.get(1).getModule());
    }

    @Test
    public void theBuildShipsTheReport() throws IOException {
        List<OssLicenseEntry> all = new ClasspathOssLicenseRepository().findAll();
        assertTrue("generateOssLicenseReport runs before processResources", all.size() > 0);
        boolean camoufox = false;
        for (OssLicenseEntry entry : all) {
            camoufox |= "camoufox".equals(entry.getArtifact()) && "MPL-2.0".equals(entry.getLicense());
        }
        assertTrue("Camoufox (downloaded at runtime) is listed", camoufox);
    }

    @Test
    public void licensesSitInALogoWideColumnLibrariesInTheRest() {
        OssLicenseRepository repository = () -> Arrays.asList(
                new OssLicenseEntry("org.example", "lib", "1.2.3", "Apache License, Version 2.0 / GNU General "
                        + "Public License (GPL) version 2, or any later version", "askai-app"),
                new OssLicenseEntry("org.example", "other", "2.0", "MIT", ""));
        OpenSourceLibrariesView view = new OpenSourceLibrariesView(repository, 256, 20);
        view.setSize(900, 600);
        layoutAll(view);
        List<JLabel> labels = new ArrayList<JLabel>();
        collect(view, labels);
        JLabel license = find(labels, "Apache License");
        JLabel library = find(labels, "<b>lib</b>");
        assertEquals("the license column is the logo's width", 256, license.getWidth());
        int libraryX = javax.swing.SwingUtilities.convertPoint(library.getParent(), library.getLocation(), view).x;
        int licenseX = javax.swing.SwingUtilities.convertPoint(license.getParent(), license.getLocation(), view).x;
        assertEquals("the library column starts after the license column and the gap", licenseX + 256 + 20, libraryX);
    }

    private static JLabel find(List<JLabel> labels, String text) {
        for (JLabel label : labels) {
            if (label.getText() != null && label.getText().contains(text)) {
                return label;
            }
        }
        throw new AssertionError("no label with " + text);
    }

    private static void collect(Container container, List<JLabel> labels) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel) {
                labels.add((JLabel) child);
            }
            if (child instanceof Container) {
                collect((Container) child, labels);
            }
        }
    }

    private static void layoutAll(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutAll((Container) child);
            }
        }
    }
}

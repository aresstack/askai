package com.aresstack.askai.ui.about;

import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.List;

/**
 * The third-party libraries of the build (Help > About), as two columns: on the LEFT the license in a column
 * exactly {@code licenseWidth} wide (the About logo's width, so it sits right under the logo), on the RIGHT
 * name, version, Maven group and the AskAI module of the library.
 */
public final class OpenSourceLibrariesView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int LICENSE_TEXT_INSET = 24;
    private final int licenseWidth;
    private final int gap;

    public OpenSourceLibrariesView(OssLicenseRepository repository, int licenseWidth, int gap) {
        super(new BorderLayout());
        this.licenseWidth = licenseWidth;
        this.gap = gap;
        setOpaque(false);
        List<OssLicenseEntry> entries;
        try {
            entries = repository.findAll();
        } catch (IOException exception) {
            JLabel error = new JLabel("Open-source library list unavailable: " + exception.getMessage());
            error.setForeground(ThemeColor.of("models.aboutProjectText"));
            add(error, BorderLayout.NORTH);
            return;
        }
        JPanel rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        addRow(rows, c, heading("License"), heading("Open-source libraries (" + entries.size() + ")"));
        for (OssLicenseEntry entry : entries) {
            addRow(rows, c, license(entry), library(entry));
        }
        // the filler takes the leftover height only (no width: column 0 stays exactly logo-wide)
        c.gridx = 0;
        c.weightx = 0;
        c.weighty = 1;
        c.fill = GridBagConstraints.NONE;
        rows.add(javax.swing.Box.createGlue(), c);
        JScrollPane scroll = new ComicScrollPane(rows,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private void addRow(JPanel rows, GridBagConstraints c, JComponent left, JComponent right) {
        // the license column is exactly the logo's width; the library column takes all the rest
        left.setPreferredSize(new Dimension(licenseWidth, left.getPreferredSize().height));
        left.setMinimumSize(left.getPreferredSize());
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(4, 0, 4, gap);
        rows.add(left, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 0, 4, 0);
        rows.add(right, c);
        c.gridy++;
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(ResearchUiTypography.semiBold(12f));
        label.setBorder(BorderFactory.createEmptyBorder(16, 0, 2, 0));
        return label;
    }

    private JLabel license(OssLicenseEntry entry) {
        // HTML wraps long license names inside the logo-wide column
        // (a little narrower than the column: the HTML view adds its own margins and would clip the right edge)
        JLabel label = new JLabel("<html><div style='width:" + (licenseWidth - LICENSE_TEXT_INSET) + "px'>"
                + escape(entry.getLicense()) + "</div></html>");
        label.setFont(ResearchUiTypography.regular(12f));
        label.setVerticalAlignment(JLabel.TOP);
        return label;
    }

    private static JComponent library(OssLicenseEntry entry) {
        JPanel library = new JPanel(new BorderLayout(0, 1));
        library.setOpaque(false);
        JLabel name = new JLabel("<html><b>" + escape(entry.getArtifact()) + "</b>&nbsp;&nbsp;"
                + escape(entry.getVersion()) + "</html>");
        name.setFont(ResearchUiTypography.regular(12f));
        library.add(name, BorderLayout.NORTH);
        String detail = entry.getGroup() + (entry.getModule().length() > 0 ? "  ·  used by " + entry.getModule() : "");
        JLabel group = new JLabel(detail);
        group.setFont(ResearchUiTypography.regular(11f));
        group.setForeground(ThemeColor.of("models.aboutDescriptionText"));
        library.add(group, BorderLayout.CENTER);
        return library;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

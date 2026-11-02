package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Product "About" screen. Deliberately free of internal development notes: it shows the product name,
 * a short description, the version/build (when packaged), the Java runtime and OS, and a project line,
 * plus a "Copy diagnostics" action. The diagnostics text never includes secrets such as the Hugging
 * Face token.
 */
public final class OllamaAboutPanel extends JPanel {

    private static final String PRODUCT = "AskAI";
    private static final int LOGO_SIZE = 256;
    /** The gap between the logo and the text — also between the license and library columns below. */
    private static final int HEADER_GAP = 20;
    private static final String DESCRIPTION =
            "Local model management and chat for Ollama, with a Hugging Face import flow.";
    private static final String PROJECT_LINE =
            "Java 8 desktop client · see the project repository for licensing and source.";

    /** The footer (Back, Copy diagnostics, copyright) comes from the {@link FooterScreen} around it. */
    public OllamaAboutPanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 16));
        JPanel header = new JPanel(new BorderLayout(HEADER_GAP, 0));
        JComponent logo = buildLogo();
        if (logo != null) {
            header.add(logo, BorderLayout.WEST);
        }
        // NORTH-pinned: the info rows keep their natural spacing instead of spreading over the logo's height
        JPanel contentTop = new JPanel(new BorderLayout());
        contentTop.add(buildContent(), BorderLayout.NORTH);
        header.add(contentTop, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        // The third-party libraries: the license column right under the logo (same width), the library
        // details in the column of the text above.
        add(new com.aresstack.askai.ui.about.OpenSourceLibrariesView(
                new com.aresstack.askai.ui.about.ClasspathOssLicenseRepository(),
                logo != null ? logo.getPreferredSize().width : LOGO_SIZE, HEADER_GAP), BorderLayout.CENTER);
    }

    /** The app icon at full About size — so the user can see what the tiny taskbar version shows. */
    private static JComponent buildLogo() {
        java.awt.Image image = AppIcon.sized(LOGO_SIZE);
        if (image == null) {
            return null;
        }
        JLabel logo = new JLabel(new javax.swing.ImageIcon(image));
        logo.setVerticalAlignment(JLabel.TOP);
        logo.setToolTipText("A · sk · A · i — the golden \"A\", \"sk\" on the wizard's robe, "
                + "the wizard himself as the second \"A\" and his sparkler staff as the \"i\".");
        return logo;
    }

    private JComponent buildContent() {
        JPanel content = new JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));

        JLabel title = new JLabel(PRODUCT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 8f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        content.add(title);

        JLabel description = new JLabel(DESCRIPTION);
        description.setForeground(ThemeColor.of("models.aboutDescriptionText"));
        description.setAlignmentX(LEFT_ALIGNMENT);
        description.setBorder(BorderFactory.createEmptyBorder(4, 0, 12, 0));
        content.add(description);

        content.add(infoRow("Version", version()));
        String build = buildInfo();
        if (build.length() > 0) {
            content.add(infoRow("Build", build));
        }
        content.add(infoRow("Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"));
        content.add(infoRow("OS", System.getProperty("os.name") + " " + System.getProperty("os.version")));

        JLabel project = new JLabel(PROJECT_LINE);
        project.setForeground(ThemeColor.of("models.aboutProjectText"));
        project.setAlignmentX(LEFT_ALIGNMENT);
        project.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        content.add(project);
        return content;
    }

    /** "Copy diagnostics" — the About footer's centered action. */
    public static JButton createCopyDiagnosticsButton() {
        JButton copy = new ComicButton("Copy diagnostics");
        copy.setToolTipText("Copy version and environment details to the clipboard (no secrets included)");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(diagnostics()), null));
        return copy;
    }

    private JComponent infoRow(String label, String value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 1));
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel key = new JLabel(label + ":  ");
        key.setFont(key.getFont().deriveFont(Font.BOLD));
        row.add(key);
        row.add(new JLabel(value));
        return row;
    }

    /**
     * @return a plain-text diagnostics block (product, version, build, Java, OS). Intentionally free
     *         of any credentials — the Hugging Face token is never included.
     */
    public static String diagnostics() {
        StringBuilder builder = new StringBuilder();
        builder.append(PRODUCT).append('\n');
        builder.append("Version: ").append(version()).append('\n');
        String build = buildInfo();
        if (build.length() > 0) {
            builder.append("Build: ").append(build).append('\n');
        }
        builder.append("Java: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")").append('\n');
        builder.append("OS: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version"))
                .append(" (").append(System.getProperty("os.arch")).append(')');
        return builder.toString();
    }

    private static String version() {
        String implementation = OllamaAboutPanel.class.getPackage().getImplementationVersion();
        return implementation == null || implementation.trim().length() == 0 ? "development build" : implementation;
    }

    /** @return a build identifier from the packaged manifest, or "" when running unpackaged. */
    private static String buildInfo() {
        Package pkg = OllamaAboutPanel.class.getPackage();
        String vendorVersion = pkg == null ? null : pkg.getSpecificationVersion();
        return vendorVersion == null ? "" : vendorVersion;
    }
}

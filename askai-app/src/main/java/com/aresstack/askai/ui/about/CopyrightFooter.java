package com.aresstack.askai.ui.about;

import com.aresstack.askai.ui.markdown.DesktopLinkOpener;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;

/**
 * The small copyright line bottom right (as in the Enterprise AI Client): "© 2026 AresStack", all grey; the name
 * turns into a blue underlined link only on hover and opens the AskAI repository. The address lives in
 * {@code copyright-footer.properties} next to this class.
 */
public final class CopyrightFooter extends JPanel {

    private static final long serialVersionUID = 1L;
    static final String RESOURCE = "copyright-footer.properties";
    private static final float FONT_SIZE = 11f;

    private final JLabel link;

    public CopyrightFooter() {
        this(repositoryUrl(), DesktopLinkOpener.systemDefault());
    }

    CopyrightFooter(final String url, final DesktopLinkOpener opener) {
        super(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        setOpaque(false);
        Font font = ResearchUiTypography.regular(FONT_SIZE);
        JLabel copyright = new JLabel("© 2026 ");
        copyright.setFont(font);
        copyright.setForeground(ResearchUiPalette.LIGHT_TEXT_MUTED);
        link = new JLabel("AresStack");
        link.setFont(font);
        link.setForeground(ResearchUiPalette.LIGHT_TEXT_MUTED);
        if (url != null && !url.isEmpty()) {
            final Font underlined = font.deriveFont(
                    Collections.singletonMap(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON));
            final Font plain = font;
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.setToolTipText(url);
            link.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    opener.open(url);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    link.setFont(underlined);
                    link.setForeground(ResearchUiPalette.ACCENT_BLUE);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    link.setFont(plain);
                    link.setForeground(ResearchUiPalette.LIGHT_TEXT_MUTED);
                }
            });
        }
        add(copyright);
        add(link);
    }

    JLabel link() {
        return link;
    }

    /** The repository address from the resource; empty when it is missing (then the name is plain text). */
    static String repositoryUrl() {
        Properties properties = new Properties();
        InputStream in = CopyrightFooter.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return "";
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return "";
        }
        return properties.getProperty("repository.url", "").trim();
    }
}

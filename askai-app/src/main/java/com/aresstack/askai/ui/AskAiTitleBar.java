package com.aresstack.askai.ui;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution;
import com.aresstack.comiccontrols.control.ComicSearchTag;
import com.aresstack.comiccontrols.control.ComicWindowHoldCloseButton;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.util.function.Supplier;

/**
 * AskAI's own window title bar (the window is frameless): on the LEFT the app icon and the window title,
 * exactly where Windows shows them; in the CENTER the title-bar SEARCH of the active agent; on the RIGHT the
 * comic window control (click minimizes, hold closes). The whole bar is the drag handle (double-click
 * maximizes) — wired by the frame.
 *
 * <p>The search is a container: the host owns ONE field, the active agent's {@link AgentSearchContribution}
 * defines placeholder, tooltip and what a search does. Without a contribution the field is hidden.</p>
 */
public final class AskAiTitleBar extends JPanel {

    private static final long serialVersionUID = 1L;
    /** The bar's height (Windows 11 caption height). */
    static final int HEIGHT = 34;

    private final JLabel titleLabel = new JLabel();
    private final JPanel right = new JPanel();
    /** Controls right-aligned directly LEFT of the close chip (e.g. connection dot, settings gear). */
    private final JPanel trailingControls = new JPanel();
    private final JPanel searchSlot = new JPanel(new GridBagLayout());
    /** The app's main navigation (Chat, Batch), centered between the title and the search. */
    private final JPanel navigationSlot = new JPanel();
    private AgentSearchContribution boundSearch;
    private Supplier<AgentSession> sessionSource;
    private ComicSearchTag searchField;

    public AskAiTitleBar(String title, Runnable minimizeAction, Runnable closeAction) {
        super(new BorderLayout(8, 0));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 6));
        setPreferredSize(new Dimension(10, HEIGHT));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.setOpaque(false);
        Image icon = AppIcon.sized(16);
        if (icon != null) {
            JLabel iconLabel = new JLabel(new ImageIcon(icon.getScaledInstance(16, 16, Image.SCALE_SMOOTH)));
            left.add(iconLabel);
            left.add(Box.createHorizontalStrut(8));
        }
        titleLabel.setText(title == null ? "" : title);
        titleLabel.setFont(ResearchUiTypography.regular(12.5f));
        left.add(titleLabel);
        add(left, BorderLayout.WEST);

        searchSlot.setOpaque(false);
        searchSlot.setVisible(false);
        // CENTER: the search centered on the WHOLE bar (not just the free space between title and controls);
        // the navigation centered in the space left of the search.
        final JPanel middle = new JPanel();
        middle.setOpaque(false);
        navigationSlot.setOpaque(false);
        navigationSlot.setLayout(new BoxLayout(navigationSlot, BoxLayout.X_AXIS));
        middle.setLayout(new CenteredSearchLayout());
        middle.add(navigationSlot);
        middle.add(searchSlot);
        add(middle, BorderLayout.CENTER);

        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.setOpaque(false);
        right.add(trailingControls);
        right.add(Box.createHorizontalStrut(6));
        right.add(new ComicWindowHoldCloseButton(ComicPalette.defaultPalette(), minimizeAction, closeAction, 24));
        trailingControls.setLayout(new BoxLayout(trailingControls, BoxLayout.X_AXIS));
        trailingControls.setOpaque(false);
        add(right, BorderLayout.EAST);
    }

    /** Adds a control right-aligned directly left of the window control (in the given order). */
    public void addTrailingControl(JComponent control) {
        if (trailingControls.getComponentCount() > 0) {
            trailingControls.add(Box.createHorizontalStrut(6));
        }
        control.setAlignmentY(CENTER_ALIGNMENT);
        trailingControls.add(control);
        trailingControls.revalidate();
    }

    /** Adds a navigation control (e.g. "Chat"), centered between the title and the search. */
    public void addNavigation(JComponent control) {
        if (navigationSlot.getComponentCount() > 0) {
            navigationSlot.add(Box.createHorizontalStrut(6));
        }
        control.setAlignmentY(CENTER_ALIGNMENT);
        navigationSlot.add(control);
        navigationSlot.revalidate();
    }

    /** The title shown next to the icon (follows the frame title). */
    public void setTitle(String title) {
        titleLabel.setText(title == null ? "" : title);
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    /**
     * Binds the search field to the active agent's contribution ({@code null} hides the field); the
     * session supplier yields the session a search runs for. Rebuilds the field only when the
     * contribution changes, so typing is never interrupted by unrelated agent events.
     */
    public void bindSearch(AgentSearchContribution search, Supplier<AgentSession> sessions) {
        this.sessionSource = sessions;
        if (search == boundSearch && (search == null || searchField != null)) {
            return;
        }
        boundSearch = search;
        searchSlot.removeAll();
        searchField = null;
        if (search != null) {
            final ComicSearchTag field = new ComicSearchTag(search.getPlaceholder(), search.getTooltip());
            field.addSearchAction(event -> runSearch(field));
            searchField = field;
            searchSlot.add(field);
        }
        searchSlot.setVisible(search != null);
        searchSlot.revalidate();
        searchSlot.repaint();
    }

    private void runSearch(ComicSearchTag field) {
        String query = field.getText().trim();
        AgentSearchContribution search = boundSearch;
        AgentSession session = sessionSource == null ? null : sessionSource.get();
        if (query.isEmpty() || search == null || session == null) {
            return;
        }
        field.setText(""); // the plugin shows the running search itself (e.g. a breadcrumb)
        search.search(session, query);
    }

    /** The visible search field, or {@code null} while no agent search is bound (tests). */
    JComponent searchField() {
        return searchSlot.isVisible() ? searchField : null;
    }

    /** Lays out the middle: the search centered on the title bar, the navigation centered left of it. */
    private final class CenteredSearchLayout implements java.awt.LayoutManager {
        public void addLayoutComponent(String name, java.awt.Component comp) {
        }

        public void removeLayoutComponent(java.awt.Component comp) {
        }

        public Dimension preferredLayoutSize(java.awt.Container parent) {
            Dimension nav = navigationSlot.getPreferredSize();
            Dimension search = searchSlot.isVisible() ? searchSlot.getPreferredSize() : new Dimension();
            return new Dimension(nav.width + search.width, Math.max(nav.height, search.height));
        }

        public Dimension minimumLayoutSize(java.awt.Container parent) {
            return new Dimension(0, 0);
        }

        public void layoutContainer(java.awt.Container parent) {
            int width = parent.getWidth();
            int height = parent.getHeight();
            int searchLeft = width;
            if (searchSlot.isVisible()) {
                Dimension pref = searchSlot.getPreferredSize();
                int w = Math.min(pref.width, width);
                // the bar's center, in the middle panel's coordinates, clamped into the middle panel
                int x = AskAiTitleBar.this.getWidth() / 2 - parent.getX() - w / 2;
                x = Math.max(0, Math.min(x, width - w));
                int h = Math.min(pref.height, height);
                searchSlot.setBounds(x, (height - h) / 2, w, h);
                searchLeft = x;
            }
            Dimension nav = navigationSlot.getPreferredSize();
            int w = Math.min(nav.width, searchLeft);
            int h = Math.min(nav.height, height);
            navigationSlot.setBounds((searchLeft - w) / 2, (height - h) / 2, w, h);
        }
    }
}

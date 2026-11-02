package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The top-right CONNECTION widget: status dot + Ollama host:port fused into ONE control, so the
 * menu bar needs no separate always-visible refresh button and the dot never pretends to be live.
 * Two in-place actions for now: hovering the STATUS turns the dot into a comic refresh glyph
 * (circular arrow) — clicking it refreshes connection, models and audio profiles; clicking the
 * ADDRESS opens the connection settings (link-blue, underlined on hover). Deliberately its own
 * class with self-contained zones and painting, so a later slice can grow a hover/click DROPDOWN
 * with more information and actions without touching the menu bar again.
 */
public final class ConnectionWidget extends JComponent {

    /** The address link colour (matches the previous bare-URL link); never green. */
    private static final Color LINK = ThemeColor.of("app.connectionLinkText");
    private static final int DOT_ZONE = 20;
    private static final int GAP = 4;
    private static final int PAD_LEFT = 8;
    private static final int PAD_RIGHT = 10;

    private final Runnable openSettings;
    private final Runnable refresh;

    private ConnectionStatus status = ConnectionStatus.NOT_CHECKED;
    private String url = "";
    private String detail = "";
    private boolean refreshEnabled = true;
    private boolean statusHovered;
    private boolean addressHovered;
    /** Compact: only the status dot; address, status and version live in the hover popup. */
    private boolean compact;

    public ConnectionWidget(Runnable openSettings, Runnable refresh) {
        this.openSettings = openSettings;
        this.refresh = refresh;
        setOpaque(false);
        setToolTipText(" "); // register with the tooltip manager; text comes per zone
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                boolean inStatus = statusZone().contains(event.getPoint());
                boolean inAddress = addressZone().contains(event.getPoint());
                if (inStatus != statusHovered || inAddress != addressHovered) {
                    statusHovered = inStatus;
                    addressHovered = inAddress;
                    setCursor(Cursor.getPredefinedCursor(inStatus || inAddress
                            ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                statusHovered = false;
                addressHovered = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (statusZone().contains(event.getPoint())) {
                    if (refreshEnabled && ConnectionWidget.this.refresh != null) {
                        ConnectionWidget.this.refresh.run();
                    }
                } else if (addressZone().contains(event.getPoint())
                        && ConnectionWidget.this.openSettings != null) {
                    ConnectionWidget.this.openSettings.run();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                if (compact) {
                    showHoverPopup();
                }
            }
        });
        popupCloser.setRepeats(true);
        buildHoverPopup();
    }

    // ------------------------------------------------------------------ compact: the hover popup

    /** The CLICKABLE address (opens the connection settings) and the status line, below the dot. */
    private final javax.swing.JPopupMenu hoverPopup = new javax.swing.JPopupMenu();
    private final javax.swing.JLabel popupAddress = new javax.swing.JLabel();
    private final javax.swing.JLabel popupStatus = new javax.swing.JLabel();
    /** Closes the popup once the pointer has left both the dot and the popup. */
    private final javax.swing.Timer popupCloser = new javax.swing.Timer(250, event -> closePopupIfLeft());

    private void buildHoverPopup() {
        hoverPopup.setFocusable(false);
        javax.swing.JPanel content = new javax.swing.JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 10, 6, 10));
        popupAddress.setForeground(LINK);
        popupAddress.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        popupAddress.setToolTipText("Open the connection settings");
        popupAddress.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                popupAddress.setText(addressHtml(true));
            }

            @Override
            public void mouseExited(MouseEvent event) {
                popupAddress.setText(addressHtml(false));
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                hoverPopup.setVisible(false);
                popupCloser.stop();
                if (openSettings != null) {
                    openSettings.run();
                }
            }
        });
        content.add(popupAddress);
        content.add(javax.swing.Box.createVerticalStrut(3));
        content.add(popupStatus);
        hoverPopup.add(content);
        refreshPopup();
    }

    private void refreshPopup() {
        popupAddress.setText(addressHtml(false));
        popupStatus.setText("Status: " + status.getLabel() + (detail.isEmpty() ? "" : " — " + detail)
                + " · click the dot to refresh");
    }

    private String addressHtml(boolean underline) {
        String text = escape(url.isEmpty() ? "(no address)" : stripScheme(url));
        return "<html>" + (underline ? "<u>" + text + "</u>" : text) + "</html>";
    }

    private void showHoverPopup() {
        if (!isShowing()) {
            return;
        }
        refreshPopup();
        if (!hoverPopup.isVisible()) {
            java.awt.Dimension size = hoverPopup.getPreferredSize();
            // right-aligned under the dot (the dot sits near the window's right edge)
            hoverPopup.show(this, getWidth() - size.width, getHeight());
        }
        popupCloser.restart();
    }

    private void closePopupIfLeft() {
        if (!hoverPopup.isVisible()) {
            popupCloser.stop();
            return;
        }
        java.awt.PointerInfo pointer = java.awt.MouseInfo.getPointerInfo();
        if (pointer == null) {
            return;
        }
        java.awt.Point at = pointer.getLocation();
        if (!isShowing() || !hoverPopup.isShowing()) {
            hoverPopup.setVisible(false);
            popupCloser.stop();
            return;
        }
        java.awt.Rectangle dot = new Rectangle(getLocationOnScreen(), getSize());
        java.awt.Rectangle popup = new Rectangle(hoverPopup.getLocationOnScreen(), hoverPopup.getSize());
        // the bridge between dot and popup counts as "inside" so moving down never closes it
        dot.grow(4, 6);
        if (!dot.contains(at) && !popup.contains(at)) {
            hoverPopup.setVisible(false);
            popupCloser.stop();
        }
    }

    /** The popup's address text (tests). */
    String popupAddressForTest() {
        return popupAddress.getText();
    }

    /** Runs the popup address click (tests). */
    void clickPopupAddressForTest() {
        for (java.awt.event.MouseListener listener : popupAddress.getMouseListeners()) {
            listener.mouseClicked(new MouseEvent(popupAddress, MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false));
        }
    }

    /**
     * @param newStatus the semantic state (drives the dot colour)
     * @param url       the Ollama base URL (shown as host:port and, in full, in the tooltip)
     * @param detail    extra tooltip context (e.g. "version 0.1.29" or an error), may be empty
     */
    public void setStatus(ConnectionStatus newStatus, String url, String detail) {
        this.status = newStatus == null ? ConnectionStatus.NOT_CHECKED : newStatus;
        this.url = url == null ? "" : url;
        this.detail = detail == null ? "" : detail;
        refreshPopup();
        revalidate();
        repaint();
        setMaximumSize(getPreferredSize()); // flush right after the menu-bar glue, never stretched
    }

    /**
     * Compact mode (title bar): only the dot, same size; hovering opens a popup with the CLICKABLE
     * address (opens the connection settings) and the status/version; a click on the dot refreshes.
     */
    public void setCompact(boolean compact) {
        this.compact = compact;
        revalidate();
        repaint();
        setMaximumSize(getPreferredSize());
    }

    public ConnectionStatus getStatus() {
        return status;
    }

    /** Disabled while a global refresh runs — the glyph dims and clicks are ignored. */
    public void setRefreshEnabled(boolean enabled) {
        this.refreshEnabled = enabled;
        repaint();
    }

    private Rectangle statusZone() {
        return compact ? new Rectangle(0, 0, getWidth(), getHeight())
                : new Rectangle(PAD_LEFT, 0, DOT_ZONE, getHeight());
    }

    private Rectangle addressZone() {
        if (compact) {
            return new Rectangle(0, 0, 0, 0);
        }
        int start = PAD_LEFT + DOT_ZONE + GAP;
        return new Rectangle(start, 0, Math.max(0, getWidth() - start - PAD_RIGHT), getHeight());
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (compact) {
            return null; // the hover popup (with the clickable address) replaces the tooltip
        }
        if (statusZone().contains(event.getPoint())) {
            return "Status: " + status.getLabel()
                    + (detail.isEmpty() ? "" : " — " + detail)
                    + ". Click to refresh connection, models and audio profiles";
        }
        return (url.isEmpty() ? "" : "Ollama: " + url + " — ")
                + "click to open connection settings";
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int cy = getHeight() / 2;
            int cx = compact ? getWidth() / 2 : PAD_LEFT + DOT_ZONE / 2;
            if (statusHovered) {
                // The dot GROWS a purpose on hover: the comic refresh glyph — a circular arrow
                // in the status colour, dimmed while a refresh is already running.
                Color color = ThemeColor.of(status.getColorToken());
                g2.setColor(refreshEnabled ? color : ThemeColor.withAlpha(color, 110));
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int r = 6;
                g2.drawArc(cx - r, cy - r, 2 * r, 2 * r, 30, 290); // open circle
                // arrow head at the arc's start (top right), pointing clockwise
                int hx = cx + (int) (r * Math.cos(Math.toRadians(30)));
                int hy = cy - (int) (r * Math.sin(Math.toRadians(30)));
                g2.drawLine(hx, hy, hx + 4, hy - 1);
                g2.drawLine(hx, hy, hx - 1, hy - 5);
            } else {
                g2.setColor(ThemeColor.of(status.getColorToken()));
                g2.fillOval(cx - 4, cy - 4, 9, 9);
            }

            if (compact) {
                return; // the address lives in the hover popup
            }
            String address = stripScheme(url);
            g2.setFont(effectiveFont());
            FontMetrics metrics = g2.getFontMetrics();
            int textX = PAD_LEFT + DOT_ZONE + GAP;
            int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.setColor(LINK);
            g2.drawString(address, textX, textY);
            if (addressHovered) {
                int width = metrics.stringWidth(address);
                g2.drawLine(textX, textY + 2, textX + width, textY + 2);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(effectiveFont());
        if (compact) {
            return new Dimension(DOT_ZONE + 4, Math.max(20, metrics.getHeight() + 4));
        }
        int width = PAD_LEFT + DOT_ZONE + GAP + metrics.stringWidth(stripScheme(url)) + PAD_RIGHT;
        return new Dimension(width, Math.max(20, metrics.getHeight() + 4));
    }

    /** The font even before the widget hangs in a window (it inherits one only then). */
    private java.awt.Font effectiveFont() {
        java.awt.Font font = getFont();
        if (font == null) {
            font = javax.swing.UIManager.getFont("Label.font");
        }
        return font != null ? font : new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** @return the URL without the http/https scheme and any trailing slash. */
    private static String stripScheme(String value) {
        String result = value == null ? "" : value.trim();
        int scheme = result.indexOf("://");
        if (scheme >= 0) {
            result = result.substring(scheme + 3);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}

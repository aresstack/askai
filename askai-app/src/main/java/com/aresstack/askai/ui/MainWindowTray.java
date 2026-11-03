package com.aresstack.askai.ui;

import java.awt.Frame;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

/**
 * Close-to-tray for the main window: closing hides the frame (gone from the taskbar) and shows the app icon in
 * the notification area. Clicking the icon or "Open" restores the window, "Exit" runs the regular shutdown.
 * When the platform has no system tray, {@link #hideToTray()} returns {@code false} and the caller keeps the
 * previous close behaviour.
 */
final class MainWindowTray {

    private final Frame frame;
    private final Runnable exitAction;
    private TrayIcon trayIcon;

    MainWindowTray(Frame frame, Runnable exitAction) {
        this.frame = frame;
        this.exitAction = exitAction;
    }

    /** Hides the window into the tray; {@code false} when no tray is available (nothing changed). */
    boolean hideToTray() {
        if (!SystemTray.isSupported()) {
            return false;
        }
        try {
            if (trayIcon == null) {
                trayIcon = createTrayIcon();
                SystemTray.getSystemTray().add(trayIcon);
            }
        } catch (Exception unavailable) {
            trayIcon = null;
            return false;
        }
        frame.setVisible(false);
        return true;
    }

    /** Brings the window back from the tray and removes the tray icon. */
    void restore() {
        removeTrayIcon();
        frame.setVisible(true);
        frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        frame.toFront();
        frame.requestFocus();
    }

    void removeTrayIcon() {
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {
                // Tray already gone.
            }
            trayIcon = null;
        }
    }

    private TrayIcon createTrayIcon() {
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Open");
        open.addActionListener(e -> SwingUtilities.invokeLater(this::restore));
        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            removeTrayIcon();
            exitAction.run();
        }));
        menu.add(open);
        menu.addSeparator();
        menu.add(exit);
        TrayIcon icon = new TrayIcon(trayImage(), frame.getTitle() == null ? "AskAI" : frame.getTitle(), menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> SwingUtilities.invokeLater(this::restore)); // double-click (Windows)
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1) {
                    SwingUtilities.invokeLater(MainWindowTray.this::restore);
                }
            }
        });
        return icon;
    }

    private static Image trayImage() {
        int size = 16;
        try {
            java.awt.Dimension d = SystemTray.getSystemTray().getTrayIconSize();
            size = Math.max(d.width, d.height);
        } catch (Exception ignored) {
        }
        Image image = AppIcon.sized(size);
        if (image == null) {
            throw new IllegalStateException("App icon resource missing");
        }
        return image;
    }
}

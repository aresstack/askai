package com.aresstack.askai.ui.theme;

import com.aresstack.comiccontrols.theme.AskAiTheme;
import com.aresstack.comiccontrols.theme.ComicTheme;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.util.Locale;

/**
 * THE one gate for switching the AskAI theme at runtime (pattern of MainframeMate's
 * {@code ThemeManager.applyTheme}): activate the theme, re-install the UIManager defaults, refresh
 * every open window, then notify the change listeners (the plugin theme service forwards to the
 * plugins). Living {@code ThemeColor}s follow on the next repaint; delegates are re-created by
 * {@code updateComponentTreeUI}. Call on the EDT.
 */
public final class ThemeSwitcher {

    /** The selectable themes in display order (ids = {@link AskAiTheme#name()}). */
    private static final String[] IDS = {AskAiTheme.ARCANE, AskAiTheme.CLASSIC};
    private static final String[] DISPLAY_NAMES = {"Arcane Knowledge", "Classic Comic"};

    private ThemeSwitcher() {
    }

    /** Applies {@code theme} to the whole application (all open windows) and fires the listeners once. */
    public static void apply(AskAiTheme theme) {
        if (theme == null) {
            throw new IllegalArgumentException("theme must not be null");
        }
        AskAiTheme.setActive(theme);
        ComicTheme.install(theme);
        for (Window window : Window.getWindows()) {
            refreshWindow(window);
        }
        AskAiTheme.fireChanged();
    }

    private static void refreshWindow(Window window) {
        SwingUtilities.updateComponentTreeUI(window);
        if (window instanceof JFrame) {
            JMenuBar menuBar = ((JFrame) window).getJMenuBar();
            if (menuBar != null) {
                SwingUtilities.updateComponentTreeUI(menuBar);
            }
        }
        window.invalidate();
        window.validate();
        window.repaint();
    }

    /**
     * Pure start-value resolution: a known JVM property value ({@code -Daskai.theme}) wins, then a known
     * stored {@code ui.theme}, otherwise the default theme.
     */
    public static AskAiTheme startTheme(String propertyValue, String configuredValue) {
        if (isKnown(propertyValue)) {
            return AskAiTheme.resolve(propertyValue);
        }
        if (isKnown(configuredValue)) {
            return AskAiTheme.resolve(configuredValue);
        }
        return AskAiTheme.resolve(null);
    }

    /** The combo entries of the Appearance settings, in display order. */
    public static String[] displayNames() {
        return DISPLAY_NAMES.clone();
    }

    /** The theme behind a combo index. */
    public static AskAiTheme themeAt(int index) {
        return AskAiTheme.resolve(IDS[Math.max(0, Math.min(IDS.length - 1, index))]);
    }

    /** The combo index of a theme. */
    public static int indexOf(AskAiTheme theme) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(theme.name())) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isKnown(String value) {
        if (value == null) {
            return false;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        return AskAiTheme.ARCANE.equals(key) || AskAiTheme.CLASSIC.equals(key);
    }
}

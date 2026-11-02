package com.aresstack.askai.config;

import java.awt.Color;

/**
 * The user-adjustable chat bubble colors (transcript background and the two message bubbles). Kept as a
 * small immutable value object so it threads through {@link AppConfiguration} like any other setting; the
 * UI maps it onto the full bubble palette (activity/failure/info colors keep their defaults).
 *
 * <p>Defaults are the bubble tokens of the ACTIVE theme as living colors ({@code ThemeColor}), so untouched
 * bubbles follow every theme switch. Only real user choices are fixed colors (and only those are persisted).
 * Migration: a stored value that equals the OLD classic default exactly counts as "not customized" and is
 * replaced by the theme default when loading.</p>
 */
public final class ChatColorSettings {

    private final Color transcriptBackground;
    private final Color userBackground;
    private final Color userForeground;
    private final Color assistantBackground;
    private final Color assistantForeground;

    public ChatColorSettings(Color transcriptBackground, Color userBackground, Color userForeground,
                             Color assistantBackground, Color assistantForeground) {
        this.transcriptBackground = orDefault(transcriptBackground, "transcriptBackground");
        this.userBackground = orDefault(userBackground, "userBackground");
        this.userForeground = orDefault(userForeground, "userForeground");
        this.assistantBackground = orDefault(assistantBackground, "assistantBackground");
        this.assistantForeground = orDefault(assistantForeground, "assistantForeground");
    }

    /** @return the defaults: the active theme's bubble tokens as living colors. */
    public static ChatColorSettings defaults() {
        return new ChatColorSettings(null, null, null, null, null);
    }

    /**
     * Loads one stored color with the migration rule: missing/invalid → theme default; exactly the OLD
     * classic default → theme default ("not customized"); anything else is a real user choice and stays.
     *
     * @param role the bubble role ({@code transcriptBackground}, {@code userBackground}, ...)
     */
    public static Color fromStored(String storedHex, String role) {
        Color stored = parseHex(storedHex, null);
        if (stored == null || (stored.getRGB() & 0xFFFFFF) == (classicDefault(role).getRGB() & 0xFFFFFF)) {
            return themeDefault(role);
        }
        return stored;
    }

    /** Whether the color is a theme default (living) rather than a user choice; defaults are not persisted. */
    public static boolean isThemeDefault(Color color) {
        return color instanceof com.aresstack.comiccontrols.theme.ThemeColor;
    }

    private static Color classicDefault(String role) {
        return com.aresstack.comiccontrols.theme.AskAiTheme.classic().bubble(role);
    }

    private static Color themeDefault(String role) {
        return com.aresstack.comiccontrols.theme.ThemeColor.of("bubble." + role);
    }

    public Color getTranscriptBackground() {
        return transcriptBackground;
    }

    public Color getUserBackground() {
        return userBackground;
    }

    public Color getUserForeground() {
        return userForeground;
    }

    public Color getAssistantBackground() {
        return assistantBackground;
    }

    public Color getAssistantForeground() {
        return assistantForeground;
    }

    public ChatColorSettings withTranscriptBackground(Color color) {
        return new ChatColorSettings(color, userBackground, userForeground, assistantBackground, assistantForeground);
    }

    public ChatColorSettings withUserBackground(Color color) {
        return new ChatColorSettings(transcriptBackground, color, userForeground, assistantBackground, assistantForeground);
    }

    public ChatColorSettings withUserForeground(Color color) {
        return new ChatColorSettings(transcriptBackground, userBackground, color, assistantBackground, assistantForeground);
    }

    public ChatColorSettings withAssistantBackground(Color color) {
        return new ChatColorSettings(transcriptBackground, userBackground, userForeground, color, assistantForeground);
    }

    public ChatColorSettings withAssistantForeground(Color color) {
        return new ChatColorSettings(transcriptBackground, userBackground, userForeground, assistantBackground, color);
    }

    /** Parses a {@code RRGGBB} (or {@code #RRGGBB}) hex string, or returns {@code fallback} when invalid. */
    public static Color parseHex(String value, Color fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() == 0) {
            return fallback;
        }
        try {
            return new Color(Integer.parseInt(trimmed, 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** @return the color as a 6-digit upper-case {@code RRGGBB} hex string (no alpha). */
    public static String toHex(Color color) {
        return String.format("%06X", color.getRGB() & 0xFFFFFF);
    }

    private static Color orDefault(Color color, String role) {
        return color == null ? themeDefault(role) : color;
    }
}

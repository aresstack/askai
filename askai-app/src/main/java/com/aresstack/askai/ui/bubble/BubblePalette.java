package com.aresstack.askai.ui.bubble;

import java.awt.Color;

/** Keep all bubble colors in one replaceable value object. */
public final class BubblePalette {

    private final Color transcriptBackground;
    private final Color userBackground;
    private final Color userForeground;
    private final Color assistantBackground;
    private final Color assistantForeground;
    private final Color activityBackground;
    private final Color activityForeground;
    private final Color activityAccent;
    private final Color failureAccent;
    private final Color infoForeground;

    public BubblePalette(Color transcriptBackground,
                         Color userBackground,
                         Color userForeground,
                         Color assistantBackground,
                         Color assistantForeground,
                         Color activityBackground,
                         Color activityForeground,
                         Color activityAccent,
                         Color failureAccent,
                         Color infoForeground) {
        this.transcriptBackground = requireColor(transcriptBackground, "transcriptBackground");
        this.userBackground = requireColor(userBackground, "userBackground");
        this.userForeground = requireColor(userForeground, "userForeground");
        this.assistantBackground = requireColor(assistantBackground, "assistantBackground");
        this.assistantForeground = requireColor(assistantForeground, "assistantForeground");
        this.activityBackground = requireColor(activityBackground, "activityBackground");
        this.activityForeground = requireColor(activityForeground, "activityForeground");
        this.activityAccent = requireColor(activityAccent, "activityAccent");
        this.failureAccent = requireColor(failureAccent, "failureAccent");
        this.infoForeground = requireColor(infoForeground, "infoForeground");
    }

    /** The classic Windows-Phone-inspired palette: the bubble tokens of the classic theme (fixed values). */
    public static BubblePalette windowsPhoneInspired() {
        return fromTheme(com.aresstack.comiccontrols.theme.AskAiTheme.classic());
    }

    /** The bubble tokens of one theme as fixed values. */
    public static BubblePalette fromTheme(com.aresstack.comiccontrols.theme.AskAiTheme theme) {
        return new BubblePalette(
                theme.bubble("transcriptBackground"),
                theme.bubble("userBackground"),
                theme.bubble("userForeground"),
                theme.bubble("assistantBackground"),
                theme.bubble("assistantForeground"),
                theme.bubble("activityBackground"),
                theme.bubble("activityForeground"),
                theme.bubble("activityAccent"),
                theme.bubble("failureAccent"),
                theme.bubble("infoForeground"));
    }

    /**
     * The LIVING bubble palette: every role is a {@code ThemeColor} of the bubble token, so the
     * transcript follows a runtime theme switch on its next repaint.
     */
    public static BubblePalette themed() {
        return new BubblePalette(
                themeColor("transcriptBackground"),
                themeColor("userBackground"),
                themeColor("userForeground"),
                themeColor("assistantBackground"),
                themeColor("assistantForeground"),
                themeColor("activityBackground"),
                themeColor("activityForeground"),
                themeColor("activityAccent"),
                themeColor("failureAccent"),
                themeColor("infoForeground"));
    }

    private static Color themeColor(String role) {
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

    public Color getActivityBackground() {
        return activityBackground;
    }

    public Color getActivityForeground() {
        return activityForeground;
    }

    public Color getActivityAccent() {
        return activityAccent;
    }

    public Color getFailureAccent() {
        return failureAccent;
    }

    public Color getInfoForeground() {
        return infoForeground;
    }

    private static Color requireColor(Color color, String name) {
        if (color == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return color;
    }
}

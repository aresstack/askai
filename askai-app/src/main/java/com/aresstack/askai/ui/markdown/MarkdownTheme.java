package com.aresstack.askai.ui.markdown;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Define visual values for rendered Markdown without coupling to a concrete look and feel. */
public final class MarkdownTheme {

    private final Font bodyFont;
    private final Font codeFont;
    private final Color foreground;
    private final Color mutedForeground;
    private final Color linkForeground;
    private final Color codeBackground;
    private final Color quoteBorder;
    private final Color separatorColor;
    private final Color errorForeground;

    public MarkdownTheme(Font bodyFont,
                         Font codeFont,
                         Color foreground,
                         Color mutedForeground,
                         Color linkForeground,
                         Color codeBackground,
                         Color quoteBorder,
                         Color separatorColor,
                         Color errorForeground) {
        this.bodyFont = bodyFont;
        this.codeFont = codeFont;
        this.foreground = foreground;
        this.mutedForeground = mutedForeground;
        this.linkForeground = linkForeground;
        this.codeBackground = codeBackground;
        this.quoteBorder = quoteBorder;
        this.separatorColor = separatorColor;
        this.errorForeground = errorForeground;
    }

    public static MarkdownTheme fromUiDefaults() {
        Font labelFont = UIManager.getFont("Label.font");
        Font textFont = UIManager.getFont("TextArea.font");
        Color foreground = color("Label.foreground", ThemeColor.of("markdown.text"));
        Color muted = color("Label.disabledForeground", ThemeColor.of("markdown.mutedText"));
        Color link = color("Component.linkColor", ThemeColor.of("markdown.linkText"));
        Color background = color("TextArea.background", ThemeColor.of("comic.fieldBg"));
        Color codeBackground = blend(background, foreground, 0.06f);
        Color quoteBorder = blend(background, foreground, 0.22f);
        Color separator = blend(background, foreground, 0.16f);
        Color error = color("Actions.Red", ThemeColor.of("markdown.errorText"));
        Font body = labelFont == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 13) : labelFont;
        Font code = textFont == null
                ? new Font(Font.MONOSPACED, Font.PLAIN, body.getSize())
                : new Font(Font.MONOSPACED, Font.PLAIN, textFont.getSize());
        return new MarkdownTheme(body, code, foreground, muted, link, codeBackground,
                quoteBorder, separator, error);
    }

    /**
     * Builds a theme that blends into a colored chat bubble: the text uses the bubble's own foreground and
     * every shaded surface (inline/code background, quote border, separators) is derived from the bubble's
     * background instead of the global light look-and-feel — so answers never show black text or a white box
     * on a colored bubble.
     */
    public static MarkdownTheme forBubble(Color background, Color foreground) {
        Font labelFont = UIManager.getFont("Label.font");
        Font textFont = UIManager.getFont("TextArea.font");
        Color fg = foreground == null ? color("Label.foreground", ThemeColor.of("markdown.text")) : foreground;
        Color bg = background == null ? color("TextArea.background", ThemeColor.of("comic.fieldBg")) : background;
        Color muted = blend(bg, fg, 0.55f);
        Color link = color("Component.linkColor", ThemeColor.of("markdown.linkText"));
        Color codeBackground = blend(bg, fg, 0.10f);
        Color quoteBorder = blend(bg, fg, 0.28f);
        Color separator = blend(bg, fg, 0.20f);
        Color error = color("Actions.Red", ThemeColor.of("markdown.errorText"));
        Font body = labelFont == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 13) : labelFont;
        Font code = textFont == null
                ? new Font(Font.MONOSPACED, Font.PLAIN, body.getSize())
                : new Font(Font.MONOSPACED, Font.PLAIN, textFont.getSize());
        return new MarkdownTheme(body, code, fg, muted, link, codeBackground,
                quoteBorder, separator, error);
    }

    /**
     * A look-and-feel default read LIVE (the theme switch re-installs the UIManager defaults before it repaints),
     * falling back to the token when the key is not installed.
     */
    private static Color color(final String key, final Color fallback) {
        return ThemeColor.derive("ui:" + key, () -> {
            Color value = UIManager.getColor(key);
            return value == null ? fallback : value;
        });
    }

    /**
     * The rounded channel blend of {@code first} towards {@code second}, derived LIVE (same arithmetic as
     * before), so shades derived from themed bubble/token colors follow a theme switch instead of being frozen.
     */
    private static Color blend(Color first, Color second, float secondWeight) {
        return ThemeColor.mix(first, second, secondWeight);
    }

    public Font getBodyFont() {
        return bodyFont;
    }

    public Font getCodeFont() {
        return codeFont;
    }

    public Color getForeground() {
        return foreground;
    }

    public Color getMutedForeground() {
        return mutedForeground;
    }

    public Color getLinkForeground() {
        return linkForeground;
    }

    public Color getCodeBackground() {
        return codeBackground;
    }

    public Color getQuoteBorder() {
        return quoteBorder;
    }

    public Color getSeparatorColor() {
        return separatorColor;
    }

    public Color getErrorForeground() {
        return errorForeground;
    }
}

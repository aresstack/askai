package com.aresstack.askai.video;

import java.awt.Rectangle;

/**
 * A NEUTRAL description of what to record — never a concrete {@code AskAiFrame} dependency, so the same
 * recorder works outside AskAI (bug report, chat attachments, docs, demos). A {@code WINDOW} source
 * carries the window's screen bounds; a {@code SCREEN} source carries the monitor bounds.
 */
public final class RecordingSource {

    public enum Kind { SCREEN, WINDOW }

    private final Kind kind;
    private final Rectangle bounds;
    private final String label;

    private RecordingSource(Kind kind, Rectangle bounds, String label) {
        if (bounds == null) {
            throw new IllegalArgumentException("bounds must not be null");
        }
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw new IllegalArgumentException("bounds must have a positive size");
        }
        this.kind = kind;
        this.bounds = new Rectangle(bounds);
        this.label = label == null ? kind.name() : label;
    }

    /** A window/region capture of exactly these screen bounds (e.g. the AskAI window's bounds). */
    public static RecordingSource window(Rectangle screenBounds, String label) {
        return new RecordingSource(Kind.WINDOW, screenBounds, label);
    }

    /** A full-monitor capture of these bounds. */
    public static RecordingSource screen(Rectangle screenBounds, String label) {
        return new RecordingSource(Kind.SCREEN, screenBounds, label);
    }

    public Kind getKind() {
        return kind;
    }

    /** A defensive copy of the capture bounds in screen coordinates. */
    public Rectangle getBounds() {
        return new Rectangle(bounds);
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return kind + "[" + label + " " + bounds.width + "x" + bounds.height + "]";
    }
}

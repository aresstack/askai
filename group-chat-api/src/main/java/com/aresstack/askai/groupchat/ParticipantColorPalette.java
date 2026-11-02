package com.aresstack.askai.groupchat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Versioned, accessibility-conscious participant color palette.
 *
 * <p>Every logical color token has a fixed light-theme variant (dark enough to read on light
 * backgrounds) and a dark-theme variant (light enough to read on dark backgrounds). Peers never
 * exchange RGB values — only the stable {@link Entry#getToken() token} is replicated, so all
 * clients render a participant with the same logical color regardless of their theme.</p>
 */
public final class ParticipantColorPalette {

    /** Palette version replicated with the color map; bump when entries change incompatibly. */
    public static final int VERSION = 1;

    /** One palette color: stable token, light/dark hex variants and a hue for distance picking. */
    public static final class Entry {
        private final String token;
        private final String lightHex;
        private final String darkHex;
        private final int hueDegrees;

        Entry(String token, String lightHex, String darkHex, int hueDegrees) {
            this.token = token;
            this.lightHex = lightHex;
            this.darkHex = darkHex;
            this.hueDegrees = hueDegrees;
        }

        /** Stable logical token replicated between peers (e.g. {@code "violet"}). */
        public String getToken() { return token; }

        /** Hex color (e.g. {@code "#6A1B9A"}) for light backgrounds. */
        public String getLightHex() { return lightHex; }

        /** Hex color for dark backgrounds. */
        public String getDarkHex() { return darkHex; }

        /** Approximate hue in degrees, used for deterministic visual-distance picking. */
        public int getHueDegrees() { return hueDegrees; }
    }

    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new Entry("violet", "#6A1B9A", "#CE93D8", 285),
            new Entry("amber", "#B26A00", "#FFD54F", 45),
            new Entry("blue", "#1565C0", "#90CAF9", 210),
            new Entry("green", "#2E7D32", "#A5D6A7", 120),
            new Entry("red", "#C62828", "#EF9A9A", 0),
            new Entry("teal", "#00695C", "#80CBC4", 174),
            new Entry("pink", "#AD1457", "#F48FB1", 330),
            new Entry("orange", "#E65100", "#FFB74D", 30),
            new Entry("indigo", "#283593", "#9FA8DA", 235),
            new Entry("olive", "#827717", "#E6EE9C", 66),
            new Entry("cyan", "#006064", "#80DEEA", 187),
            new Entry("brown", "#4E342E", "#BCAAA4", 15)));

    private ParticipantColorPalette() {
    }

    /** All palette entries in their canonical, stable order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** All stable color tokens in canonical order. */
    public static List<String> tokens() {
        List<String> tokens = new ArrayList<String>(ENTRIES.size());
        for (Entry entry : ENTRIES) {
            tokens.add(entry.getToken());
        }
        return Collections.unmodifiableList(tokens);
    }

    /** @return the entry for {@code token}, or {@code null} when the token is unknown. */
    public static Entry byToken(String token) {
        if (token == null) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (entry.getToken().equals(token)) {
                return entry;
            }
        }
        return null;
    }
}

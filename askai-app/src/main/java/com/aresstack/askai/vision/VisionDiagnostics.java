package com.aresstack.askai.vision;

import com.aresstack.askai.client.OllamaChatTurn;

import java.util.Base64;
import java.util.List;

/**
 * Opt-in, privacy-safe diagnostics for outbound vision requests.
 *
 * <p>Enabled with the system property {@code -Daskai.vision.diagnostics=true}.  For every user turn
 * that carries images it reports, per image, only safe metadata — {@code turnIndex}, {@code
 * imageIndex}, sniffed {@code mediaType} and decoded {@code byteLength} — so one can confirm that
 * each attachment is transmitted and associated with the intended turn.  It never logs Base64 data,
 * file contents or paths.</p>
 */
public final class VisionDiagnostics {

    private static final String FLAG = "askai.vision.diagnostics";

    private VisionDiagnostics() {
    }

    /** @return {@code true} when vision request diagnostics are enabled. */
    public static boolean isEnabled() {
        return Boolean.getBoolean(FLAG);
    }

    /** Log the per-turn/per-image safe metadata for a conversation about to be sent. */
    public static void logConversation(List<OllamaChatTurn> turns) {
        if (!isEnabled() || turns == null) {
            return;
        }
        for (String line : describe(turns)) {
            System.err.println(line);
        }
    }

    /**
     * Build the diagnostic lines for {@code turns} (visible for tests).  One line per attached
     * image; a trailing summary line reports the totals.
     */
    public static java.util.List<String> describe(List<OllamaChatTurn> turns) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        int imageTurns = 0;
        int totalImages = 0;
        for (int turnIndex = 0; turnIndex < turns.size(); turnIndex++) {
            OllamaChatTurn turn = turns.get(turnIndex);
            List<String> images = turn.getImages();
            if (images.isEmpty()) {
                continue;
            }
            imageTurns++;
            for (int imageIndex = 0; imageIndex < images.size(); imageIndex++) {
                totalImages++;
                long byteLength = decodedLength(images.get(imageIndex));
                lines.add("[vision] turnIndex=" + turnIndex + " role=" + turn.getRole()
                        + " imageIndex=" + imageIndex
                        + " mediaType=" + sniffMediaType(images.get(imageIndex))
                        + " byteLength=" + byteLength);
            }
        }
        lines.add("[vision] summary turns=" + turns.size() + " imageTurns=" + imageTurns
                + " totalImages=" + totalImages);
        return lines;
    }

    /** @return the decoded byte length of a base64 image without materializing the bytes twice. */
    private static long decodedLength(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return 0;
        }
        int padding = 0;
        int len = base64.length();
        if (base64.charAt(len - 1) == '=') {
            padding++;
        }
        if (len >= 2 && base64.charAt(len - 2) == '=') {
            padding++;
        }
        return (long) (len / 4) * 3 - padding;
    }

    /** Sniff the media type from the first decoded bytes; never exposes the content. */
    private static String sniffMediaType(String base64) {
        try {
            byte[] head = Base64.getDecoder().decode(base64.substring(0, Math.min(base64.length(), 24)));
            if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "image/png";
            }
            if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
                return "image/jpeg";
            }
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return "image/webp";
            }
        } catch (RuntimeException ignored) {
            // Malformed base64 head — fall through to unknown.
        }
        return "unknown";
    }
}

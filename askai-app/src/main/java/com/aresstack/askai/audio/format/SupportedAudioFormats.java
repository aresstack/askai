package com.aresstack.askai.audio.format;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Define the audio formats accepted by every file-based transcription workflow. */
public final class SupportedAudioFormats {

    private static final List<String> EXTENSIONS = Collections.unmodifiableList(
            Arrays.asList("wav", "mp3", "m4a", "ogg", "flac"));

    private SupportedAudioFormats() {
    }

    public static List<String> extensions() {
        return EXTENSIONS;
    }

    public static String[] extensionArray() {
        return EXTENSIONS.toArray(new String[EXTENSIONS.size()]);
    }

    public static String fileChooserDescription() {
        return "Audio files (" + joinExtensions() + ")";
    }

    public static boolean supports(File file) {
        return file != null && supportsFileName(file.getName());
    }

    public static boolean supportsFileName(String fileName) {
        String extension = extensionOf(fileName);
        return EXTENSIONS.contains(extension);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int separator = fileName.lastIndexOf('.');
        if (separator < 0 || separator == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private static String joinExtensions() {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < EXTENSIONS.size(); index++) {
            if (index > 0) {
                result.append(", ");
            }
            result.append(EXTENSIONS.get(index));
        }
        return result.toString();
    }
}

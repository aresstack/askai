package com.aresstack.askai.localruntime.onnx;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Nachsichtiger Zugriff auf JSON-Dateien der Modelle (Gson bleibt hier). */
final class Json {

    private static final Gson GSON = new Gson();

    private Json() {
    }

    static Object parse(String text) {
        return GSON.fromJson(text, Object.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) throws IOException {
        Object parsed = parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        return parsed instanceof Map ? (Map<String, Object>) parsed : Collections.<String, Object>emptyMap();
    }

    /** Die Datei, falls vorhanden; sonst eine leere Map. */
    static Map<String, Object> readIfPresent(Path file) throws IOException {
        return Files.isRegularFile(file) ? read(file) : Collections.<String, Object>emptyMap();
    }

    static String toJson(Object value) {
        return GSON.toJson(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    static String text(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    static int integer(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}

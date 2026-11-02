package com.aresstack.askai.ui.about;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the build's {@code /oss-licenses.json} (written by gradle/oss-licenses.gradle) from the classpath. */
public final class ClasspathOssLicenseRepository implements OssLicenseRepository {

    private static final String RESOURCE = "/oss-licenses.json";
    private static final Pattern STRING_PROPERTY = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    @Override
    public List<OssLicenseEntry> findAll() throws IOException {
        InputStream stream = getClass().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IOException("oss-licenses.json is not on the classpath (run the generateOssLicenseReport Gradle task).");
        }
        try (InputStream input = stream) {
            return parseEntries(readAll(input));
        }
    }

    private String readAll(InputStream stream) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
        }
        return content.toString();
    }

    /** Parses a report (package-visible for tests). */
    List<OssLicenseEntry> parseEntries(String json) throws IOException {
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IOException("oss-licenses.json does not contain a JSON array.");
        }

        List<OssLicenseEntry> entries = new ArrayList<OssLicenseEntry>();
        for (String object : findObjects(trimmed)) {
            Map<String, String> fields = readStringProperties(object);
            entries.add(new OssLicenseEntry(
                    requireField(fields, "group"),
                    requireField(fields, "artifact"),
                    requireField(fields, "version"),
                    requireField(fields, "license"),
                    fields.get("module")));
        }
        return Collections.unmodifiableList(entries);
    }

    private List<String> findObjects(String json) throws IOException {
        List<String> objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quoted && character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (character == '{') {
                if (depth++ == 0) {
                    start = index;
                }
            } else if (character == '}') {
                if (--depth < 0) {
                    throw new IOException("Unexpected brace in oss-licenses.json.");
                }
                if (depth == 0) {
                    objects.add(json.substring(start, index + 1));
                }
            }
        }
        if (depth != 0 || quoted) {
            throw new IOException("Incomplete JSON in oss-licenses.json.");
        }
        return objects;
    }

    private Map<String, String> readStringProperties(String object) throws IOException {
        Map<String, String> fields = new HashMap<String, String>();
        Matcher matcher = STRING_PROPERTY.matcher(object);
        while (matcher.find()) {
            fields.put(matcher.group(1), unescapeJson(matcher.group(2)));
        }
        return fields;
    }

    private String unescapeJson(String escapedValue) throws IOException {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < escapedValue.length(); index++) {
            char character = escapedValue.charAt(index);
            if (character != '\\') {
                value.append(character);
                continue;
            }
            if (++index >= escapedValue.length()) {
                throw new IOException("Invalid JSON escape sequence.");
            }
            char escaped = escapedValue.charAt(index);
            switch (escaped) {
                case '"': value.append('"'); break;
                case '\\': value.append('\\'); break;
                case '/': value.append('/'); break;
                case 'b': value.append('\b'); break;
                case 'f': value.append('\f'); break;
                case 'n': value.append('\n'); break;
                case 'r': value.append('\r'); break;
                case 't': value.append('\t'); break;
                case 'u':
                    if (index + 4 >= escapedValue.length()) {
                        throw new IOException("Invalid unicode escape sequence.");
                    }
                    try {
                        value.append((char) Integer.parseInt(
                                escapedValue.substring(index + 1, index + 5), 16));
                    } catch (NumberFormatException exception) {
                        throw new IOException("Invalid unicode escape sequence.", exception);
                    }
                    index += 4;
                    break;
                default:
                    throw new IOException("Unknown JSON escape sequence: " + escaped);
            }
        }
        return value.toString();
    }

    private String requireField(Map<String, String> fields, String name) throws IOException {
        String value = fields.get(name);
        if (value == null) {
            throw new IOException("Missing field in oss-licenses.json: " + name);
        }
        return value;
    }
}

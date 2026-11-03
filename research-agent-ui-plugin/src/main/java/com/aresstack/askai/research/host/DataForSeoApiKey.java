package com.aresstack.askai.research.host;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * DataForSEO's API key as the dashboard shows it: Base64 of {@code login:password}, i.e. exactly the value
 * of the HTTP Basic header. Split only in memory; the password is handed on as {@code char[]}. A raw
 * {@code login:password} is accepted as well.
 */
final class DataForSeoApiKey {

    final String login;
    final char[] password;

    private DataForSeoApiKey(String login, char[] password) {
        this.login = login;
        this.password = password;
    }

    /** @return the parsed key, or {@code null} when nothing (or nothing usable) was typed */
    static DataForSeoApiKey parse(char[] typed) {
        if (typed == null) {
            return null;
        }
        char[] text = trim(typed);
        if (text.length == 0) {
            return null;
        }
        char[] plain = indexOf(text, ':') > 0 ? text : decode(text);
        try {
            int colon = plain == null ? -1 : indexOf(plain, ':');
            if (colon <= 0 || colon == plain.length - 1) {
                return null;
            }
            return new DataForSeoApiKey(new String(plain, 0, colon),
                    Arrays.copyOfRange(plain, colon + 1, plain.length));
        } finally {
            if (plain != null && plain != text) {
                Arrays.fill(plain, '\0');
            }
            Arrays.fill(text, '\0');
        }
    }

    private static char[] decode(char[] base64) {
        try {
            ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(
                    StandardCharsets.US_ASCII.encode(CharBuffer.wrap(base64))).array());
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder().decode(bytes);
            char[] out = new char[chars.remaining()];
            chars.get(out);
            Arrays.fill(bytes.array(), (byte) 0);
            return out;
        } catch (Exception notBase64) {
            return null;
        }
    }

    private static char[] trim(char[] in) {
        int start = 0;
        int end = in.length;
        while (start < end && Character.isWhitespace(in[start])) {
            start++;
        }
        while (end > start && Character.isWhitespace(in[end - 1])) {
            end--;
        }
        return Arrays.copyOfRange(in, start, end);
    }

    private static int indexOf(char[] in, char c) {
        for (int i = 0; i < in.length; i++) {
            if (in[i] == c) {
                return i;
            }
        }
        return -1;
    }
}

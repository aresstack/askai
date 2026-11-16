package com.aresstack.askai.localruntime.onnx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Die Byte-zu-Zeichen-Abbildung von GPT-2 (Byte-Level-BPE): druckbare Bytes stehen für sich, die übrigen 0–255 werden
 * auf Zeichen ab U+0100 gelegt, damit jedes Byte ein sichtbares Zeichen hat.
 */
final class ByteLevelCodec {

    private static final char[] BYTE_TO_CHAR = new char[256];
    private static final int[] CHAR_TO_BYTE = new int[512];

    static {
        java.util.Arrays.fill(CHAR_TO_BYTE, -1);
        int next = 0;
        for (int b = 0; b < 256; b++) {
            boolean printable = (b >= '!' && b <= '~') || (b >= 0xA1 && b <= 0xAC) || (b >= 0xAE && b <= 0xFF);
            char c = printable ? (char) b : (char) (256 + next++);
            BYTE_TO_CHAR[b] = c;
            CHAR_TO_BYTE[c] = b;
        }
    }

    private ByteLevelCodec() {
    }

    /** UTF-8-Bytes des Textes als Byte-Level-Zeichen. */
    static String encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            out.append(BYTE_TO_CHAR[b & 0xff]);
        }
        return out.toString();
    }

    /** Zurück zu Text; Zeichen außerhalb der Abbildung bleiben als UTF-8 erhalten. */
    static String decode(String symbols) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(symbols.length());
        for (int i = 0; i < symbols.length(); i++) {
            char c = symbols.charAt(i);
            int b = c < CHAR_TO_BYTE.length ? CHAR_TO_BYTE[c] : -1;
            if (b >= 0) {
                bytes.write(b);
            } else {
                byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(raw, 0, raw.length);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}

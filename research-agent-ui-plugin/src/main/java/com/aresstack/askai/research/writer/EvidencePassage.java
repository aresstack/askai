package com.aresstack.askai.research.writer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * One numbered passage of an evidence pack: the local number {@code [n]}, the passage id (a derived link only), the
 * accepted source with its checksum, and the passage text exactly as cut for the pack with its SHA-256 hash (D8).
 */
public final class EvidencePassage {

    private final int number;
    private final String passageId;
    private final String sourceId;
    private final String sourceChecksum;
    private final String text;
    private final String textHash;

    public EvidencePassage(int number, String passageId, String sourceId, String sourceChecksum, String text) {
        if (number < 1) {
            throw new IllegalArgumentException("local numbers start at 1");
        }
        this.number = number;
        this.passageId = passageId == null ? "" : passageId;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.sourceChecksum = sourceChecksum == null ? "" : sourceChecksum;
        this.text = text == null ? "" : text;
        this.textHash = sha256(this.text);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing", e);
        }
    }

    public int getNumber() {
        return number;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceChecksum() {
        return sourceChecksum;
    }

    public String getText() {
        return text;
    }

    public String getTextHash() {
        return textHash;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EvidencePassage)) {
            return false;
        }
        EvidencePassage that = (EvidencePassage) other;
        return number == that.number && passageId.equals(that.passageId) && sourceId.equals(that.sourceId)
                && sourceChecksum.equals(that.sourceChecksum) && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return ((number * 31 + passageId.hashCode()) * 31 + sourceId.hashCode()) * 31 + text.hashCode();
    }
}

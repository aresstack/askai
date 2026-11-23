package com.aresstack.askai.research.text.opennlp;

import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache OpenNLP adapter for the pipeline's {@link SentenceSegmentationPort}: a trained
 * {@link SentenceModel} (e.g. {@code opennlp-de-sentence} / {@code en-sent.bin}) loaded from a local
 * file — the model is a deployment artifact, never bundled here. The domain and the pipeline never see
 * OpenNLP types; without a model the deterministic {@code RegexSentenceSegmenter} stays the fallback.
 */
public final class OpenNlpSentenceSegmenter implements SentenceSegmentationPort {

    private final SentenceDetectorME detector;
    private final String fingerprint;

    public OpenNlpSentenceSegmenter(File sentenceModelFile) throws IOException {
        InputStream in = new FileInputStream(sentenceModelFile);
        try {
            this.detector = new SentenceDetectorME(new SentenceModel(in));
        } finally {
            in.close();
        }
        this.fingerprint = "opennlp-" + checksum(sentenceModelFile);
    }

    /** {@code opennlp-} plus the SHA-256 of the model file: another model gives other sentences (PR 38). */
    @Override
    public String fingerprint() {
        return fingerprint;
    }

    private static String checksum(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public synchronized List<String> segment(String text) {
        // SentenceDetectorME is not thread-safe; segmentation is cheap enough to serialize.
        List<String> sentences = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) {
            return sentences;
        }
        for (String sentence : detector.sentDetect(text)) {
            String trimmed = sentence.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }
}

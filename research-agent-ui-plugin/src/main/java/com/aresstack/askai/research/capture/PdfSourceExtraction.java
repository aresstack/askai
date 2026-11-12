package com.aresstack.askai.research.capture;

import com.aresstack.askai.browser.playwright.PdfDocumentText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #39 — MECHANICAL PDF text extraction for user imports. The extraction itself is {@link PdfDocumentText}, the one
 * PDF extraction of AskAI that the research browser uses for PDF search hits too, so a PDF yields the same text
 * whichever way it entered the research. Deterministic, no model, no OCR in this slice — an image-only or encrypted
 * document reports an honest warning and empty text instead of invented content; the raw PDF stays byte-identical
 * in the {@link ImportedSourceStore} regardless.
 */
final class PdfSourceExtraction {

    static final String EXTRACTOR_ID = "pdfbox-text-v1";

    /** The extraction result: metadata title (may be empty), text, warnings. */
    static final class Extracted {
        final String title;
        final String text;
        final List<String> warnings;

        Extracted(String title, String text, List<String> warnings) {
            this.title = title;
            this.text = text;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    private PdfSourceExtraction() {
    }

    static Extracted extract(byte[] rawBytes) {
        List<String> warnings = new ArrayList<String>();
        if (rawBytes == null || rawBytes.length == 0) {
            return new Extracted("", "", warnings);
        }
        PdfDocumentText pdf = PdfDocumentText.extract(rawBytes);
        if (pdf.failure == PdfDocumentText.Failure.PASSWORD_PROTECTED) {
            warnings.add("PDF is password-protected; no text extracted");
            return new Extracted("", "", warnings);
        }
        if (pdf.failure == PdfDocumentText.Failure.UNPARSEABLE) {
            warnings.add("PDF could not be parsed: " + pdf.failureDetail);
            return new Extracted("", "", warnings);
        }
        if (pdf.encrypted) {
            warnings.add("PDF is encrypted; extraction may be incomplete");
        }
        warnings.add("pages=" + pdf.pages);
        if (pdf.text.isEmpty()) {
            warnings.add("no extractable text — image-only or scanned PDF"
                    + " (OCR is not part of this slice)");
        }
        return new Extracted(pdf.title, pdf.text, warnings);
    }
}

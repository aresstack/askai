package com.aresstack.askai.batch.service;

/**
 * Structured, UI- and filesystem-independent editor for a batch transcription Markdown document. It parses
 * the document into model/profile sections and upserts one result by its stable {@code modelId + profileId}
 * key, so re-running a model/profile replaces exactly that section (never appends a duplicate model heading,
 * never leaves a stale tail of a longer previous text). Pure {@code String -> String}: no file access.
 */
public interface BatchTranscriptionDocumentEditor {

    /**
     * Insert or replace {@code entry} in {@code markdown} and return the new document.
     *
     * @param markdown the current document (may be empty for a new file)
     * @param entry    the result to upsert, keyed by model id + profile id
     */
    String upsertTranscription(String markdown, TranscriptionDocumentEntry entry);
}

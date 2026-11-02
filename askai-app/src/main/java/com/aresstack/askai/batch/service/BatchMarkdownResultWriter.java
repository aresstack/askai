package com.aresstack.askai.batch.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Upsert one model/profile transcription into the source-adjacent Markdown file through the structured
 * {@link BatchTranscriptionDocumentEditor} (no blind append, no string replace), then write the result
 * atomically. Re-running the same model + profile replaces exactly that section; other sections are left
 * untouched. On any failure the existing file stays unchanged.
 */
public final class BatchMarkdownResultWriter {

    private final BatchTranscriptionDocumentEditor editor;

    public BatchMarkdownResultWriter() {
        this(new MarkdownBatchTranscriptionDocumentEditor());
    }

    public BatchMarkdownResultWriter(BatchTranscriptionDocumentEditor editor) {
        this.editor = editor;
    }

    public synchronized File append(File audioFile, String modelName, String profileId,
                                    String profileName, String transcription) throws IOException {
        File markdownFile = markdownFileFor(audioFile);
        String current = markdownFile.isFile()
                ? new String(Files.readAllBytes(markdownFile.toPath()), StandardCharsets.UTF_8) : "";
        String updated = editor.upsertTranscription(current,
                new TranscriptionDocumentEntry(modelName, profileId, profileName, transcription));
        writeAtomically(markdownFile, updated);
        return markdownFile;
    }

    public File markdownFileFor(File audioFile) {
        String name = audioFile.getName();
        int extension = name.lastIndexOf('.');
        String baseName = extension > 0 ? name.substring(0, extension) : name;
        return new File(audioFile.getParentFile(), baseName + ".md");
    }

    /** Write to a temp file in the same directory and atomically replace the target, so a crash mid-write
     *  cannot corrupt or truncate the existing document. */
    private static void writeAtomically(File target, String content) throws IOException {
        File directory = target.getAbsoluteFile().getParentFile();
        if (directory != null && !directory.isDirectory()) {
            directory.mkdirs();
        }
        File temp = File.createTempFile("askai-md-", ".tmp", directory);
        try {
            Files.write(temp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }
}

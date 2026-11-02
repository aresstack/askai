package com.aresstack.askai.hf.convert;

/**
 * GGUF is already Ollama's target format, so a repository that ships a GGUF model needs no
 * conversion — a direct import. Executable today via the existing blob-upload + {@code /api/create}
 * path. mmproj-only repos (an encoder with no base GGUF) are not a standalone model and are not
 * claimed by this strategy.
 */
public final class GgufImportStrategy implements ImportStrategy {

    @Override
    public String name() {
        return "GGUF direct import";
    }

    @Override
    public boolean recognizes(RepositoryAnalysis analysis) {
        return analysis.hasFormat(ModelFormat.GGUF);
    }

    @Override
    public SupportDecision evaluate(RepositoryAnalysis analysis, OllamaEnvironment environment) {
        String reason = "GGUF ist Ollamas Zielformat — direkter Import."
                + (analysis.hasMmproj() ? " Enthält einen mmproj-Encoder (Audio/Vision)." : "");
        return SupportDecision.supported(true, analysis.isVerified(), reason, name(), ModelFormat.GGUF);
    }
}

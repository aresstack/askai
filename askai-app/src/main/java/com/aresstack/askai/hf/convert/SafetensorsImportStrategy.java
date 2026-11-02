package com.aresstack.askai.hf.convert;

import java.util.List;

/**
 * Safetensors can be imported by Ollama's convert path, but only for architectures Ollama supports
 * and only with the required repository files present. This strategy fully implements that
 * <em>classification</em> (architecture allowlist via {@link ArchitectureCatalog} + required files +
 * Ollama version awareness); the actual multi-file upload + {@code /api/create} execution is a
 * deliberate follow-up, so a qualifying repository is reported as SUPPORTED but <em>not executable</em>
 * — the UI shows it as recognized with the install action disabled and an honest "import folgt"
 * reason, never a fake "installed".
 */
public final class SafetensorsImportStrategy implements ImportStrategy {

    @Override
    public String name() {
        return "Safetensors import";
    }

    @Override
    public boolean recognizes(RepositoryAnalysis analysis) {
        return analysis.hasFormat(ModelFormat.SAFETENSORS);
    }

    @Override
    public SupportDecision evaluate(RepositoryAnalysis analysis, OllamaEnvironment environment) {
        if (!analysis.hasConfigJson()) {
            return SupportDecision.unsupported(analysis.isVerified(),
                    "Safetensors ohne config.json — Architektur nicht bestimmbar, kein Import.",
                    ModelFormat.SAFETENSORS);
        }
        if (!analysis.isConfigReadable()) {
            return SupportDecision.unsupported(analysis.isVerified(),
                    "config.json nicht lesbar (evtl. gated — HF-Token in den Einstellungen hinterlegen).",
                    ModelFormat.SAFETENSORS);
        }
        List<String> architectures = analysis.getArchitectures();
        if (architectures.isEmpty()) {
            return SupportDecision.unsupported(analysis.isVerified(),
                    "config.json nennt keine Architektur — Import nicht möglich.", ModelFormat.SAFETENSORS);
        }
        if (!ArchitectureCatalog.isSupported(architectures)) {
            return SupportDecision.unsupported(analysis.isVerified(),
                    "Architektur " + join(architectures) + " wird von Ollamas Safetensors-Import nicht "
                            + "unterstützt.", ModelFormat.SAFETENSORS);
        }
        if (!analysis.hasTokenizer()) {
            return SupportDecision.unsupported(analysis.isVerified(),
                    "Safetensors ohne Tokenizer-Dateien — unvollständig für den Import.",
                    ModelFormat.SAFETENSORS);
        }
        // Qualifies by architecture + files. Execution is a later, separately verified step.
        String versionNote = environment.isVersionKnown()
                ? " (Ollama " + environment.getVersion() + ")"
                : " (Ollama-Version unbekannt — Server nicht erreichbar)";
        return SupportDecision.supported(false, analysis.isVerified(),
                "Safetensors (" + join(architectures) + ") erkannt und importierbar" + versionNote
                        + " — der direkte Safetensors-Import folgt in einem späteren Schritt.",
                name(), ModelFormat.SAFETENSORS);
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}

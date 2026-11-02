package com.aresstack.askai.hf.convert;

import com.aresstack.askai.hf.HuggingFaceModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Central registry of import strategies and the single entry point for deciding a repository's
 * import support (spec §17). Strategies are consulted in registration order; the first that
 * {@link ImportStrategy#recognizes} the repository decides. When none does, the repository is
 * unsupported with a reason naming the detected formats — it stays visible and openable, only its
 * install action is gated (spec §16.2).
 *
 * <p>New formats are added by registering a strategy here, without changing search or UI logic.</p>
 */
public final class ConverterService {

    private final List<ImportStrategy> strategies;

    public ConverterService() {
        this(Arrays.<ImportStrategy>asList(new GgufImportStrategy(), new SafetensorsImportStrategy()));
    }

    public ConverterService(List<ImportStrategy> strategies) {
        this.strategies = new ArrayList<ImportStrategy>(strategies);
    }

    /** The authoritative, file+config-based verdict for a fully analyzed repository. */
    public SupportDecision classify(RepositoryAnalysis analysis, OllamaEnvironment environment) {
        for (int i = 0; i < strategies.size(); i++) {
            ImportStrategy strategy = strategies.get(i);
            if (strategy.recognizes(analysis)) {
                return strategy.evaluate(analysis, environment);
            }
        }
        return SupportDecision.unsupported(analysis.isVerified(),
                "Kein unterstützter Importweg (erkannte Formate: " + analysis.describeFormats() + ").",
                firstFormat(analysis));
    }

    /**
     * Fast, network-free provisional verdict from a search row's tags/library_name, for the initial
     * list render. It only commits to UNSUPPORTED when the tags clearly show no importable format
     * (something is tagged, but neither GGUF nor Safetensors is among them); GGUF is provisionally
     * supported; anything ambiguous (incl. safetensors, which needs the architecture check) stays a
     * non-committal "checking" so the list doesn't grey a hit that a deep analysis would clear.
     */
    public SupportDecision provisionalClassify(HuggingFaceModel model) {
        RepositoryAnalysis provisional = RepositoryAnalysis.provisionalFrom(model);
        if (provisional.hasFormat(ModelFormat.GGUF)) {
            return SupportDecision.supported(true, false, "GGUF (vorläufig, wird bei Auswahl geprüft).",
                    "GGUF direct import", ModelFormat.GGUF);
        }
        if (provisional.hasFormat(ModelFormat.SAFETENSORS)) {
            return SupportDecision.checking();
        }
        if (!provisional.getFormats().isEmpty()) {
            // Something is tagged but no importable format is present -> provisionally unsupported.
            return SupportDecision.unsupported(false,
                    "Vorläufig kein Importweg (" + provisional.describeFormats() + ") — Auswahl prüft genau.",
                    firstFormat(provisional));
        }
        return SupportDecision.checking();
    }

    private static ModelFormat firstFormat(RepositoryAnalysis analysis) {
        for (ModelFormat format : analysis.getFormats()) {
            return format;
        }
        return null;
    }
}

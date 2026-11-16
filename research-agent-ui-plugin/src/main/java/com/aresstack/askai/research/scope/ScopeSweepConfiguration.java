package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.DiverseProbeSelector;
import com.aresstack.askai.research.domain.scope.ScopeFenceCalibrator;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;

/**
 * EVERY behavior-limiting knob of one sweep run in ONE explicit place — no constant hides in any
 * class along the chain. {@link #defaults()} carries the values measured in the Z1-Z3b live gates;
 * they become user-visible settings together with the Z4 trigger UI (until then nothing invokes a
 * sweep productively, so no behavior is silently limited). The quantile/margin formula is still
 * the documented measurement spike — frozen only after productive measurements.
 */
public final class ScopeSweepConfiguration {

    public final int targetBroadProbes;
    public final int controlsPerAnchor;
    public final double generatorTemperature;
    public final int generatorMaxOutputTokens;
    public final int generationTimeoutSeconds;
    public final ScopeFenceEvaluator.Thresholds fenceThresholds;
    public final ScopeFenceCalibrator.CalibrationParameters calibrationParameters;
    public final double boundaryMargin;
    public final double sweepNoveltyGap;
    public final DiverseProbeSelector.Parameters selectorParameters;
    /** Z4b chooser knobs — the second, much smaller model call of one check. */
    public final double chooserTemperature;
    public final int chooserMaxOutputTokens;
    public final int choiceTimeoutSeconds;

    public ScopeSweepConfiguration(int targetBroadProbes, int controlsPerAnchor,
                                   double generatorTemperature, int generatorMaxOutputTokens,
                                   int generationTimeoutSeconds,
                                   ScopeFenceEvaluator.Thresholds fenceThresholds,
                                   ScopeFenceCalibrator.CalibrationParameters calibrationParameters,
                                   double boundaryMargin, double sweepNoveltyGap,
                                   DiverseProbeSelector.Parameters selectorParameters,
                                   double chooserTemperature, int chooserMaxOutputTokens,
                                   int choiceTimeoutSeconds) {
        this.chooserTemperature = chooserTemperature;
        this.chooserMaxOutputTokens = Math.max(1, chooserMaxOutputTokens);
        this.choiceTimeoutSeconds = Math.max(1, choiceTimeoutSeconds);
        this.targetBroadProbes = Math.max(1, targetBroadProbes);
        this.controlsPerAnchor = Math.max(1, controlsPerAnchor);
        this.generatorTemperature = generatorTemperature;
        this.generatorMaxOutputTokens = Math.max(1, generatorMaxOutputTokens);
        this.generationTimeoutSeconds = Math.max(1, generationTimeoutSeconds);
        this.fenceThresholds = fenceThresholds;
        this.calibrationParameters = calibrationParameters;
        this.boundaryMargin = boundaryMargin;
        this.sweepNoveltyGap = sweepNoveltyGap;
        this.selectorParameters = selectorParameters;
    }

    /**
     * The live-gate values, named — the baseline behind every scope-check setting. The two
     * embedding-dependent cut-offs (near similarity, mission slack) are measured on bge-m3
     * (OllamaFenceConceptLiveTest, 2026-11-16); bge-m3 spreads cosines wider than nomic-embed-text.
     */
    public static ScopeSweepConfiguration defaults() {
        return new ScopeSweepConfiguration(
                20,      // targetBroadProbes — the DEFAULT model class (gemma4:e2b) loses count
                         // at 50 and truncates (gate-8 live failure; measured 10s at 20 in Z-E2E),
                         // and Zielbild slice 1 adds mindmap-anchor controls on top. Larger
                         // models raise this in the settings (gpt-oss delivered 50/50).
                2,       // controlsPerAnchor — 2 local variations per negotiated post
                0.7d,    // generatorTemperature — breadth needs variety
                8192,    // generatorMaxOutputTokens — live finding: German answers (longer words,
                         // plus reasoning-model thinking overhead) undershot the 50-probe target
                         // at 4096; 8192 gives the list room
                360,     // generationTimeoutSeconds — live runs took 45-115s; generous headroom
                new ScopeFenceEvaluator.Thresholds(
                        0.68d,   // nearSimilarity — bge-m3: midway between the strongest unexplored
                                 // probe (0.641) and the weakest known/excluded probe (0.722)
                        0.05d),  // hint boundary margin — bge-m3 IN/OUT margins +0.30/-0.20
                new ScopeFenceCalibrator.CalibrationParameters(
                        0.0d, 0.15d,  // mission: min quantile − slack; bge-m3: weakest anchor 0.581, the
                                      // floor must fall between the fridge (0.408) and the
                                      // weakest wearable probe (0.458) — slack 0.123..0.173
                        0.0d, 0.0d,   // neighbor floor: min quantile (spike formula)
                        3, 4),        // minimum negotiated posts / neighbor samples
                0.05d,   // boundaryMargin — live IN/OUT margins were ±0.1-0.2
                0.0d,    // sweepNoveltyGap — relative novelty line at the sweep median
                new DiverseProbeSelector.Parameters(3, 1.0d, 0.8d), // ≤3 regions, dupes ≥0.8 collapse
                0.4d,    // chooserTemperature — picking one offer wants focus, not variety
                1024,    // chooserMaxOutputTokens — one decision + one question fit easily
                120);    // choiceTimeoutSeconds — a single short model call
    }
}

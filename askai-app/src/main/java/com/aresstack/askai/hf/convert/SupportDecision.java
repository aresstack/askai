package com.aresstack.askai.hf.convert;

/**
 * The verdict for one repository: whether AskAI has a registered import strategy for it, whether
 * that import can actually be executed today, whether the verdict is verified (file+config based) or
 * a provisional tag-based placeholder, and a concrete human reason for the UI.
 *
 * <p>Three meaningful UI states arise from {@code support} × {@code executable}:</p>
 * <ul>
 *   <li>SUPPORTED + executable → normal, install enabled (GGUF);</li>
 *   <li>SUPPORTED + not executable → not greyed, install disabled with the reason (Safetensors that
 *       qualifies — its actual import is a later step);</li>
 *   <li>UNSUPPORTED → greyed, install disabled with the reason (no strategy / bad architecture /
 *       missing files).</li>
 * </ul>
 */
public final class SupportDecision {

    public enum Support {
        SUPPORTED, UNSUPPORTED, CHECKING
    }

    private final Support support;
    private final boolean executable;
    private final boolean verified;
    private final String reason;
    private final String strategyName;
    private final ModelFormat format;

    public SupportDecision(Support support, boolean executable, boolean verified, String reason,
                           String strategyName, ModelFormat format) {
        this.support = support;
        this.executable = executable;
        this.verified = verified;
        this.reason = reason == null ? "" : reason;
        this.strategyName = strategyName == null ? "" : strategyName;
        this.format = format;
    }

    public static SupportDecision supported(boolean executable, boolean verified, String reason,
                                            String strategyName, ModelFormat format) {
        return new SupportDecision(Support.SUPPORTED, executable, verified, reason, strategyName, format);
    }

    public static SupportDecision unsupported(boolean verified, String reason, ModelFormat format) {
        return new SupportDecision(Support.UNSUPPORTED, false, verified, reason, "", format);
    }

    /** A "still checking" placeholder used while the authoritative analysis runs. */
    public static SupportDecision checking() {
        return new SupportDecision(Support.CHECKING, false, false, "Kompatibilität wird geprüft …", "", null);
    }

    public Support getSupport() {
        return support;
    }

    public boolean isSupported() {
        return support == Support.SUPPORTED;
    }

    /** @return true while the authoritative analysis is still pending (neither supported nor rejected). */
    public boolean isChecking() {
        return support == Support.CHECKING;
    }

    /** @return true only for a supported format whose import can actually be run now (GGUF). */
    public boolean isExecutable() {
        return support == Support.SUPPORTED && executable;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getReason() {
        return reason;
    }

    public String getStrategyName() {
        return strategyName;
    }

    public ModelFormat getFormat() {
        return format;
    }
}

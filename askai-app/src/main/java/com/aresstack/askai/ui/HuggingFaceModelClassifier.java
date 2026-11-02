package com.aresstack.askai.ui;

import com.aresstack.askai.hf.HuggingFaceModel;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies HuggingFace GGUF search hits so the UI can group them: by provenance (who published
 * it relative to the original model) and by whether the repo is a faithful quantization of an
 * original model or a community <em>variant</em> (a finetune/merge/abliteration).
 *
 * <p>All signals come from real API data, not guesses. The {@code base_model:quantized:&lt;id&gt;}
 * tag names the model a GGUF repo was quantized from, so:</p>
 * <ul>
 *   <li>owner equals the base owner, or owner is a known vendor org &rarr; {@code OFFICIAL};</li>
 *   <li>a known quantizer org quantizing a vendor model &rarr; {@code KNOWN_QUANTIZER};</li>
 *   <li>anything else quantizing a vendor model &rarr; {@code COMMUNITY}.</li>
 * </ul>
 *
 * <p>A hit is a <em>variant</em> when it is built on a non-vendor base model (i.e. a quant of
 * someone's finetune, e.g. {@code base_model:quantized:p-e-w/gemma-3-12b-it-heretic}) or when the
 * repo name carries an unmistakable finetune marker (heretic, abliterated, roleplay, ...). Vendor
 * repos are never treated as variants, so legitimate vendor distills/merges stay in the main list.</p>
 */
public final class HuggingFaceModelClassifier {

    /** Vendor organizations that publish original models. */
    private static final Set<String> VENDOR_OWNERS = new HashSet<String>(Arrays.asList(
            "google", "meta-llama", "mistralai", "qwen", "microsoft", "openai", "deepseek-ai",
            "ibm-granite", "nvidia", "apple", "cohereforai", "stabilityai", "tiiuae", "bigcode",
            "allenai", "hugging-quants", "fixie-ai", "openbmb"));

    /** Well-known quantizer/community orgs whose GGUF conversions are widely used. */
    private static final Set<String> KNOWN_QUANTIZERS = new HashSet<String>(Arrays.asList(
            "ggml-org", "bartowski", "unsloth", "lmstudio-community", "mradermacher", "thebloke"));

    /**
     * Substrings in a repo name that mark a community finetune/merge (checked only for non-vendor
     * repos). Kept conservative: each word signals a remix, not a faithful quantization.
     */
    private static final String[] VARIANT_MARKERS = {
            "heretic", "abliterat", "uncensor", "roleplay", "storywriter", "storyteller",
            "agentic", "nsfw", "waifu", "dolphin", "-merge", "merge-", "-dpo", "-rp-"};

    /** Provenance of a hit relative to the original model. */
    public enum Provenance {
        OFFICIAL, KNOWN_QUANTIZER, COMMUNITY
    }

    /** Orders hits for display: OFFICIAL first, then known quantizers, then community; ties by popularity. */
    public static final Comparator<HuggingFaceModel> DISPLAY_ORDER = new Comparator<HuggingFaceModel>() {
        public int compare(HuggingFaceModel a, HuggingFaceModel b) {
            int byGroup = provenanceOf(a).ordinal() - provenanceOf(b).ordinal();
            if (byGroup != 0) {
                return byGroup;
            }
            if (a.getDownloads() != b.getDownloads()) {
                return a.getDownloads() > b.getDownloads() ? -1 : 1;
            }
            if (a.getLikes() != b.getLikes()) {
                return a.getLikes() > b.getLikes() ? -1 : 1;
            }
            return a.getId().compareToIgnoreCase(b.getId());
        }
    };

    private HuggingFaceModelClassifier() {
    }

    private static boolean isVendor(String owner) {
        return owner.length() > 0 && VENDOR_OWNERS.contains(owner);
    }

    public static Provenance provenanceOf(HuggingFaceModel model) {
        String owner = model.getOwner().toLowerCase(Locale.ROOT);
        String baseOwner = model.getBaseModelOwner().toLowerCase(Locale.ROOT);
        if (owner.length() > 0 && owner.equals(baseOwner)) {
            return Provenance.OFFICIAL;
        }
        if (isVendor(owner)) {
            return Provenance.OFFICIAL;
        }
        if (KNOWN_QUANTIZERS.contains(owner)) {
            return Provenance.KNOWN_QUANTIZER;
        }
        return Provenance.COMMUNITY;
    }

    /**
     * @return true when this hit is a community finetune/merge rather than a faithful quantization
     *         of an original model, so the UI can route it to a separate "variants" list.
     */
    public static boolean isVariant(HuggingFaceModel model) {
        // A vendor's own repo is authoritative — never a variant (protects legit distills/merges).
        if (provenanceOf(model) == Provenance.OFFICIAL) {
            return false;
        }
        String baseOwner = model.getBaseModelOwner().toLowerCase(Locale.ROOT);
        if (baseOwner.length() > 0 && !isVendor(baseOwner)) {
            return true; // quantization of someone's community model
        }
        String name = model.getRepoName().toLowerCase(Locale.ROOT);
        for (int i = 0; i < VARIANT_MARKERS.length; i++) {
            if (name.indexOf(VARIANT_MARKERS[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Map the HuggingFace pipeline tag onto the shared model-capability set (for the icon strip). */
    public static Set<ModelCapability> modalitiesOf(HuggingFaceModel model) {
        String pipeline = model.getPipelineTag().toLowerCase(Locale.ROOT);
        if ("image-text-to-text".equals(pipeline)) {
            return EnumSet.of(ModelCapability.TEXT, ModelCapability.VISION);
        }
        if ("audio-text-to-text".equals(pipeline)) {
            return EnumSet.of(ModelCapability.TEXT, ModelCapability.AUDIO);
        }
        if ("automatic-speech-recognition".equals(pipeline) || "audio-to-audio".equals(pipeline)) {
            return EnumSet.of(ModelCapability.AUDIO);
        }
        if ("any-to-any".equals(pipeline)) {
            return EnumSet.of(ModelCapability.TEXT, ModelCapability.AUDIO, ModelCapability.VISION);
        }
        return EnumSet.of(ModelCapability.TEXT);
    }
}

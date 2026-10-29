package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Catalog- and MANIFEST-driven presentation of an AskAI local-engine model. Business data (family,
 * capabilities, backends, package, revision, install date, state) comes from the model's catalog-validated
 * {@link InstalledModelManifest} — never from a hardcoded "rerank / CPU" string and never reconstructed by
 * splitting the virtual id. Whether a generation model is still "pending" is driven by the SIDECAR's
 * reported generation-runtime linkage, not by a hardcoded family list, so it clears automatically once a
 * productive runtime is linked. {@code supportedBackends} is the catalogued support set, NOT a claim about
 * the currently active backend mode.
 */
public final class LocalEngineModelView {

    private static final DateTimeFormatter INSTALLED_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Shown when a local model's metadata cannot be validated (fail-closed: offer no actions). */
    public static final String METADATA_UNAVAILABLE = "Local model metadata unavailable";

    private LocalEngineModelView() {
    }

    /** The repository id encoded in a {@code local/<repo>:latest} virtual name, or "" when not one. */
    public static String repositoryOf(String virtualName) {
        if (virtualName == null) {
            return "";
        }
        String name = virtualName.trim();
        if (!name.startsWith(LocalModelNames.LOCAL_PREFIX)) {
            return "";
        }
        name = name.substring(LocalModelNames.LOCAL_PREFIX.length());
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(0, colon) : name;
    }

    /** The catalog descriptor for a virtual model id, or {@code null} when it is not catalogued. */
    public static LocalRuntimeModelDescriptor descriptorOf(String virtualName) {
        return LocalModelCatalog.findByRepositoryId(repositoryOf(virtualName));
    }

    /** Whether this virtual model is catalogued at all (fail-closed gate for the UI). */
    public static boolean hasLocalMetadata(String virtualName) {
        return descriptorOf(virtualName) != null;
    }

    /** Whether this virtual model advertises a capability, per the catalog (never per its name). */
    public static boolean hasCapability(String virtualName, ModelCapability capability) {
        LocalRuntimeModelDescriptor descriptor = descriptorOf(virtualName);
        return descriptor != null && descriptor.hasCapability(capability);
    }

    /** Whether a capability set is a generation set (completion or chat). */
    public static boolean isGenerationManifest(InstalledModelManifest manifest) {
        return manifest.hasCapability(ModelCapability.COMPLETION)
                || manifest.hasCapability(ModelCapability.CHAT);
    }

    /**
     * Whether this model is a generation model whose runtime is not linked yet — capability-based, NOT a
     * hardcoded family list. {@code generationLinked} comes from the local runtime's reported feature set.
     */
    public static boolean isRuntimePending(InstalledModelManifest manifest, boolean generationLinked) {
        return isGenerationManifest(manifest) && !generationLinked;
    }

    /**
     * The manifest-backed detail line for an INSTALLED local card. For a not-yet-linked generation model it
     * states the pending status; otherwise it lists family, capabilities, catalogued backends, the runtime
     * package, the installed revision and date. Values that a v1 reranker manifest omits (family/package)
     * are taken from the matching catalog descriptor.
     */
    public static String installedDetailLine(InstalledModelManifest manifest, boolean generationLinked) {
        if (manifest == null) {
            return METADATA_UNAVAILABLE;
        }
        LocalRuntimeModelDescriptor descriptor =
                LocalModelCatalog.findByRepositoryId(manifest.getHuggingFaceRepository());
        String family = familyDisplay(manifest, descriptor);
        if (isRuntimePending(manifest, generationLinked)) {
            return "Family: " + family
                    + " · Catalogued for AskAI Local Engine · Runtime integration pending";
        }
        String pkg = !manifest.getRuntimePackage().isEmpty() ? manifest.getRuntimePackage()
                : descriptor != null ? descriptor.runtimePackageFileName() : "";
        StringBuilder line = new StringBuilder("Family: ").append(family)
                .append(" · Capability: ").append(join(manifest.getCapabilities()))
                .append(" · Backend: ").append(join(manifest.getSupportedBackends()));
        if (!pkg.isEmpty()) {
            line.append(" · Package: ").append(pkg);
        }
        if (!manifest.getResolvedRevision().isEmpty()) {
            line.append(" · Revision: ").append(shortRevision(manifest.getResolvedRevision()));
        }
        if (manifest.getInstalledAt() > 0) {
            line.append(" · Installed: ").append(installedDate(manifest.getInstalledAt()));
        }
        return line.append(" · Runtime: win-directml-java").toString();
    }

    /**
     * The line for a RUNNING local card: family + capabilities only. It deliberately does NOT claim an
     * active backend mode (the local runtime does not report one) and does not invent one from the catalog.
     */
    public static String runningDetailLine(String virtualName) {
        LocalRuntimeModelDescriptor descriptor = descriptorOf(virtualName);
        if (descriptor == null) {
            return METADATA_UNAVAILABLE;
        }
        return "Family: " + descriptor.runtimeFamily().displayName()
                + " · Capability: " + join(InstalledModelManifest.expectedCapabilityTokens(descriptor))
                + " · Runtime: win-directml-java";
    }

    // ------------------------------------------------------------------ helpers

    private static String familyDisplay(InstalledModelManifest manifest,
                                        LocalRuntimeModelDescriptor descriptor) {
        if (descriptor != null) {
            return descriptor.runtimeFamily().displayName();
        }
        return manifest.getRuntimeFamily().isEmpty() ? "AskAI Local Engine" : manifest.getRuntimeFamily();
    }

    private static String shortRevision(String revision) {
        return revision.length() <= 12 ? revision : revision.substring(0, 12);
    }

    private static String installedDate(long epochMillis) {
        return INSTALLED_DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private static String join(List<String> tokens) {
        return tokens.isEmpty() ? "?" : String.join(", ", tokens);
    }
}

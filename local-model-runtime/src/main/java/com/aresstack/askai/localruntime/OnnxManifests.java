package com.aresstack.askai.localruntime;

import com.aresstack.askai.localruntime.onnx.OnnxModelCatalog;
import com.aresstack.askai.localruntime.onnx.OnnxModelDescriptor;
import com.aresstack.windirectml.catalog.InstalledModelManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The installed-model manifest of models served by ONNX Runtime. It reuses the shared
 * {@link InstalledModelManifest} shape (so the host codec, model browser and capability catalogs read it unchanged)
 * with {@code runtimePackage = "onnx"}; trust is decided against {@link OnnxModelCatalog}, never against the
 * win-directml catalog, and never by what the manifest claims.
 */
public final class OnnxManifests {

    /** {@code runtimePackage} and {@code sourceFormat} token of ONNX models. */
    public static final String ONNX = LocalModel.ONNX_PACKAGE;
    /** The only state the installer writes for a completed install. */
    public static final String STATE_RUNNABLE = "RUNNABLE";

    private OnnxManifests() {
    }

    /** The manifest the host writes LAST after a verified install + smoke-load. */
    public static InstalledModelManifest forInstall(OnnxModelDescriptor descriptor, String backend, String revision,
                                                    long installedAt) {
        return new InstalledModelManifest(InstalledModelManifest.CURRENT_SCHEMA_VERSION,
                descriptor.virtualModelName(), descriptor.repositoryId(), revision == null ? "" : revision,
                descriptor.repositoryId(), descriptor.architecture(), ONNX,
                List.of(descriptor.capability().token()), List.of(backend), ONNX, STATE_RUNNABLE, installedAt);
    }

    /** Whether an ONNX manifest in {@code directory} is trustworthy: catalogued, consistent, files present. */
    public static boolean isValid(InstalledModelManifest manifest, Path directory) {
        return rejection(manifest, directory) == null;
    }

    /** @return why the manifest is rejected, or {@code null} when it is valid. */
    static String rejection(InstalledModelManifest manifest, Path directory) {
        if (manifest == null || !ONNX.equals(manifest.getRuntimePackage())) {
            return "not an ONNX manifest";
        }
        OnnxModelDescriptor descriptor = OnnxModelCatalog.findByRepositoryId(manifest.getHuggingFaceRepository());
        if (descriptor == null) {
            return "repository '" + manifest.getHuggingFaceRepository() + "' is not in the ONNX catalog";
        }
        if (!descriptor.isRunnable()) {
            return "repository '" + descriptor.repositoryId() + "' is not runnable (" + descriptor.status() + ")";
        }
        if (!descriptor.virtualModelName().equals(manifest.getVirtualName())) {
            return "virtual name '" + manifest.getVirtualName() + "' does not match the catalog";
        }
        if (!List.of(descriptor.capability().token()).equals(manifest.getCapabilities())) {
            return "capabilities " + manifest.getCapabilities() + " do not match the catalog";
        }
        if (!STATE_RUNNABLE.equals(manifest.getState())) {
            return "state is '" + manifest.getState() + "'";
        }
        for (String file : List.of(OnnxModelDescriptor.LOCAL_GRAPH, OnnxModelDescriptor.LOCAL_TOKENIZER)) {
            if (!Files.isRegularFile(directory.resolve(file))) {
                return file + " is missing";
            }
        }
        return null;
    }
}

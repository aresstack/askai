package com.aresstack.askai.localmodels;

import com.aresstack.askai.localruntime.OnnxManifests;
import com.aresstack.askai.localruntime.onnx.OnnxModelCatalog;
import com.aresstack.askai.localruntime.onnx.OnnxModelDescriptor;
import com.aresstack.windirectml.catalog.LocalModelCatalog;

import com.aresstack.askai.hf.DownloadProgressListener;
import com.aresstack.askai.hf.HuggingFaceClient;
import com.aresstack.askai.hf.HuggingFaceFile;
import com.aresstack.windirectml.catalog.DownloadFile;
import com.aresstack.windirectml.catalog.DownloadManifest;
import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The staged LOCAL installation of a catalogued Hugging Face model. The FAMILY is decided by the neutral
 * catalog (never a name heuristic): resolve revision → download the catalogued files (checksums verified by
 * the HF client) → hand the staged directory to the local runtime, which re-resolves the repository
 * against the catalog ITSELF, compiles the family's {@code *.wdmlpack} and runs a package-backed
 * smoke-load → write the shared v2 provenance manifest LAST → atomically activate the directory. Nothing
 * half-installed becomes visible; the local runtime only publishes directories whose manifest validates against
 * the catalog.
 */
public final class LocalModelInstaller {

    /** Step-by-step progress for the UI; download progress reports bytes. */
    public interface Listener {
        void onStep(String step);

        void onDownloadProgress(String fileName, long completed, long total);
    }

    private final HuggingFaceClient client;
    private final LocalModelRuntimeManager manager;

    public LocalModelInstaller(HuggingFaceClient client, LocalModelRuntimeManager manager) {
        this.client = client;
        this.manager = manager;
    }

    /** @return the virtual model name after a fully verified installation. */
    public String install(String repositoryId, final Listener listener) throws IOException {
        OnnxModelDescriptor onnx = OnnxModelCatalog.findByRepositoryId(repositoryId);
        if (onnx != null) {
            return installOnnx(onnx, listener);
        }
        LocalModelInstallResolution resolution = LocalModelInstallResolution.resolve(repositoryId);
        if (!resolution.isInstallable()) {
            throw new IOException(resolution.getMessage() != null ? resolution.getMessage()
                    : "'" + repositoryId + "' cannot be installed into the local runtime.");
        }
        LocalRuntimeModelDescriptor descriptor = resolution.getDescriptor();
        DownloadManifest downloads = descriptor.downloadManifest();
        String downloadRepo = downloads.repositoryId();

        listener.onStep("Resolving repository revision…");
        String revision = client.resolveRevisionSha(downloadRepo, "main");
        List<HuggingFaceFile> remoteFiles = client.listAllFilesDetailed(downloadRepo);

        File localRoot = manager.getModelRoot();
        if (!localRoot.isDirectory() && !localRoot.mkdirs()) {
            throw new IOException("Cannot create local model root: " + localRoot);
        }
        String directoryName = descriptor.runtimeDirectoryName();
        File staging = new File(localRoot, ".staging-" + directoryName + "-" + System.nanoTime());
        // The runtime validates the FINAL path component strictly, so the assembly directory already
        // carries the canonical name and is renamed as a whole at the end.
        File assembly = new File(staging, directoryName);
        if (!assembly.mkdirs()) {
            throw new IOException("Cannot create staging directory: " + assembly);
        }
        try {
            for (DownloadFile spec : downloads.files()) {
                HuggingFaceFile remote = findFile(remoteFiles, spec.remotePath());
                if (remote == null) {
                    if (spec.required()) {
                        throw new IOException("Repository file missing: " + spec.remotePath());
                    }
                    continue; // an absent OPTIONAL file is fine
                }
                listener.onStep("Downloading " + spec.localName() + "…");
                final String fileName = spec.localName();
                File downloaded = client.download(remote, staging, revision,
                        new DownloadProgressListener() {
                            public void onProgress(long completed, long total) {
                                listener.onDownloadProgress(fileName, completed, total);
                            }
                        });
                // The runtime sees ONE flat directory: store every file under its flat local name.
                Files.move(downloaded.toPath(), new File(assembly, spec.localName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            String virtualName = descriptor.virtualModelName();
            String baseUrl = manager.ensureStarted();
            File finalDirectory = new File(localRoot, directoryName);
            if (finalDirectory.exists()) {
                // Reinstallation: ask the local runtime to unload the previous installation, then remove it.
                try {
                    LocalRuntimeHttp.postJson(baseUrl, "/api/generate", unloadRequest(virtualName));
                } catch (IOException ignored) {
                    // the previous model may not be loaded — deletion below still applies
                }
                deleteRecursively(finalDirectory);
            }
            // Move the raw assets into their FINAL home BEFORE the local runtime compiles + smoke-loads them. A
            // generation smoke memory-maps the compiled package and the win-directml runtime keeps that file
            // locked for the local runtime's lifetime, so renaming the directory AFTER the smoke is impossible on
            // Windows. Compiling in place and writing the manifest LAST preserves the "nothing half-installed
            // is visible" guarantee: the fail-closed reader ignores any directory without a valid manifest.
            activate(assembly, finalDirectory);
            boolean committed = false;
            try {
                listener.onStep("Compiling the runtime package (" + descriptor.runtimePackageFileName()
                        + ") and smoke-loading it…");
                Map<String, Object> installRequest = new LinkedHashMap<String, Object>();
                // The local runtime re-derives family/capabilities/package from the catalog; these are identifiers.
                installRequest.put("repositoryId", descriptor.huggingFaceRepositoryId());
                installRequest.put("runtimeModelId", descriptor.runtimeModelId());
                installRequest.put("virtualName", virtualName);
                installRequest.put("modelDirectory", finalDirectory.getAbsolutePath());
                installRequest.put("force", Boolean.TRUE);
                Map<String, Object> installed =
                        LocalRuntimeHttp.postJson(baseUrl, "/internal/install", installRequest);
                if (!Boolean.TRUE.equals(installed.get("ok"))) {
                    throw new IOException("Local install failed [" + String.valueOf(installed.get("code"))
                            + "]: " + String.valueOf(installed.get("reason")));
                }

                listener.onStep("Writing the installation manifest…");
                // Manifest v2 (shared, catalog-validated on read): the catalog facts plus the resolved
                // revision and install time. Written LAST — the commit marker after the verified compile +
                // package-backed smoke-load.
                Files.write(new File(finalDirectory, "askai-local-model.json").toPath(),
                        LocalModelManifestCodec.toJson(InstalledModelManifest.forInstall(descriptor, revision,
                                System.currentTimeMillis())).getBytes(Charset.forName("UTF-8")));
                committed = true;
                return virtualName;
            } finally {
                if (!committed) {
                    // A manifest-less directory is invisible to the reader, but remove it so a failed install
                    // does not leave gigabytes of raw assets behind.
                    deleteRecursively(finalDirectory);
                }
            }
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * The ONNX installation: download the preferred graph (int8 when the setting asks for it and the repository has
     * one; external weights too), {@code tokenizer.json} and {@code config.json} into flat local names, let the local
     * runtime load the graph with ONNX Runtime and run one inference, then write the manifest LAST and activate. A
     * win-directml installation of the same repository is removed, so one virtual name has one installation.
     */
    private String installOnnx(OnnxModelDescriptor descriptor, final Listener listener) throws IOException {
        if (!descriptor.isRunnable()) {
            throw new IOException("'" + descriptor.repositoryId() + "' has no ONNX export yet. "
                    + descriptor.exportNote());
        }
        listener.onStep("Resolving repository revision…");
        String repo = descriptor.repositoryId();
        String revision = client.resolveRevisionSha(repo, "main");
        final List<HuggingFaceFile> remoteFiles = client.listAllFilesDetailed(repo);
        OnnxModelDescriptor.GraphVariant graph = descriptor.preferredGraph(manager.onnxOptions().preferQuantized(),
                path -> findFile(remoteFiles, path) != null);
        if (graph == null) {
            throw new IOException("Repository " + repo + " has none of the catalogued ONNX graphs");
        }
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put(graph.remotePath(), OnnxModelDescriptor.LOCAL_GRAPH);
        if (!graph.externalDataRemotePath().isEmpty()) {
            files.put(graph.externalDataRemotePath(), graph.externalDataLocalName());
        }
        files.put(descriptor.tokenizerRemotePath(), OnnxModelDescriptor.LOCAL_TOKENIZER);
        files.put(descriptor.configRemotePath(), OnnxModelDescriptor.LOCAL_CONFIG);

        File localRoot = manager.getModelRoot();
        if (!localRoot.isDirectory() && !localRoot.mkdirs()) {
            throw new IOException("Cannot create local model root: " + localRoot);
        }
        String directoryName = descriptor.directoryName();
        File staging = new File(localRoot, ".staging-" + directoryName + "-" + System.nanoTime());
        File assembly = new File(staging, directoryName);
        if (!assembly.mkdirs()) {
            throw new IOException("Cannot create staging directory: " + assembly);
        }
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                HuggingFaceFile remote = findFile(remoteFiles, file.getKey());
                if (remote == null) {
                    throw new IOException("Repository file missing: " + file.getKey());
                }
                final String fileName = file.getValue();
                listener.onStep("Downloading " + fileName + "…");
                File downloaded = client.download(remote, staging, revision, new DownloadProgressListener() {
                    public void onProgress(long completed, long total) {
                        listener.onDownloadProgress(fileName, completed, total);
                    }
                });
                Files.move(downloaded.toPath(), new File(assembly, fileName).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            String virtualName = descriptor.virtualModelName();
            String baseUrl = manager.ensureStarted();
            File finalDirectory = new File(localRoot, directoryName);
            List<File> previous = new ArrayList<File>();
            previous.add(finalDirectory);
            LocalRuntimeModelDescriptor directml = LocalModelCatalog.findByRepositoryId(repo);
            if (directml != null) {
                previous.add(new File(localRoot, directml.runtimeDirectoryName()));
            }
            boolean unloaded = false;
            for (File old : previous) {
                if (old.exists()) {
                    if (!unloaded) {
                        try {
                            LocalRuntimeHttp.postJson(baseUrl, "/api/generate", unloadRequest(virtualName));
                        } catch (IOException ignored) {
                            // not loaded — deletion below still applies
                        }
                        unloaded = true;
                    }
                    deleteRecursively(old);
                }
            }
            activate(assembly, finalDirectory);
            boolean committed = false;
            try {
                listener.onStep("Loading the model with ONNX Runtime and running a test inference…");
                Map<String, Object> installRequest = new LinkedHashMap<String, Object>();
                installRequest.put("repositoryId", repo);
                installRequest.put("virtualName", virtualName);
                installRequest.put("modelDirectory", finalDirectory.getAbsolutePath());
                Map<String, Object> installed =
                        LocalRuntimeHttp.postJson(baseUrl, "/internal/install", installRequest);
                if (!Boolean.TRUE.equals(installed.get("ok"))) {
                    throw new IOException("Local install failed [" + String.valueOf(installed.get("code"))
                            + "]: " + String.valueOf(installed.get("reason")));
                }
                listener.onStep("Writing the installation manifest…");
                String backend = String.valueOf(installed.get("backend"));
                Files.write(new File(finalDirectory, "askai-local-model.json").toPath(),
                        LocalModelManifestCodec.toJson(OnnxManifests.forInstall(descriptor, backend, revision,
                                System.currentTimeMillis())).getBytes(Charset.forName("UTF-8")));
                committed = true;
                return virtualName;
            } finally {
                if (!committed) {
                    deleteRecursively(finalDirectory);
                }
            }
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Rename the staged raw-asset directory into its final home (before the local runtime compiles/smokes it). A
     * few retries absorb the transient {@link java.nio.file.AccessDeniedException} Windows raises when an
     * antivirus/search indexer momentarily holds a just-downloaded file.
     */
    private static void activate(File assembly, File finalDirectory) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 25; attempt++) {
            try {
                Files.move(assembly.toPath(), finalDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException notAtomic) {
                try {
                    Files.move(assembly.toPath(), finalDirectory.toPath());
                    return;
                } catch (IOException retryable) {
                    last = retryable;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("Could not activate the installed model directory " + finalDirectory
                + " (the runtime may still hold the compiled package): "
                + (last != null ? last.getMessage() : "unknown"), last);
    }

    private static Map<String, Object> unloadRequest(String virtualName) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("model", virtualName);
        request.put("keep_alive", 0);
        return request;
    }

    // ------------------------------------------------------------------ helpers

    /** @return the remote file matching this repo-root-relative path, or {@code null} when absent. */
    private static HuggingFaceFile findFile(List<HuggingFaceFile> files, String remotePath) {
        for (HuggingFaceFile file : files) {
            if (file.getPath().equals(remotePath)) {
                return file;
            }
        }
        return null;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}

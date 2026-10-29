package com.aresstack.askai.java8.service;

import com.aresstack.askai.java8.client.AskAiOllamaClient;
import com.aresstack.askai.java8.config.AppConfiguration;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.hf.DownloadProgressListener;
import com.aresstack.askai.java8.hf.HuggingFaceClient;
import com.aresstack.askai.java8.hf.HuggingFaceFile;
import com.aresstack.askai.java8.hf.HuggingFaceSearchResult;
import com.aresstack.askai.java8.hf.HuggingFaceSearchUseCase;
import com.aresstack.askai.java8.hf.ModelSearchCriteria;
import com.aresstack.askai.java8.hf.catalog.CatalogRepository;
import com.aresstack.askai.java8.hf.convert.ConverterService;
import com.aresstack.askai.java8.hf.convert.OllamaEnvironment;
import com.aresstack.askai.java8.hf.convert.RepositoryAnalysis;
import com.aresstack.askai.java8.hf.convert.RepositoryAnalyzer;
import com.aresstack.askai.java8.hf.convert.SupportDecision;
import com.aresstack.askai.java8.ollamalib.OllamaLibraryClient;
import com.aresstack.askai.java8.client.OllamaPullListener;
import com.aresstack.askai.java8.client.OllamaPullProgress;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class DefaultAskAiService implements AskAiService {

    private final AppConfigurationRepository configurationRepository;
    private final ExecutorService executorService;
    private final ConverterService converterService = new ConverterService();
    private final CatalogRepository catalogRepository = new CatalogRepository();
    // Shared app-wide owner of the Java-21 local model runtime process (R0).
    private final com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager localRuntimeManager =
            new com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager();
    // Single cached instance so its short-TTL page cache survives across calls.
    private volatile OllamaLibraryClient ollamaLibraryClient;

    public DefaultAskAiService(AppConfigurationRepository configurationRepository) {
        this.configurationRepository = configurationRepository;
        this.executorService = Executors.newCachedThreadPool(new DaemonThreadFactory());
    }

    public void pullOllamaModel(final String modelName, final PullListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    client().pullModel(modelName, new OllamaPullListener() {
                        public void onProgress(OllamaPullProgress progress) {
                            listener.onProgress(progress);
                        }
                    });
                    // Accept only what the installed Ollama actually reports via /api/show.
                    listener.onVerified(verifyInstalled(modelName, java.util.Collections.<String>emptyList()));
                    listener.onComplete("Installed " + modelName + " on remote Ollama.");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void searchHuggingFaceModels(final ModelSearchCriteria criteria, final HuggingFaceSearchListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onResult(new HuggingFaceSearchUseCase(huggingFaceClient()).search(criteria));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void loadMoreHuggingFaceModels(final ModelSearchCriteria criteria, final HuggingFaceSearchResult previous,
                                          final HuggingFaceSearchListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onResult(new HuggingFaceSearchUseCase(huggingFaceClient()).loadMore(criteria, previous));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void listHuggingFaceFiles(final String modelId, final HuggingFaceFileListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onFiles(huggingFaceClient().listFiles(modelId));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void analyzeRepository(final String modelId, final RepositoryAnalysisListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    RepositoryAnalysis analysis = new RepositoryAnalyzer(huggingFaceClient()).analyze(modelId);
                    OllamaEnvironment environment = new OllamaEnvironment(safeOllamaVersion());
                    SupportDecision decision = converterService.classify(analysis, environment);
                    listener.onDecision(decision, analysis);
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void searchOllamaLibrary(final String query, final OllamaLibraryListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onModels(ollamaLibraryClient().search(query));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void loadOllamaVariants(final String baseName, final OllamaVariantsListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onVariants(ollamaLibraryClient().loadVariants(baseName));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    private OllamaLibraryClient ollamaLibraryClient() {
        OllamaLibraryClient local = ollamaLibraryClient;
        if (local == null) {
            synchronized (this) {
                if (ollamaLibraryClient == null) {
                    AppConfiguration configuration = configurationRepository.load();
                    ollamaLibraryClient = new OllamaLibraryClient(
                            configuration.getProxyConfiguration(),
                            configuration.getCertificateTrustConfiguration(),
                            configuration.getHttpClientConfiguration());
                }
                local = ollamaLibraryClient;
            }
        }
        return local;
    }

    public void loadFilterCatalogs(final boolean forceLive, final FilterCatalogListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    listener.onLoaded(catalogRepository.load(huggingFaceClient(), forceLive));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    /** @return the Ollama server version, or "" when the server is unreachable (best-effort). */
    private String safeOllamaVersion() {
        try {
            return client().getVersion();
        } catch (Exception ex) {
            return "";
        }
    }

    public void downloadHuggingFaceFile(final HuggingFaceFile file, final DownloadListener listener) {
        downloadHuggingFaceFile(file, null, listener);
    }

    public void downloadHuggingFaceFile(final HuggingFaceFile file, final String pinnedRevision,
                                        final DownloadListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    com.aresstack.askai.java8.hf.HuggingFaceClient client = huggingFaceClient();
                    // Pin to an immutable commit: use the caller's pinned SHA when given (so a companion
                    // shares the main model's commit), otherwise resolve main once here.
                    String sha = pinnedRevision != null && pinnedRevision.trim().length() > 0
                            ? pinnedRevision.trim()
                            : client.resolveRevisionSha(file.getModelId(), "main");
                    listener.onResolvedRevision(sha);
                    File downloaded = client.download(file,
                            configurationRepository.load().getModelDownloadDirectory(), sha,
                            new DownloadProgressListener() {
                                public void onProgress(long completed, long total) {
                                    listener.onProgress(completed, total);
                                }
                            });
                    listener.onComplete(downloaded);
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public InstallTask installGgufFile(final String modelName, final File ggufFile, final InstallListener listener) {
        return installGgufFileWithCompanions(modelName, ggufFile, java.util.Collections.<File>emptyList(), listener);
    }

    public InstallTask installGgufFileWithCompanions(final String modelName, final File ggufFile,
                                                     final List<File> companionFiles,
                                                     final InstallListener listener) {
        return installGgufFileWithCompanions(modelName, ggufFile, companionFiles,
                java.util.Collections.<String>emptyList(), listener);
    }

    public InstallTask installGgufFileWithCompanions(final String modelName, final File ggufFile,
                                                     final List<File> companionFiles,
                                                     final List<String> requiredCapabilities,
                                                     final InstallListener listener) {
        return installGgufFileWithCompanions(modelName, ggufFile, companionFiles,
                com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.ofCapabilities(
                        RemoteGgufInstaller.normalizeCapabilities(requiredCapabilities)),
                listener);
    }

    public InstallTask installGgufFileWithCompanions(final String modelName, final File ggufFile,
                                                     final List<File> companionFiles,
                                                     final com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata metadata,
                                                     final InstallListener listener) {
        return runInstall(modelName, ggufFile, companionFiles, new java.util.concurrent.Callable<
                com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata>() {
            public com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata call() {
                return metadata;
            }
        }, listener);
    }

    public InstallTask installGgufFileWithPlan(final String modelName, final File ggufFile,
                                               final List<File> companionFiles,
                                               final com.aresstack.askai.java8.hf.HuggingFaceInstallPlan plan,
                                               final InstallListener listener) {
        final com.aresstack.askai.java8.hf.HuggingFaceClient hf = huggingFaceClient();
        return runInstall(modelName, ggufFile, companionFiles, new java.util.concurrent.Callable<
                com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata>() {
            public com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata call() {
                // Runs on the install executor (never the EDT): loads config.json / generation_config.json
                // / HF model-info for the frozen repo+revision and maps the trusted values to /api/create.
                listener.onProgress("Preparing metadata", 0, 0);
                return buildPlanMetadata(hf, plan, ggufFile, companionFiles);
            }
        }, listener);
    }

    /** Best-effort enrichment; degrades to the plan's capabilities + registry family when HF is unreachable. */
    private com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata buildPlanMetadata(
            com.aresstack.askai.java8.hf.HuggingFaceClient hf,
            com.aresstack.askai.java8.hf.HuggingFaceInstallPlan plan, File ggufFile, List<File> companionFiles) {
        // The capability set sent to /api/create is decided by the INSTALLED files, not by HF alone:
        //  - vision/audio only when a GGUF here actually carries that encoder (RuntimeCapabilities), and
        //  - tools/thinking/insert only from the main GGUF's own baked-in template (the real runtime),
        // so a HF tokenizer config can never feign a capability the runtime does not have.
        com.aresstack.askai.java8.hf.RuntimeCapabilities runtime =
                com.aresstack.askai.java8.hf.RuntimeCapabilities.fromFiles(ggufFile, companionFiles);
        List<String> runtimeTemplateCaps = templateCapabilities(ggufFile);
        try {
            com.aresstack.askai.java8.hf.meta.HuggingFaceMetadataLoader.Result result =
                    new com.aresstack.askai.java8.hf.meta.HuggingFaceMetadataLoader(
                            new com.aresstack.askai.java8.hf.meta.HuggingFaceClientMetadataGateway(hf))
                            .loadWithProvenance(plan, ggufFile.getAbsolutePath(), sha256(ggufFile), ggufFile.length());
            // Best-effort, diagnostic-only provenance sidecar (never used for the installed display).
            try {
                result.provenance().writeSidecar(ggufFile);
            } catch (Exception ignored) {
                // provenance is optional; a write failure must not block the install
            }
            return result.metadata().withCapabilities(
                    finalCapabilities(result.metadata().capabilities(), runtime, runtimeTemplateCaps));
        } catch (RuntimeException ex) {
            return com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata.ofCapabilities(finalCapabilities(
                    RemoteGgufInstaller.normalizeCapabilities(plan.getRequiredOllamaCapabilities()),
                    runtime, runtimeTemplateCaps));
        }
    }

    /** vision/audio intersected with the installed files, unioned with the GGUF template's tools/thinking/insert. */
    private static List<String> finalCapabilities(List<String> base,
            com.aresstack.askai.java8.hf.RuntimeCapabilities runtime, List<String> runtimeTemplateCaps) {
        java.util.LinkedHashSet<String> caps =
                new java.util.LinkedHashSet<String>(runtime.intersect(base));
        caps.addAll(runtimeTemplateCaps);
        return new java.util.ArrayList<String>(caps);
    }

    /** @return tools/thinking/insert the main GGUF's own template supports, or empty when unreadable. */
    private static List<String> templateCapabilities(File ggufFile) {
        try {
            return com.aresstack.askai.java8.hf.GgufFile.inspect(ggufFile).templateCapabilities();
        } catch (Exception ex) {
            return java.util.Collections.emptyList();
        }
    }

    /** SHA-256 of a file as lowercase hex, or "" on any error (provenance is best-effort). */
    private static String sha256(File file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    public InstallTask attachEncoder(final String existingModelName, final File projectorGguf,
                                     final InstallListener listener) {
        AppConfiguration configuration = configurationRepository.load();
        final RemoteGgufInstaller installer = new RemoteGgufInstaller(configuration.getOllamaBaseUrl());
        final Future<?> future = executorService.submit(new Runnable() {
            public void run() {
                try {
                    // Inspect the projector exactly ONCE, before any upload/create. It must be a real
                    // projector and yield a non-empty expected capability set; that immutable list is then
                    // used for verification. No "read again after attach, fall back to empty on error" path,
                    // which could otherwise resurrect the empty-list sham verification.
                    com.aresstack.askai.java8.hf.GgufFile.GgufInfo info =
                            com.aresstack.askai.java8.hf.GgufFile.inspect(projectorGguf);
                    if (!info.isProjector()) {
                        throw new IllegalArgumentException(
                                "The chosen file is not a multimodal encoder (projector) GGUF.");
                    }
                    final List<String> expected = info.modalityCapabilities();
                    installer.attachAdapter(existingModelName, projectorGguf,
                            new RemoteGgufInstaller.ProgressListener() {
                                public void onProgress(String phase, long completed, long total) {
                                    listener.onProgress(phase, completed, total);
                                }
                            });
                    // Verify against what THIS projector actually backs (vision and/or audio), not an empty
                    // list — otherwise a no-op attach that still reports only "completion" would count as
                    // verified.
                    VerificationResult verification = verifyInstalled(existingModelName, expected);
                    listener.onVerified(verification);
                    if (verification.getStatus() == VerificationStatus.VERIFIED) {
                        listener.onComplete("Attached encoder to " + existingModelName + " on remote Ollama.");
                    } else {
                        listener.onIncomplete(verification);
                    }
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
        return new InstallTask() {
            public void cancel() {
                installer.cancel();
                future.cancel(true);
            }
        };
    }

    /**
     * Shared install pipeline: resolve the metadata (possibly with a network fetch) on the executor, send
     * it to Ollama on {@code /api/create}, then verify via {@code /api/show}. Only a VERIFIED result
     * reports "Installed"; MISSING_REQUIRED / UNKNOWN / FAILED end as incomplete so the UI never shows a
     * failed verification and "Installed" together.
     */
    private InstallTask runInstall(final String modelName, final File ggufFile, final List<File> companionFiles,
                                   final java.util.concurrent.Callable<
                                           com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata> metadataSupplier,
                                   final InstallListener listener) {
        AppConfiguration configuration = configurationRepository.load();
        final RemoteGgufInstaller installer = new RemoteGgufInstaller(configuration.getOllamaBaseUrl());
        final Future<?> future = executorService.submit(new Runnable() {
            public void run() {
                try {
                    com.aresstack.askai.java8.hf.meta.OllamaCreateMetadata metadata = metadataSupplier.call();
                    // The metadata's capability list is the install contract, used for BOTH steps.
                    List<String> capabilities = metadata.capabilities();
                    installer.install(modelName, ggufFile, companionFiles, metadata,
                            new RemoteGgufInstaller.ProgressListener() {
                                public void onProgress(String phase, long completed, long total) {
                                    listener.onProgress(phase, completed, total);
                                }
                            });
                    VerificationResult verification = verifyInstalled(modelName, capabilities);
                    listener.onVerified(verification);
                    if (verification.getStatus() == VerificationStatus.VERIFIED) {
                        listener.onComplete("Installed " + modelName + " on remote Ollama.");
                    } else {
                        listener.onIncomplete(verification);
                    }
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
        return new InstallTask() {
            public void cancel() {
                installer.cancel();
                future.cancel(true);
            }
        };
    }

    /**
     * Post-install verification: query {@code /api/show} for the exact installed name via the existing
     * {@link AskAiOllamaClient#getModelInfo} path and report what Ollama actually detected. Never
     * throws — a failed probe becomes a {@link VerificationStatus#FAILED} result so a successful
     * install is not turned into an install error.
     */
    private VerificationResult verifyInstalled(String modelName, List<String> requiredCapabilities) {
        try {
            AppConfiguration configuration = configurationRepository.load();
            AskAiOllamaClient verificationClient = new AskAiOllamaClient(configuration.getOllamaBaseUrl());
            return new InstalledModelVerificationService(verificationClient::getModelInfo)
                    .verify(modelName, requiredCapabilities);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return new VerificationResult(modelName, java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList(), requiredCapabilities,
                    VerificationStatus.FAILED, message);
        }
    }

    public void shutdown() {
        executorService.shutdownNow();
    }

    /** The Ollama adapter for the configured server (the official ollama4j client behind it). */
    private AskAiOllamaClient client() {
        return new AskAiOllamaClient(configurationRepository.load().getOllamaBaseUrl());
    }

    public void analyzeLocalRuntimeCompatibility(final String modelId,
                                                 final LocalCompatibilityListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    HuggingFaceClient client = huggingFaceClient();
                    String revision = client.resolveRevisionSha(modelId, "main");
                    java.util.List<String> paths = client.listAllFiles(modelId);
                    String config = fetchQuietly(client, modelId, revision, "config.json");
                    String tokenizer = fetchQuietly(client, modelId, revision, "tokenizer.json");
                    listener.onResult(new com.aresstack.askai.java8.localmodels
                            .LocalModelCompatibilityAnalyzer()
                            .analyze(modelId, paths, config, tokenizer));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public void installLocalModel(final String modelId, final LocalInstallListener listener) {
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    com.aresstack.askai.java8.localmodels.LocalModelInstaller installer =
                            new com.aresstack.askai.java8.localmodels.LocalModelInstaller(
                                    huggingFaceClient(), localRuntimeManager);
                    String virtualName = installer.install(modelId,
                            new com.aresstack.askai.java8.localmodels.LocalModelInstaller.Listener() {
                                public void onStep(String step) {
                                    listener.onStep(step);
                                }

                                public void onDownloadProgress(String fileName, long completed,
                                                               long total) {
                                    listener.onDownloadProgress(fileName, completed, total);
                                }
                            });
                    listener.onInstalled(virtualName);
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    public com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager localRuntimeManager() {
        return localRuntimeManager;
    }

    private static String fetchQuietly(HuggingFaceClient client, String modelId, String revision,
                                       String path) {
        try {
            return client.fetchFileText(modelId, revision, path);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private HuggingFaceClient huggingFaceClient() {
        AppConfiguration configuration = configurationRepository.load();
        return new HuggingFaceClient(
                configuration.getProxyConfiguration(),
                configuration.getCertificateTrustConfiguration(),
                configuration.getHttpClientConfiguration(),
                configuration.getHuggingFaceToken());
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-java8-service-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

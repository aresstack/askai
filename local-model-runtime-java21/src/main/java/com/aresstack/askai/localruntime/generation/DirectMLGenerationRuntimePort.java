package com.aresstack.askai.localruntime.generation;

import com.aresstack.windirectml.catalog.CatalogBackend;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.inference.artifact.DefaultModelArtifactService;
import com.aresstack.windirectml.inference.artifact.ModelArtifactService;
import com.aresstack.windirectml.modelpack.ModelConversionResult;
import com.aresstack.windirectml.modelpack.ModelFamily;
import com.aresstack.windirectml.inference.api.GenerationErrorCode;
import com.aresstack.windirectml.inference.api.GenerationException;
import com.aresstack.windirectml.inference.api.GenerationModelHandle;
import com.aresstack.windirectml.inference.api.GenerationRequest;
import com.aresstack.windirectml.inference.api.GenerationResult;
import com.aresstack.windirectml.inference.api.GenerationRuntime;
import com.aresstack.windirectml.inference.api.GenerationToken;
import com.aresstack.windirectml.inference.api.GenerationTokenListener;
import com.aresstack.windirectml.inference.api.LoadPolicy;

/**
 * The productive generation port: bridges the AskAI-owned {@link LocalGenerationRuntimePort} to the
 * published {@code com.aresstack:directml-inference} public API. It uses ONLY that public API — no internal
 * family class. The win-directml runtime renders the family's own chat template (AskAI never duplicates
 * prompt logic); the ACTUAL backend the runtime used is read back from the result, never assumed from the
 * request.
 *
 * <p>The generation {@code inference} module separates COMPILE from LOAD: {@code GenerationRuntime.open}
 * (and its family adapters) only ever open a pre-built {@code model.wdmlpack} — {@code ALLOW_COMPILE} merely
 * defers the missing-package check, it does NOT compile. Compilation is the explicit
 * {@link ModelArtifactService#convert} step. So install compiles the package ONCE (convert) and then
 * package-backed smoke-loads it; a later runtime load is always {@code PACKAGE_ONLY} against that package.</p>
 */
public final class DirectMLGenerationRuntimePort implements LocalGenerationRuntimePort {

    private final GenerationRuntime runtime = GenerationRuntime.create();
    private final ModelArtifactService artifacts = DefaultModelArtifactService.createDefault();

    @Override
    public boolean isLinked() {
        return true;
    }

    @Override
    public LoadedGenerationHandle load(LocalGenerationLoadRequest request) throws LocalGenerationException {
        return open(request, LoadPolicy.PACKAGE_ONLY);
    }

    @Override
    public void compileAndSmokeLoad(LocalGenerationLoadRequest request) throws LocalGenerationException {
        compile(request);
        LoadedGenerationHandle handle = open(request, LoadPolicy.PACKAGE_ONLY);
        try {
            handle.generate(LocalGenerationRequest.completion("ok").numPredict(1).build());
        } finally {
            // The win-directml runtime keeps the compiled package memory-mapped (file-locked on Windows) for
            // this process's lifetime even after close(), so the host compiles + smokes in the model's FINAL
            // directory and never renames it afterwards — see LocalModelInstaller.
            handle.close();
        }
    }

    /** Compile the family's {@code model.wdmlpack} from the downloaded raw assets (the one-time install step). */
    private void compile(LocalGenerationLoadRequest request) throws LocalGenerationException {
        LocalRuntimeModelDescriptor descriptor = descriptorFor(request);
        ModelFamily family;
        try {
            family = ModelFamily.valueOf(descriptor.runtimeFamily().name());
        } catch (IllegalArgumentException notAGenerationFamily) {
            throw new LocalGenerationException(LocalGenerationErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "family " + descriptor.runtimeFamily() + " has no generation package compiler");
        }
        try {
            ModelConversionResult result = artifacts.convert(family, request.modelDirectory(), true);
            if (!result.ok()) {
                throw new LocalGenerationException(LocalGenerationErrorCode.PACKAGE_NOT_LOADABLE,
                        "could not compile " + descriptor.runtimePackageFileName() + ": " + result.message());
            }
        } catch (LocalGenerationException already) {
            throw already;
        } catch (RuntimeException ex) {
            throw new LocalGenerationException(LocalGenerationErrorCode.PACKAGE_NOT_LOADABLE,
                    "compile of " + descriptor.runtimePackageFileName() + " failed: " + ex.getMessage(), ex);
        }
    }

    private LoadedGenerationHandle open(LocalGenerationLoadRequest request, LoadPolicy policy)
            throws LocalGenerationException {
        LocalRuntimeModelDescriptor descriptor = descriptorFor(request);
        CatalogBackend backend = catalogBackend(request);
        try {
            GenerationModelHandle handle = runtime.open(descriptor, request.modelDirectory(), backend, policy);
            return new Handle(request.virtualName(), handle);
        } catch (GenerationException ex) {
            throw new LocalGenerationException(map(ex.errorCode()), ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new LocalGenerationException(LocalGenerationErrorCode.MODEL_NOT_LOADABLE,
                    String.valueOf(ex.getMessage()), ex);
        }
    }

    private static LocalRuntimeModelDescriptor descriptorFor(LocalGenerationLoadRequest request)
            throws LocalGenerationException {
        LocalRuntimeModelDescriptor descriptor =
                LocalModelCatalog.findByRepositoryId(repositoryOf(request.virtualName()));
        if (descriptor == null) {
            throw new LocalGenerationException(LocalGenerationErrorCode.MODEL_NOT_FOUND,
                    "'" + request.virtualName() + "' is not a catalogued model");
        }
        return descriptor;
    }

    private static CatalogBackend catalogBackend(LocalGenerationLoadRequest request)
            throws LocalGenerationException {
        LocalGenerationBackend backend = request.backend();
        try {
            return CatalogBackend.valueOf(backend == null ? "CPU" : backend.name());
        } catch (IllegalArgumentException unknown) {
            throw new LocalGenerationException(LocalGenerationErrorCode.UNSUPPORTED_BACKEND,
                    "unknown backend " + backend);
        }
    }

    static String repositoryOf(String virtualName) {
        String name = virtualName == null ? "" : virtualName.trim();
        if (!name.startsWith("local/")) {
            return name;
        }
        name = name.substring("local/".length());
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(0, colon) : name;
    }

    private static LocalGenerationErrorCode map(GenerationErrorCode code) {
        switch (code) {
            case UNSUPPORTED_BACKEND:
                return LocalGenerationErrorCode.UNSUPPORTED_BACKEND;
            case UNSUPPORTED_FAMILY:
                return LocalGenerationErrorCode.MODEL_CAPABILITY_MISMATCH;
            case INVALID_REQUEST:
                return LocalGenerationErrorCode.INVALID_REQUEST;
            case PACKAGE_MISSING:
                return LocalGenerationErrorCode.PACKAGE_MISSING;
            case PACKAGE_NOT_LOADABLE:
            case RAW_WEIGHTS_FALLBACK_BLOCKED:
            case MODEL_ASSETS_MISSING:
            case MODEL_DIRECTORY_NOT_FOUND:
            case GATED_ACCESS_BLOCKED:
            case INITIALIZATION_FAILED:
                return LocalGenerationErrorCode.PACKAGE_NOT_LOADABLE;
            case GENERATION_FAILED:
            default:
                return LocalGenerationErrorCode.GENERATION_FAILED;
        }
    }

    private static String doneReason(GenerationResult result) {
        switch (result.finishReason()) {
            case LENGTH:
                return "length";
            case CANCELLED:
                return "cancel";
            case ERROR:
                return "error";
            case STOP:
            default:
                return "stop";
        }
    }

    /** Wraps a library {@link GenerationModelHandle}, mapping AskAI requests/results to/from the public API. */
    private static final class Handle implements LoadedGenerationHandle {
        private final String virtualName;
        private final GenerationModelHandle handle;

        Handle(String virtualName, GenerationModelHandle handle) {
            this.virtualName = virtualName;
            this.handle = handle;
        }

        @Override
        public LocalGenerationResult generate(LocalGenerationRequest request)
                throws LocalGenerationException {
            try {
                return toResult(handle.generate(toRequest(request)));
            } catch (GenerationException ex) {
                throw new LocalGenerationException(map(ex.errorCode()), ex.getMessage(), ex);
            }
        }

        @Override
        public void generate(LocalGenerationRequest request, LocalGenerationTokenListener listener)
                throws LocalGenerationException {
            final StringBuilder soFar = new StringBuilder();
            try {
                GenerationResult result = handle.generate(toRequest(request), new GenerationTokenListener() {
                    @Override
                    public void onToken(GenerationToken token) {
                        soFar.append(token.text());
                        // 0.2.0 has no mid-stream cancel hook, so the listener's cancel-return can only
                        // stop the HTTP write; the runtime finishes the current generation.
                        listener.onToken(token.text(), soFar.toString());
                    }
                });
                listener.onComplete(toResult(result));
            } catch (GenerationException ex) {
                throw new LocalGenerationException(map(ex.errorCode()), ex.getMessage(), ex);
            }
        }

        @Override
        public String virtualName() {
            return virtualName;
        }

        @Override
        public void close() {
            handle.close();
        }

        private GenerationRequest toRequest(LocalGenerationRequest request) {
            String user;
            String system = "";
            if (request.kind() == LocalGenerationRequest.Kind.CHAT) {
                StringBuilder systemPrompt = new StringBuilder();
                String lastUser = "";
                for (LocalGenerationMessage message : request.messages()) {
                    if ("system".equals(message.role())) {
                        if (systemPrompt.length() > 0) {
                            systemPrompt.append('\n');
                        }
                        systemPrompt.append(message.content());
                    } else if ("user".equals(message.role())) {
                        lastUser = message.content();
                    }
                }
                user = lastUser;
                system = systemPrompt.toString();
            } else {
                user = request.prompt();
            }
            GenerationRequest.Builder builder = GenerationRequest.builder(user);
            if (!system.isEmpty()) {
                builder.systemPrompt(system);
            }
            if (request.numPredict() > 0) {
                builder.maxNewTokens(request.numPredict());
            }
            return builder.build();
        }

        private LocalGenerationResult toResult(GenerationResult result) {
            String backend = result.backend() == null ? "" : result.backend().token();
            return new LocalGenerationResult(result.text(), result.promptTokenCount(),
                    result.generatedTokenCount(), doneReason(result), backend);
        }
    }
}

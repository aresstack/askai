package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.runtime.api.Backend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * AskAI's virtual LOCAL model container, running INSIDE the application: the Ollama-compatible loopback
 * HTTP surface ({@link LocalModelRuntimeServer}) over the installed models under {@code modelRoot}, the
 * encoder/reranker engine and the productive win-directml generation runtime. The host starts it on demand
 * (no separate JVM, no ready line, no watchdog any more) and talks to it exactly like to an Ollama server
 * through {@link #baseUrl()}; {@link #close()} unloads every model and frees the port.
 */
public final class LocalModelRuntime implements AutoCloseable {

    /** The version string the container reports on {@code /api/version}. */
    public static final String VERSION = LocalModelRuntimeServer.VERSION;

    private final LocalModelRuntimeServer server;
    private final String baseUrl;
    private final Backend backend;

    private LocalModelRuntime(LocalModelRuntimeServer server, String baseUrl, Backend backend) {
        this.server = server;
        this.baseUrl = baseUrl;
        this.backend = backend;
    }

    /**
     * Start the container on a free loopback port.
     *
     * @param modelRoot the directory of the installed local models (one sub-directory per model, each with
     *                  its {@code askai-local-model.json} manifest)
     * @param backend   the inference backend name ({@code cpu}, {@code directml}, {@code warp}, {@code auto});
     *                  unknown names fall back to {@code cpu}
     */
    public static LocalModelRuntime start(Path modelRoot, String backend) throws IOException {
        Backend resolved = resolveBackend(backend);
        LocalModelStore store = new LocalModelStore(modelRoot);
        LocalModelEngine engine = new LocalModelEngine(resolved);
        // The productive win-directml generation runtime (directml-inference), linked behind the AskAI
        // port: /api/version reports features.generation=true and /api/chat + /api/generate run real
        // local generation through it.
        LocalGenerationEngine generationEngine = new LocalGenerationEngine(
                new com.aresstack.askai.localruntime.generation.DirectMLGenerationRuntimePort(),
                com.aresstack.askai.localruntime.generation.LocalGenerationBackend.parse(resolved.name()));
        LocalModelRuntimeServer server = new LocalModelRuntimeServer(store, engine, generationEngine);
        int port;
        try {
            port = server.start("127.0.0.1", 0);
        } catch (IOException | RuntimeException failed) {
            engine.close();
            generationEngine.close();
            throw failed;
        }
        String baseUrl = "http://127.0.0.1:" + port;
        System.err.println("[local-runtime] ready on " + baseUrl + " backend=" + resolved
                + " modelRoot=" + modelRoot);
        return new LocalModelRuntime(server, baseUrl, resolved);
    }

    /** The loopback base URL of the Ollama-compatible API, e.g. {@code http://127.0.0.1:49183}. */
    public String baseUrl() {
        return baseUrl;
    }

    public Backend backend() {
        return backend;
    }

    /** Stop serving, unload every loaded model and free the port. Idempotent. */
    @Override
    public void close() {
        server.stop();
    }

    private static Backend resolveBackend(String backend) {
        String token = backend == null ? "" : backend.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            return Backend.CPU;
        }
        try {
            return Backend.valueOf(token);
        } catch (IllegalArgumentException unknown) {
            System.err.println("[local-runtime] unknown backend '" + backend + "', using CPU");
            return Backend.CPU;
        }
    }
}

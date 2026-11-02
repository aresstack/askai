package com.aresstack.askai.localruntime;

import com.aresstack.askai.localruntime.generation.DirectMLGenerationRuntimePort;
import com.aresstack.askai.localruntime.generation.LocalGenerationBackend;
import com.aresstack.windirectml.runtime.api.Backend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * AskAI's local model runtime inside the AskAI JVM (askai #49): the Ollama-compatible endpoint for locally
 * installed models (reranking, embeddings, generation over win-directml), started and stopped by AskAI itself.
 * No child process, no ready line on stdout, no parent-death watchdog: it lives exactly as long as AskAI keeps
 * it open. The endpoint binds to loopback on a port the OS chooses.
 */
public final class LocalModelRuntime implements AutoCloseable {

    private final LocalModelRuntimeServer server;
    private final String baseUrl;
    private final Backend backend;

    private LocalModelRuntime(LocalModelRuntimeServer server, String baseUrl, Backend backend) {
        this.server = server;
        this.baseUrl = baseUrl;
        this.backend = backend;
    }

    /**
     * @param modelRoot directory of the locally installed models
     * @param backend   {@code cpu} (default) or another win-directml backend name; unknown names fall back to CPU
     */
    public static LocalModelRuntime start(Path modelRoot, String backend) throws IOException {
        return start(modelRoot, backend, "127.0.0.1", 0);
    }

    static LocalModelRuntime start(Path modelRoot, String backendName, String host, int port) throws IOException {
        Backend backend = parseBackend(backendName);
        LocalModelStore store = new LocalModelStore(modelRoot);
        LocalModelEngine engine = new LocalModelEngine(backend);
        LocalGenerationEngine generationEngine = new LocalGenerationEngine(
                new DirectMLGenerationRuntimePort(), LocalGenerationBackend.parse(backend.name()));
        LocalModelRuntimeServer server = new LocalModelRuntimeServer(store, engine, generationEngine);
        try {
            int boundPort = server.start(host, port);
            return new LocalModelRuntime(server, "http://" + host + ":" + boundPort, backend);
        } catch (IOException | RuntimeException failed) {
            server.stop();
            throw failed;
        }
    }

    static Backend parseBackend(String name) {
        String normalized = name == null || name.trim().isEmpty() ? "CPU" : name.trim().toUpperCase(Locale.ROOT);
        try {
            return Backend.valueOf(normalized);
        } catch (IllegalArgumentException unknown) {
            System.err.println("[local-runtime] unknown backend '" + name + "', using CPU");
            return Backend.CPU;
        }
    }

    /** The Ollama-compatible base URL, e.g. {@code http://127.0.0.1:49183}. */
    public String baseUrl() {
        return baseUrl;
    }

    public String backend() {
        return backend.name();
    }

    /** Stops the endpoint and releases the loaded models. */
    @Override
    public void close() {
        server.stop();
    }
}

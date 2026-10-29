package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host-side OWNER of the Java-21 local model runtime process. AskAI starts the sidecar (never the
 * research agent), reads its single machine-readable ready line from stdout, provides the base URL,
 * monitors the process, restarts it on demand after an AskAI restart when local models exist, and
 * ends it on AskAI shutdown. There is no global fixed port — the sidecar picks one per start.
 */
public final class LocalModelRuntimeManager {

    /** Parsed {@code {"event":"ready","baseUrl":…,"version":…}} stdout line. */
    static final class ReadyLine {
        final String baseUrl;
        final String version;

        private ReadyLine(String baseUrl, String version) {
            this.baseUrl = baseUrl;
            this.version = version;
        }

        /** @return the parsed ready line, or null when the line is not the ready event. */
        static ReadyLine parse(String line) {
            if (line == null || !line.contains("\"ready\"")) {
                return null;
            }
            try {
                Object parsed = SimpleJson.parse(line);
                if (!(parsed instanceof Map)) {
                    return null;
                }
                Map<?, ?> map = (Map<?, ?>) parsed;
                if (!"ready".equals(map.get("event"))) {
                    return null;
                }
                String baseUrl = String.valueOf(map.get("baseUrl"));
                return baseUrl.startsWith("http")
                        ? new ReadyLine(baseUrl, String.valueOf(map.get("version"))) : null;
            } catch (RuntimeException notJson) {
                return null;
            }
        }
    }

    private static final long READY_TIMEOUT_SECONDS = 60;

    private final File modelRoot;
    private Process process;
    private String baseUrl;

    public LocalModelRuntimeManager() {
        this(LocalModelNames.localModelRoot());
    }

    public LocalModelRuntimeManager(File modelRoot) {
        this.modelRoot = modelRoot;
    }

    public File getModelRoot() {
        return modelRoot;
    }

    /** True when at least one locally installed model directory exists (restart-on-boot signal). */
    public boolean hasInstalledModels() {
        File[] children = modelRoot.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory() && new File(child, "askai-local-model.json").isFile()) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive() && baseUrl != null;
    }

    /**
     * True when the runtime can serve at all: already running, or its sidecar jar exists to be started.
     * The runtime is an OPTIONAL component — callers that only aggregate (model lists, boot start) skip
     * it silently when it is not staged; nothing waits, nothing fails.
     */
    public boolean isAvailable() {
        return isRunning() || sidecarJar().isFile();
    }

    /** The base URL of the RUNNING sidecar, or null. */
    public synchronized String getBaseUrl() {
        return isRunning() ? baseUrl : null;
    }

    /** A failed start is not retried for this long — N queued callers fail fast instead of serially. */
    private static final long START_RETRY_BACKOFF_MILLIS = 15_000L;
    private long lastStartFailureMillis;

    /** Start the sidecar if it is not running; @return the base URL. */
    public synchronized String ensureStarted() throws IOException {
        if (isRunning()) {
            return baseUrl;
        }
        if (System.currentTimeMillis() - lastStartFailureMillis < START_RETRY_BACKOFF_MILLIS) {
            // The monitor serializes callers: without this, every model-list capability probe would
            // re-run the whole failing start and stall the queue behind it.
            throw new IOException("local model runtime start failed recently; retry postponed");
        }
        File jar = sidecarJar();
        if (!jar.isFile()) {
            // Without the jar the child JVM dies instantly but the ready-await would still burn the
            // full timeout while holding this monitor — answer immediately instead.
            throw new IOException("local model runtime is not staged (missing " + jar.getAbsolutePath()
                    + ")");
        }
        stop();
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.add("-jar");
        command.add(sidecarJar().getAbsolutePath());
        command.add("--host=127.0.0.1");
        command.add("--port=0");
        command.add("--model-root=" + modelRoot.getAbsolutePath());
        command.add("--backend=cpu");
        final Process started = new ProcessBuilder(command).start();

        final CountDownLatch ready = new CountDownLatch(1);
        final String[] readyBaseUrl = new String[1];
        Thread stdout = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            started.getInputStream(), Charset.forName("UTF-8")));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ReadyLine parsed = ReadyLine.parse(line);
                        if (parsed != null) {
                            readyBaseUrl[0] = parsed.baseUrl;
                            ready.countDown();
                        }
                    }
                } catch (IOException ignored) {
                    // stream ends with the process
                }
            }
        }, "local-runtime-stdout");
        stdout.setDaemon(true);
        stdout.start();
        Thread stderr = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            started.getErrorStream(), Charset.forName("UTF-8")));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.err.println("[local-runtime] " + line);
                    }
                } catch (IOException ignored) {
                }
            }
        }, "local-runtime-stderr");
        stderr.setDaemon(true);
        stderr.start();

        boolean up = false;
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
        try {
            while (System.nanoTime() < deadlineNanos) {
                if (ready.await(250, TimeUnit.MILLISECONDS)) {
                    up = true;
                    break;
                }
                if (!started.isAlive()) {
                    break; // died during startup (wrong Java, missing DirectML, …): fail NOW, not at timeout
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!up || readyBaseUrl[0] == null) {
            boolean diedOnItsOwn = !started.isAlive();
            started.destroyForcibly();
            lastStartFailureMillis = System.currentTimeMillis();
            throw new IOException(diedOnItsOwn
                    ? "The local model runtime exited during startup (jar: " + sidecarJar() + ")"
                    : "The local model runtime did not report ready within "
                            + READY_TIMEOUT_SECONDS + "s (jar: " + sidecarJar() + ")");
        }
        this.process = started;
        this.baseUrl = readyBaseUrl[0];
        return baseUrl;
    }

    /**
     * Idempotent shutdown: destroy → SHORT grace → forced kill, then return without waiting for the
     * kill to complete. Sidecar teardown must never delay or block the application exit; the forced
     * kill is asynchronous and the child is a daemon of this app either way.
     */
    public synchronized void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
        baseUrl = null;
    }

    // ------------------------------------------------------------------ discovery

    /**
     * The Java executable for the sidecar: {@code askai.local.runtime.java} (set by the dev run
     * task to a Java-21 toolchain launcher), the {@code ASKAI_LOCAL_RUNTIME_JAVA} environment
     * variable, or {@code java} on the PATH.
     */
    private static String javaExecutable() {
        String property = System.getProperty("askai.local.runtime.java", "").trim();
        if (!property.isEmpty()) {
            return property;
        }
        String env = System.getenv("ASKAI_LOCAL_RUNTIME_JAVA");
        return env != null && !env.trim().isEmpty() ? env.trim() : "java";
    }

    /**
     * The sidecar thin jar: {@code askai.local.runtime.dir} (dev hand-off of the assembled
     * distribution) or the {@code local-runtime} directory next to the application.
     */
    private static File sidecarJar() {
        String dir = System.getProperty("askai.local.runtime.dir", "").trim();
        File base = dir.isEmpty() ? new File("local-runtime") : new File(dir);
        return new File(base, "local-model-runtime-sidecar.jar");
    }
}

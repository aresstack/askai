package com.aresstack.askai.localruntime;


import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Entry point of the Java-21 LOCAL MODEL RUNTIME sidecar. Started by the Java-8 AskAI host (never
 * by the research agent) with:
 * <pre>--host=127.0.0.1 --port=0 --model-root=&lt;absolute path&gt; --backend=cpu</pre>
 * With {@code --port=0} the OS chooses the port; the sidecar then writes EXACTLY ONE
 * machine-readable ready line to STDOUT:
 * <pre>{"event":"ready","baseUrl":"http://127.0.0.1:49183","version":"askai-local-1"}</pre>
 * Every other log goes to STDERR. There is no global fixed port.
 */
public final class LocalModelRuntimeMain {

    private LocalModelRuntimeMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = arg(args, "--host=", "127.0.0.1");
        int port = Integer.parseInt(arg(args, "--port=", "0"));
        String modelRoot = arg(args, "--model-root=", "");
        String backendArg = arg(args, "--backend=", "cpu").toUpperCase(Locale.ROOT);
        if (modelRoot.isEmpty()) {
            System.err.println("usage: --host=127.0.0.1 --port=0 --model-root=<path> --backend=cpu");
            System.exit(2);
        }
        LocalModelRuntime runtime = LocalModelRuntime.start(Path.of(modelRoot), backendArg, host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "local-runtime-shutdown"));
        // Die with the parent (stdin EOF): a hard-killed host must never leave this JVM behind.
        ParentDeathWatchdog.install("local-runtime");

        String baseUrl = runtime.baseUrl();
        // The ONE machine-readable ready line on stdout (the host parses exactly this).
        System.out.println(LocalJson.write(Map.of(
                "event", "ready",
                "baseUrl", baseUrl,
                "version", LocalModelRuntimeServer.VERSION)));
        System.out.flush();
        System.err.println("[local-runtime] ready on " + baseUrl + " backend=" + runtime.backend()
                + " modelRoot=" + modelRoot);
    }

    private static String arg(String[] args, String prefix, String fallback) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return fallback;
    }
}

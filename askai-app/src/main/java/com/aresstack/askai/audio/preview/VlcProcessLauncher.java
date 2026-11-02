package com.aresstack.askai.audio.preview;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Launches the VLC sidecar process. Abstracted behind an interface so the playback service can be tested
 * without a real VLC install. The default implementation uses {@link ProcessBuilder} directly (never via
 * {@code cmd.exe}) with stderr merged into stdout.
 */
public interface VlcProcessLauncher {

    Handle start(List<String> command) throws IOException;

    /** A running sidecar process: read its merged output, wait for it, or terminate it. */
    interface Handle {
        InputStream mergedOutput();

        int awaitExit() throws InterruptedException;

        void destroy();
    }

    static VlcProcessLauncher processBuilder() {
        return new VlcProcessLauncher() {
            public Handle start(List<String> command) throws IOException {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                final Process process = builder.start();
                return new Handle() {
                    public InputStream mergedOutput() {
                        return process.getInputStream();
                    }

                    public int awaitExit() throws InterruptedException {
                        return process.waitFor();
                    }

                    public void destroy() {
                        process.destroy();
                    }
                };
            }
        };
    }
}

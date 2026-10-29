package com.aresstack.askai.localruntime;

import com.aresstack.askai.localruntime.generation.LoadedGenerationHandle;
import com.aresstack.askai.localruntime.generation.LocalGenerationBackend;
import com.aresstack.askai.localruntime.generation.LocalGenerationErrorCode;
import com.aresstack.askai.localruntime.generation.LocalGenerationException;
import com.aresstack.askai.localruntime.generation.LocalGenerationLoadRequest;
import com.aresstack.askai.localruntime.generation.LocalGenerationRequest;
import com.aresstack.askai.localruntime.generation.LocalGenerationResult;
import com.aresstack.askai.localruntime.generation.LocalGenerationRuntimePort;
import com.aresstack.askai.localruntime.generation.LocalGenerationTokenListener;
import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;

import org.junit.Test;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Generation handle lifecycle hardening (engine level, fake ports): a handle is closed exactly once, a
 * model switch closes the previous handle, a failed load leaves no warm model, onComplete fires at most
 * once, a listener cancel stops generation, and an unload waits for in-flight inference (no use-after-close).
 */
public class LocalGenerationLifecycleTest {

    private static LocalModel model(String repo) {
        return new LocalModel(
                InstalledModelManifest.forInstall(LocalModelCatalog.findByRepositoryId(repo), "rev", 1L),
                Path.of("unused"));
    }

    /** A port whose handles count their closes and echo the request as three streamed tokens. */
    private static final class CountingPort implements LocalGenerationRuntimePort {
        final AtomicInteger closes = new AtomicInteger();
        volatile String lastLoaded;

        public LoadedGenerationHandle load(LocalGenerationLoadRequest request) {
            lastLoaded = request.virtualName();
            return new LoadedGenerationHandle() {
                public LocalGenerationResult generate(LocalGenerationRequest req) {
                    return new LocalGenerationResult("ok", 1, 1, "stop");
                }

                public void generate(LocalGenerationRequest req, LocalGenerationTokenListener listener) {
                    for (int i = 0; i < 3; i++) {
                        if (!listener.onToken("t" + i + " ", "")) {
                            listener.onComplete(new LocalGenerationResult("partial", 1, i, "cancel"));
                            return;
                        }
                    }
                    listener.onComplete(new LocalGenerationResult("full", 1, 3, "stop"));
                }

                public String virtualName() {
                    return request.virtualName();
                }

                public void close() {
                    closes.incrementAndGet();
                }
            };
        }
    }

    @Test
    public void handleIsClosedExactlyOnceAcrossGenerateAndRepeatedUnload() throws Exception {
        CountingPort port = new CountingPort();
        LocalGenerationEngine engine = new LocalGenerationEngine(port, LocalGenerationBackend.CPU);
        LocalModel qwen = model("Qwen/Qwen2.5-Coder-0.5B-Instruct");
        engine.generate(qwen, LocalGenerationRequest.completion("hi").build(), null);
        assertEquals(qwen.virtualName(), engine.loadedVirtualName());
        engine.unload(qwen.virtualName());
        engine.unload(qwen.virtualName()); // idempotent
        assertNull(engine.loadedVirtualName());
        assertEquals("closed exactly once", 1, port.closes.get());
        engine.close();
        assertEquals("close() does not re-close an already unloaded handle", 1, port.closes.get());
    }

    @Test
    public void switchingModelsClosesThePreviousHandle() throws Exception {
        CountingPort port = new CountingPort();
        LocalGenerationEngine engine = new LocalGenerationEngine(port, LocalGenerationBackend.CPU);
        engine.generate(model("Qwen/Qwen2.5-Coder-0.5B-Instruct"),
                LocalGenerationRequest.completion("a").build(), null);
        engine.generate(model("HuggingFaceTB/SmolLM2-135M-Instruct"),
                LocalGenerationRequest.completion("b").build(), null);
        assertEquals("the previous generation handle was closed on switch", 1, port.closes.get());
        assertEquals("local/HuggingFaceTB/SmolLM2-135M-Instruct:latest", engine.loadedVirtualName());
        engine.close();
    }

    @Test
    public void aFailedLoadLeavesNoWarmModel() {
        LocalGenerationRuntimePort failing = new LocalGenerationRuntimePort() {
            public LoadedGenerationHandle load(LocalGenerationLoadRequest request)
                    throws LocalGenerationException {
                throw new LocalGenerationException(LocalGenerationErrorCode.PACKAGE_NOT_LOADABLE, "boom");
            }
        };
        LocalGenerationEngine engine = new LocalGenerationEngine(failing, LocalGenerationBackend.CPU);
        try {
            engine.generate(model("Qwen/Qwen2.5-Coder-0.5B-Instruct"),
                    LocalGenerationRequest.completion("hi").build(), null);
            fail("expected the load to fail");
        } catch (LocalGenerationException expected) {
            assertEquals(LocalGenerationErrorCode.PACKAGE_NOT_LOADABLE, expected.code());
        }
        assertNull("a failed load must not leave a warm model", engine.loadedVirtualName());
        engine.close();
    }

    @Test
    public void streamingCallsOnCompleteExactlyOnceAndAcceptsCancellation() throws Exception {
        CountingPort port = new CountingPort();
        LocalGenerationEngine engine = new LocalGenerationEngine(port, LocalGenerationBackend.CPU);
        AtomicInteger completes = new AtomicInteger();
        AtomicInteger tokens = new AtomicInteger();
        engine.generate(model("Qwen/Qwen2.5-Coder-0.5B-Instruct"),
                LocalGenerationRequest.completion("hi").build(), new LocalGenerationTokenListener() {
                    public boolean onToken(String delta, String textSoFar) {
                        // Cancel after the first token.
                        return tokens.incrementAndGet() < 1;
                    }

                    public void onComplete(LocalGenerationResult result) {
                        completes.incrementAndGet();
                        assertEquals("cancel", result.doneReason());
                    }
                });
        assertEquals("onComplete fires exactly once", 1, completes.get());
        assertEquals("generation stopped at the first token", 1, tokens.get());
        engine.close();
    }

    @Test(timeout = 10_000)
    public void unloadWaitsForInFlightInferenceThenClosesOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch mayFinish = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        boolean[] closedDuringInference = {false};
        LocalGenerationRuntimePort blockingPort = request -> new LoadedGenerationHandle() {
            private volatile boolean closed;

            public LocalGenerationResult generate(LocalGenerationRequest req) {
                started.countDown();
                try {
                    mayFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (closed) {
                    closedDuringInference[0] = true;
                }
                return new LocalGenerationResult("ok", 1, 1, "stop");
            }

            public void generate(LocalGenerationRequest req, LocalGenerationTokenListener listener) {
            }

            public String virtualName() {
                return request.virtualName();
            }

            public void close() {
                closed = true;
                closes.incrementAndGet();
            }
        };
        LocalGenerationEngine engine = new LocalGenerationEngine(blockingPort, LocalGenerationBackend.CPU);
        LocalModel qwen = model("Qwen/Qwen2.5-Coder-0.5B-Instruct");
        Thread worker = new Thread(() -> {
            try {
                engine.generate(qwen, LocalGenerationRequest.completion("hi").build(), null);
            } catch (LocalGenerationException e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // Unload while generation is in flight: it must NOT close the handle yet (serialized by the lock).
        Thread unloader = new Thread(() -> engine.unload(qwen.virtualName()));
        unloader.start();
        Thread.sleep(200);
        assertEquals("unload must not close a handle mid-inference", 0, closes.get());
        mayFinish.countDown();
        worker.join(5_000);
        unloader.join(5_000);
        assertFalse("the handle was never closed during inference", closedDuringInference[0]);
        assertEquals("closed exactly once after inference finished", 1, closes.get());
        assertNull(engine.loadedVirtualName());
        engine.close();
    }
}

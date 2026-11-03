package com.aresstack.askai.ui;

import com.aresstack.askai.service.OllamaService;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class ChatModelWarmupTest {

    private final List<String> calls = new ArrayList<String>();
    private boolean failLoads;

    /** Runs queued steps inline, in order. */
    private static final class Inline extends AbstractExecutorService {
        public void execute(Runnable r) { r.run(); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return new ArrayList<Runnable>(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    private OllamaService service() {
        return (OllamaService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{OllamaService.class}, (proxy, method, args) -> {
                    OllamaService.ActionListener l = null;
                    if (method.getName().equals("loadModel")) {
                        calls.add("load " + args[0] + " " + args[1]);
                        l = (OllamaService.ActionListener) args[2];
                        if (failLoads) {
                            l.onError(new Exception("timeout"));
                            return null;
                        }
                    } else if (method.getName().equals("unloadModel")) {
                        calls.add("unload " + args[0]);
                        l = (OllamaService.ActionListener) args[1];
                    }
                    if (l != null) {
                        l.onComplete("ok");
                    }
                    return null;
                });
    }

    private ChatModelWarmup warmup(java.util.function.Predicate<String> elsewhere) {
        return new ChatModelWarmup(service(), () -> "10m", elsewhere, msg -> { }, new Inline());
    }

    @Test
    public void loadsOnStartAndSwapsOnChange() {
        ChatModelWarmup w = warmup(name -> false);
        w.activate("t1-a");
        w.activate("t1-a");
        w.activate("t1-b");
        w.release();
        assertEquals(Arrays.asList("load t1-a 10m", "unload t1-a", "load t1-b 10m"), calls);
    }

    @Test
    public void keepsModelsNeededElsewhereAndIgnoresEmpty() {
        ChatModelWarmup w = warmup(name -> name.equals("t2-dict"));
        w.activate(null);
        w.activate(" ");
        w.activate("t2-dict");
        w.activate("t2-b");
        w.release();
        assertEquals(Arrays.asList("load t2-dict 10m", "load t2-b 10m"), calls);
    }

    @Test
    public void keepsModelUsedByAnotherChat() {
        ChatModelWarmup one = warmup(name -> false);
        ChatModelWarmup two = warmup(name -> false);
        one.activate("t3-a");
        two.activate("t3-a");
        one.activate("t3-b");
        assertEquals(Arrays.asList("load t3-a 10m", "load t3-a 10m", "load t3-b 10m"), calls);
        one.release();
        two.release();
    }

    @Test
    public void retriesAfterFailedLoad() {
        ChatModelWarmup w = warmup(name -> false);
        failLoads = true;
        w.activate("t4-a");
        failLoads = false;
        w.activate("t4-a");
        assertEquals(Arrays.asList("load t4-a 10m", "load t4-a 10m"), calls);
        assertEquals("t4-a", w.activeModel());
        w.release();
    }
}

package com.aresstack.askai.ui;

import com.aresstack.askai.service.OllamaService;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ChatModelWarmupTest {

    private final List<String> calls = new ArrayList<String>();

    private OllamaService service() {
        return (OllamaService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{OllamaService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("loadModel")) {
                        calls.add("load " + args[0] + " " + args[1]);
                    } else if (method.getName().equals("unloadModel")) {
                        calls.add("unload " + args[0]);
                    }
                    return null;
                });
    }

    @Test
    public void loadsOnStartAndSwapsOnChange() {
        ChatModelWarmup warmup = new ChatModelWarmup(service(), () -> "10m", name -> false, msg -> { });
        warmup.activate("gemma4:e2b");
        warmup.activate("gemma4:e2b");
        warmup.activate("llama3.1:8b");
        assertEquals(java.util.Arrays.asList("load gemma4:e2b 10m", "unload gemma4:e2b",
                "load llama3.1:8b 10m"), calls);
    }

    @Test
    public void keepsModelsNeededElsewhereAndIgnoresEmpty() {
        ChatModelWarmup warmup = new ChatModelWarmup(service(), () -> "5m",
                name -> name.equals("gemma4:e2b"), msg -> { });
        warmup.activate(null);
        warmup.activate(" ");
        warmup.activate("gemma4:e2b");
        warmup.activate("llama3.1:8b");
        assertEquals(java.util.Arrays.asList("load gemma4:e2b 5m", "load llama3.1:8b 5m"), calls);
    }
}

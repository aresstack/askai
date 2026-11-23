package com.aresstack.askai.research.benchmark.retrieval;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** The reload proof of a model switch in the model protocol, without Ollama. */
public class ModelReloadVerdictTest {

    private static ProtocolProbes.LoadedModel loaded(String name, long size, long vram) throws Exception {
        Constructor<ProtocolProbes.LoadedModel> c = ProtocolProbes.LoadedModel.class
                .getDeclaredConstructor(String.class, long.class, long.class);
        c.setAccessible(true);
        return c.newInstance(name, size, vram);
    }

    @Test
    public void contextMismatchIsNamed() {
        ProtocolProbes.LoadedModel big = new ProtocolProbes.LoadedModel("llama3.1:8b", 100, 100, 131072);
        ProtocolProbes.LoadedModel ok = new ProtocolProbes.LoadedModel("llama3.1:8b", 100, 100, 16384);
        ProtocolProbes.LoadedModel unknown = new ProtocolProbes.LoadedModel("llama3.1:8b", 100, 100);
        assertEquals("; num_ctx NOT applied (Ollama allocated 131072)",
                ModelProtocolLiveTest.contextVerdict("llama3.1:8b", 16384, Collections.singletonList(big)));
        assertEquals("; num_ctx applied",
                ModelProtocolLiveTest.contextVerdict("llama3.1:8b", 16384, Collections.singletonList(ok)));
        assertEquals("", ModelProtocolLiveTest.contextVerdict("llama3.1:8b", 16384,
                Collections.singletonList(unknown)));
    }

    @Test
    public void listedWithPositiveLoadDurationIsAReload() throws Exception {
        assertEquals("yes", ModelProtocolLiveTest.reloadVerdict("llama3.1:8b", 2100,
                Collections.singletonList(loaded("llama3.1:8b", 100, 100))));
    }

    @Test
    public void cpuSpillIsNamed() throws Exception {
        assertEquals("yes, only 80% on GPU", ModelProtocolLiveTest.reloadVerdict("gpt-oss:20b", 9000,
                Collections.singletonList(loaded("gpt-oss:20b", 100, 80))));
    }

    @Test
    public void otherModelOrNoLoadIsNoReload() throws Exception {
        assertEquals("no: not in /api/ps", ModelProtocolLiveTest.reloadVerdict("llama3.1:8b", 2100,
                Arrays.asList(loaded("gemma4:e2b", 100, 100))));
        assertEquals("no: load_duration 0, model was still loaded", ModelProtocolLiveTest.reloadVerdict(
                "llama3.1:8b", 0, Collections.singletonList(loaded("llama3.1:8b", 100, 100))));
        assertEquals("no: generate failed", ModelProtocolLiveTest.reloadVerdict("llama3.1:8b", -1,
                Collections.<ProtocolProbes.LoadedModel>emptyList()));
    }
}

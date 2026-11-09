package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.modelport.OllamaModelPort;

import java.io.File;

/** Opens {@link BenchmarkModelPort} for probes in sub-packages (the writer A/B). */
public final class BenchmarkModelPortAccess {

    private BenchmarkModelPortAccess() {
    }

    public static OllamaModelPort open(String model, String meterBaseUrl, long timeoutMillis, File settingsDir) {
        return BenchmarkModelPort.open(model, meterBaseUrl, timeoutMillis, settingsDir);
    }
}

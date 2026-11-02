package com.aresstack.askai.localruntime.generation;

/**
 * How a generation model may be loaded. Only {@link #PACKAGE_ONLY} is permitted: the runtime must load from
 * the compiled {@code *.wdmlpack}, never from raw weights — a green load therefore proves the package runs.
 */
public enum LocalGenerationLoadPolicy {
    PACKAGE_ONLY
}

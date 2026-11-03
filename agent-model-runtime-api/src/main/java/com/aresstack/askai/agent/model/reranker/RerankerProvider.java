package com.aresstack.askai.agent.model.reranker;

/**
 * Who serves a reranker endpoint. {@link #ASKAI_LOCAL} is the AskAI-managed local model runtime
 * (the R0 sidecar) reached over plain HTTP; the enum leaves room for future remote providers without
 * a magic string. The descriptor never carries process paths or a way to START a runtime — only how
 * to REACH an already-available endpoint.
 */
public enum RerankerProvider {
    ASKAI_LOCAL
}

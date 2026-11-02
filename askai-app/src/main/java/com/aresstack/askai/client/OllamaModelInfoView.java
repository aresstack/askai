package com.aresstack.askai.client;

import java.util.Collections;
import java.util.List;

/**
 * Rich model information returned by Ollama /api/show, in AskAI domain terms.
 */
public final class OllamaModelInfoView {

    private final OllamaModelDetails details;
    private final String template;
    private final String system;
    private final String parameters;
    private final String modelfile;
    private final List<String> capabilities;
    private final String containerId;
    private final String containerDisplayName;
    private final boolean local;

    public OllamaModelInfoView(OllamaModelDetails details, String template, String system,
                               String parameters, String modelfile, List<String> capabilities) {
        this(details, template, system, parameters, modelfile, capabilities, "", "", false);
    }

    public OllamaModelInfoView(OllamaModelDetails details, String template, String system,
                               String parameters, String modelfile, List<String> capabilities,
                               String containerId, String containerDisplayName, boolean local) {
        this.details = details == null ? OllamaModelDetails.empty() : details;
        this.template = safe(template);
        this.system = safe(system);
        this.parameters = safe(parameters);
        this.modelfile = safe(modelfile);
        this.capabilities = capabilities == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(capabilities);
        this.containerId = safe(containerId);
        this.containerDisplayName = safe(containerDisplayName);
        this.local = local;
    }

    /** A copy tagged with its virtual-container origin (R0). */
    public OllamaModelInfoView withContainer(String containerId, String containerDisplayName,
                                             boolean local) {
        return new OllamaModelInfoView(details, template, system, parameters, modelfile,
                capabilities, containerId, containerDisplayName, local);
    }

    public String getContainerId() {
        return containerId;
    }

    public String getContainerDisplayName() {
        return containerDisplayName;
    }

    public boolean isLocal() {
        return local;
    }

    public OllamaModelDetails getDetails() {
        return details;
    }

    public String getTemplate() {
        return template;
    }

    public String getSystem() {
        return system;
    }

    public String getParameters() {
        return parameters;
    }

    public String getModelfile() {
        return modelfile;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

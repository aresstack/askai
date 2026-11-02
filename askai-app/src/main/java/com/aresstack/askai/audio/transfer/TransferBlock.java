package com.aresstack.askai.audio.transfer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One block inside a transferred profile. {@code type} is the block-type token (an enum name), never a Java
 * class name; parameters are plain string key/value pairs.
 */
public final class TransferBlock {

    public String id;
    public String type;
    public boolean enabled = true;
    public Map<String, String> parameters = new LinkedHashMap<String, String>();

    public TransferBlock() {
    }

    public TransferBlock(String id, String type, boolean enabled, Map<String, String> parameters) {
        this.id = id;
        this.type = type;
        this.enabled = enabled;
        this.parameters = parameters == null ? new LinkedHashMap<String, String>() : parameters;
    }
}

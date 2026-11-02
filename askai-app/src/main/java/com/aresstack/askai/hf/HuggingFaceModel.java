package com.aresstack.askai.hf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HuggingFaceModel {

    private final String id;
    private final String pipelineTag;
    private final long downloads;
    private final long likes;
    private final List<String> tags;
    private final String libraryName;

    public HuggingFaceModel(String id, String pipelineTag, long downloads, long likes) {
        this(id, pipelineTag, downloads, likes, null);
    }

    public HuggingFaceModel(String id, String pipelineTag, long downloads, long likes, List<String> tags) {
        this(id, pipelineTag, downloads, likes, tags, "");
    }

    public HuggingFaceModel(String id, String pipelineTag, long downloads, long likes, List<String> tags,
                            String libraryName) {
        this.id = id == null ? "" : id;
        this.pipelineTag = pipelineTag == null ? "" : pipelineTag;
        this.downloads = downloads;
        this.likes = likes;
        this.tags = tags == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(tags));
        this.libraryName = libraryName == null ? "" : libraryName;
    }

    public String getId() {
        return id;
    }

    public String getPipelineTag() {
        return pipelineTag;
    }

    public long getDownloads() {
        return downloads;
    }

    public long getLikes() {
        return likes;
    }

    /** The raw HuggingFace tags, e.g. "gguf", "base_model:quantized:google/gemma-3-12b-it". */
    public List<String> getTags() {
        return tags;
    }

    /** @return the HuggingFace {@code library_name} (e.g. "gguf", "transformers", "diffusers"), or "". */
    public String getLibraryName() {
        return libraryName;
    }

    /**
     * @return true when any tag declares a {@code base_model:} relation (plain or qualified, e.g.
     *         {@code base_model:finetune:...}, {@code base_model:quantized:...}, {@code
     *         base_model:adapter:...}, {@code base_model:merge:...}) — i.e. this repo is derived from
     *         another model rather than being the root. Used for the "Base only" best-effort filter,
     *         since HuggingFace's public search API cannot filter this server-side (it only matches a
     *         {@code base_model:<relation>:<id>} tag exactly, not as a prefix).
     */
    public boolean hasBaseModelRelation() {
        for (int i = 0; i < tags.size(); i++) {
            if (tags.get(i).startsWith("base_model:")) {
                return true;
            }
        }
        return false;
    }

    /** @return the repository owner (the part of the id before the slash), or "". */
    public String getOwner() {
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : "";
    }

    /** @return the repository name (the part of the id after the slash), or the whole id. */
    public String getRepoName() {
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(slash + 1) : id;
    }

    /**
     * @return the owner of the base model this repo was derived/quantized from, taken from the
     *         {@code base_model:} tags, or "" when unknown.
     */
    public String getBaseModelOwner() {
        String base = getBaseModelId();
        int slash = base.indexOf('/');
        return slash > 0 ? base.substring(0, slash) : "";
    }

    /** @return the full base model id from the tags (prefers the quantized: variant), or "". */
    public String getBaseModelId() {
        String plain = "";
        for (int i = 0; i < tags.size(); i++) {
            String tag = tags.get(i);
            if (tag.startsWith("base_model:quantized:")) {
                return tag.substring("base_model:quantized:".length());
            }
            if (plain.length() == 0 && tag.startsWith("base_model:") && tag.indexOf(':', 11) < 0) {
                plain = tag.substring("base_model:".length());
            }
        }
        return plain;
    }

    public String toString() {
        return id;
    }
}

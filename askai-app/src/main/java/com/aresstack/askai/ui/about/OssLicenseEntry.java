package com.aresstack.askai.ui.about;

/** One third-party library of the build: Maven coordinates, version and the license label of the report. */
public final class OssLicenseEntry {

    private final String group;
    private final String artifact;
    private final String version;
    private final String license;
    private final String module;

    public OssLicenseEntry(String group, String artifact, String version, String license, String module) {
        this.group = group;
        this.artifact = artifact;
        this.version = version;
        this.license = license;
        this.module = module == null ? "" : module;
    }

    public String getName() {
        return group + ":" + artifact;
    }

    public String getGroup() {
        return group;
    }

    public String getArtifact() {
        return artifact;
    }

    public String getVersion() {
        return version;
    }

    public String getLicense() {
        return license;
    }

    /** The first AskAI module that uses the library ("" when the report does not say). */
    public String getModule() {
        return module;
    }
}

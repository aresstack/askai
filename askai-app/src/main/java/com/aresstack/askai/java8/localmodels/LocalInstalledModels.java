package com.aresstack.askai.java8.localmodels;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.ManifestValidation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Host-side reader of on-disk local-model manifests. Fail-closed: a directory's manifest is returned only
 * when it parses AND validates VALID against the shared catalog (same rules the local runtime applies for
 * /api/tags), so an invalid manifest, an unknown schema or an invented capability never reaches the UI.
 */
public final class LocalInstalledModels {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private LocalInstalledModels() {
    }

    /**
     * The catalog-VALID manifest of the installed model with this virtual id under {@code modelRoot}, or
     * {@code null} when there is none (or it does not validate).
     */
    public static InstalledModelManifest readByVirtualName(File modelRoot, String virtualName) {
        if (modelRoot == null || virtualName == null || !modelRoot.isDirectory()) {
            return null;
        }
        File[] children = modelRoot.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            InstalledModelManifest manifest = readValid(new File(child, "askai-local-model.json"));
            if (manifest != null && virtualName.equals(manifest.getVirtualName())) {
                return manifest;
            }
        }
        return null;
    }

    /** Parse + validate a single manifest file; {@code null} unless it is catalog-VALID. */
    static InstalledModelManifest readValid(File manifestFile) {
        if (!manifestFile.isFile()) {
            return null;
        }
        InstalledModelManifest manifest;
        try {
            manifest = LocalModelManifestCodec.parse(
                    new String(Files.readAllBytes(manifestFile.toPath()), UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        if (manifest == null) {
            return null;
        }
        return manifest.validate(manifest.getSchemaVersion()) == ManifestValidation.VALID ? manifest : null;
    }
}

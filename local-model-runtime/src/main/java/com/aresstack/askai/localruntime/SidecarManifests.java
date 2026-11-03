package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.ManifestValidation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Sidecar JSON I/O for the shared {@link InstalledModelManifest}. Mirrors the host codec exactly (same
 * absent-vs-malformed schemaVersion semantics, same legacy {@code backendSupport} key) so both sides make
 * the SAME trust decision. The manifest is validated against the catalog here; only {@link ManifestValidation#VALID}
 * manifests become a {@link LocalModel}.
 */
final class SidecarManifests {

    static final String FILE_NAME = "askai-local-model.json";

    private SidecarManifests() {
    }

    /** @return the catalog-validated model in this directory, or {@code null} (with a logged reason). */
    static LocalModel read(Path modelDirectory) {
        Path file = modelDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        InstalledModelManifest manifest;
        try {
            manifest = parse(LocalJson.parseObject(Files.readString(file)));
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("[local-runtime] unreadable manifest in " + modelDirectory + ": "
                    + unreadable.getMessage());
            return null;
        }
        if (manifest == null) {
            return null;
        }
        ManifestValidation validation = manifest.validate(manifest.getSchemaVersion());
        if (validation != ManifestValidation.VALID) {
            System.err.println("[local-runtime] rejecting manifest in " + modelDirectory + ": "
                    + validation);
            return null;
        }
        return new LocalModel(manifest, modelDirectory);
    }

    /** Parse a manifest map into the neutral value object with the shared schemaVersion semantics. */
    static InstalledModelManifest parse(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        int schema = schemaVersion(m);
        List<String> backends = m.containsKey("supportedBackends")
                ? LocalJson.strings(m, "supportedBackends") : LocalJson.strings(m, "backendSupport");
        return new InstalledModelManifest(schema, LocalJson.str(m, "virtualName"),
                LocalJson.str(m, "huggingFaceRepository"), LocalJson.str(m, "resolvedRevision"),
                LocalJson.str(m, "runtimeModelId"), LocalJson.str(m, "runtimeFamily"),
                LocalJson.str(m, "runtimePackage"), LocalJson.strings(m, "capabilities"), backends,
                LocalJson.str(m, "sourceFormat"), LocalJson.str(m, "state"), longValue(m.get("installedAt")));
    }

    /** Absent -> historical v1; present-but-non-integer -> MALFORMED; integral -> that value. */
    private static int schemaVersion(Map<String, Object> m) {
        if (!m.containsKey("schemaVersion") || m.get("schemaVersion") == null) {
            return InstalledModelManifest.LEGACY_RERANKER_SCHEMA_VERSION;
        }
        Object value = m.get("schemaVersion");
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d)
                    && d >= Integer.MIN_VALUE + 1 && d <= Integer.MAX_VALUE) {
                return (int) d;
            }
        }
        return InstalledModelManifest.SCHEMA_VERSION_MALFORMED;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

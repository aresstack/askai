package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The DEDICATED local store for static NLP model artifacts — separate from the runtime model store
 * ({@code .../models/local}) and from any research-agent-specific path, because OpenNLP models are a global AskAI
 * NLP resource loaded directly by Java, not a runnable local-runtime model. Default location
 * {@code %APPDATA%\.askai\models\nlp\<implementation>\<id>\} with a small {@code askai-nlp-model.json} manifest +
 * the artifact file; the file name is NEVER the identity (the manifest is).
 *
 * <p>This store only READS/inspects what is installed (N4 adds explicit download+install). Each installed model
 * yields a neutral {@link NlpModelDescriptor} with the resolved absolute {@code artifactPath}.</p>
 */
public final class LocalNlpModelStore {

    static final String MANIFEST_NAME = "askai-nlp-model.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File root;

    public LocalNlpModelStore() {
        this(defaultRoot());
    }

    public LocalNlpModelStore(File root) {
        this.root = root;
    }

    /** {@code %APPDATA%\.askai\models\nlp} (or {@code ~/.askai/models/nlp}) — sibling of the runtime {@code local} store. */
    public static File defaultRoot() {
        String appData = System.getenv("APPDATA");
        File base = appData != null && !appData.trim().isEmpty()
                ? new File(appData) : new File(System.getProperty("user.home"), "AppData/Roaming");
        return new File(new File(new File(base, ".askai"), "models"), "nlp");
    }

    public File getRoot() {
        return root;
    }

    /** Every installed model whose manifest + artifact are present, as neutral descriptors (stable order by id). */
    public List<NlpModelDescriptor> listInstalled() {
        List<NlpModelDescriptor> models = new ArrayList<NlpModelDescriptor>();
        collect(root, 0, models);
        Collections.sort(models, new Comparator<NlpModelDescriptor>() {
            public int compare(NlpModelDescriptor a, NlpModelDescriptor b) {
                return a.getModelId().compareTo(b.getModelId());
            }
        });
        return models;
    }

    /** The installed descriptor with this model id, or {@code null}. */
    public NlpModelDescriptor find(String modelId) {
        if (modelId == null) {
            return null;
        }
        for (NlpModelDescriptor descriptor : listInstalled()) {
            if (descriptor.getModelId().equals(modelId.trim())) {
                return descriptor;
            }
        }
        return null;
    }

    /** The SHA-256 (lowercase hex) of an installed artifact, for the snapshot provider's integrity check. */
    public static String sha256Of(File file) throws IOException {
        return sha256(Files.readAllBytes(file.toPath()));
    }

    /** The SHA-256 (lowercase hex) of the given bytes. */
    public static String sha256(byte[] bytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Install a VERIFIED artifact (the installer has already checked size + SHA-256) into the store: write the
     * artifact, then write the manifest LAST so its presence marks the install "active" (a crash before the
     * manifest leaves an artifact without a manifest, which {@link #listInstalled} ignores). Atomic per file.
     */
    public NlpModelDescriptor install(NlpModelCatalogEntry entry, byte[] artifact) throws IOException {
        File dir = new File(new File(root, safe(entry.getImplementation())), safe(entry.getModelId()));
        Files.createDirectories(dir.toPath());
        atomicWrite(new File(dir, entry.getArtifactFileName()), artifact);
        // Manifest LAST = activation.
        atomicWrite(new File(dir, MANIFEST_NAME), manifestJson(entry).getBytes(UTF8));
        return parse(new File(dir, MANIFEST_NAME));
    }

    private static String manifestJson(NlpModelCatalogEntry e) {
        return "{"
                + "\"id\":" + json(e.getModelId())
                + ",\"capability\":" + json(e.getCapability().getTag())
                + ",\"language\":" + json(e.getLanguageCode())
                + ",\"implementation\":" + json(e.getImplementation())
                + ",\"version\":" + json(e.getVersion())
                + ",\"compatibleRuntime\":" + json(e.getCompatibleRuntime())
                + ",\"artifact\":" + json(e.getArtifactFileName())
                + ",\"sha256\":" + json(e.getExpectedSha256())
                + "}";
    }

    private static String json(String value) {
        String v = value == null ? "" : value;
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String safe(String value) {
        return (value == null ? "" : value).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void atomicWrite(File target, byte[] content) throws IOException {
        File parent = target.getParentFile();
        Files.createDirectories(parent.toPath());
        File tmp = new File(parent, target.getName() + ".tmp");
        Files.write(tmp.toPath(), content);
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ manifest scanning

    private static void collect(File dir, int depth, List<NlpModelDescriptor> out) {
        if (dir == null || !dir.isDirectory() || depth > 4) {
            return;
        }
        File manifest = new File(dir, MANIFEST_NAME);
        if (manifest.isFile()) {
            NlpModelDescriptor descriptor = parse(manifest);
            if (descriptor != null) {
                out.add(descriptor);
            }
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    collect(child, depth + 1, out);
                }
            }
        }
    }

    /** Parse an {@code askai-nlp-model.json} into a descriptor; returns null when unusable (never throws). */
    @SuppressWarnings("unchecked")
    static NlpModelDescriptor parse(File manifestFile) {
        try {
            Object parsed = SimpleJson.parse(new String(Files.readAllBytes(manifestFile.toPath()), UTF8));
            if (!(parsed instanceof Map)) {
                return null;
            }
            Map<String, Object> m = (Map<String, Object>) parsed;
            String id = string(m.get("id"));
            String capabilityTag = string(m.get("capability"));
            String artifact = string(m.get("artifact"));
            if (id.isEmpty() || capabilityTag.isEmpty() || artifact.isEmpty()) {
                return null;
            }
            NlpCapability capability;
            try {
                capability = NlpCapability.fromTag(capabilityTag);
            } catch (IllegalArgumentException unknown) {
                return null; // a capability this build does not understand is simply not installed for us
            }
            File artifactFile = new File(manifestFile.getParentFile(), artifact);
            if (!artifactFile.isFile()) {
                return null; // manifest without its artifact is not a usable install
            }
            return new NlpModelDescriptor(id, capability, string(m.get("language")),
                    string(m.get("implementation")), string(m.get("version")),
                    string(m.get("compatibleRuntime")), artifactFile.getAbsolutePath(),
                    string(m.get("sha256")));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String string(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }
}

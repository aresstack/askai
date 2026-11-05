package com.aresstack.askai.research.store;

import com.aresstack.askai.research.visualize.VisualizationProjection;
import com.aresstack.askai.research.visualize.VisualizationResult;
import com.aresstack.askai.research.visualize.VisualizationType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Persists the CURRENT derived visualization of one project under
 * {@code <projectDir>/visualization/current.properties} + {@code current.mmd} (the Mermaid source), so the
 * "Visualisierung" tab shows the last generated diagram after a restart instead of regenerating it (issue
 * #29: opening a tab displays persisted state; generation is an explicit user action). Derived state only —
 * a missing/corrupt file simply means "not generated yet", never an error. The persisted source content hash
 * is the staleness anchor against the research brief.
 */
public final class FileVisualizationStore {

    private static final int SCHEMA_VERSION = 2;

    private final File dir;

    public FileVisualizationStore(File visualizationDir) {
        this.dir = visualizationDir;
    }

    /** Overwrite the persisted visualization atomically; a null projection is ignored. */
    public synchronized void save(VisualizationProjection projection) {
        if (projection == null) {
            return;
        }
        VisualizationResult result = projection.getResult();
        StringBuilder sb = new StringBuilder();
        sb.append("schemaVersion=").append(SCHEMA_VERSION).append('\n');
        sb.append("sourceArtifactId=").append(escape(projection.getSourceArtifactId())).append('\n');
        sb.append("sourceContentHash=").append(escape(projection.getSourceContentHash())).append('\n');
        sb.append("phaseId=").append(escape(projection.getPhaseId())).append('\n');
        sb.append("kind=").append(result.getKind().name()).append('\n');
        sb.append("type=").append(result.getType() == null ? "" : result.getType().name()).append('\n');
        sb.append("title=").append(escape(result.getTitle())).append('\n');
        sb.append("reason=").append(escape(result.getReason())).append('\n');
        try {
            StoreIo.atomicWrite(propertiesFile(), sb.toString());
            if (result.isPresent()) {
                StoreIo.atomicWrite(mermaidFile(), result.getMermaid());
            } else if (mermaidFile().isFile()) {
                //noinspection ResultOfMethodCallIgnored
                mermaidFile().delete();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist the visualization: " + ex.getMessage(), ex);
        }
    }

    /** The persisted visualization, or {@code null} when missing/corrupt (= not generated yet). */
    public synchronized VisualizationProjection load() {
        File props = propertiesFile();
        if (!props.isFile()) {
            return null;
        }
        try {
            Properties p = new Properties();
            p.putAll(StoreIo.loadUtf8Properties(props));
            if (!Integer.toString(SCHEMA_VERSION).equals(p.getProperty("schemaVersion"))) {
                return null; // incompatible → "not generated yet", never guessed
            }
            String kind = p.getProperty("kind", "");
            VisualizationResult result;
            if (VisualizationResult.Kind.DIAGRAM.name().equals(kind)) {
                String mermaid = StoreIo.readUtf8(mermaidFile());
                result = VisualizationResult.diagram(
                        VisualizationType.fromToken(p.getProperty("type", "")),
                        p.getProperty("title", ""), mermaid);
            } else if (VisualizationResult.Kind.NONE.name().equals(kind)) {
                result = VisualizationResult.none(p.getProperty("reason", ""));
            } else {
                return null; // FAILED runs are not worth restoring — the tab offers regeneration anyway
            }
            return new VisualizationProjection(p.getProperty("sourceArtifactId", ""),
                    p.getProperty("sourceContentHash", ""), p.getProperty("phaseId", ""), result);
        } catch (Exception corrupt) {
            return null; // a corrupt derived file only costs a regeneration
        }
    }

    private File propertiesFile() {
        return new File(dir, "current.properties");
    }

    private File mermaidFile() {
        return new File(dir, "current.mmd");
    }

    private static String escape(String value) {
        return value == null ? ""
                : value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}

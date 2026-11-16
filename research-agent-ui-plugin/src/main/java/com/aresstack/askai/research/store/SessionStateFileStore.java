package com.aresstack.askai.research.store;

import com.aresstack.askai.research.state.oo.LegacyResearchStateMigration;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;

import java.io.File;
import java.io.IOException;

/**
 * Persists the research session's state memento (ids only) as {@code state/research-session.json}, written
 * atomically. Only stable ids are stored — never state objects. A missing or corrupt file yields {@code null}
 * from {@link #load()} (isolated), so a restart with a damaged file degrades to "no restored state" instead of
 * crashing. The tiny JSON is written and parsed by hand (no JSON library on the plugin classloader).
 *
 * <p>#43: every save stamps the {@code model} marker. A file WITHOUT it predates the 4-phase
 * product model, where "outline" was the pre-research legacy phase — such a memento goes
 * through {@link LegacyResearchStateMigration#migratePreV4} before anyone interprets it.</p>
 */
public final class SessionStateFileStore {

    /** The phase-model vocabulary this store writes; absent in files = pre-4-phase legacy. */
    static final String MODEL_PHASES_V4 = "phases-v4";

    private final File file;

    public SessionStateFileStore(File stateDir) {
        this.file = new File(stateDir, "research-session.json");
    }

    public void save(ResearchStateMemento memento) throws IOException {
        save(memento, null);
    }

    /** The memento together with the id of the last committed transition (D3: the commit point). */
    public static final class StoredState {
        private final ResearchStateMemento memento;
        private final String lastTransitionId;

        StoredState(ResearchStateMemento memento, String lastTransitionId) {
            this.memento = memento;
            this.lastTransitionId = lastTransitionId;
        }

        public ResearchStateMemento getMemento() {
            return memento;
        }

        /** The id of the transition this memento commits, or {@code null}. */
        public String getLastTransitionId() {
            return lastTransitionId;
        }
    }

    public java.io.File getFile() {
        return file;
    }

    /**
     * The strict read of PR 06: {@code null} only when the file is missing; a file that exists but cannot
     * be read throws, so the caller blocks with a repair hint instead of starting over at scoping/new.
     */
    public StoredState loadStored()
            throws com.aresstack.askai.research.transition.CorruptProjectFileException {
        if (!file.isFile()) {
            return null; // missing (a directory in its place fails the mandatory initial write)
        }
        try {
            String json = StoreIo.readUtf8(file);
            String phaseId = field(json, "phaseId");
            String stateId = field(json, "stateId");
            if (phaseId == null || stateId == null || json.indexOf("\"revision\":") < 0) {
                throw new IllegalStateException("required ids missing");
            }
            ResearchStateMemento memento = new ResearchStateMemento(phaseId, stateId,
                    field(json, "continuationStateId"),
                    longField(json, "revision"), field(json, "pendingApprovalId"));
            if (!MODEL_PHASES_V4.equals(field(json, "model"))) {
                memento = LegacyResearchStateMigration.migratePreV4(memento);
            }
            return new StoredState(memento, field(json, "lastTransitionId"));
        } catch (Exception corrupt) {
            throw new com.aresstack.askai.research.transition.CorruptProjectFileException(file,
                    file.getName() + " is unreadable (" + corrupt.getMessage() + ")");
        }
    }

    /** Write the memento and the id of its transition atomically, flushed before the rename. */
    public void save(ResearchStateMemento memento, String lastTransitionId) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"model\": ").append(quote(MODEL_PHASES_V4)).append(",\n");
        sb.append("  \"phaseId\": ").append(quote(memento.getPhaseId())).append(",\n");
        sb.append("  \"stateId\": ").append(quote(memento.getStateId())).append(",\n");
        sb.append("  \"continuationStateId\": ").append(quote(memento.getContinuationStateId())).append(",\n");
        sb.append("  \"revision\": ").append(memento.getRevision()).append(",\n");
        sb.append("  \"pendingApprovalId\": ").append(quote(memento.getPendingApprovalId())).append(",\n");
        sb.append("  \"lastTransitionId\": ").append(quote(lastTransitionId)).append("\n");
        sb.append("}\n");
        StoreIo.atomicWrite(file, sb.toString());
    }

    /** @return the restored memento, or {@code null} if the file is missing/unreadable/corrupt. */
    public ResearchStateMemento load() {
        if (!file.isFile()) {
            return null;
        }
        try {
            String json = StoreIo.readUtf8(file);
            String phaseId = field(json, "phaseId");
            String stateId = field(json, "stateId");
            if (phaseId == null || stateId == null) {
                return null; // corrupt: required ids missing
            }
            ResearchStateMemento memento = new ResearchStateMemento(phaseId, stateId,
                    field(json, "continuationStateId"),
                    longField(json, "revision"), field(json, "pendingApprovalId"));
            if (!MODEL_PHASES_V4.equals(field(json, "model"))) {
                memento = LegacyResearchStateMigration.migratePreV4(memento);
            }
            return memento;
        } catch (Exception corrupt) {
            return null;
        }
    }

    // ---- tiny, controlled JSON (matches what save() writes) ----

    private static String quote(String v) {
        if (v == null) {
            return "null";
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String field(String json, String key) {
        String marker = "\"" + key + "\":";
        int i = json.indexOf(marker);
        if (i < 0) {
            return null;
        }
        int p = i + marker.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (json.startsWith("null", p)) {
            return null;
        }
        if (p < json.length() && json.charAt(p) == '"') {
            int end = json.indexOf('"', p + 1);
            if (end < 0) {
                return null;
            }
            return json.substring(p + 1, end).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return null;
    }

    private static long longField(String json, String key) {
        String marker = "\"" + key + "\":";
        int i = json.indexOf(marker);
        if (i < 0) {
            return 0L;
        }
        int p = i + marker.length();
        StringBuilder num = new StringBuilder();
        while (p < json.length()) {
            char c = json.charAt(p);
            if (Character.isDigit(c) || c == '-') {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
            p++;
        }
        try {
            return num.length() == 0 ? 0L : Long.parseLong(num.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}

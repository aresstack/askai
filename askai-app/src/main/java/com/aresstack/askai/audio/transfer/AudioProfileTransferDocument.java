package com.aresstack.askai.audio.transfer;

import java.util.ArrayList;
import java.util.List;

/**
 * The versioned transfer envelope (not a raw profile array). Serialized/deserialized with Gson by field
 * name only — no Java class names or polymorphic type information are written or read. Unknown extra fields
 * in incoming JSON are tolerated (Gson ignores them). {@code exportedAt} is metadata only and is never used
 * as profile identity.
 */
public final class AudioProfileTransferDocument {

    public int schemaVersion;
    public String format;
    public String exportedAt;
    public List<TransferProfile> profiles = new ArrayList<TransferProfile>();

    public AudioProfileTransferDocument() {
    }

    public AudioProfileTransferDocument(int schemaVersion, String format, String exportedAt,
                                        List<TransferProfile> profiles) {
        this.schemaVersion = schemaVersion;
        this.format = format;
        this.exportedAt = exportedAt;
        this.profiles = profiles == null ? new ArrayList<TransferProfile>() : profiles;
    }
}

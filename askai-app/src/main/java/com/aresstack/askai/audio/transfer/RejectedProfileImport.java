package com.aresstack.askai.audio.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A profile that cannot be imported, with the concrete reasons (never silently dropped). */
public final class RejectedProfileImport {

    private final String displayName;
    private final List<String> reasons;

    public RejectedProfileImport(String displayName, List<String> reasons) {
        this.displayName = displayName;
        this.reasons = Collections.unmodifiableList(
                new ArrayList<String>(reasons == null ? new ArrayList<String>() : reasons));
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getReasons() {
        return reasons;
    }
}

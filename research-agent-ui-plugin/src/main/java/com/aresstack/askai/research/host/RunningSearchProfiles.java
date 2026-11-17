package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The search profiles of the research sessions running in this AskAI process. The search settings are app-wide
 * ({@link LegacyBrowserSearchSettingsStore}), and a running session's agent reads its profile file before every
 * search ({@code ASKAI_BROWSER_SEARCH_CONFIG}). So every save rewrites the profile of every running session from
 * the saved values, and the next search of each chat uses the saved engine table without a restart.
 *
 * <p>A session joins when its resources are built and leaves when they close. A session that joins after a save it
 * missed (its factory read the store just before) is rewritten at once. A profile that cannot be written keeps its
 * old revision, visibly logged; the other sessions are still rewritten.</p>
 */
final class RunningSearchProfiles {

    private static final class Running {
        final Object owner;
        final String sessionKey;
        final File profileFile;
        final File browserConfigFile;
        final Consumer<SearchProcessingProfileSnapshot> onRewritten;
        long revision;

        Running(Object owner, String sessionKey, long revision, File profileFile, File browserConfigFile,
                Consumer<SearchProcessingProfileSnapshot> onRewritten) {
            this.owner = owner;
            this.sessionKey = sessionKey;
            this.revision = revision;
            this.profileFile = profileFile;
            this.browserConfigFile = browserConfigFile;
            this.onRewritten = onRewritten;
        }
    }

    private static final List<Running> RUNNING = new ArrayList<Running>();
    /** The values of the last save in this process and their revision; null before the first save. */
    private static Map<String, String> savedValues;
    private static long savedRevision;

    private RunningSearchProfiles() {
    }

    /**
     * A running session: {@code owner} is its resources (they leave with it), {@code revision} the revision its
     * profile was written with, {@code onRewritten} gets each rewritten profile.
     */
    static synchronized void join(Object owner, String sessionKey, long revision, File profileFile,
                                  File browserConfigFile, Consumer<SearchProcessingProfileSnapshot> onRewritten) {
        if (owner == null || sessionKey == null || profileFile == null || browserConfigFile == null
                || onRewritten == null) {
            throw new IllegalArgumentException("owner, session key, profile files and listener are required");
        }
        Running running = new Running(owner, sessionKey, revision, profileFile, browserConfigFile, onRewritten);
        RUNNING.add(running);
        if (savedValues != null && savedRevision > revision) {
            rewrite(running, savedValues, savedRevision);
        }
    }

    /** The session of {@code owner} has closed: later saves leave its files alone. */
    static synchronized void leave(Object owner) {
        for (Iterator<Running> it = RUNNING.iterator(); it.hasNext(); ) {
            if (it.next().owner == owner) {
                it.remove();
            }
        }
    }

    /**
     * The settings were saved as {@code values} (the stored overrides) with {@code revision}: rewrite every running
     * session's profile from them. @return the number of sessions rewritten.
     */
    static synchronized int saved(Map<String, String> values, long revision) {
        savedValues = new LinkedHashMap<String, String>(values);
        savedRevision = revision;
        int rewritten = 0;
        for (Running running : RUNNING) {
            if (rewrite(running, savedValues, revision)) {
                rewritten++;
            }
        }
        return rewritten;
    }

    /** Tests: no running session, no save. */
    static synchronized void clear() {
        RUNNING.clear();
        savedValues = null;
        savedRevision = 0L;
    }

    private static boolean rewrite(Running running, Map<String, String> values, long revision) {
        try {
            SearchProcessingProfileSnapshot profile = ProductiveResearchBackendFactory.rewriteSearchProfile(
                    running.sessionKey, revision, values, running.profileFile, running.browserConfigFile);
            System.err.println("[research-host] search settings revision " + running.revision + " -> " + revision
                    + " for the running session " + running.sessionKey + ": its next search uses them");
            running.revision = revision;
            running.onRewritten.accept(profile);
            return true;
        } catch (IOException | RuntimeException failed) {
            System.err.println("[research-host] the search profile of the running session " + running.sessionKey
                    + " could not be rewritten (" + failed.getMessage() + "); it keeps revision "
                    + running.revision);
            return false;
        }
    }
}

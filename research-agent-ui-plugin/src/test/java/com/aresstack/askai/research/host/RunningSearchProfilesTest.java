package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Live run 5: the user unchecked Google under Search → Engines and saved, and the running research kept searching
 * with Google until AskAI was restarted. A save now rewrites the search profile of every running session, which its
 * agent reads before every search.
 */
public class RunningSearchProfilesTest {

    private static final String GOOGLE_ON = "google:on:3:15,duckduckgo:on:3";
    private static final String GOOGLE_OFF = "google:off:3:15,duckduckgo:on:3";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final WorkspaceStateStore store = new LegacyBrowserSearchSettingsStoreTest.InMemoryStore();

    @Before
    @After
    public void noRunningSession() {
        RunningSearchProfiles.clear();
    }

    @Test
    public void aSaveRewritesTheProfileOfARunningSessionSoItsNextSearchUsesIt() throws IOException {
        save(GOOGLE_ON);
        File dir = folder.newFolder("chat-1");
        File profile = new File(dir, "search-profile.json");
        File browserConfig = new File(dir, "browser-config.json");
        startSession("chat-1", profile, browserConfig);
        List<SearchProcessingProfileSnapshot> shown = new ArrayList<SearchProcessingProfileSnapshot>();
        Object resources = new Object();
        RunningSearchProfiles.join(resources, "chat-1", 1L, profile, browserConfig, shown::add);

        save(GOOGLE_OFF);

        SearchProcessingProfileSnapshot rewritten = SearchProcessingProfileSnapshot.parse(read(profile));
        assertEquals(2L, rewritten.profileRevision);
        assertFalse("the next search asks no Google", enabled(rewritten, "google"));
        assertTrue(enabled(rewritten, "duckduckgo"));
        LegacyBrowserSearchConfigDocument browser = LegacyBrowserSearchConfigDocument.parse(read(browserConfig));
        assertEquals(2L, browser.settingsRevision);
        assertTrue(browser.values.get("navigation.engines").startsWith("google:off"));
        assertEquals("the settings view shows the rewritten profile", 1, shown.size());
        assertEquals(2L, shown.get(0).profileRevision);
    }

    @Test
    public void aClosedSessionIsLeftAlone() throws IOException {
        save(GOOGLE_ON);
        File dir = folder.newFolder("chat-1");
        File profile = new File(dir, "search-profile.json");
        File browserConfig = new File(dir, "browser-config.json");
        startSession("chat-1", profile, browserConfig);
        Object resources = new Object();
        RunningSearchProfiles.join(resources, "chat-1", 1L, profile, browserConfig, p -> { });
        RunningSearchProfiles.leave(resources);

        save(GOOGLE_OFF);

        SearchProcessingProfileSnapshot kept = SearchProcessingProfileSnapshot.parse(read(profile));
        assertEquals(1L, kept.profileRevision);
        assertTrue(enabled(kept, "google"));
    }

    @Test
    public void aSessionThatJoinsAfterASaveItMissedIsRewrittenAtOnce() throws IOException {
        save(GOOGLE_ON);
        File dir = folder.newFolder("chat-1");
        File profile = new File(dir, "search-profile.json");
        File browserConfig = new File(dir, "browser-config.json");
        startSession("chat-1", profile, browserConfig); // its factory read revision 1 …
        save(GOOGLE_OFF); // … the user saved revision 2 before the session was up

        RunningSearchProfiles.join(new Object(), "chat-1", 1L, profile, browserConfig, p -> { });

        SearchProcessingProfileSnapshot rewritten = SearchProcessingProfileSnapshot.parse(read(profile));
        assertEquals(2L, rewritten.profileRevision);
        assertFalse(enabled(rewritten, "google"));
    }

    @Test
    public void aProfileThatCannotBeWrittenKeepsItsRevisionAndTheOtherSessionsAreStillRewritten()
            throws IOException {
        save(GOOGLE_ON);
        File notADirectory = folder.newFile("chat-broken");
        List<SearchProcessingProfileSnapshot> brokenShown = new ArrayList<SearchProcessingProfileSnapshot>();
        RunningSearchProfiles.join(new Object(), "chat-broken", 1L, new File(notADirectory, "search-profile.json"),
                new File(notADirectory, "browser-config.json"), brokenShown::add);
        File dir = folder.newFolder("chat-1");
        File profile = new File(dir, "search-profile.json");
        File browserConfig = new File(dir, "browser-config.json");
        startSession("chat-1", profile, browserConfig);
        RunningSearchProfiles.join(new Object(), "chat-1", 1L, profile, browserConfig, p -> { });

        Map<String, String> values = settings(GOOGLE_OFF);
        LegacyBrowserSearchSettingsStore.saveValues(store, values);

        assertTrue("the broken session got no profile", brokenShown.isEmpty());
        assertEquals(2L, SearchProcessingProfileSnapshot.parse(read(profile)).profileRevision);
        assertEquals("the next save still reaches the good session only", 1,
                RunningSearchProfiles.saved(LegacyBrowserSearchSettingsStore.loadValues(store), 3L));
    }

    /** What the factory writes when a session starts: the profile of the stored settings. */
    private void startSession(String sessionKey, File profile, File browserConfig) throws IOException {
        ProductiveResearchBackendFactory.rewriteSearchProfile(sessionKey,
                LegacyBrowserSearchSettingsStore.revision(store), LegacyBrowserSearchSettingsStore.loadValues(store),
                profile, browserConfig);
    }

    private void save(String engines) {
        LegacyBrowserSearchSettingsStore.saveValues(store, settings(engines));
    }

    private static Map<String, String> settings(String engines) {
        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(LegacyBrowserSearchDefaults.create());
        values.put("navigation.engines", engines);
        return values;
    }

    private static boolean enabled(SearchProcessingProfileSnapshot profile, String engineId) {
        for (BrowserSearchEngineSelection.Entry entry : profile.settings.navigation.engineSelection.getEntries()) {
            if (entry.getEngineId().equals(engineId)) {
                return entry.isEnabled();
            }
        }
        return false;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

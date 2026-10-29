package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.acp.ResearchBackendMode;
import com.aresstack.askai.research.agent.ResearchAgentSessionFactory;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The mode switch is strict and visible: settings round-trip through the ONE typed mapper, FAKE builds only
 * the fake backend (no host-service lookups at all), an unusable productive configuration is rejected before
 * anything starts, a missing host service is named individually — and there is never a fallback to FAKE.
 * The Swing panel reads/writes the SAME mapper (no second mapping).
 */
public class ResearchRuntimeModeTest {

    /** In-memory store (the app persists via the same interface). */
    private static final class MemoryStore implements WorkspaceStateStore {
        final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key) : defaultValue;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }

    private static final class FakeHostContext implements AgentHostContext {
        final MemoryStore store = new MemoryStore();
        final Map<Class<?>, Object> services = new HashMap<Class<?>, Object>();
        int serviceLookups;
        File workspaceDir;

        public UiExecutor getUiExecutor() {
            return new UiExecutor() {
                public void execute(Runnable runnable) {
                    runnable.run();
                }

                public void assertUiThread() {
                }

                public boolean isUiThread() {
                    return true;
                }
            };
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public NotificationService getNotificationService() {
            return null;
        }

        public WorkspaceStateStore getStateStore() {
            return store;
        }

        public PluginPathService getPluginPathService() {
            return new PluginPathService() {
                public File getPluginDataDirectory() {
                    return workspaceDir;
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return new File(workspaceDir, workspaceInstanceId);
                }
            };
        }

        public AgentConversationSink getConversationSink() {
            return null;
        }

        @SuppressWarnings("unchecked")
        public <T> T getService(Class<T> type) {
            serviceLookups++;
            return (T) services.get(type);
        }
    }

    @Test
    public void settingsRoundTripThroughTheStore() {
        MemoryStore store = new MemoryStore();
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "C:/j8/java.exe", "C:/agent.jar",
                "msedge", false)
                .save(store);
        ResearchRuntimeSettings loaded = ResearchRuntimeSettings.load(store);
        assertEquals(ResearchBackendMode.ACP, loaded.getMode());
        assertEquals("C:/j8/java.exe", loaded.getAgentJavaExecutable());
        assertEquals("C:/agent.jar", loaded.getAgentJar());
        assertEquals("msedge", loaded.getBrowserChannel());
        assertEquals(false, loaded.isHeadless());
    }

    @Test
    public void camoufoxIsTheDefaultChannelAndTheOldStoredChromeDefaultSwitchesOnce() {
        assertEquals("camoufox", ResearchRuntimeSettings.defaults().getBrowserChannel());
        assertEquals("camoufox", ResearchRuntimeSettings.load(new MemoryStore()).getBrowserChannel());

        MemoryStore old = new MemoryStore();
        old.put(ResearchRuntimeSettings.KEY_BROWSER_CHANNEL, "chrome"); // written by every earlier save
        assertEquals("camoufox", ResearchRuntimeSettings.load(old).getBrowserChannel());
        // Choosing Chrome again after the switch is respected.
        old.put(ResearchRuntimeSettings.KEY_BROWSER_CHANNEL, "chrome");
        assertEquals("chrome", ResearchRuntimeSettings.load(old).getBrowserChannel());

        MemoryStore edge = new MemoryStore();
        edge.put(ResearchRuntimeSettings.KEY_BROWSER_CHANNEL, "msedge");
        assertEquals("msedge", ResearchRuntimeSettings.load(edge).getBrowserChannel());
    }

    @Test
    public void theManagedCamoufoxChannelIsNeverReplacedByAnInstalledBrowser() {
        assertEquals("camoufox", ResearchRuntimeDefaults.installedBrowserChannel("camoufox"));
    }

    @Test
    public void unknownPersistedModeLoadsAsFake() {
        MemoryStore store = new MemoryStore();
        store.put(ResearchRuntimeSettings.KEY_MODE, "SOMETHING_ELSE");
        assertEquals(ResearchBackendMode.FAKE, ResearchRuntimeSettings.load(store).getMode());
    }

    @Test
    public void fakeModeCreatesTheFakeBackendWithoutAnyServiceLookup() {
        FakeHostContext host = new FakeHostContext();
        AgentSession session = new ResearchAgentSessionFactory().create(
                new AgentSessionCreationRequest("s1", "p1", new HashMap<String, String>()), host);
        assertNotNull(session);
        assertEquals("the fake path never asks for host runtime services", 0, host.serviceLookups);
        session.close();
    }

    @Test
    public void unmetRequirementsStartTheDemoBackendVisiblyWithoutServiceLookups() {
        // There is no user-facing mode choice anymore: unmet requirements mean new sessions run the
        // DEMO backend with a visible startup notice (surfaced in the chat on activate) — and the
        // host runtime services are never touched.
        FakeHostContext host = new FakeHostContext();
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "", "", "chrome", true)
                .save(host.store);
        AgentSession session = new ResearchAgentSessionFactory().create(
                new AgentSessionCreationRequest("s1", "p1", new HashMap<String, String>()), host);
        assertNotNull(session);
        assertEquals("no service lookup for a demo start", 0, host.serviceLookups);
        session.close();
    }

    @Test
    public void missingHostServiceIsNamedIndividually() throws Exception {
        FakeHostContext host = new FakeHostContext();
        // Paths pass validation (real temp files), but the host offers no runtime services.
        File dir = Files.createTempDirectory("askai-mode").toFile();
        File java8 = touch(dir, "java.exe");
        File agentJar = touch(dir, "agent.jar");
        host.workspaceDir = dir;
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, java8.getAbsolutePath(),
                agentJar.getAbsolutePath(), "chrome", true).save(host.store);
        try {
            new ResearchAgentSessionFactory().create(
                    new AgentSessionCreationRequest("s1", "p1", new HashMap<String, String>()), host);
            fail("a missing host service must fail visibly");
        } catch (IllegalStateException expected) {
            assertTrue("the FIRST missing service is named: " + expected.getMessage(),
                    expected.getMessage().contains("McpServerRegistry"));
            assertTrue(expected.getMessage().contains("no fallback"));
        }
    }

    @Test
    public void completedSettingsNeverComplainAboutTheAgentJava() throws Exception {
        // Regression (user-reported): Save validated the RAW panel fields and demanded the removed
        // agent-Java field. Validation must always run on the COMPLETED settings — the agent runs on
        // AskAI's own JVM.
        File dir = Files.createTempDirectory("askai-defaults").toFile();
        File agentJar = touch(dir, "research-agent-runtime.jar");
        ResearchRuntimeSettings raw = new ResearchRuntimeSettings(ResearchBackendMode.ACP,
                "", agentJar.getAbsolutePath(), "chrome", true);
        java.util.List<String> problems =
                ResearchRuntimeDefaults.complete(raw).validateProductive();
        assertTrue("completed settings must be fully usable: " + problems, problems.isEmpty());
    }

    @Test
    public void agentLanguageIsPersistedAndSwitchesThePlaybook() {
        MemoryStore store = new MemoryStore();
        assertEquals("en", ResearchRuntimeSettings.loadLanguage(store)); // English default
        ResearchRuntimeSettings.saveLanguage(store, "de");
        assertEquals("de", ResearchRuntimeSettings.loadLanguage(store));
        // The persisted code seeds a session-LOCAL playbook; two instances never influence each other.
        com.aresstack.askai.research.agent.ResearchPlaybook german =
                new com.aresstack.askai.research.agent.ResearchPlaybook(
                        com.aresstack.askai.research.agent.ResearchLanguage.fromCode(
                                ResearchRuntimeSettings.loadLanguage(store)));
        com.aresstack.askai.research.agent.ResearchPlaybook english =
                new com.aresstack.askai.research.agent.ResearchPlaybook(
                        com.aresstack.askai.research.agent.ResearchLanguage.ENGLISH);
        assertTrue(german.greeting().contains("Was möchtest du herausfinden?"));
        assertTrue(english.greeting().contains("what would you like to find out?"));
    }

    @Test
    public void panelReadsAndWritesTheSameTypedMapper() {
        MemoryStore store = new MemoryStore();
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "a", "b", "msedge", false)
                .save(store);
        ResearchRuntimeSettingsPanel panel = new ResearchRuntimeSettingsPanel(store);
        ResearchRuntimeSettings fromPanel = panel.currentSettings();
        assertEquals(ResearchBackendMode.ACP, fromPanel.getMode());
        assertEquals("a", fromPanel.getAgentJavaExecutable());
        assertEquals("msedge", fromPanel.getBrowserChannel());
        assertEquals(false, fromPanel.isHeadless());
    }

    @Test
    public void rerankerSelectionIsPersistedThroughTheSameTypedMapper() {
        MemoryStore store = new MemoryStore();
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, "a", "b", "chrome", true,
                false, "local/cross-encoder/ms-marco-MiniLM-L-6-v2:latest")
                .save(store);
        ResearchRuntimeSettings loaded = ResearchRuntimeSettings.load(store);
        assertEquals("local/cross-encoder/ms-marco-MiniLM-L-6-v2:latest",
                loaded.getSelectedRerankerModel());
        assertEquals("the selection reaches the runtime config unchanged",
                "local/cross-encoder/ms-marco-MiniLM-L-6-v2:latest",
                loaded.toRuntimeConfig().getSelectedRerankerModel());
        assertEquals("defaults completion must never drop the selection",
                "local/cross-encoder/ms-marco-MiniLM-L-6-v2:latest",
                ResearchRuntimeDefaults.complete(loaded).getSelectedRerankerModel());
    }

    @Test
    public void thePanelHasNoRerankerPickerButCarriesAPersistedSelectionInvisibly() {
        // The reranker model is chosen centrally in AskAI now — the runtime panel offers no picker.
        // A previously persisted plugin-side selection must still survive a panel save (it is the
        // transitional carrier the host migrates into the central store), never be wiped to "".
        MemoryStore empty = new MemoryStore();
        ResearchRuntimeSettingsPanel panel = new ResearchRuntimeSettingsPanel(empty);
        assertEquals("nothing persisted → nothing carried", "",
                panel.currentSettings().getSelectedRerankerModel());

        MemoryStore withLegacy = new MemoryStore();
        withLegacy.put(ResearchRuntimeSettings.KEY_RERANKER_MODEL, "local/legacy:latest");
        ResearchRuntimeSettingsPanel panel2 = new ResearchRuntimeSettingsPanel(withLegacy);
        assertEquals("a persisted legacy selection is carried through unchanged",
                "local/legacy:latest", panel2.currentSettings().getSelectedRerankerModel());
        // Saving from the panel must not lose it.
        panel2.currentSettings().save(withLegacy);
        assertEquals("local/legacy:latest",
                ResearchRuntimeSettings.load(withLegacy).getSelectedRerankerModel());
    }

    @Test
    public void browserChannelFallsBackToTheInstalledBrowserOnly() {
        // The configured channel wins whenever its browser exists.
        assertEquals("chrome", ResearchRuntimeDefaults.pickChannel("chrome", true, true, "msedge"));
        assertEquals("msedge", ResearchRuntimeDefaults.pickChannel("msedge", true, false, "chrome"));
        // A provably absent browser falls back to the installed alternative (Chrome uninstalled → Edge).
        assertEquals("msedge", ResearchRuntimeDefaults.pickChannel("chrome", false, true, "msedge"));
        assertEquals("chrome", ResearchRuntimeDefaults.pickChannel("msedge", false, true, "chrome"));
        // Neither installed: keep the configured channel so validation reports it readably.
        assertEquals("chrome", ResearchRuntimeDefaults.pickChannel("chrome", false, false, "msedge"));
    }

    private static File touch(File dir, String name) throws Exception {
        File file = new File(dir, name);
        assertTrue(file.createNewFile() || file.isFile());
        return file;
    }
}

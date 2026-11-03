package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Proves the NEW agent extension point crosses the real PF4J boundary just like the workspace one: the plugin
 * is loaded from a plugin directory (not the test classpath), the {@link AgentPluginExtension} is discovered,
 * implements the HOST-loaded interface, and comes from its own plugin classloader. Also exercises the agent
 * session lifecycle across the boundary with host fakes.
 */
public class AgentPluginLoadIntegrationTest {

    private static final String AGENT_ID = "com.aresstack.askai.research";
    private static final String EXTENSION_CLASS =
            "com.aresstack.askai.research.plugin.ResearchAgentPluginExtension";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File pluginDir;

    @Before
    public void resolvePluginDir() {
        String path = System.getProperty("research.plugin.dir");
        assumeTrue("research.plugin.dir system property not set (run via Gradle)",
                path != null && path.trim().length() > 0);
        pluginDir = new File(path.trim());
        assumeTrue("built plugin directory not found: " + pluginDir,
                pluginDir.isDirectory() && new File(pluginDir, "classes").isDirectory()
                        && new File(pluginDir, "lib").isDirectory());
    }

    @Test
    public void pluginDirHasPf4jManifestAndOnlyTheAgentExtension() throws Exception {
        java.util.jar.Manifest manifest;
        java.io.InputStream in = new java.io.FileInputStream(
                new File(pluginDir, "classes/META-INF/MANIFEST.MF"));
        try {
            manifest = new java.util.jar.Manifest(in);
        } finally {
            in.close();
        }
        java.util.jar.Attributes attrs = manifest.getMainAttributes();
        assertEquals(AGENT_ID, attrs.getValue("Plugin-Id"));
        assertEquals("com.aresstack.askai.research.plugin.ResearchPlugin", attrs.getValue("Plugin-Class"));
        File idxFile = new File(pluginDir, "classes/META-INF/extensions.idx");
        assertTrue("extensions.idx must be generated", idxFile.isFile());
        String idx = new String(Files.readAllBytes(idxFile.toPath()), "UTF-8");
        assertTrue("agent extension must be registered", idx.contains(EXTENSION_CLASS));
        assertFalse("workspace extension must no longer be registered",
                idx.contains("ResearchWorkspacePluginExtension"));
    }

    @Test
    public void pluginClassesHaveNoBundledHostApiOrLegacyShell() throws Exception {
        final Path classesDir = new File(pluginDir, "classes").toPath();
        java.util.stream.Stream<Path> files = Files.walk(classesDir);
        try {
            files.filter(Files::isRegularFile).forEach(p -> {
                String name = classesDir.relativize(p).toString().replace('\\', '/');
                assertFalse("bundled PF4J: " + name, name.startsWith("org/pf4j/"));
                assertFalse("bundled workspace API: " + name,
                        name.startsWith("com/aresstack/askai/plugin/api/"));
                assertFalse("bundled pf4j-bridge API: " + name,
                        name.startsWith("com/aresstack/askai/plugin/pf4j/api/"));
                assertFalse("bundled askai-app: " + name, isAskAiAppClass(name));
                assertFalse("orphan legacy workspace class: " + name,
                        name.contains("ResearchWorkspace") || name.contains("ResearchDemoData")
                                || name.contains("/research/domain/"));
            });
        } finally {
            files.close();
        }
    }

    /** askai-app shares the {@code com.aresstack.askai} root package with the plugin since #51; these are its own. */
    private static final String[] ASKAI_APP_PACKAGES = {"audio/", "batch/", "catalog/", "client/", "config/", "hf/", "json/", "modelport/",
            "history/", "localmodels/", "net/", "notify/", "ollamalib/", "party/", "plugin/host/", "service/",
            "settings/", "speech/", "state/", "stt/", "text/", "tool/", "tts/", "ui/", "video/", "vision/"};

    private static boolean isAskAiAppClass(String name) {
        String root = "com/aresstack/askai/";
        if (!name.startsWith(root)) {
            return false;
        }
        String rest = name.substring(root.length());
        if (rest.indexOf('/') < 0) {
            return true; // AskAiApp, AskAiModel, ...
        }
        for (String appPackage : ASKAI_APP_PACKAGES) {
            if (rest.startsWith(appPackage)) {
                return true;
            }
        }
        return false;
    }

    private static String readEntry(java.util.jar.JarFile jar, String name) throws Exception {
        java.io.InputStream in = jar.getInputStream(jar.getEntry(name));
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    @Test
    public void discoversAgentExtensionWithSeparateClassloaderAndSharedApiIdentity() throws Exception {
        Path pluginsRoot = installIntoTempPluginsDir();
        AskAiPluginManager manager = new AskAiPluginManager(pluginsRoot, "0.1.0");
        try {
            manager.loadPlugins();
            manager.startPlugins();

            List<AgentPluginExtension> extensions =
                    manager.getExtensions(AgentPluginExtension.class, AGENT_ID);
            assertEquals("exactly one agent extension", 1, extensions.size());
            AgentPluginExtension extension = extensions.get(0);

            assertTrue("must implement the host's AgentPluginExtension",
                    extension instanceof AgentPluginExtension);
            assertEquals(EXTENSION_CLASS, extension.getClass().getName());
            assertTrue("extension must come from its own plugin classloader",
                    extension.getClass().getClassLoader() != AgentPluginExtension.class.getClassLoader());

            // Prove the plugin's lib/ dependencies are on its classloader: a web-search-providers class
            // (lib/web-search-providers.jar, which drags in AsyncHttpClient/Netty/Gson) loads through the
            // plugin classloader — not the host, which does not provide it.
            Class<?> providerModule = Class.forName(
                    "com.aresstack.askai.research.search.application.WebSearchProvidersModule",
                    false, extension.getClass().getClassLoader());
            assertEquals("lib/ classes load via the plugin classloader",
                    extension.getClass().getClassLoader(), providerModule.getClassLoader());

            AgentPluginDescriptor descriptor = extension.getAgentDescriptor();
            assertEquals(AGENT_ID, descriptor.getId());
            assertEquals("Research Agent", descriptor.getDisplayName());
            assertEquals(1, descriptor.getPluginApiVersion());

            assertNotNull(extension.getSessionFactory());
            assertFalse("expected slash commands", extension.getChatCommands().isEmpty());
            assertFalse("expected at least one artifact view", extension.getArtifactViews().isEmpty());

            // The chat commands are thin TEXT adapters (/search, /open) — all state control goes
            // through the semantic command processor, not slash commands.
            boolean hasSearch = false;
            for (ChatCommandContribution c : extension.getChatCommands()) {
                if ("search".equals(c.getDescriptor().getName())) {
                    hasSearch = true;
                }
            }
            assertTrue("expected a /search command", hasSearch);

            // A specialized (non-Markdown) artifact view is contributed for the state artifact.
            boolean hasStateView = false;
            for (ArtifactViewContribution v : extension.getArtifactViews()) {
                if ("research.state".equals(v.getArtifactTypeId())) {
                    hasStateView = true;
                }
            }
            assertTrue("expected a research.state artifact view", hasStateView);
        } finally {
            quietShutdown(manager);
        }
    }

    @Test
    public void createsIsolatedAgentSessionsAcrossTheBoundary() throws Exception {
        Path pluginsRoot = installIntoTempPluginsDir();
        AskAiPluginManager manager = new AskAiPluginManager(pluginsRoot, "0.1.0");
        try {
            manager.loadPlugins();
            manager.startPlugins();
            AgentSessionFactory factory =
                    manager.getExtensions(AgentPluginExtension.class, AGENT_ID).get(0).getSessionFactory();

            AgentSession a = factory.create(
                    new AgentSessionCreationRequest("a", "", new HashMap<String, String>()), new FakeHost());
            AgentSession b = factory.create(
                    new AgentSessionCreationRequest("b", "", new HashMap<String, String>()), new FakeHost());
            try {
                assertNotSame(a, b);
                assertNotSame(a.getChatTarget(), b.getChatTarget());
                assertFalse(a.getArtifacts().isEmpty());
                a.activate();
                assertNotNull(a.getState().getPhaseLabel());
                a.deactivate();
            } finally {
                a.close();
                a.close(); // idempotent
                b.close();
            }
        } finally {
            quietShutdown(manager);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Path installIntoTempPluginsDir() throws Exception {
        Path root = folder.newFolder("plugins").toPath();
        final Path dest = root.resolve("research-agent-ui-plugin");
        final Path src = pluginDir.toPath();
        java.util.stream.Stream<Path> walk = Files.walk(src);
        try {
            walk.forEach(p -> {
                try {
                    Path target = dest.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } finally {
            walk.close();
        }
        return root;
    }

    private static void quietShutdown(AskAiPluginManager manager) {
        try {
            manager.stopPlugins();
        } catch (RuntimeException ignored) {
            // best-effort (PF4J 3.15.0 stopPlugins CME)
        }
        try {
            manager.unloadPlugins();
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    private static final class FakeHost implements AgentHostContext {
        public UiExecutor getUiExecutor() {
            return new UiExecutor() {
                public boolean isUiThread() {
                    return true;
                }

                public void execute(Runnable runnable) {
                    runnable.run();
                }

                public void assertUiThread() {
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
            return new NotificationService() {
                public void notify(Severity severity, String message) {
                }
            };
        }

        public WorkspaceStateStore getStateStore() {
            return null;
        }

        public PluginPathService getPluginPathService() {
            return new PluginPathService() {
                public File getPluginDataDirectory() {
                    return new File(System.getProperty("java.io.tmpdir"));
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return new File(System.getProperty("java.io.tmpdir"));
                }
            };
        }

        public AgentConversationSink getConversationSink() {
            return new AgentConversationSink() {
                public void appendUserMessage(String messageId, String markdown) {
                }

                public void appendAssistantMessage(String messageId, String markdown) {
                }

                public void startThinking(String activityId, String title) {
                }

                public void updateThinking(String activityId, String text) {
                }

                public void finishThinking(String activityId, String summary) {
                }

                public void startToolActivity(String activityId, String title, String explanation) {
                }

                public void updateToolActivity(String activityId, String title, String explanation) {
                }

                public void completeToolActivity(String activityId, String summary) {
                }

                public void failToolActivity(String activityId, String summary) {
                }

                public void requestApproval(String approvalId, String prompt) {
                }

                public void showProblem(String problemId, String publicMessage) {
                }
            };
        }
    }
}

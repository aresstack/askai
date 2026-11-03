package com.aresstack.askai.ui;

import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.service.AskAiService;
import org.junit.Assume;
import org.junit.Test;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Hugging Face tab layout: install settings on a "Details" tab, the log on a trailing "Technical details" tab. */
public class ModelSearchPanelLayoutTest {

    @Test
    public void installSettingsLiveOnDetailsTabAndLogOnTrailingTechnicalDetailsTab() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        final File config = File.createTempFile("askai-config", ".properties");
        config.deleteOnExit();
        final AskAiService service = (AskAiService) Proxy.newProxyInstance(
                AskAiService.class.getClassLoader(), new Class<?>[]{AskAiService.class},
                (proxy, method, args) -> null);
        final AtomicReference<ModelSearchPanel> panel = new AtomicReference<ModelSearchPanel>();
        SwingUtilities.invokeAndWait(() ->
                panel.set(new ModelSearchPanel(new AppConfigurationRepository(config), service)));

        List<String> titles = panel.get().tabTitles();
        assertEquals("Technical details is always the last tab",
                ModelSearchPanel.TECHNICAL_DETAILS, titles.get(titles.size() - 1));

        List<String> innerTabs = new ArrayList<String>();
        collectTabTitles(panel.get(), innerTabs, panel.get());
        assertTrue("the GGUF files area carries Files and Details tabs: " + innerTabs,
                innerTabs.contains("Files") && innerTabs.contains("Details"));
    }

    private static void collectTabTitles(Container container, List<String> out, Container root) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTabbedPane && child != firstTabs(root)) {
                JTabbedPane tabs = (JTabbedPane) child;
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    out.add(tabs.getTitleAt(i));
                }
            }
            if (child instanceof Container) {
                collectTabTitles((Container) child, out, root);
            }
        }
    }

    private static Component firstTabs(Container root) {
        return root.getComponentCount() > 0 ? root.getComponent(0) : null;
    }
}

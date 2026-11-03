package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.Component;
import java.awt.Container;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SearchProviderCardsPanelTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private String home;

    @Before
    public void ownHome() {
        home = System.getProperty("user.home");
        System.setProperty("user.home", temp.getRoot().getAbsolutePath());
    }

    @After
    public void restoreHome() {
        System.setProperty("user.home", home);
    }

    @Test
    public void inTheWizardConfigureSitsBesideTheComboAndNoSettingsAreInline() {
        SearchProviderCardsPanel panel = new SearchProviderCardsPanel(new MemoryStore(), false);
        Container row = panel.providerCombo().getParent();
        assertTrue("Configure… is in the combo's row", isInside(panel.configureButton(), row));
        assertEquals("only the provider row, no inline settings", 1, panel.getComponentCount());
        assertFalse("the browser needs no settings", panel.configureButton().isEnabled());

        panel.providerCombo().setSelectedItem("DataForSEO");
        assertTrue(panel.configureButton().isEnabled());
        SearchProviderCardsPanel.ProviderEditor editor = panel.editorFor(panel.providerCombo().getSelectedIndex());
        assertNotNull("the dialog gets the provider's settings", editor);
        assertTrue(editor.component instanceof DataForSeoSettingsPanel);
        editor.discard();

        panel.providerCombo().setSelectedItem("Bright Data");
        assertTrue(panel.editorFor(panel.providerCombo().getSelectedIndex()).component
                instanceof BrightDataSettingsPanel);
        panel.providerCombo().setSelectedItem("Brave Search API");
        assertTrue(panel.editorFor(panel.providerCombo().getSelectedIndex()).component
                instanceof BraveSettingsPanel);
        panel.providerCombo().setSelectedItem("SerpApi");
        assertTrue(panel.editorFor(panel.providerCombo().getSelectedIndex()).component
                instanceof SerpApiSettingsPanel);
    }

    @Test
    public void inTheSettingsTheSelectedProvidersSettingsStayInline() {
        SearchProviderCardsPanel panel = new SearchProviderCardsPanel(new MemoryStore());
        assertEquals("provider row and settings", 2, panel.getComponentCount());
        assertSame(null, panel.configureButton().getParent());
    }

    private static boolean isInside(Component component, Container container) {
        for (Component c = component; c != null; c = c.getParent()) {
            if (c == container) {
                return true;
            }
        }
        return false;
    }

    private static final class MemoryStore implements WorkspaceStateStore {
        private final java.util.Map<String, String> values = new java.util.HashMap<String, String>();

        public String get(String key, String defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : value;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Boolean.parseBoolean(value);
        }

        public int getInt(String key, int defaultValue) {
            String value = values.get(key);
            try {
                return value == null ? defaultValue : Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, Boolean.toString(value));
        }

        public void putInt(String key, int value) {
            values.put(key, Integer.toString(value));
        }
    }
}

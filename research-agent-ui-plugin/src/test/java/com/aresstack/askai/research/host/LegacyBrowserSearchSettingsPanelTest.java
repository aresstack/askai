package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A2d surface behaviour: the panel builds every catalog field, prompt editors carry the PRODUCTIVE
 * default text (never an empty editor with a placeholder), and Save on unchanged defaults persists
 * cleanly (revision bump) — validation failures would refuse the save instead.
 */
public class LegacyBrowserSearchSettingsPanelTest {

    private final WorkspaceStateStore store =
            new LegacyBrowserSearchSettingsStoreTest.InMemoryStore();

    @Test
    public void promptEditorsCarryTheProductiveDefaultText() {
        LegacyBrowserSearchSettingsPanel panel = LegacyBrowserSearchSettingsPanel.serpSearch(store,
                SearchProcessingProfileSnapshot.create("s1", 4L, 1L,
                        LegacyBrowserSearchDefaults.create()));
        List<JTextArea> areas = new ArrayList<JTextArea>();
        collect(panel, JTextArea.class, areas);
        boolean promptSeen = false;
        for (JTextArea area : areas) {
            if (area.getText().contains("{containerDescriptors}")) {
                promptSeen = true; // the real default prompt IS the editor value
            }
        }
        assertTrue("layout resolver prompt must be pre-filled with its productive default",
                promptSeen);
    }

    @Test
    public void savingUnchangedDefaultsBumpsTheRevisionWithoutViolations() {
        LegacyBrowserSearchSettingsPanel panel = LegacyBrowserSearchSettingsPanel.serpSearch(store, null);
        JButton save = findButton(panel, "Save");
        assertNotNull(save);
        save.doClick();
        assertEquals(1L, LegacyBrowserSearchSettingsStore.revision(store));
        assertTrue(LegacyBrowserSearchSettingsStore.loadValues(store).isEmpty());
        findButton(LegacyBrowserSearchSettingsPanel.browsing(store, null, null), "Save").doClick();
        assertEquals(2L, LegacyBrowserSearchSettingsStore.revision(store));
        assertTrue(LegacyBrowserSearchSettingsStore.loadValues(store).isEmpty());
    }

    @Test
    public void theTwoHalvesTogetherEditEveryCatalogFieldExactlyOnce() {
        java.util.Set<String> browsing = LegacyBrowserSearchSettingsPanel.browsing(store, null, null).editedKeys();
        java.util.Set<String> search = LegacyBrowserSearchSettingsPanel.serpSearch(store, null).editedKeys();
        java.util.Set<String> all = new java.util.HashSet<String>();
        for (com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCatalog.Field field
                : com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCatalog.fields()) {
            all.add(field.key);
            assertTrue("exactly one place: " + field.key, browsing.contains(field.key) ^ search.contains(field.key));
        }
        assertEquals(all.size(), browsing.size() + search.size());
        assertTrue("every page load's deadline is a browser setting",
                browsing.contains("navigation.navigationCommitTimeoutMillis"));
        assertTrue(browsing.contains("consent.enabled"));
        assertTrue(browsing.contains("captcha.waitForUser"));
        assertTrue(browsing.contains("readiness.minimumReadableCharacters"));
        assertTrue("engines are the browser search's own", search.contains("navigation.engines"));
        assertTrue(search.contains("navigation.searchResultLimit"));
        assertTrue(search.contains("reranker.enabled"));
    }

    @Test
    public void savingOneHalfKeepsTheOtherHalfsStoredOverrides() {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>(
                com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec.toValues(
                        LegacyBrowserSearchDefaults.create()));
        values.put("navigation.searchResultLimit", "37");
        LegacyBrowserSearchSettingsStore.saveValues(store, values);
        // A browsing panel opened afterwards saves its half — the stored search override must survive.
        findButton(LegacyBrowserSearchSettingsPanel.browsing(store, null, null), "Save").doClick();
        assertEquals("37", LegacyBrowserSearchSettingsStore.loadValues(store).get("navigation.searchResultLimit"));
    }

    private static JButton findButton(Container root, String text) {
        List<JButton> buttons = new ArrayList<JButton>();
        collect(root, JButton.class, buttons);
        for (JButton button : buttons) {
            if (text.equals(button.getText())) {
                return button;
            }
        }
        fail("button not found: " + text);
        return null;
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> out) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            if (child instanceof Container) {
                collect((Container) child, type, out);
            }
        }
    }
}

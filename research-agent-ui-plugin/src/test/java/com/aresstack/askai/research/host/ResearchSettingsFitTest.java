package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.comiccontrols.control.ComicLayoutAudit;
import org.junit.Test;

import javax.swing.JComponent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Every Research Agent settings tab and sub-page, rendered narrow and wide: nothing may stick out of the
 * window, and shrinking the window must shrink the content again (no horizontal scroll bar, ever).
 */
public class ResearchSettingsFitTest {

    @Test
    public void everyTabFitsNarrowWideAndAfterShrinkingAgain() {
        // Exactly as the host shows it: Settings card / plugin dialog = ComicForm.fit(page).
        JComponent page = com.aresstack.comiccontrols.control.ComicForm.fit(
                new ResearchSettingsContribution().createPluginSettingsComponent(store()));
        assertFits(page, 1300, 900);
        assertFits(page, 620, 700);
        assertFits(page, 1600, 1000);
        assertFits(page, 540, 600); // shrink after having been wide
    }

    private static void assertFits(JComponent page, int width, int height) {
        List<String> overflows = ComicLayoutAudit.overflows(page, width, height);
        assertTrue("At " + width + "x" + height + " the settings run out of the window:\n"
                + String.join("\n", overflows), overflows.isEmpty());
    }

    private static WorkspaceStateStore store() {
        final Map<String, String> values = new HashMap<String, String>();
        return new WorkspaceStateStore() {
            public String get(String key, String defaultValue) {
                return values.containsKey(key) ? values.get(key) : defaultValue;
            }
            public boolean getBoolean(String key, boolean defaultValue) {
                return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
            }
            public int getInt(String key, int defaultValue) {
                return Integer.parseInt(get(key, String.valueOf(defaultValue)));
            }
            public void put(String key, String value) {
                values.put(key, value);
            }
            public void putBoolean(String key, boolean value) {
                put(key, String.valueOf(value));
            }
            public void putInt(String key, int value) {
                put(key, String.valueOf(value));
            }
        };
    }
}

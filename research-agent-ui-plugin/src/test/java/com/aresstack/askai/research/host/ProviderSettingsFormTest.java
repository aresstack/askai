package com.aresstack.askai.research.host;

import com.aresstack.askai.research.search.config.DataForSeoSettingsDraft;
import com.aresstack.comiccontrols.control.ComicForm;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Every provider's settings share one frame: the same width, the same sections in the same order, no switch. */
public class ProviderSettingsFormTest {

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
    public void allProvidersUseTheSameFrame() {
        ResearchProviderServices services = new ResearchProviderServices();
        List<JComponent> panels = new ArrayList<JComponent>();
        panels.add(new BraveSettingsPanel(services.configurationService()));
        panels.add(new BrightDataSettingsPanel(services.configurationService()));
        panels.add(new SerpApiSettingsPanel(services.configurationService()));
        DataForSeoSettingsDraft draft = DataForSeoSettingsDraft.load(services.configurationService());
        panels.add(new DataForSeoSettingsPanel(draft, services.dataForSeoPlayground(), services.secrets()));
        List<String> order = Arrays.asList("Credentials", "Search options", "Advanced");
        for (JComponent panel : panels) {
            String name = panel.getClass().getSimpleName();
            assertEquals(name + " is as wide as every other provider",
                    ProviderSettingsForm.DIALOG_WIDTH, panel.getPreferredSize().width);
            List<String> sections = sectionTitles(panel);
            assertTrue(name + " starts with Credentials: " + sections, sections.get(0).equals("Credentials"));
            List<String> expected = new ArrayList<String>();
            for (String s : order) {
                if (sections.contains(s)) {
                    expected.add(s);
                }
            }
            assertEquals(name + " keeps the shared section order", expected, sections);
            for (JCheckBox box : all(panel, JCheckBox.class)) {
                assertFalse(name + " has no 'initial search' switch: the provider dropdown decides",
                        box.getText().toLowerCase().contains("initial search"));
            }
            for (JSpinner spinner : all(panel, JSpinner.class)) {
                String text = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().getText();
                assertFalse(name + " shows numbers without grouping: " + text, text.contains(",") || text.contains("."));
            }
        }
        draft.dispose();
    }

    private static List<String> sectionTitles(Container root) {
        List<String> titles = new ArrayList<String>();
        for (ComicForm form : all(root, ComicForm.class)) {
            String title = title(form.getBorder());
            if (title != null) {
                titles.add(title);
            }
        }
        return titles;
    }

    private static String title(Border border) {
        if (border instanceof TitledBorder) {
            return ((TitledBorder) border).getTitle();
        }
        if (border instanceof CompoundBorder) {
            String outer = title(((CompoundBorder) border).getOutsideBorder());
            return outer != null ? outer : title(((CompoundBorder) border).getInsideBorder());
        }
        return null;
    }

    private static <T> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<T>();
        collect(root, type, found);
        return found;
    }

    private static <T> void collect(Container root, Class<T> type, List<T> into) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) {
                into.add(type.cast(c));
            }
            if (c instanceof Container) {
                collect((Container) c, type, into);
            }
        }
    }
}

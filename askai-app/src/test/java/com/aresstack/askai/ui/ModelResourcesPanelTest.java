package com.aresstack.askai.ui;

import com.aresstack.askai.modelport.ModelPortSettings;
import com.aresstack.askai.modelport.ModelPortSettingsRepository;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The model slots of the model resources: the model column is an editable dropdown with an empty entry
 * (inherit) and the catalog's models; a choice is saved, restored, and an emptied slot inherits again.
 */
public class ModelResourcesPanelTest {

    private static final Executor DIRECT = new Executor() {
        public void execute(Runnable command) {
            command.run();
        }
    };
    private static final List<String> CATALOG = Arrays.asList("gemma4:e2b", "llama3.1:8b", "gpt-oss:20b",
            "local/acme/writer:latest");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ModelPortSettingsRepository repository() {
        return new ModelPortSettingsRepository(new File(folder.getRoot(), "model-port.properties"));
    }

    private static ModelResourcesPanel panel(ModelPortSettingsRepository repository, final List<String> catalog) {
        return new ModelResourcesPanel(repository, () -> catalog, DIRECT, DIRECT);
    }

    private static int row(JTable table, String slot) {
        for (int row = 0; row < table.getRowCount(); row++) {
            if (slot.equals(table.getModel().getValueAt(row, 0))) {
                return row;
            }
        }
        throw new AssertionError("no row for slot " + slot);
    }

    /** Opens the dropdown of a slot's model cell, as a click would. */
    @SuppressWarnings("unchecked")
    private static JComboBox<String> openModelEditor(JTable table, String slot) {
        assertTrue(table.editCellAt(row(table, slot), 1));
        return (JComboBox<String>) table.getEditorComponent();
    }

    private static List<String> items(JComboBox<String> combo) {
        List<String> items = new ArrayList<String>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            items.add(combo.getItemAt(i));
        }
        return items;
    }

    private static void choose(JTable table, String slot, String model) {
        JComboBox<String> combo = openModelEditor(table, slot);
        combo.setSelectedItem(model); // picking an entry commits it, as in a table's dropdown
        if (table.isEditing()) {
            assertTrue(table.getCellEditor().stopCellEditing());
        }
        assertEquals(model, table.getModel().getValueAt(row(table, slot), 1));
    }

    private static void onEdt(Runnable test) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                test.run();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        });
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    @Test
    public void theOptionsAreTheEmptyEntryAndTheCatalogNeverAFixedList() {
        assertEquals(Arrays.asList("", "a:1", "b:2"),
                ModelResourcesPanel.slotModelOptions("", Arrays.asList("a:1", "b:2"), null));
        assertEquals("without a catalog only the inherit entry is offered", Collections.singletonList(""),
                ModelResourcesPanel.slotModelOptions(null, Collections.<String>emptyList(), null));
        assertEquals("a saved model that is not installed is kept, no duplicates",
                Arrays.asList("", "a:1", "saved:7b", "typed:3b"),
                ModelResourcesPanel.slotModelOptions("typed:3b", Arrays.asList("a:1", " a:1 "),
                        Arrays.asList("", "saved:7b", "a:1")));
    }

    @Test
    public void anEmptySlotInheritsAlongTheChain() {
        Map<String, String> models = new LinkedHashMap<String, String>();
        models.put("phase.outline.labels", "");
        assertEquals(ModelPortSettings.DEFAULT_MODEL,
                ModelResourcesPanel.inheritedModel("phase.outline.labels", models));
        models.put(ModelPortSettings.DEFAULT_SLOT, "llama3.1:8b");
        assertEquals("the default slot", "llama3.1:8b",
                ModelResourcesPanel.inheritedModel("phase.outline.labels", models));
        models.put("phase.outline", "gpt-oss:20b");
        assertEquals("the parent slot", "gpt-oss:20b",
                ModelResourcesPanel.inheritedModel("phase.outline.labels", models));
        models.put("phase.outline.labels", "gemma4:e2b");
        assertEquals("the slot's own model is not what it inherits", "gpt-oss:20b",
                ModelResourcesPanel.inheritedModel("phase.outline.labels", models));
    }

    @Test
    public void everyRoleOffersTheCatalogInItsModelDropdown() throws Exception {
        final ModelResourcesPanel panel = panel(repository(), CATALOG);
        onEdt(() -> {
            JTable table = panel.slotTable();
            for (String slot : new String[]{"default", "phase.outline", "phase.outline.labels",
                    "phase.outline.briefs", "phase.draft.writer", "judge"}) {
                JComboBox<String> combo = openModelEditor(table, slot);
                assertTrue(slot + " has an editable dropdown", combo.isEditable());
                List<String> expected = new ArrayList<String>();
                expected.add("");
                expected.addAll(CATALOG);
                assertEquals(slot, expected, items(combo));
                assertEquals(slot + " starts inherited", "", combo.getSelectedItem());
                table.getCellEditor().cancelCellEditing();
            }
        });
    }

    @Test
    public void aModelChoiceIsSavedRestoredAndAnEmptiedSlotInheritsAgain() throws Exception {
        final ModelPortSettingsRepository repository = repository();
        final ModelResourcesPanel first = panel(repository, CATALOG);
        onEdt(() -> {
            JTable table = first.slotTable();
            choose(table, "phase.outline", "llama3.1:8b");
            choose(table, "phase.outline.labels", "gemma4:e2b");
            choose(table, "phase.outline.briefs", "local/acme/writer:latest");
            first.save();
        });
        ModelPortSettings saved = repository.load();
        assertEquals("llama3.1:8b", saved.slotModel("phase.outline"));
        assertEquals("gemma4:e2b", saved.slotModel("phase.outline.labels"));
        assertEquals("local/acme/writer:latest", saved.slotModel("phase.outline.briefs"));
        assertEquals("phase.draft keeps no model of its own", "", saved.slotModel("phase.draft"));
        assertEquals("no model default changed", ModelPortSettings.DEFAULT_MODEL, saved.resolveModel("phase.draft"));

        // Restored by a new panel (the next start), even while the catalog does not list the model.
        final ModelResourcesPanel second = panel(repository, Collections.<String>emptyList());
        onEdt(() -> {
            JTable table = second.slotTable();
            assertEquals("llama3.1:8b", table.getModel().getValueAt(row(table, "phase.outline"), 1));
            JComboBox<String> combo = openModelEditor(table, "phase.outline.briefs");
            assertEquals("local/acme/writer:latest", combo.getSelectedItem());
            assertTrue(items(combo).contains("local/acme/writer:latest"));
            table.getCellEditor().cancelCellEditing();
            // A model change, and the inherit entry for the labels.
            choose(table, "phase.outline", "gpt-oss:20b");
            choose(table, "phase.outline.labels", "");
            second.save();
        });
        ModelPortSettings changed = repository.load();
        assertEquals("gpt-oss:20b", changed.slotModel("phase.outline"));
        assertEquals("", changed.slotModel("phase.outline.labels"));
        assertFalse(changed.getSlotModels().containsKey("phase.outline.labels"));
        assertEquals("the emptied slot inherits its parent again", "gpt-oss:20b",
                changed.resolveModel("phase.outline.labels"));
    }

    @Test
    public void aTypedModelNameIsKept() throws Exception {
        final ModelPortSettingsRepository repository = repository();
        final ModelResourcesPanel panel = panel(repository, CATALOG);
        onEdt(() -> {
            JTable table = panel.slotTable();
            JComboBox<String> combo = openModelEditor(table, "judge");
            combo.getEditor().setItem(" qwen-free:4b ");
            assertTrue(table.getCellEditor().stopCellEditing());
            panel.save();
        });
        assertEquals("qwen-free:4b", repository.load().slotModel("judge"));
    }

    @Test
    public void anUnreachableCatalogStillAllowsTheInheritEntry() throws Exception {
        final ModelResourcesPanel panel = new ModelResourcesPanel(repository(), () -> {
            throw new IllegalStateException("Ollama is not running");
        }, DIRECT, DIRECT);
        onEdt(() -> {
            JComboBox<String> combo = openModelEditor(panel.slotTable(), "phase.outline");
            assertEquals(Collections.singletonList(""), items(combo));
            panel.slotTable().getCellEditor().cancelCellEditing();
        });
    }
}

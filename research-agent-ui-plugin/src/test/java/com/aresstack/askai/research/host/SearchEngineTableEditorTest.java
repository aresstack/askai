package com.aresstack.askai.research.host;

import org.junit.Test;

import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The engine table edits the real thing: what the table shows after an edit is what is stored, in the order the
 * search will follow (priority, then name).
 */
public class SearchEngineTableEditorTest {

    private static final String DEFAULTS = "google:on:1:15:0:de+en+fr+es+it:off:,duckduckgo:on:3:0:1:de+en:off:,"
            + "bing:off:3:0:2:de+en+fr+es:off:";

    @Test
    public void aStoredTableRoundTripsUnchanged() throws Exception {
        onEdt(() -> assertEquals(DEFAULTS, new SearchEngineTableEditor(DEFAULTS).get()));
    }

    @Test
    public void anOldListShowsItsOrderAsPriorities() throws Exception {
        onEdt(() -> {
            SearchEngineTableEditor editor = new SearchEngineTableEditor("google:on:1:15,duckduckgo:on:3,bing:off:3");
            assertEquals(DEFAULTS, editor.get());
            TableModel model = editor.table().getModel();
            assertEquals("15", model.getValueAt(0, SearchEngineTableEditor.COL_DELAY));
            assertEquals("de, en", model.getValueAt(1, SearchEngineTableEditor.COL_LANGUAGES));
        });
    }

    @Test
    public void aCommittedPriorityMovesTheRowIntoItsGroup() throws Exception {
        onEdt(() -> {
            SearchEngineTableEditor editor = new SearchEngineTableEditor(DEFAULTS);
            TableModel model = editor.table().getModel();
            model.setValueAt("0", 1, SearchEngineTableEditor.COL_PRIORITY); // DuckDuckGo next to Google
            assertEquals("DuckDuckGo", model.getValueAt(0, SearchEngineTableEditor.COL_ENGINE));
            assertEquals("same priority: by name", "Google", model.getValueAt(1, SearchEngineTableEditor.COL_ENGINE));
            assertEquals(0, editor.table().getSelectedRow());
            assertTrue(editor.get().startsWith("duckduckgo:on:3:0:0:de+en:off:,google:on:1:15:0:"));
        });
    }

    @Test
    public void theSameEngineCanBeAddedAgainWithItsCatalogLanguages() throws Exception {
        onEdt(() -> {
            SearchEngineTableEditor editor = new SearchEngineTableEditor(DEFAULTS);
            editor.addEngine("google");
            assertEquals(4, editor.table().getRowCount());
            assertTrue(editor.get().endsWith(",google:on:3:0:3:de+en+fr+es+it:off:"));
            editor.toggleLanguage(3, "es");
            editor.toggleLanguage(3, "ru");
            assertTrue(editor.get(), editor.get().endsWith(",google:on:3:0:3:de+en+fr+it+ru:off:"));
            editor.removeRow(3);
            assertEquals(DEFAULTS, editor.get());
        });
    }

    @Test
    public void furtherLanguagesAreNormalisedAndRejectedPiecesNamed() throws Exception {
        onEdt(() -> {
            SearchEngineTableEditor editor = new SearchEngineTableEditor(DEFAULTS);
            TableModel model = editor.table().getModel();
            model.setValueAt("PL, uk; polish, pl", 2, SearchEngineTableEditor.COL_FURTHER);
            assertEquals(Boolean.TRUE, model.getValueAt(2, SearchEngineTableEditor.COL_MORE));
            assertTrue(editor.get().endsWith("bing:off:3:0:2:de+en+fr+es:on:pl+uk"));
            assertTrue(editor.statusText(), editor.statusText().contains("polish"));
            model.setValueAt(Boolean.FALSE, 2, SearchEngineTableEditor.COL_MORE);
            assertTrue("switched off, the codes are kept", editor.get().endsWith(":off:pl+uk"));
        });
    }

    @Test
    public void invalidNumbersAreRefusedAndTheOldValueStays() throws Exception {
        onEdt(() -> {
            SearchEngineTableEditor editor = new SearchEngineTableEditor(DEFAULTS);
            TableModel model = editor.table().getModel();
            model.setValueAt("-1", 0, SearchEngineTableEditor.COL_PRIORITY);
            model.setValueAt("schnell", 0, SearchEngineTableEditor.COL_DELAY);
            model.setValueAt("0", 0, SearchEngineTableEditor.COL_PAGES);
            assertEquals(DEFAULTS, editor.get());
            model.setValueAt("2,5", 0, SearchEngineTableEditor.COL_DELAY);
            assertTrue(editor.get().startsWith("google:on:1:2.5:0:"));
        });
    }

    @Test
    public void unknownAndNotImplementedRowsAreKeptAndShownAsSuch() throws Exception {
        onEdt(() -> {
            String stored = "mojeek:on:3:0:0:de:off:,mystery:on:3:0:1:en:off:," + "duckduckgo:on:3:0:2:de:off:";
            SearchEngineTableEditor editor = new SearchEngineTableEditor(stored);
            TableModel model = editor.table().getModel();
            assertEquals("Mojeek  (not implemented)", model.getValueAt(0, SearchEngineTableEditor.COL_ENGINE));
            assertEquals("mystery (unknown)", model.getValueAt(1, SearchEngineTableEditor.COL_ENGINE));
            assertEquals(stored, editor.get());
            assertFalse(model.isCellEditable(0, SearchEngineTableEditor.COL_ENGINE));
        });
    }

    private static void onEdt(Runnable body) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        }
        if (failure[0] != null) {
            throw new RuntimeException(failure[0]);
        }
    }
}

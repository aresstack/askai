package com.aresstack.askai.research.host;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * The arrows move the real thing. A list that reorders on screen while the stored value stays as it was
 * would be worse than no arrows at all — the user would believe they had decided something.
 */
public class SearchEngineOrderEditorTest {

    @Test
    public void movingAnEngineUpChangesTheStoredOrder() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                SearchEngineOrderEditor editor = new SearchEngineOrderEditor("duckduckgo:on,bing:on");
                assertEquals("legacy input round-trips into the paged form (default 3)",
                        "duckduckgo:on:3,bing:on:3,google:off:3", editor.get());

                select(editor, 1);
                move(editor, -1);

                assertEquals("bing:on:3,duckduckgo:on:3,google:off:3", editor.get());
            }
        });
    }

    @Test
    public void anEngineTheStoredValueNeverMentionedAppearsSwitchedOff() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                SearchEngineOrderEditor editor = new SearchEngineOrderEditor("duckduckgo:on");
                assertEquals("gaining an engine is the user's decision, not an update's",
                        "duckduckgo:on:3,google:off:3,bing:off:3", editor.get());
            }
        });
    }

    @Test
    public void theStoredDelayRoundTripsAndAZeroDelayStaysInvisible() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                SearchEngineOrderEditor editor =
                        new SearchEngineOrderEditor("duckduckgo:on:3:1.5,bing:on:3");
                assertEquals("the delay survives the editor; 0 is simply not written",
                        "duckduckgo:on:3:1.5,bing:on:3,google:off:3", editor.get());
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void select(SearchEngineOrderEditor editor, int index) {
        try {
            Method method = SearchEngineOrderEditor.class.getDeclaredMethod("select", int.class);
            method.setAccessible(true);
            method.invoke(editor, index);
        } catch (Exception noSuchSeam) {
            throw new IllegalStateException(noSuchSeam);
        }
    }

    private static void move(SearchEngineOrderEditor editor, int delta) {
        try {
            Method method = SearchEngineOrderEditor.class.getDeclaredMethod("move", int.class);
            method.setAccessible(true);
            method.invoke(editor, delta);
        } catch (Exception noSuchSeam) {
            throw new IllegalStateException(noSuchSeam);
        }
    }

    private static void onEdt(Runnable body) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        SwingUtilities.invokeAndWait(body);
    }
}

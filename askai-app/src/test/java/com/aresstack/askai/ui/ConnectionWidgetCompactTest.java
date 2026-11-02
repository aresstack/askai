package com.aresstack.askai.ui;

import org.junit.Test;

import java.awt.event.MouseEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** In the title bar the connection widget is only the dot; the address lives in the hover popup. */
public class ConnectionWidgetCompactTest {

    @Test
    public void compactShowsOnlyTheDotAndTheClickableAddressLivesInTheHoverPopup() {
        final int[] opened = {0};
        ConnectionWidget widget = new ConnectionWidget(() -> opened[0]++, null);
        // deliberately NO font: the frame calls setCompact before the widget hangs in a window
        widget.setStatus(ConnectionStatus.CONNECTED, "http://127.0.0.1:11434", "version 0.32.3");
        int full = widget.getPreferredSize().width;
        widget.setCompact(true);
        assertTrue("only the dot remains", widget.getPreferredSize().width < full
                && widget.getPreferredSize().width <= 30);
        widget.setSize(widget.getPreferredSize());
        assertNull("no tooltip: the popup carries the address",
                widget.getToolTipText(new MouseEvent(widget, MouseEvent.MOUSE_MOVED, 0L, 0, 5, 5, 0, false)));
        assertTrue(widget.popupAddressForTest(), widget.popupAddressForTest().contains("127.0.0.1:11434"));
        widget.clickPopupAddressForTest();
        assertEquals("clicking the address opens the connection settings", 1, opened[0]);
    }
}

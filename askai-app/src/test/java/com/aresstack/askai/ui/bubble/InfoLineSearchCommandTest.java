package com.aresstack.askai.ui.bubble;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * A "Websuche: <query>" info line is a repeatable action: clicking it offers the ready-made composer
 * command. Only real search breadcrumbs get the menu — other info lines stay plain text.
 */
public class InfoLineSearchCommandTest {

    @Test
    public void aSearchBreadcrumbYieldsItsComposerCommand() {
        assertEquals("/search blashuener",
                BubbleTranscriptPanel.searchCommandOf("Websuche: blashuener"));
        assertEquals("/search Rechtliche Unterschiede Pedelec S-Pedelec E-Bike",
                BubbleTranscriptPanel.searchCommandOf(
                        "  Websuche: Rechtliche Unterschiede Pedelec S-Pedelec E-Bike  "));
    }

    @Test
    public void otherInfoLinesStayPlain() {
        assertNull(BubbleTranscriptPanel.searchCommandOf("⚠ Der Rechercheumfang wurde NICHT aktualisiert"));
        assertNull(BubbleTranscriptPanel.searchCommandOf("Websuche: "));
        assertNull(BubbleTranscriptPanel.searchCommandOf("Websuche läuft…"));
        assertNull(BubbleTranscriptPanel.searchCommandOf(null));
    }
}

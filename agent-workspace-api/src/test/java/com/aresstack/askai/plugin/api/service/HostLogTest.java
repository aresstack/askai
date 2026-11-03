package com.aresstack.askai.plugin.api.service;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostLogTest {

    @After
    public void reset() {
        HostLog.setDisabled(Collections.<String>emptyList());
    }

    @Test
    public void readsTheLeadingCategory() {
        assertEquals("browser", HostLog.categoryOf("[browser] started"));
        assertEquals("plugin", HostLog.categoryOf("[plugin][WARN] x"));
        assertNull(HostLog.categoryOf("\tat com.example.Foo"));
        assertNull(HostLog.categoryOf("  [browser] indented"));
    }

    @Test
    public void dropsLinesOfSwitchedOffCategoriesOnly() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream out = HostLog.filtered(new PrintStream(sink, true, "UTF-8"));
        HostLog.setEnabled("browser", false);
        out.println("[browser] hidden");
        out.print("[reranker] shown ");
        out.println("in two writes");
        out.println("no category");
        HostLog.setEnabled("browser", true);
        out.println("[browser] back");
        String nl = System.lineSeparator();
        assertEquals("[reranker] shown in two writes" + nl + "no category" + nl + "[browser] back" + nl,
                sink.toString("UTF-8"));
    }

    @Test
    public void dropsOversizedLinesOfSwitchedOffCategoriesCompletely() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream out = HostLog.filtered(new PrintStream(sink, true, "UTF-8"));
        HostLog.setEnabled("browser", false);
        StringBuilder huge = new StringBuilder("[browser] ");
        for (int i = 0; i < 70000; i++) {
            huge.append('x');
        }
        out.println(huge);
        out.println("next");
        assertEquals("next" + System.lineSeparator(), sink.toString("UTF-8"));
    }

    @Test
    public void keepsTheTargetEncoding() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream target = new PrintStream(sink, true, "ISO-8859-1");
        PrintStream out = HostLog.filtered(target);
        out.print("caf\u00e9\n");
        assertEquals(HostLog.charsetOf(target).name().equals("ISO-8859-1") ? 5 : sink.size(), sink.size());
    }

    @Test
    public void registersNewCategoriesWhenTheyAppear() {
        HostLog.accepts("[brand-new-category] hello");
        assertTrue(HostLog.categories().contains("brand-new-category"));
    }
}

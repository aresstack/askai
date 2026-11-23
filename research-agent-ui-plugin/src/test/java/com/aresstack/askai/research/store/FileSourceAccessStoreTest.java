package com.aresstack.askai.research.store;

import com.aresstack.askai.research.sources.SourceAccessLog;
import com.aresstack.askai.research.sources.SourceAccessState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FileSourceAccessStoreTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void roundTripsStatesAndNonAsciiUrls() throws Exception {
        File state = folder.newFolder("state");
        FileSourceAccessStore store = new FileSourceAccessStore(state);
        store.save(SourceAccessLog.EMPTY
                .blocked("researchgate.net", "https://www.researchgate.net/publication/Grüße_„Zitat“")
                .resolved("almas-industries.de", "https://almas-industries.de/"));
        SourceAccessLog back = new FileSourceAccessStore(state).load();
        assertEquals(SourceAccessState.BLOCKED, back.stateOf("https://researchgate.net/"));
        assertEquals("https://www.researchgate.net/publication/Grüße_„Zitat“",
                back.lookup("https://researchgate.net/").getUrl());
        assertEquals(SourceAccessState.RESOLVED, back.stateOf("https://www.almas-industries.de/x"));
    }

    @Test
    public void missingOrCorruptFileIsEmpty() throws Exception {
        File state = folder.newFolder("state");
        assertTrue(new FileSourceAccessStore(state).load().isEmpty());
        Files.write(new File(state, "source-access.properties").toPath(),
                "researchgate.net=NOT_A_STATE x\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(new FileSourceAccessStore(state).load().isEmpty());
    }
}

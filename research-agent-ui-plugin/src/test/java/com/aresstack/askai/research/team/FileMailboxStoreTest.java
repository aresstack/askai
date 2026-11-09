package com.aresstack.askai.research.team;

import com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox;
import com.aresstack.askai.research.runtime.team.mailbox.AgentMessage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plan T3: team/&lt;runId&gt;/messages.jsonl survives a restart, keeps umlauts, ignores a torn last line. */
public class FileMailboxStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void undeliveredMessagesSurviveARestartOnDisk() throws Exception {
        File project = tmp.newFolder("project");
        AgentMailbox mailbox = AgentMailbox.open("run-7", FileMailboxStore.forRun(project, "run-7"));
        mailbox.registerWorker("task-1");
        mailbox.registerWorker("task-2");
        mailbox.send("task-1", "worker 2", "Prüfe bitte die Studie über Hitzestress.");

        File journal = new File(project, "team/run-7/messages.jsonl");
        assertTrue(journal.isFile());
        FileOutputStream torn = new FileOutputStream(journal, true);
        torn.write("{\"kind\":\"mess".getBytes(Charset.forName("UTF-8")));
        torn.close();

        FileMailboxStore reopened = FileMailboxStore.forRun(project, "run-7");
        AgentMailbox restarted = AgentMailbox.open("run-7", reopened);
        List<AgentMessage> inbox = restarted.takeInbox("task-2");
        assertEquals(1, inbox.size());
        assertEquals("Prüfe bitte die Studie über Hitzestress.", inbox.get(0).getText());
        // the torn tail was cut before the next append, so the journal stays readable
        assertEquals(0, AgentMailbox.open("run-7", FileMailboxStore.forRun(project, "run-7")).takeInbox("task-2").size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aRunIdMustBeAPlainFolderName() {
        FileMailboxStore.forRun(new File("x"), "../evil");
    }
}

package com.aresstack.askai.research.book;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.sources.SourceUpdate;
import com.aresstack.askai.research.sources.SourceUpdateResult;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.store.FileResearchSourceRepository;
import com.aresstack.askai.research.transition.PhaseTransition;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan PR 14 (D4): the approved source set and the fixed text of a stored source. */
public class SourcesPinTest {

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("askai-sources-pin").toFile();
    }

    private static ResearchSourceRecord source(String id, String text, String checksum, SourceStatus status) {
        return ResearchSourceRecord.builder(id).title(id).url("https://example.org/" + id)
                .fullText(text).checksum(checksum).status(status).revision(1L).build();
    }

    private static PhaseTransition transition(String id, ResearchCommandType command) {
        return new PhaseTransition(id, command, null, null, null, null, "", 0L, "", 4242L, null);
    }

    private static BookManifest.CommitCheck committed(final String id) {
        return new BookManifest.CommitCheck() {
            public boolean isCommitted(String transitionId) {
                return id.equals(transitionId);
            }
        };
    }

    @Test
    public void approveEvidencePinsTheAcceptedSourcesWithTheirChecksums() throws Exception {
        File dir = tempDir();
        FileResearchSourceRepository repository = new FileResearchSourceRepository(new File(dir, "sources"));
        repository.put(source("source-1", "text one", "sha-1", SourceStatus.ACCEPTED));
        repository.put(source("source-2", "text two", "sha-2", SourceStatus.EXCLUDED));
        repository.put(source("source-3", "text three", "sha-3", SourceStatus.ACCEPTED));
        BookManifest manifest = new BookManifest(dir);
        SourcesFreezeHook hook = new SourcesFreezeHook(repository, manifest);

        hook.record(transition("t-start", ResearchCommandType.START_RESEARCH));
        assertTrue("only APPROVE_EVIDENCE writes the pin", manifest.sourcesPins().isEmpty());
        hook.record(transition("t-approve", ResearchCommandType.APPROVE_EVIDENCE));

        SourcesPin pin = new BookManifest(dir).validSourcesPin(committed("t-approve"));
        assertEquals(2, pin.getSources().size());
        assertEquals("source-1", pin.getSources().get(0).getSourceId());
        assertEquals("sha-1", pin.getSources().get(0).getChecksum());
        assertEquals("ACCEPTED", pin.getSources().get(0).getStatus());
        assertEquals("source-3", pin.getSources().get(1).getSourceId());
        assertFalse(pin.contains("source-2"));
        assertEquals(4242L, pin.getApprovedAt());
        assertNull("a pin whose transition never committed is not valid",
                manifest.validSourcesPin(committed("other")));

        // a later change of the repository leaves the pin as it is
        ResearchSourceRecord one = repository.get("source-1");
        repository.update("source-1", one.getRevision(),
                SourceUpdate.from(one).status(SourceStatus.EXCLUDED).build());
        assertTrue(manifest.validSourcesPin(committed("t-approve")).contains("source-1"));
    }

    @Test
    public void aDamagedManifestRefusesTheApproval() throws Exception {
        File dir = tempDir();
        Files.write(new File(dir, BookManifest.FILE_NAME).toPath(), "{broken".getBytes("UTF-8"));
        SourcesFreezeHook hook = new SourcesFreezeHook(
                new FileResearchSourceRepository(new File(dir, "sources")), new BookManifest(dir));
        try {
            hook.record(transition("t", ResearchCommandType.APPROVE_EVIDENCE));
            fail("a failed record refuses the transition");
        } catch (IOException expected) {
            // refused
        }
    }

    @Test
    public void theTextOfAStoredSourceIsFixedAndOnlyAParkedCandidateReceivesItOnce() throws Exception {
        FileResearchSourceRepository repository = new FileResearchSourceRepository(new File(tempDir(), "s"));
        repository.put(source("source-1", "", "", SourceStatus.PARKED));
        repository.put(source("source-1", "page text", "sha-1", SourceStatus.NEW)); // the parked visit
        try {
            repository.put(source("source-1", "changed page", "sha-2", SourceStatus.NEW));
            fail("overwriting the text of a stored source is refused");
        } catch (IOException expected) {
            assertEquals(FileResearchSourceRepository.TEXT_FIXED, expected.getMessage());
        }
        assertEquals("page text", repository.get("source-1").getFullText());
        // the same text with other fields still saves
        repository.put(repository.get("source-1").toBuilder().status(SourceStatus.ACCEPTED).build());
        assertEquals(SourceStatus.ACCEPTED, repository.get("source-1").getStatus());
    }

    @Test
    public void theUpdateGuardRefusesWithItsReasonAndChangesNothing() throws Exception {
        FileResearchSourceRepository repository = new FileResearchSourceRepository(new File(tempDir(), "s"));
        repository.put(source("source-1", "text", "sha", SourceStatus.ACCEPTED));
        repository.setUpdateGuard(new FileResearchSourceRepository.UpdateGuard() {
            public String refusal(ResearchSourceRecord current, SourceUpdate update) {
                return update.getStatus() == SourceStatus.EXCLUDED ? "Reopen Sources to remove an approved source."
                        : null;
            }
        });
        ResearchSourceRecord one = repository.get("source-1");
        SourceUpdateResult refused = repository.update("source-1", one.getRevision(),
                SourceUpdate.from(one).status(SourceStatus.EXCLUDED).build());
        assertFalse(refused.isSuccess());
        assertEquals("Reopen Sources to remove an approved source.", refused.getReason());
        assertEquals(SourceStatus.ACCEPTED, repository.get("source-1").getStatus());
        assertTrue(repository.update("source-1", one.getRevision(),
                SourceUpdate.from(one).comment("note").build()).isSuccess());
    }
}

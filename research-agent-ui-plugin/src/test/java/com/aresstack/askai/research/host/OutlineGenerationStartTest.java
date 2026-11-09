package com.aresstack.askai.research.host;

import com.aresstack.askai.research.book.FrozenConcept;
import com.aresstack.askai.research.store.FileConceptStore;
import com.aresstack.askai.research.outline.FileOutlineStore;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.outline.generate.OutlineGenerationInput;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #152: when the label/brief enrichment cannot run (a mis-declared role), "Generate outline" must refuse
 * the start with the reason instead of reporting "started" and failing in the background.
 */
public class OutlineGenerationStartTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aBrokenEnrichmentRefusesTheStartLoudly() {
        final OutlineWorkspace workspace = new OutlineWorkspace(new FileOutlineStore(folder.getRoot()));
        OutlineClusteringRun run = new OutlineClusteringRun();
        final AtomicBoolean executed = new AtomicBoolean();
        OutlineGeneration generation = new OutlineGeneration(inputs(workspace), null, null,
                new OutlineGenerationInput.Settings(OutlineGenerationInput.Settings.DEFAULT_THRESHOLD,
                        OutlineGenerationInput.Settings.DEFAULT_MARGIN, 3, 2),
                run, new Executor() {
                    public void execute(Runnable command) {
                        executed.set(true);
                    }
                });
        generation.setEnrichment(new OutlineGeneration.Enrichment() {
            public String refusal() {
                return "the chapter label/brief roles are not configured: role outline-labels declares no prompt"
                        + " source";
            }

            public Outline enrich(Outline generated, BooleanSupplier cancelled) {
                throw new AssertionError("must not be reached");
            }
        });

        String refusal = generation.start();

        assertEquals("The outline generation cannot start: the chapter label/brief roles are not configured: "
                + "role outline-labels declares no prompt source", refusal);
        assertFalse("nothing was scheduled", executed.get());
        assertFalse(run.isRunning());
        assertFalse("the job guard was not taken", workspace.jobs().isBusy());
    }

    private static OutlineGeneration.Inputs inputs(final OutlineWorkspace workspace) {
        return new OutlineGeneration.Inputs() {
            public OutlineWorkspace workspace() {
                return workspace;
            }

            public FrozenConcept frozenConcept() {
                return null;
            }

            public FileConceptStore conceptStore() {
                return null;
            }

            public Collection<String> pinnedSourceIds() {
                return Collections.emptyList();
            }

            public Collection<ResearchSourceRecord> sources() {
                return Collections.emptyList();
            }

            public long now() {
                return 1L;
            }
        };
    }
}

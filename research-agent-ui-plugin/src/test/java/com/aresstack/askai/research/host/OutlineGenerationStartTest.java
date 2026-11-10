package com.aresstack.askai.research.host;

import com.aresstack.askai.research.book.FrozenConcept;
import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.outline.synthesis.OutlineSynthesisSettings;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.store.FileOutlineStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
        OutlineGeneration generation = new OutlineGeneration(inputs(workspace), run, new Executor() {
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

            public String bookTitle(FrozenConcept frozen) {
                return "";
            }

            public SharedTopicDiscovery discovery() {
                return null;
            }

            public OutlineGenerationSettings settings() {
                return OutlineGenerationSettings.defaults();
            }

            public MainModelChat synthesisChat(OutlineSynthesisSettings settings) {
                return null;
            }

            public long now() {
                return 1L;
            }
        };
    }
}

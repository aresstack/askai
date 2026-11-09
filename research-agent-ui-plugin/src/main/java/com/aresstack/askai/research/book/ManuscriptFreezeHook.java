package com.aresstack.askai.research.book;

import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.store.FileOutlineStore;
import com.aresstack.askai.research.transition.PhaseTransition;
import com.aresstack.askai.research.transition.PhaseTransitionHook;
import com.aresstack.askai.research.transition.TransitionEffect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plan PR 26: the manuscript on the transition hook (D3, D4).
 * <ul>
 * <li>the last record step of {@code APPROVE_FINAL} pins the sections of the approved outline with their current
 * revisions and model labels; a failure refuses the transition;</li>
 * <li>after {@code APPROVE_OUTLINE} a section whose outline or chapter entry changed becomes stale, a new id
 * pending and a deleted id an orphan ({@link #EFFECT_OUTLINE});</li>
 * <li>after {@code APPROVE_EVIDENCE} a section that cites a source outside the new approved set becomes stale
 * ({@link #EFFECT_SOURCES});</li>
 * <li>{@code REOPEN_OUTLINE} marks nothing.</li>
 * </ul>
 * Register it after {@link OutlineFreezeHook} and {@link SourcesFreezeHook}, so the effects read their pins.
 */
public final class ManuscriptFreezeHook implements PhaseTransitionHook {

    public static final String EFFECT_OUTLINE = "manuscript-stale-outline";
    public static final String EFFECT_SOURCES = "manuscript-stale-sources";

    private final ManuscriptWorkspace manuscript;
    private final FileOutlineStore outlines;
    private final BookManifest manifest;
    private final BookManifest.CommitCheck commits;
    private volatile java.util.concurrent.Callable<com.aresstack.askai.research.assembly.AssembledBook> assembly;

    /** Plan PR 30: the final assembly whose report and cited sources go into the pin. */
    public void setAssembly(java.util.concurrent.Callable<com.aresstack.askai.research.assembly.AssembledBook> value) {
        assembly = value;
    }

    public ManuscriptFreezeHook(ManuscriptWorkspace manuscript, FileOutlineStore outlines, BookManifest manifest,
                                BookManifest.CommitCheck commits) {
        this.manuscript = manuscript;
        this.outlines = outlines;
        this.manifest = manifest;
        this.commits = commits;
    }

    @Override
    public void record(PhaseTransition transition) throws IOException {
        if (transition.getCommand() != ResearchCommandType.APPROVE_FINAL) {
            return;
        }
        try {
            manifest.appendManuscriptPin(pin(transition.getId(), transition.getTimeMillis()));
        } catch (IOException failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw new IOException("The manuscript could not be pinned: " + failed.getMessage(), failed);
        } catch (Exception failed) {
            throw new IOException("The manuscript could not be pinned: " + failed.getMessage(), failed);
        }
    }

    /** The manuscript pin of this moment: the sections of the approved outline in order. */
    ManuscriptPin pin(String transitionId, long now) throws Exception {
        ApprovedOutline approved = ApprovedOutline.read(manifest, outlines, commits);
        List<ManuscriptPin.Entry> entries = new ArrayList<ManuscriptPin.Entry>();
        int outlineRevision = 0;
        if (approved != null) {
            outlineRevision = approved.getRevision();
            for (OutlineChapter chapter : approved.getOutline().getChapters()) {
                for (OutlineSection section : chapter.getSections()) {
                    ManuscriptSection state = manuscript.store().section(section.getId());
                    if (state == null) {
                        entries.add(new ManuscriptPin.Entry(section.getId(), "PENDING", 0, "", ""));
                        continue;
                    }
                    SectionRevision current = state.hasText()
                            ? manuscript.store().revision(section.getId(), state.getRevision()) : null;
                    entries.add(new ManuscriptPin.Entry(section.getId(), state.getStatus().name(),
                            state.getRevision(), current == null ? "" : current.textHash(),
                            current == null ? "" : current.getModelLabel()));
                }
            }
        }
        ManuscriptPin pin = new ManuscriptPin(transitionId, outlineRevision, now, entries);
        java.util.concurrent.Callable<com.aresstack.askai.research.assembly.AssembledBook> source = assembly;
        com.aresstack.askai.research.assembly.AssembledBook book = source == null ? null : source.call();
        if (book == null) {
            return pin;
        }
        java.util.Map<String, String> cited = new java.util.LinkedHashMap<String, String>();
        for (com.aresstack.askai.research.assembly.AssemblySource s : book.getCitations().references()) {
            cited.put(s.getSourceId(), s.getChecksum());
        }
        return pin.withBook(book.getReport().getStatus().label(), book.getReport().toMarkdown(), cited);
    }

    @Override
    public List<String> effects(PhaseTransition transition) {
        if (transition.getCommand() == ResearchCommandType.APPROVE_OUTLINE) {
            return Collections.singletonList(EFFECT_OUTLINE);
        }
        if (transition.getCommand() == ResearchCommandType.APPROVE_EVIDENCE) {
            return Collections.singletonList(EFFECT_SOURCES);
        }
        return Collections.emptyList();
    }

    /** The effect of {@code APPROVE_OUTLINE}: reconcile with the outline that transition pinned. */
    public TransitionEffect outlineEffect() {
        return new TransitionEffect() {
            public void run(PhaseTransition transition) throws Exception {
                OutlinePin pin = manifest.outlinePinOf(transition.getId());
                if (pin == null) {
                    return;
                }
                com.aresstack.askai.research.outline.Outline outline = outlines.approved(pin.getApprovedRevision());
                if (outline == null) {
                    throw new IllegalStateException("The approved outline revision " + pin.getApprovedRevision()
                            + " is missing.");
                }
                manuscript.reconcileOutline(outline, pin.getApprovedRevision(), transition.getTimeMillis());
            }
        };
    }

    /** The effect of {@code APPROVE_EVIDENCE}: stale marks for citations outside the pinned set. */
    public TransitionEffect sourcesEffect() {
        return new TransitionEffect() {
            public void run(PhaseTransition transition) throws Exception {
                SourcesPin pin = manifest.sourcesPinOf(transition.getId());
                if (pin == null) {
                    return;
                }
                Set<String> ids = new HashSet<String>();
                for (SourcesPin.Entry entry : pin.getSources()) {
                    ids.add(entry.getSourceId());
                }
                manuscript.markCitingOutside(ids, transition.getTimeMillis());
            }
        };
    }
}

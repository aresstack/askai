package com.aresstack.askai.research.book;

import com.aresstack.askai.research.concept.ConceptIdentity;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.store.FileConceptStore;
import com.aresstack.askai.research.transition.PhaseTransition;
import com.aresstack.askai.research.transition.TransitionEffect;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR 21 (D4): after a {@code SUBMIT_SCOPE} that follows a reopen, chapters whose concept node was renamed, moved
 * or removed are marked stale, by node id. Both sides come from the working revisions the two Phase-1 records
 * name, so node ids are those of the identity history. Nothing regenerates; a chapter without a concept node and
 * every chapter of an unchanged node stay as they are. The marks are exempt from the write guard and idempotent
 * per transition id (the same reasons write no new revision).
 */
public final class ConceptStaleMarker implements TransitionEffect {

    public static final String EFFECT = "outline-stale-concept";

    private final Phase1Source source;
    private final BookManifest manifest;
    private final OutlineWorkspace workspace;
    private final BookManifest.CommitCheck commits;

    public ConceptStaleMarker(Phase1Source source, BookManifest manifest, OutlineWorkspace workspace,
                              BookManifest.CommitCheck commits) {
        this.source = source;
        this.manifest = manifest;
        this.workspace = workspace;
        this.commits = commits;
    }

    @Override
    public void run(PhaseTransition transition) throws Exception {
        ConceptPin current = manifest.conceptPinOf(transition.getId());
        ConceptPin previous = current == null ? null : manifest.conceptPinBefore(transition.getId(), commits);
        FileConceptStore store = source.conceptStore();
        if (previous == null || store == null) {
            return; // the first submit: nothing was approved downstream yet
        }
        Outline outline = workspace.current();
        if (outline.isEmpty()) {
            return;
        }
        Map<String, Node> before = nodes(store, previous.getWorkingRevision());
        Map<String, Node> after = nodes(store, current.getWorkingRevision());
        if (before == null || after == null) {
            throw new IllegalStateException("The concept revisions " + previous.getWorkingRevision() + " and "
                    + current.getWorkingRevision() + " have no identity history; stale chapters cannot be found.");
        }
        Map<String, String> reasons = new LinkedHashMap<String, String>();
        String revisions = " (concept revision " + previous.getApprovedRevision() + " -> "
                + current.getApprovedRevision() + ")";
        for (OutlineChapter chapter : outline.getChapters()) {
            String nodeId = chapter.getProvenance().getConceptNodeId();
            Node old = before.get(nodeId);
            if (nodeId.isEmpty() || old == null) {
                continue; // not based on a node of the previous concept
            }
            Node now = after.get(nodeId);
            if (now == null) {
                reasons.put(chapter.getId(), "Concept node \"" + old.label + "\" was removed" + revisions);
            } else if (!now.label.equals(old.label)) {
                reasons.put(chapter.getId(), "Concept node \"" + old.label + "\" was renamed to \"" + now.label
                        + "\"" + revisions);
            } else if (!now.parentId.equals(old.parentId)) {
                reasons.put(chapter.getId(), "Concept node \"" + old.label + "\" was moved" + revisions);
            }
        }
        if (!reasons.isEmpty()) {
            workspace.markChaptersStale(reasons, transition.getTimeMillis());
        }
    }

    private static final class Node {
        final String label;
        final String parentId;

        Node(String label, String parentId) {
            this.label = label;
            this.parentId = parentId;
        }
    }

    /** Node id -> label and parent id at a working revision, or {@code null} without identity history. */
    static Map<String, Node> nodes(FileConceptStore store, long workingRevision) {
        String document = store.workingHistoryContent(workingRevision);
        String identityJson = store.identityHistoryContent(workingRevision);
        if (document == null || identityJson == null) {
            return null;
        }
        JsonElement root = JsonParser.parseString(document);
        ConceptIdentity identity = ConceptIdentity.parse(identityJson);
        if (identity == null) {
            return null;
        }
        Map<String, Node> nodes = new HashMap<String, Node>();
        for (String id : identity.allIds()) {
            List<String> path = identity.pathOfId(root, id);
            if (path == null || path.isEmpty()) {
                continue;
            }
            String parent = path.size() > 1 ? identity.idAtPath(root, path.subList(0, path.size() - 1)) : "";
            nodes.put(id, new Node(path.get(path.size() - 1), parent == null ? "" : parent));
        }
        return nodes;
    }
}

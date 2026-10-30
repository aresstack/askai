package com.aresstack.askai.research.concept;

import com.aresstack.askai.research.jsontree.BranchEditRequest;
import com.aresstack.askai.research.jsontree.BranchEditResult;
import com.aresstack.askai.research.jsontree.JsonBranchCompiler;
import com.aresstack.askai.research.jsontree.JsonBranchExporter;
import com.aresstack.askai.research.jsontree.JsonBranchPath;
import com.aresstack.askai.research.jsontree.JsonBranchReplacer;
import com.aresstack.askai.research.jsontree.JsonTreeDiagnostic;
import com.aresstack.askai.research.jsontree.JsonTreeErrorCode;
import com.aresstack.askai.research.jsontree.JsonTreeParseResult;
import com.aresstack.askai.research.jsontree.JsonTreeParser;
import com.aresstack.askai.research.jsontree.StrictJsonParseResult;
import com.aresstack.askai.research.jsontree.StrictJsonParser;
import com.aresstack.askai.research.store.FileConceptStore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The POLICY layer between the (later) concept tools and the neutral jsontree machinery — the
 * one place that makes the Konzeptpapier editable in bites, never regenerated:
 * <ul>
 * <li>works exclusively on the {@code concept} array of the document envelope (title/subtitle,
 *     and later book sections like outline/content/style, are a different tool's business —
 *     each section gets its OWN view and tool contract on the shared JsonTree; this service's
 *     name-chain addressing is the CONCEPT contract and would be wrong for a manuscript of
 *     repeating {@code paragraph} blocks, which must address by array position),</li>
 * <li>addresses nodes by NAME chains and hands out opaque branch HANDLES — the model's JSON
 *     stays free of technical addresses, the host alone maps handle → path + base revision,</li>
 * <li>a depth-limited read yields a READ-ONLY handle: writing back a pruned branch would
 *     silently wipe the pruned grandchildren, so editing requires a full-depth read first,</li>
 * <li>non-destructive by default: a refinement that silently drops existing structural nodes
 *     is rejected as {@link JsonTreeErrorCode#STRUCTURE_LOSS_DETECTED} — a node that merely
 *     MOVED elsewhere within the branch is recognized and allowed; removal is its own
 *     explicit operation,</li>
 * <li>every write runs the full compile-before-swap pipeline and lands in the
 *     {@link FileConceptStore}, whose working revision is the stale-branch guard.</li>
 * </ul>
 * All methods are synchronized — one concept, strictly serialized edits.
 */
public final class ConceptBranchService {

    /** The one property of the envelope this service ever touches. */
    public static final String CONCEPT_PROPERTY = "concept";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final FileConceptStore store;
    private final Map<String, Handle> handles = new HashMap<String, Handle>();
    private long handleCounter;

    // ------------------------------------------------------------------ identity state (V3)
    /** The current identity core — loaded/minted by the staged recovery, advanced per commit. */
    private ConceptIdentity identity;
    /** The confirmed document hash (manifest truth, memory-tracked) for the no-op check. */
    private String confirmedDocumentHash;
    /** Fail-closed: manifest AND backup invalid — the store is write-protected (V3 §7.4). */
    private boolean failClosed;
    private String failClosedReason;
    private final java.util.List<String> pendingDiagnostics = new java.util.ArrayList<String>();
    private DiagnosticSink diagnosticSink;
    private LifecycleListener lifecycleListener;

    /** Technical-log lines from identity recovery/migration (buffered until a sink exists). */
    public interface DiagnosticSink {
        void line(String line);
    }

    /**
     * Fired INSIDE the mutation lock right after a commit that crossed a transient boundary
     * (raw save, restore, degraded restore, legacy graft): the session discards conflicts,
     * plans and UI handles before anything else can observe the new state (V3 §8a).
     */
    public interface LifecycleListener {
        void onTransientBoundary(String reason, String newEpoch);
    }
    /**
     * Observers of COMMITTED changes. The service is the ONE shared instance per session, so a
     * view that subscribes HERE is in sync by construction — no delegation chain (tool → context
     * → resources → session → state listeners) that can silently drop a hop. Listeners only get
     * "something committed"; they re-read via {@link #snapshot()}, never receive the JSON.
     */
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> changeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    public ConceptBranchService(FileConceptStore store) {
        this.store = store;
        ensureIdentity();
    }

    /** Buffered recovery/migration diagnostics flush into the sink as soon as one exists. */
    public synchronized void setDiagnosticSink(DiagnosticSink sink) {
        this.diagnosticSink = sink;
        if (sink != null) {
            for (String line : pendingDiagnostics) {
                sink.line(line);
            }
            pendingDiagnostics.clear();
        }
    }

    public synchronized void setLifecycleListener(LifecycleListener listener) {
        this.lifecycleListener = listener;
    }

    private void diagnostic(String line) {
        if (diagnosticSink != null) {
            diagnosticSink.line(line);
        } else {
            pendingDiagnostics.add(line);
        }
    }

    private void transientBoundary(String reason, String newEpoch) {
        diagnostic("concept identity -> transient boundary (" + reason + ", epoch "
                + newEpoch + ")");
        if (lifecycleListener != null) {
            try {
                lifecycleListener.onTransientBoundary(reason, newEpoch);
            } catch (RuntimeException broken) {
                // the boundary observer must never take the commit down
            }
        }
    }

    // ------------------------------------------------------------------ staged recovery (V3 §7)

    /**
     * Load-or-mint the identity for the store's confirmed state. Stages, never guessing:
     * (1) working files off the manifest → self-heal from the confirmed history pair;
     * (2) manifest-confirmed document without valid identity → mint (migration when the
     * manifest is pre-identity, otherwise an explicit new epoch);
     * (3) manifest corrupt → validated backup manifest;
     * (4) both invalid → fail-closed, write-protected, recovery diagnosis.
     */
    private void ensureIdentity() {
        FileConceptStore.Manifest manifest = store.manifest();
        if (manifest == null) {
            FileConceptStore.Manifest backup = store.backupManifest();
            if (backup != null && backup.revision > 0 && confirmedDocumentOf(backup) != null) {
                store.restoreManifestFromBackup();
                diagnostic("concept identity -> manifest RESTORED FROM BACKUP (rev "
                        + backup.revision + "; the last unconfirmed commit is lost by design)");
                manifest = store.manifest();
            } else {
                failClosed = true;
                failClosedReason = "RECOVERY REQUIRED — manifest and backup are invalid; the "
                        + "concept store is write-protected (repair the files on disk)";
                diagnostic("concept identity -> " + failClosedReason);
                return;
            }
        }
        if (manifest == null) { // backup restore raced/failed to parse — stay safe
            failClosed = true;
            failClosedReason = "RECOVERY REQUIRED — manifest unreadable after backup restore";
            diagnostic("concept identity -> " + failClosedReason);
            return;
        }
        for (Long orphan : store.orphanHistoryRevisions(manifest.revision)) {
            diagnostic("concept identity -> orphan rev " + orphan + " ignored (uncommitted)");
        }
        if (manifest.revision == 0) {
            // Fresh store: identity lives in memory until the first commit persists the pair.
            identity = ConceptIdentity.mintFor(parseOrEmpty(store.effectiveContent()));
            confirmedDocumentHash = null;
            return;
        }
        String confirmedDocument = confirmedDocumentOf(manifest);
        if (confirmedDocument == null) {
            FileConceptStore.Manifest backup = store.backupManifest();
            if (backup != null && backup.revision > 0 && backup.revision != manifest.revision
                    && confirmedDocumentOf(backup) != null) {
                store.restoreManifestFromBackup();
                diagnostic("concept identity -> manifest FELL BACK TO BACKUP (rev "
                        + backup.revision + "): rev " + manifest.revision
                        + " has no confirmable document");
                manifest = store.manifest();
                confirmedDocument = manifest == null ? null : confirmedDocumentOf(manifest);
            }
            if (confirmedDocument == null || manifest == null) {
                failClosed = true;
                failClosedReason = "RECOVERY REQUIRED — no manifest-confirmed document exists; "
                        + "the concept store is write-protected";
                diagnostic("concept identity -> " + failClosedReason);
                return;
            }
        }
        if (!confirmedDocument.equals(store.rawWorkingContent())) {
            store.publishWorking(confirmedDocument, null);
            diagnostic("concept identity -> SELF-HEALED working document from history rev "
                    + manifest.revision);
        }
        confirmedDocumentHash = manifest.documentHash;
        com.google.gson.JsonElement documentRoot = parseOrEmpty(confirmedDocument);
        ConceptIdentity candidate = validIdentity(store.workingIdentityContent(), manifest,
                documentRoot);
        if (candidate == null) {
            String historical = store.identityHistoryContent(manifest.revision);
            candidate = validIdentity(historical, manifest, documentRoot);
            if (candidate != null) {
                store.publishWorking(confirmedDocument, historical);
                diagnostic("concept identity -> SELF-HEALED identity from history rev "
                        + manifest.revision);
            }
        }
        if (candidate != null) {
            identity = candidate;
            return;
        }
        // Mint over the CONFIRMED document only (never over an unconfirmed stand).
        identity = ConceptIdentity.mintFor(documentRoot);
        store.adoptIdentityForCurrentRevision(
                identity.stampedJson(manifest.revision, manifest.documentHash),
                FileConceptStore.sha256(identity.coreJson()), identity.epoch(),
                System.currentTimeMillis());
        diagnostic(manifest.epoch == null
                ? "concept identity -> MIGRATED (epoch " + identity.epoch() + ", rev "
                        + manifest.revision + ")"
                : "concept identity -> NEW EPOCH " + identity.epoch()
                        + " (reason: identity missing or invalid at rev "
                        + manifest.revision + ")");
    }

    /** The manifest-confirmed document text: working file if it hashes right, else history. */
    private String confirmedDocumentOf(FileConceptStore.Manifest manifest) {
        String working = store.rawWorkingContent();
        if (working != null && FileConceptStore.sha256(working).equals(manifest.documentHash)) {
            return working;
        }
        String historical = store.workingHistoryContent(manifest.revision);
        if (historical != null && FileConceptStore.sha256(historical).equals(manifest.documentHash)) {
            return historical;
        }
        return null;
    }

    /** Full identity validation against manifest + confirmed document; null when invalid. */
    private static ConceptIdentity validIdentity(String sidecarJson,
                                                 FileConceptStore.Manifest manifest,
                                                 com.google.gson.JsonElement documentRoot) {
        if (sidecarJson == null) {
            return null;
        }
        ConceptIdentity parsed = ConceptIdentity.parse(sidecarJson);
        if (parsed == null || parsed.stampedRevision() != manifest.revision
                || !manifest.documentHash.equals(parsed.stampedDocumentHash())
                || (manifest.epoch != null && !manifest.epoch.equals(parsed.epoch()))
                || (manifest.identityCoreHash != null
                        && !manifest.identityCoreHash.equals(
                                FileConceptStore.sha256(parsed.coreJson())))
                || !parsed.matchesShape(documentRoot)) {
            return null;
        }
        return parsed;
    }

    private static com.google.gson.JsonElement parseOrEmpty(String documentJson) {
        try {
            return com.google.gson.JsonParser.parseString(documentJson);
        } catch (RuntimeException broken) {
            return com.google.gson.JsonParser.parseString(FileConceptStore.EMPTY_DOCUMENT);
        }
    }

    /** The current identity epoch (never null after construction unless fail-closed). */
    public synchronized String currentEpoch() {
        return identity == null ? null : identity.epoch();
    }

    /** The stable node id of the card at {@code names}, or {@code null}. */
    public synchronized String nodeIdAtPath(List<String> names) {
        return identity == null ? null
                : identity.idAtPath(parseOrEmpty(store.effectiveContent()), names);
    }

    /** The CURRENT name path of the card carrying {@code nodeId}, or {@code null} (gone). */
    public synchronized List<String> pathOfNodeId(String nodeId) {
        return identity == null ? null
                : identity.pathOfId(parseOrEmpty(store.effectiveContent()), nodeId);
    }

    /** All positive concept node ids (novelty window: renames/moves mint none). */
    public synchronized java.util.List<String> allNodeIds() {
        return identity == null ? java.util.Collections.<String>emptyList()
                : identity.allIds();
    }

    /** Fail-closed state (manifest and backup invalid): every write is refused. */
    public synchronized boolean isFailClosed() {
        return failClosed;
    }

    // --------------------------------------------------- ID-based adapters (tree-editor UI)
    //
    // The tree editor's writing gestures carry (epoch, nodeId) — never a render-time path
    // (GPT's tree-editor ruling: an agent commit between render and click must not make an
    // old path hit a DIFFERENT card, and a stale epoch aborts instead of guessing). The path
    // resolves HERE, inside the same synchronized call that executes the operation — id
    // resolution and mutation are one atomic step.

    /**
     * Insert {@code names} (empty leaves, list order kept) into the parent level's FLAT card
     * sequence: before {@code anchorName}'s property inside ITS container (order-preserving
     * container rebuild, the renameNode technique), or at the TRUE flat end ({@code null}
     * anchor = the LAST container; containers stay invisible storage, never order).
     */
    private static void insertCards(JsonArray parentArray, List<String> names,
                                    String anchorName) {
        List<JsonElement> values = new ArrayList<JsonElement>();
        for (int index = 0; index < names.size(); index++) {
            values.add(new JsonArray());
        }
        insertCardValues(parentArray, names, values, anchorName);
    }

    /** The one positional writer: named cards WITH their values land before the anchor's
     *  property (order-preserving container rebuild) or at the TRUE flat end (last container). */
    private static void insertCardValues(JsonArray parentArray, List<String> names,
                                         List<JsonElement> cardValues, String anchorName) {
        if (anchorName == null) {
            JsonObject last = null;
            for (JsonElement element : parentArray) {
                if (element.isJsonObject()) {
                    last = element.getAsJsonObject();
                }
            }
            if (last == null) {
                last = new JsonObject();
                parentArray.add(last);
            }
            for (int index = 0; index < names.size(); index++) {
                last.add(names.get(index), cardValues.get(index));
            }
            return;
        }
        for (JsonElement element : parentArray) {
            if (!element.isJsonObject() || !element.getAsJsonObject().has(anchorName)) {
                continue;
            }
            JsonObject owner = element.getAsJsonObject();
            List<String> keys = new ArrayList<String>();
            List<JsonElement> values = new ArrayList<JsonElement>();
            for (Map.Entry<String, JsonElement> entry : owner.entrySet()) {
                keys.add(entry.getKey());
                values.add(entry.getValue());
            }
            for (String key : keys) {
                owner.remove(key);
            }
            for (int index = 0; index < keys.size(); index++) {
                if (keys.get(index).equals(anchorName)) {
                    for (int card = 0; card < names.size(); card++) {
                        owner.add(names.get(card), cardValues.get(card));
                    }
                }
                owner.add(keys.get(index), values.get(index));
            }
            return;
        }
    }

    /** The parent level's FLAT card-name sequence (containers are storage, never order). */
    private static List<String> flatCardNames(JsonArray parentArray) {
        List<String> names = new ArrayList<String>();
        for (JsonElement element : parentArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    names.add(entry.getKey());
                }
            }
        }
        return names;
    }

    /** Resolve an (epoch, nodeId) reference to the CURRENT path, or set a refusal. */
    private List<String> resolveById(String epoch, String nodeId) {
        idResolveError = null;
        if (failClosed) {
            idResolveError = failClosedError().getDiagnostic();
            return null;
        }
        if (identity == null || epoch == null || !epoch.equals(identity.epoch())) {
            idResolveError = JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "The document state changed (identity epoch differs) — the action was "
                            + "aborted; nothing was modified. Reload the view and retry.")
                    .build();
            return null;
        }
        List<String> path = identity.pathOfId(parseOrEmpty(store.effectiveContent()), nodeId);
        if (path == null) {
            idResolveError = JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                    "The card no longer exists — nothing was modified.").build();
        }
        return path;
    }

    /**
     * The RATIFIED positional leaf move (slice B, UI path): everything resolves by
     * (epoch, nodeId) under THIS lock, and {@code insertBeforeNodeId} targets the POST-IMAGE —
     * the source leaves the sibling sequence first, then the anchor is located in the
     * remaining sequence and the source lands immediately before it ({@code null} = the true
     * flat end). Same-parent reorders are legal single-revision moves; the ratified NO_CHANGE
     * cases (source==anchor, already directly before the anchor, already last with a null
     * anchor) commit nothing. {@code targetParent == source} is INVALID_TARGET. The MODEL path
     * ({@link #moveLeaf}) deliberately keeps its accepted PRESENCE semantics — a spoken
     * "verschiebe X unter Y" names no position, so "already under Y" stays ALREADY_AT_TARGET.
     */
    public synchronized MoveLeafResult moveLeafById(String epoch, String sourceNodeId,
                                                    String targetParentNodeId,
                                                    String insertBeforeNodeId) {
        List<String> sourcePath = resolveById(epoch, sourceNodeId);
        if (sourcePath == null) {
            return moveError(idResolveError);
        }
        if (sourceNodeId.equals(targetParentNodeId)) {
            return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "INVALID_TARGET — a card cannot become its own parent; nothing was "
                            + "moved.").build());
        }
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return moveError(parsed.getDiagnostic());
        }
        String label = sourcePath.get(sourcePath.size() - 1);
        if (!isLeafShape(parsed.getElement(), sourcePath)) {
            return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "SOURCE_NOT_LEAF \"" + label + "\" — only a LEAF moves with this tool; "
                            + "branches stay manual editor work.").build());
        }
        List<String> targetPath;
        if (targetParentNodeId == null) {
            targetPath = java.util.Collections.emptyList();
        } else {
            targetPath = identity.pathOfId(parsed.getElement(), targetParentNodeId);
            if (targetPath == null) {
                return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "TARGET_PARENT_NOT_FOUND — the target parent no longer exists; "
                                + "nothing was moved.").build());
            }
        }
        String anchorName = null;
        if (insertBeforeNodeId != null) {
            if (insertBeforeNodeId.equals(sourceNodeId)) {
                // Ratified: moving before yourself is the position you already occupy.
                return new MoveLeafResult(true, true, store.workingRevision(), label,
                        new ArrayList<String>(sourcePath), new ArrayList<String>(sourcePath),
                        sourceNodeId, null);
            }
            List<String> anchorPath =
                    identity.pathOfId(parsed.getElement(), insertBeforeNodeId);
            if (anchorPath == null) {
                return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "INSERT_ANCHOR_NOT_FOUND — the neighbour card no longer exists; "
                                + "nothing was moved.").build());
            }
            if (!anchorPath.subList(0, anchorPath.size() - 1).equals(targetPath)) {
                return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                        "ANCHOR_NOT_IN_TARGET — the neighbour card is not a direct child of "
                                + "the target parent anymore; nothing was moved.").build());
            }
            anchorName = anchorPath.get(anchorPath.size() - 1);
        }
        List<String> currentParent = sourcePath.subList(0, sourcePath.size() - 1);
        boolean sameParent = currentParent.equals(targetPath);
        Resolution sourceResolution = resolve(parsed.getElement(), sourcePath);
        if (sourceResolution.diagnostic != null) {
            return moveError(sourceResolution.diagnostic);
        }
        JsonElement candidate = parsed.getElement().deepCopy();
        JsonArray targetArray;
        if (targetPath.isEmpty()) {
            targetArray = candidate.getAsJsonObject().get(CONCEPT_PROPERTY).getAsJsonArray();
        } else {
            Resolution targetResolution = resolve(parsed.getElement(), targetPath);
            if (targetResolution.diagnostic != null) {
                return moveError(targetResolution.diagnostic);
            }
            targetArray = arrayAt(candidate, targetResolution.path);
            if (targetArray == null) {
                return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "TARGET_PARENT_NOT_FOUND " + targetPath + ".").build());
            }
        }
        if (sameParent) {
            // The ratified NO_CHANGE pins, judged on the POST-IMAGE outcome.
            List<String> sequence = flatCardNames(targetArray);
            int at = sequence.indexOf(label);
            boolean unchanged = anchorName == null
                    ? at == sequence.size() - 1
                    : at >= 0 && at + 1 < sequence.size()
                            && sequence.get(at + 1).equals(anchorName);
            if (unchanged) {
                return new MoveLeafResult(true, true, store.workingRevision(), label,
                        new ArrayList<String>(sourcePath), new ArrayList<String>(sourcePath),
                        sourceNodeId, null);
            }
        } else {
            for (JsonElement element : targetArray) {
                if (element.isJsonObject() && element.getAsJsonObject().has(label)) {
                    return moveError(JsonTreeDiagnostic.of(
                            JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                            "TARGET_NAME_COLLISION \"" + label + "\" — a different card "
                                    + "with this name already sits at the target; nothing "
                                    + "was merged or overwritten.").build());
                }
            }
        }
        // POST-IMAGE algorithm: detach first, then place before the anchor in what remains.
        JsonElement leafValue = valueAt(candidate, sourceResolution.path);
        JsonTreeDiagnostic removal = removeAt(candidate, sourceResolution.path);
        if (removal != null) {
            return moveError(removal);
        }
        insertCardValues(targetArray, java.util.Collections.singletonList(label),
                java.util.Collections.singletonList(
                        leafValue == null ? (JsonElement) new JsonArray() : leafValue),
                anchorName);
        List<String> newPath = new ArrayList<String>(targetPath);
        newPath.add(label);
        ConceptIdentity identityAfter = identity.afterMoveLeaf(parsed.getElement(), sourcePath,
                candidate, newPath);
        EditResult committed = commitCandidate(candidate, identityAfter, null);
        if (!committed.isApplied()) {
            return moveError(committed.getDiagnostic());
        }
        return new MoveLeafResult(true, false, committed.getNewRevision(), label,
                new ArrayList<String>(sourcePath), newPath, sourceNodeId, null);
    }

    private JsonTreeDiagnostic idResolveError;

    public synchronized EditResult renameNodeById(String epoch, String nodeId, String newName) {
        List<String> path = resolveById(epoch, nodeId);
        return path == null ? editError(idResolveError) : renameNode(path, newName);
    }

    /** The guarded delete (leaf/terminal) by identity. */
    public synchronized EditResult deleteTerminalBranchById(String epoch, String nodeId) {
        List<String> path = resolveById(epoch, nodeId);
        return path == null ? editError(idResolveError) : deleteTerminalBranch(path);
    }

    /** The DEEP removal by identity — the confirmed tree-editor branch delete. */
    public synchronized EditResult removeNodeById(String epoch, String nodeId) {
        List<String> path = resolveById(epoch, nodeId);
        return path == null ? editError(idResolveError) : removeNodeAt(path);
    }

    /** The atomic list add under an identity ({@code parentNodeId == null} = the top level). */
    public synchronized AddCardsResult addCardsUnderId(String epoch, String parentNodeId,
                                                       List<String> names) {
        return addCardsUnderId(epoch, parentNodeId, names, null);
    }

    /** As above with the ratified {@code insertBeforeNodeId} position (null = flat end). */
    public synchronized AddCardsResult addCardsUnderId(String epoch, String parentNodeId,
                                                       List<String> names,
                                                       String insertBeforeNodeId) {
        List<String> parentPath;
        if (parentNodeId == null) {
            // Root has no node id; the epoch check alone guards the state.
            if (failClosed || identity == null || epoch == null
                    || !epoch.equals(identity.epoch())) {
                resolveById(epoch, "-"); // sets the honest refusal
                return new AddCardsResult(false, -1L,
                        java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList(), null, idResolveError);
            }
            parentPath = java.util.Collections.emptyList();
        } else {
            parentPath = resolveById(epoch, parentNodeId);
            if (parentPath == null) {
                return new AddCardsResult(false, -1L,
                        java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList(), null, idResolveError);
            }
        }
        return addCards(parentPath, names, insertBeforeNodeId);
    }

    private EditResult failClosedError() {
        return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                failClosedReason == null ? "RECOVERY REQUIRED" : failClosedReason).build());
    }

    /** Subscribe to committed changes (addIfAbsent — re-registering on re-show is safe). */
    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            changeListeners.addIfAbsent(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    /** Every APPLIED edit funnels through here; a broken observer never breaks the commit. */
    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException broken) {
                // observers must never take the edit down
            }
        }
    }

    // ------------------------------------------------------------------ results

    /** Outcome of a read: branch JSON plus orientation metadata, or a diagnostic. */
    public static final class ReadResult {
        private final String handleId;
        private final long workingRevision;
        private final String branchJson;
        private final String parentName;
        private final List<String> siblingNames;
        private final boolean editable;
        private final JsonTreeDiagnostic diagnostic;

        private ReadResult(String handleId, long workingRevision, String branchJson,
                String parentName, List<String> siblingNames, boolean editable,
                JsonTreeDiagnostic diagnostic) {
            this.handleId = handleId;
            this.workingRevision = workingRevision;
            this.branchJson = branchJson;
            this.parentName = parentName;
            this.siblingNames = siblingNames;
            this.editable = editable;
            this.diagnostic = diagnostic;
        }

        public boolean isOk() {
            return diagnostic == null;
        }

        public String getHandleId() {
            return handleId;
        }

        public long getWorkingRevision() {
            return workingRevision;
        }

        public String getBranchJson() {
            return branchJson;
        }

        /** The enclosing concept node's name, or {@code null} for a top-level read. */
        public String getParentName() {
            return parentName;
        }

        /** Structural neighbours around the target — enough to avoid duplicate concepts. */
        public List<String> getSiblingNames() {
            return siblingNames;
        }

        /** {@code false} for depth-limited reads: orientation only, never a write base. */
        public boolean isEditable() {
            return editable;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    /** Outcome of an update/remove: the new working revision, or a diagnostic. */
    public static final class EditResult {
        private final boolean applied;
        private final long newRevision;
        private final JsonTreeDiagnostic diagnostic;

        private EditResult(boolean applied, long newRevision, JsonTreeDiagnostic diagnostic) {
            this.applied = applied;
            this.newRevision = newRevision;
            this.diagnostic = diagnostic;
        }

        public boolean isApplied() {
            return applied;
        }

        public long getNewRevision() {
            return newRevision;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    private static final class Handle {
        final JsonBranchPath path;
        final long baseRevision;
        final boolean editable;

        Handle(JsonBranchPath path, long baseRevision, boolean editable) {
            this.path = path;
            this.baseRevision = baseRevision;
            this.editable = editable;
        }
    }

    // ------------------------------------------------------------------ read

    /** One ATOMIC (document, revision) pair — so every view renders ONE consistent snapshot. */
    public static final class DocumentSnapshot {
        private final String documentJson;
        private final long workingRevision;
        private final String recoveryNotice;

        DocumentSnapshot(String documentJson, long workingRevision) {
            this(documentJson, workingRevision, null);
        }

        DocumentSnapshot(String documentJson, long workingRevision, String recoveryNotice) {
            this.documentJson = documentJson;
            this.workingRevision = workingRevision;
            this.recoveryNotice = recoveryNotice;
        }

        public String getDocumentJson() {
            return documentJson;
        }

        public long getWorkingRevision() {
            return workingRevision;
        }

        /** Non-null in fail-closed recovery: the document is an UNCONFIRMED preview. */
        public String getRecoveryNotice() {
            return recoveryNotice;
        }
    }

    /**
     * The current document with its revision, read atomically. The UI renders EVERYTHING (mindmap,
     * JSON view, revision label) from one snapshot — never mixing revision N's tree with N+1's text.
     */
    public synchronized DocumentSnapshot snapshot() {
        return new DocumentSnapshot(store.effectiveContent(), store.workingRevision(),
                failClosed ? failClosedReason : null);
    }

    /**
     * Read the whole working surface ({@code names} empty) or one branch addressed by its name
     * chain. {@code depth <= 0} reads the full subtree and yields an EDITABLE handle;
     * {@code depth >= 1} prunes structural children below that depth to {@code []} and yields a
     * read-only orientation handle.
     */
    public synchronized ReadResult readBranch(List<String> names, int depth) {
        if (failClosed) {
            // The unconfirmed preview must never GROUND model work (V3 ratification note).
            return error(JsonTreeDiagnostic.of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                    failClosedReason == null ? "RECOVERY REQUIRED" : failClosedReason).build());
        }
        pruneExpiredHandles();
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return error(parsed.getDiagnostic());
        }
        Resolution resolution = resolve(parsed.getElement(), names);
        if (resolution.diagnostic != null) {
            return error(resolution.diagnostic);
        }
        JsonBranchExporter.Result export =
                JsonBranchExporter.exportBranch(document, resolution.path);
        if (!export.isOk()) {
            return error(export.getDiagnostic());
        }
        String branchJson = export.getBranchJson();
        boolean editable = depth <= 0;
        if (!editable) {
            branchJson = pruneToDepth(branchJson, depth);
        }
        String handleId = "b-" + Long.toHexString(++handleCounter);
        handles.put(handleId, new Handle(resolution.path, store.workingRevision(), editable));
        return new ReadResult(handleId, store.workingRevision(), branchJson,
                resolution.parentName, resolution.siblingNames, editable, null);
    }

    // ------------------------------------------------------------------ manual whole-document edit

    /**
     * Replace the WHOLE working document — the user's raw-JSON edit in the concept tab (the
     * Zielbild raw mode). The same discipline as every other write: STRICT parse (a diagnostic
     * names line/column, never creative repair), envelope check ({@code concept} must be an
     * ARRAY), compare-and-swap against {@code expectedRevision} (a concurrent agent edit wins,
     * the user reloads), then the text is Gson-pretty-printed and committed atomically. Invalid
     * input never reaches the store.
     */
    public synchronized EditResult replaceDocument(String documentJson, long expectedRevision) {
        if (failClosed) {
            return failClosedError();
        }
        long currentRevision = store.workingRevision();
        if (expectedRevision != currentRevision) {
            return editError(stale(expectedRevision, currentRevision));
        }
        StrictJsonParseResult parsed = StrictJsonParser.parse(
                documentJson == null ? "" : documentJson);
        if (!parsed.isOk()) {
            return editError(parsed.getDiagnostic());
        }
        com.google.gson.JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(documentJson);
        } catch (RuntimeException impossible) {
            // The strict parse above accepted it; Gson is more lenient — this cannot happen.
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.JSON_SYNTAX_ERROR,
                    "The document could not be re-read: " + impossible.getMessage()).build());
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has(CONCEPT_PROPERTY)
                || !root.getAsJsonObject().get(CONCEPT_PROPERTY).isJsonArray()) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                    "The document envelope needs a \"" + CONCEPT_PROPERTY + "\" ARRAY at the "
                            + "top level ({\"title\":\"\",\"subtitle\":\"\",\"concept\":[...]}).")
                    .hint("Keep the envelope and edit inside the concept array.")
                    .build());
        }
        String pretty = new com.google.gson.GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(root);
        // V3 §8: a dirty raw save ALWAYS opens a new identity epoch — fresh UUIDs for every
        // node, never a no-op (even when the user typed their way back to the same JSON), and
        // the transient boundary fires before anything can observe the new state.
        ConceptIdentity fresh = ConceptIdentity.mintFor(root);
        String documentHash = FileConceptStore.sha256(pretty);
        long newRevision = store.commitPair(pretty,
                fresh.stampedJson(store.workingRevision() + 1, documentHash),
                FileConceptStore.sha256(fresh.coreJson()), fresh.epoch(),
                System.currentTimeMillis());
        identity = fresh;
        confirmedDocumentHash = documentHash;
        transientBoundary("raw-save", fresh.epoch());
        notifyChanged();
        return new EditResult(true, newRevision, null);
    }

    /**
     * Restore an earlier working revision as the NEW head (V3 §5) — the concept editor's
     * ◀▶-browse Save on an UNCHANGED historical text. The historical document/identity pair is
     * validated against each other, the identity CORE (epoch, UUIDs, tree) is taken over, and
     * the pair is re-stamped as revision N+1 — a historical epoch can become current again.
     * The historical files stay byte-identical. A pre-identity revision degrades explicitly to
     * raw-save semantics (fresh epoch), never to silent reconstruction.
     */
    public synchronized EditResult restoreRevision(long revision) {
        if (failClosed) {
            return failClosedError();
        }
        String document = store.workingHistoryContent(revision);
        if (document == null) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                    "No stored history for working revision " + revision + ".").build());
        }
        String documentHash = FileConceptStore.sha256(document);
        String identityJson = store.identityHistoryContent(revision);
        JsonElement root = parseOrEmpty(document);
        if (identityJson == null) {
            ConceptIdentity fresh = ConceptIdentity.mintFor(root);
            long newRevision = store.commitPair(document,
                    fresh.stampedJson(store.workingRevision() + 1, documentHash),
                    FileConceptStore.sha256(fresh.coreJson()), fresh.epoch(),
                    System.currentTimeMillis());
            identity = fresh;
            confirmedDocumentHash = documentHash;
            diagnostic("concept identity -> restore rev " + revision
                    + " DEGRADED to a fresh epoch (pre-identity revision)");
            transientBoundary("restore-degraded", fresh.epoch());
            notifyChanged();
            return new EditResult(true, newRevision, null);
        }
        ConceptIdentity restored = ConceptIdentity.parse(identityJson);
        if (restored == null || restored.stampedRevision() != revision
                || !documentHash.equals(restored.stampedDocumentHash())
                || !restored.matchesShape(root)) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                    "The historical document/identity pair of revision " + revision
                            + " does not validate — restore refused.").build());
        }
        if (identity != null && documentHash.equals(confirmedDocumentHash)
                && restored.epoch().equals(identity.epoch())
                && restored.coreJson().equals(identity.coreJson())) {
            return new EditResult(true, store.workingRevision(), null); // restoring the head
        }
        long newRevision = store.commitPair(document,
                restored.stampedJson(store.workingRevision() + 1, documentHash),
                FileConceptStore.sha256(restored.coreJson()), restored.epoch(),
                System.currentTimeMillis());
        identity = restored;
        confirmedDocumentHash = documentHash;
        diagnostic("concept identity -> RESTORED rev " + revision + " as head rev "
                + newRevision + " (epoch " + restored.epoch() + ")");
        transientBoundary("restore", restored.epoch());
        notifyChanged();
        return new EditResult(true, newRevision, null);
    }

    /** The document at an earlier WORKING revision, or {@code null} (pre-history commits). */
    public synchronized String workingHistoryContent(long revision) {
        return store.workingHistoryContent(revision);
    }

    // ------------------------------------------------------------------ update

    /** Non-destructive refinement — the default the (later) concept_update tool uses. */
    public synchronized EditResult updateBranch(String handleId, String branchJson) {
        return updateBranch(handleId, branchJson, false);
    }

    public synchronized EditResult updateBranch(String handleId, String branchJson,
            boolean allowRemovals) {
        Handle handle = handles.get(handleId);
        if (handle == null) {
            return editError(unknownHandle(handleId));
        }
        if (!handle.editable) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "This branch handle came from a DEPTH-LIMITED read and is read-only — "
                            + "writing a pruned branch back would silently delete the pruned "
                            + "children.")
                    .hint("Read the branch again without a depth limit, then edit that "
                            + "full branch.")
                    .build());
        }
        String document = store.effectiveContent();
        long currentRevision = store.workingRevision();
        if (handle.baseRevision != currentRevision) {
            return editError(stale(handle.baseRevision, currentRevision));
        }
        if (!allowRemovals) {
            JsonTreeDiagnostic loss = detectStructureLoss(document, handle.path, branchJson);
            if (loss != null) {
                return editError(loss);
            }
        }
        BranchEditResult result = JsonBranchReplacer.apply(document, currentRevision,
                new BranchEditRequest(handle.baseRevision, handle.path, branchJson));
        if (!result.isCommitted()) {
            return editError(result.getDiagnostic());
        }
        if (failClosed) {
            return failClosedError();
        }
        String documentHash = FileConceptStore.sha256(result.getDocumentJson());
        if (documentHash.equals(confirmedDocumentHash)) {
            return new EditResult(true, currentRevision, null); // no-op graft, identity kept
        }
        // LEGACY graft: an arbitrary structural rewrite cannot be mapped onto stable identity
        // per-operation, and V3 forbids heuristic reuse — so a real graft cuts a NEW epoch.
        // Not model-reachable (no concept_update tool); only old tests and future host paths.
        ConceptIdentity fresh = ConceptIdentity.mintFor(parseOrEmpty(result.getDocumentJson()));
        long newRevision = store.commitPair(result.getDocumentJson(),
                fresh.stampedJson(store.workingRevision() + 1, documentHash),
                FileConceptStore.sha256(fresh.coreJson()), fresh.epoch(),
                System.currentTimeMillis());
        identity = fresh;
        confirmedDocumentHash = documentHash;
        transientBoundary("legacy-graft", fresh.epoch());
        notifyChanged();
        return new EditResult(true, newRevision, null);
    }

    // ------------------------------------------------------------------ small-model facade (K2c)
    //
    // The MODEL-facing contract is deliberately dumb (the MainframeMate lesson: small models use
    // tools reliably when the contract is tiny, concrete and example-backed): one atomic
    // operation per call, addressed by a human-readable name path — NO handles, NO revisions,
    // NO full-branch replacement. All transactional machinery (strict parse, candidate
    // validation, atomic commit, revision bump) still runs underneath on every call.

    /** Add one new EMPTY card under the parent path (empty parent = the concept root). */
    public synchronized EditResult addNode(List<String> parentNames, String name) {
        if (failClosed) {
            return failClosedError();
        }
        String cardName = name == null ? "" : name.trim();
        if (cardName.isEmpty()) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "A new concept card needs a non-empty name.").build());
        }
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return editError(parsed.getDiagnostic());
        }
        Resolution resolution = resolve(parsed.getElement(), parentNames);
        if (resolution.diagnostic != null) {
            return editError(resolution.diagnostic);
        }
        JsonElement candidate = parsed.getElement().deepCopy();
        JsonArray parentArray = arrayAt(candidate, resolution.path);
        if (parentArray == null) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                    "The parent no longer exists.").path(resolution.path.describe()).build());
        }
        // Duplicate guard across ALL containers of the parent: the SAME card twice is always a
        // model mistake — the diagnostic names the conflict instead of silently stacking it.
        for (JsonElement element : parentArray) {
            if (element.isJsonObject() && element.getAsJsonObject().has(cardName)) {
                return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                        "A card named \"" + cardName + "\" already exists here.")
                        .path(resolution.path.describe())
                        .hint("Read the concept (concept_read) to see the existing cards, or "
                                + "choose a different name.")
                        .build());
            }
        }
        // Append: join the first container object (the concept's compact grouping style), or
        // open the first container if the array has none yet.
        JsonObject container = null;
        for (JsonElement element : parentArray) {
            if (element.isJsonObject()) {
                container = element.getAsJsonObject();
                break;
            }
        }
        if (container == null) {
            container = new JsonObject();
            parentArray.add(container);
        }
        container.add(cardName, new JsonArray());
        List<String> cardPath = new ArrayList<String>(
                parentNames == null ? java.util.Collections.<String>emptyList() : parentNames);
        cardPath.add(cardName);
        return commitCandidate(candidate, identity.afterAdd(candidate, cardPath), null);
    }

    /** Outcome of the atomic list add: what was added, what already sat there, one revision. */
    public static final class AddCardsResult {
        private final boolean applied;
        private final long newRevision;
        private final List<String> added;
        private final List<String> alreadyPresent;
        private final String createdParent;
        private final JsonTreeDiagnostic diagnostic;

        private AddCardsResult(boolean applied, long newRevision, List<String> added,
                               List<String> alreadyPresent, String createdParent,
                               JsonTreeDiagnostic diagnostic) {
            this.applied = applied;
            this.newRevision = newRevision;
            this.added = added;
            this.alreadyPresent = alreadyPresent;
            this.createdParent = createdParent;
            this.diagnostic = diagnostic;
        }

        /** The ONE missing terminal parent created with its cards, or {@code null}. */
        public String getCreatedParent() {
            return createdParent;
        }

        public boolean isApplied() {
            return applied;
        }

        public long getNewRevision() {
            return newRevision;
        }

        public List<String> getAdded() {
            return added;
        }

        public List<String> getAlreadyPresent() {
            return alreadyPresent;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    /**
     * The ATOMIC list add (add_cards slice): every genuinely new card of {@code names} appears
     * under the ONE resolved parent in a single commit — all together or none at all. In-list
     * duplicates collapse (order kept), existing siblings come back as ALREADY_PRESENT
     * (idempotent: repeating the same list is a no-op without a revision bump), a missing or
     * unresolvable parent rejects the WHOLE operation without any mutation. Multi-word names
     * ("Computer Science") are single names by construction — the list is typed, the host
     * never splits on spaces. Each new card mints one UUID in the shared identity candidate.
     */
    public synchronized AddCardsResult addCards(List<String> parentNames, List<String> names) {
        return addCards(parentNames, names, null);
    }

    /**
     * As {@link #addCards(List, List)} with the RATIFIED position semantics:
     * {@code insertBeforeNodeId} is the stable neighbour reference — the new cards land
     * immediately BEFORE that card in the parent's FLAT card sequence (containers are storage,
     * never order); {@code null} appends at the TRUE flat end (deliberate correction: appends
     * used to land at the end of the FIRST container, which is not the flat end on
     * multi-container levels). The anchor resolves under THIS lock and must be a direct child
     * of the target parent — otherwise the WHOLE call rejects without mutation.
     */
    public synchronized AddCardsResult addCards(List<String> parentNames, List<String> names,
                                                String insertBeforeNodeId) {
        if (failClosed) {
            EditResult refused = failClosedError();
            return new AddCardsResult(false, -1L, java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList(), null, refused.getDiagnostic());
        }
        java.util.LinkedHashSet<String> cleaned = new java.util.LinkedHashSet<String>();
        for (String name : names == null ? java.util.Collections.<String>emptyList() : names) {
            String card = name == null ? "" : name.trim();
            if (!card.isEmpty()) {
                cleaned.add(card);
            }
        }
        if (cleaned.isEmpty()) {
            return new AddCardsResult(false, -1L, java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList(), null,
                    JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                            "add_cards needs at least one non-empty card name.").build());
        }
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return new AddCardsResult(false, -1L, java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList(), null, parsed.getDiagnostic());
        }
        // Parent semantics (add_cards gate corrections): a full path resolves as always; a
        // SINGLE segment additionally resolves as a globally UNIQUE exact card name (the small
        // model addresses "Architektur", not a path chain); and the ONE missing TERMINAL
        // parent — explicitly asked for or plausibly derived, like the book's core topic on
        // the first turn — is created together with its cards in the SAME atomic revision.
        // Deeper missing chains and ambiguous names reject the WHOLE call without mutation.
        JsonElement candidate = parsed.getElement().deepCopy();
        List<String> trimmedParent = new ArrayList<String>();
        for (String segment : parentNames == null
                ? java.util.Collections.<String>emptyList() : parentNames) {
            String value = segment == null ? "" : segment.trim();
            if (!value.isEmpty()) {
                trimmedParent.add(value);
            }
        }
        List<String> effectiveParent = trimmedParent;
        String createdParent = null;
        JsonArray parentArray = null;
        Resolution resolution = resolve(parsed.getElement(), effectiveParent);
        if (resolution.diagnostic == null) {
            parentArray = arrayAt(candidate, resolution.path);
        } else if (effectiveParent.size() == 1) {
            String shortName = effectiveParent.get(0);
            List<List<String>> matches = new ArrayList<List<String>>();
            for (List<String> path : ConceptTopicScanner.collectCardPaths(document)) {
                if (path.get(path.size() - 1).equals(shortName)) {
                    matches.add(path);
                }
            }
            if (matches.size() > 1) {
                StringBuilder candidates = new StringBuilder();
                for (List<String> match : matches) {
                    candidates.append(candidates.length() > 0 ? ", " : "").append(match);
                }
                return new AddCardsResult(false, -1L,
                        java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList(), null,
                        JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                                "AMBIGUOUS_PARENT \"" + shortName + "\" — candidates: "
                                        + candidates + ".")
                                .hint("Use the full path to the intended parent.")
                                .build());
            }
            if (matches.size() == 1) {
                effectiveParent = matches.get(0);
                Resolution unique = resolve(parsed.getElement(), effectiveParent);
                parentArray = unique.diagnostic == null
                        ? arrayAt(candidate, unique.path) : null;
            } else {
                // No such card anywhere: create the ONE missing terminal parent at the root.
                JsonArray root = candidate.getAsJsonObject()
                        .get(CONCEPT_PROPERTY).getAsJsonArray();
                JsonObject rootContainer = null;
                for (JsonElement element : root) {
                    if (element.isJsonObject()) {
                        rootContainer = element.getAsJsonObject();
                        break;
                    }
                }
                if (rootContainer == null) {
                    rootContainer = new JsonObject();
                    root.add(rootContainer);
                }
                parentArray = new JsonArray();
                rootContainer.add(shortName, parentArray);
                createdParent = shortName;
            }
        } else if (effectiveParent.size() > 1) {
            List<String> prefix = effectiveParent.subList(0, effectiveParent.size() - 1);
            String terminal = effectiveParent.get(effectiveParent.size() - 1);
            Resolution prefixResolution = resolve(parsed.getElement(), prefix);
            if (prefixResolution.diagnostic != null) {
                // Never silently create a deep chain — only the ONE terminal parent may.
                return new AddCardsResult(false, -1L,
                        java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList(), null,
                        resolution.diagnostic);
            }
            JsonArray prefixArray = arrayAt(candidate, prefixResolution.path);
            if (prefixArray != null) {
                JsonObject prefixContainer = null;
                for (JsonElement element : prefixArray) {
                    if (element.isJsonObject()) {
                        prefixContainer = element.getAsJsonObject();
                        break;
                    }
                }
                if (prefixContainer == null) {
                    prefixContainer = new JsonObject();
                    prefixArray.add(prefixContainer);
                }
                parentArray = new JsonArray();
                prefixContainer.add(terminal, parentArray);
                createdParent = terminal;
            }
        }
        if (parentArray == null) {
            return new AddCardsResult(false, -1L, java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList(), null,
                    resolution.diagnostic != null ? resolution.diagnostic
                            : JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                                    "The parent no longer exists.").build());
        }
        // Resolve the ratified anchor BEFORE any mutation: it must be a DIRECT child of the
        // target parent right now, or the whole call rejects.
        String anchorName = null;
        if (insertBeforeNodeId != null) {
            List<String> anchorPath = identity == null ? null
                    : identity.pathOfId(parsed.getElement(), insertBeforeNodeId);
            if (anchorPath == null) {
                return new AddCardsResult(false, -1L,
                        java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList(), null,
                        JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                                "INSERT_ANCHOR_NOT_FOUND — the neighbour card no longer "
                                        + "exists; nothing was inserted.").build());
            }
            List<String> anchorParent = anchorPath.subList(0, anchorPath.size() - 1);
            if (createdParent != null || !anchorParent.equals(effectiveParent)) {
                return new AddCardsResult(false, -1L,
                        java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList(), null,
                        JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                                "ANCHOR_NOT_IN_TARGET — the neighbour card is not a direct "
                                        + "child of the target parent anymore; nothing was "
                                        + "inserted.").build());
            }
            anchorName = anchorPath.get(anchorPath.size() - 1);
        }
        List<String> added = new ArrayList<String>();
        List<String> alreadyPresent = new ArrayList<String>();
        List<String> toInsert = new ArrayList<String>();
        for (String card : cleaned) {
            boolean exists = false;
            for (JsonElement element : parentArray) {
                if (element.isJsonObject() && element.getAsJsonObject().has(card)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                alreadyPresent.add(card);
            } else {
                toInsert.add(card);
            }
        }
        if (!toInsert.isEmpty()) {
            insertCards(parentArray, toInsert, anchorName);
            added.addAll(toInsert);
        }
        if (added.isEmpty() && createdParent == null) {
            // Idempotent repeat: nothing new, no commit, no revision bump (receipt: NO_CHANGE).
            return new AddCardsResult(true, store.workingRevision(), added, alreadyPresent,
                    null, null);
        }
        ConceptIdentity identityAfter = identity;
        if (createdParent != null) {
            // The created parent mints its identity node first; the children hang below it.
            identityAfter = identityAfter.afterAdd(candidate, effectiveParent);
        }
        for (String card : added) {
            List<String> cardPath = new ArrayList<String>(effectiveParent);
            cardPath.add(card);
            identityAfter = identityAfter.afterAdd(candidate, cardPath);
        }
        EditResult committed = commitCandidate(candidate, identityAfter, null);
        if (!committed.isApplied()) {
            return new AddCardsResult(false, -1L, java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList(), null,
                    committed.getDiagnostic());
        }
        return new AddCardsResult(true, committed.getNewRevision(), added, alreadyPresent,
                createdParent, null);
    }

    /** Outcome of a leaf move: the receipt facts, or NO_CHANGE, or a diagnostic. */
    public static final class MoveLeafResult {
        private final boolean applied;
        private final boolean noChange;
        private final long newRevision;
        private final String label;
        private final List<String> fromPath;
        private final List<String> toPath;
        private final String nodeId;
        private final JsonTreeDiagnostic diagnostic;

        private MoveLeafResult(boolean applied, boolean noChange, long newRevision, String label,
                               List<String> fromPath, List<String> toPath, String nodeId,
                               JsonTreeDiagnostic diagnostic) {
            this.applied = applied;
            this.noChange = noChange;
            this.newRevision = newRevision;
            this.label = label;
            this.fromPath = fromPath;
            this.toPath = toPath;
            this.nodeId = nodeId;
            this.diagnostic = diagnostic;
        }

        public boolean isApplied() {
            return applied;
        }

        /** {@code true}: the leaf already sat under the target — nothing was committed. */
        public boolean isNoChange() {
            return noChange;
        }

        public long getNewRevision() {
            return newRevision;
        }

        public String getLabel() {
            return label;
        }

        public List<String> getFromPath() {
            return fromPath;
        }

        public List<String> getToPath() {
            return toPath;
        }

        public String getNodeId() {
            return nodeId;
        }

        public JsonTreeDiagnostic getDiagnostic() {
            return diagnostic;
        }
    }

    /**
     * The ATOMIC leaf move (move_leaf slice): exactly one existing LEAF changes its parent —
     * UUID, label and card data stay, only the path changes, document and sidecar commit as
     * one pair in exactly one revision. Resolution happens COMPLETELY before the mutation:
     * source and target accept a full path or a globally unique exact short name; ambiguity
     * ({@code AMBIGUOUS_SOURCE}/{@code AMBIGUOUS_PARENT}), a branch source
     * ({@code SOURCE_NOT_LEAF}), a missing target ({@code TARGET_PARENT_NOT_FOUND} — never
     * auto-created) and a same-name card at the target ({@code TARGET_NAME_COLLISION} — never
     * merged or overwritten) all reject the WHOLE call with zero mutation. Moving a leaf onto
     * its current parent is the honest idempotent {@code NO_CHANGE}.
     */
    public synchronized MoveLeafResult moveLeaf(List<String> sourceNames,
                                                List<String> targetParentNames) {
        if (failClosed) {
            return moveError(failClosedError().getDiagnostic());
        }
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return moveError(parsed.getDiagnostic());
        }
        // ---- source resolution (full path, or globally unique exact short name)
        List<String> sourcePath = resolveUnique(document, parsed.getElement(), sourceNames,
                "AMBIGUOUS_SOURCE");
        if (sourcePath == null) {
            return moveError(lastResolveDiagnostic);
        }
        // ---- target resolution ([] = root; full path; globally unique short name; NO create)
        List<String> targetPath;
        if (targetParentNames == null || trimmedSegments(targetParentNames).isEmpty()) {
            targetPath = java.util.Collections.emptyList();
        } else {
            targetPath = resolveUnique(document, parsed.getElement(),
                    trimmedSegments(targetParentNames), "AMBIGUOUS_PARENT");
            if (targetPath == null) {
                // Ambiguity keeps its candidate-path diagnostic; anything else is the honest
                // "no such parent — and this tool NEVER creates one" refusal.
                return moveError(lastResolveDiagnostic != null
                        && lastResolveDiagnostic.describeForModel().contains("AMBIGUOUS")
                        ? lastResolveDiagnostic
                        : JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                                "TARGET_PARENT_NOT_FOUND " + trimmedSegments(targetParentNames)
                                        + " — the target parent must already exist; "
                                        + "move_leaf never creates it.").build());
            }
        }
        String label = sourcePath.get(sourcePath.size() - 1);
        String nodeId = identity == null ? null
                : identity.idAtPath(parsed.getElement(), sourcePath);
        // ---- leaf-only + same-parent + collision checks, all BEFORE any mutation
        if (!isLeafShape(parsed.getElement(), sourcePath)) {
            return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "SOURCE_NOT_LEAF \"" + label + "\" — only a LEAF moves with this tool; "
                            + "branches stay manual editor work.").build());
        }
        List<String> currentParent = sourcePath.subList(0, sourcePath.size() - 1);
        if (currentParent.equals(targetPath)) {
            return new MoveLeafResult(true, true, store.workingRevision(), label,
                    new ArrayList<String>(sourcePath), new ArrayList<String>(sourcePath),
                    nodeId, null);
        }
        // ---- mutate ONE candidate: detach at the source, attach under the target
        Resolution sourceResolution = resolve(parsed.getElement(), sourcePath);
        if (sourceResolution.diagnostic != null) {
            return moveError(sourceResolution.diagnostic);
        }
        JsonElement candidate = parsed.getElement().deepCopy();
        JsonArray targetArray;
        if (targetPath.isEmpty()) {
            targetArray = candidate.getAsJsonObject().get(CONCEPT_PROPERTY).getAsJsonArray();
        } else {
            Resolution targetResolution = resolve(parsed.getElement(), targetPath);
            if (targetResolution.diagnostic != null) {
                return moveError(targetResolution.diagnostic);
            }
            targetArray = arrayAt(candidate, targetResolution.path);
            if (targetArray == null) {
                return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "TARGET_PARENT_NOT_FOUND " + targetPath + ".").build());
            }
        }
        for (JsonElement element : targetArray) {
            if (element.isJsonObject() && element.getAsJsonObject().has(label)) {
                return moveError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                        "TARGET_NAME_COLLISION \"" + label + "\" — a different card with this "
                                + "name already sits at the target; nothing was merged or "
                                + "overwritten.").build());
            }
        }
        JsonElement leafValue = valueAt(candidate, sourceResolution.path);
        JsonTreeDiagnostic removal = removeAt(candidate, sourceResolution.path);
        if (removal != null) {
            return moveError(removal);
        }
        JsonObject targetContainer = null;
        for (JsonElement element : targetArray) {
            if (element.isJsonObject()) {
                targetContainer = element.getAsJsonObject();
                break;
            }
        }
        if (targetContainer == null) {
            targetContainer = new JsonObject();
            targetArray.add(targetContainer);
        }
        targetContainer.add(label, leafValue == null ? new JsonArray() : leafValue);
        List<String> newPath = new ArrayList<String>(targetPath);
        newPath.add(label);
        ConceptIdentity identityAfter = identity.afterMoveLeaf(parsed.getElement(), sourcePath,
                candidate, newPath);
        EditResult committed = commitCandidate(candidate, identityAfter, null);
        if (!committed.isApplied()) {
            return moveError(committed.getDiagnostic());
        }
        return new MoveLeafResult(true, false, committed.getNewRevision(), label,
                new ArrayList<String>(sourcePath), newPath, nodeId, null);
    }

    /** Set by {@link #resolveUnique} when it returns {@code null}. */
    private JsonTreeDiagnostic lastResolveDiagnostic;

    /** Full path or globally unique exact short name → full path; {@code null} + diagnostic. */
    private List<String> resolveUnique(String document, JsonElement documentRoot,
                                       List<String> names, String ambiguityMarker) {
        List<String> trimmed = trimmedSegments(names);
        lastResolveDiagnostic = null;
        if (trimmed.isEmpty()) {
            lastResolveDiagnostic = JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "Name the card (a full path, or one globally unique card name).").build();
            return null;
        }
        Resolution direct = resolve(documentRoot, trimmed);
        if (direct.diagnostic == null) {
            return trimmed;
        }
        if (trimmed.size() == 1) {
            List<List<String>> matches = new ArrayList<List<String>>();
            for (List<String> path : ConceptTopicScanner.collectCardPaths(document)) {
                if (path.get(path.size() - 1).equals(trimmed.get(0))) {
                    matches.add(path);
                }
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                StringBuilder candidates = new StringBuilder();
                for (List<String> match : matches) {
                    candidates.append(candidates.length() > 0 ? ", " : "").append(match);
                }
                lastResolveDiagnostic = JsonTreeDiagnostic.of(
                        JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                        ambiguityMarker + " \"" + trimmed.get(0) + "\" — candidates: "
                                + candidates + ".")
                        .hint("Use the full path.").build();
                return null;
            }
        }
        lastResolveDiagnostic = direct.diagnostic;
        return null;
    }

    private static List<String> trimmedSegments(List<String> names) {
        List<String> trimmed = new ArrayList<String>();
        for (String name : names == null ? java.util.Collections.<String>emptyList() : names) {
            String value = name == null ? "" : name.trim();
            if (!value.isEmpty()) {
                trimmed.add(value);
            }
        }
        return trimmed;
    }

    /** Whether the card at {@code names} has NO children (leaf) in {@code documentRoot}. */
    private boolean isLeafShape(JsonElement documentRoot, List<String> names) {
        Resolution resolution = resolve(documentRoot, names);
        if (resolution.diagnostic != null) {
            return false;
        }
        JsonArray value = arrayAt(documentRoot, resolution.path);
        return value != null && value.size() == 0;
    }

    /** The card's own array value at a resolved path (deep-copied candidate), or null. */
    private static JsonElement valueAt(JsonElement documentRoot, JsonBranchPath path) {
        JsonArray value = arrayAt(documentRoot, path);
        return value;
    }

    private static MoveLeafResult moveError(JsonTreeDiagnostic diagnostic) {
        return new MoveLeafResult(false, false, -1L, null, null, null, null, diagnostic);
    }

    /** Remove the card at the name path with its whole subtree. Deliberately destructive. */
    public synchronized EditResult removeNodeAt(List<String> names) {
        if (failClosed) {
            return failClosedError();
        }
        if (names == null || names.isEmpty()) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "The concept's working surface itself cannot be removed — name the card "
                            + "to remove (e.g. \"<card>/<child card>\").").build());
        }
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return editError(parsed.getDiagnostic());
        }
        Resolution resolution = resolve(parsed.getElement(), names);
        if (resolution.diagnostic != null) {
            return editError(resolution.diagnostic);
        }
        JsonElement candidate = parsed.getElement().deepCopy();
        // The identity mirror needs the PRE-image ordinals — computed before the removal.
        ConceptIdentity identityAfter = identity.afterRemove(parsed.getElement(), names);
        JsonTreeDiagnostic removal = removeAt(candidate, resolution.path);
        if (removal != null) {
            return editError(removal);
        }
        return commitCandidate(candidate, identityAfter, null);
    }

    // ------------------------------------------------------------------ Zielbild slice 2 ops

    /**
     * Rename ONE card, any depth — never a deletion, children and order stay untouched. The
     * duplicate guard spans all containers of the parent (a second card with the new name would
     * make name-chain addressing ambiguous).
     */
    public synchronized EditResult renameNode(List<String> names, String newName) {
        if (failClosed) {
            return failClosedError();
        }
        String cardName = newName == null ? "" : newName.trim();
        if (cardName.isEmpty()) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "The new card name must not be empty.").build());
        }
        Located located = locateForEdit(names);
        if (located.error != null) {
            return located.error;
        }
        if (cardName.equals(located.property)) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "The card is already named \"" + cardName + "\".").build());
        }
        for (JsonElement element : located.parentArray) {
            if (element.isJsonObject() && element.getAsJsonObject().has(cardName)) {
                return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                        "A sibling card named \"" + cardName + "\" already exists — renaming "
                                + "would make the name ambiguous.").build());
            }
        }
        // Rebuild the owner object so the card keeps its POSITION (order is structure).
        JsonObject renamed = new JsonObject();
        List<String> keys = new ArrayList<String>();
        for (Map.Entry<String, JsonElement> entry : located.owner.entrySet()) {
            keys.add(entry.getKey());
            renamed.add(entry.getKey().equals(located.property) ? cardName : entry.getKey(),
                    entry.getValue());
        }
        for (String key : keys) {
            located.owner.remove(key);
        }
        for (Map.Entry<String, JsonElement> entry : renamed.entrySet()) {
            located.owner.add(entry.getKey(), entry.getValue());
        }
        // A rename is identity-NEUTRAL (invariant 1): the sidecar carries no names.
        return commitCandidate(located.candidate, identity, null);
    }

    /**
     * Replace a TERMINAL branch's leaves in one atomic step — the "häppchenweise" rewrite: the
     * node keeps its name and position, its children become exactly the given leaf names. A
     * branch with deeper structure is refused (work bottom-up); this is also the designed spot
     * where blacklisted leaves silently LEAVE the stored mindmap — the caller checks the
     * replacement against the blacklist before it gets here.
     */
    public synchronized EditResult rewriteTerminalBranch(List<String> names, List<String> leaves) {
        if (failClosed) {
            return failClosedError();
        }
        Located located = locateForEdit(names);
        if (located.error != null) {
            return located.error;
        }
        if (!allChildrenAreLeaves(located.children)) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "\"" + located.property + "\" has deeper structure — only a TERMINAL branch "
                            + "(children without own children) can be rewritten in one step.")
                    .hint("Work bottom-up: rewrite or delete the deeper branches first.")
                    .build());
        }
        java.util.LinkedHashSet<String> cleaned = new java.util.LinkedHashSet<String>();
        for (String leaf : leaves == null ? java.util.Collections.<String>emptyList() : leaves) {
            String name = leaf == null ? "" : leaf.trim();
            if (!name.isEmpty()) {
                cleaned.add(name); // duplicates collapse — the same leaf twice is never intent
            }
        }
        while (located.children.size() > 0) {
            located.children.remove(0);
        }
        if (!cleaned.isEmpty()) {
            JsonObject container = new JsonObject();
            for (String leaf : cleaned) {
                container.add(leaf, new JsonArray());
            }
            located.children.add(container);
        }
        // Replaced leaves are NEW content — their old IDs die with their cards; the branch's
        // own ordinals are untouched by the child swap, so the post-image resolves them.
        return commitCandidate(located.candidate,
                identity.afterRewrite(located.candidate, names, cleaned.size()), null);
    }

    /**
     * The GUARDED delete for the model contract: leaves and terminal branches only — a small
     * model must never be able to vaporise a deep part of the book in one call. Deep removals
     * remain host-authorized paths ({@code removeNodeAt} via the concept-conflict resolution).
     */
    public synchronized EditResult deleteTerminalBranch(List<String> names) {
        if (failClosed) {
            return failClosedError();
        }
        Located located = locateForEdit(names);
        if (located.error != null) {
            return located.error;
        }
        if (!allChildrenAreLeaves(located.children)) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "\"" + located.property + "\" has deeper structure — deep branches are "
                            + "never deleted in one step.")
                    .hint("Work bottom-up: rewrite or delete the deeper branches first, or ask "
                            + "the user to remove it in the concept editor.")
                    .build());
        }
        // Pre-image ordinals for the identity mirror, then the actual removal.
        ConceptIdentity identityAfter = identity.afterRemove(located.candidate, names);
        JsonTreeDiagnostic removal = removeAt(located.candidate,
                located.resolutionPath);
        if (removal != null) {
            return editError(removal);
        }
        return commitCandidate(located.candidate, identityAfter, null);
    }

    /**
     * Whether the card at {@code names} is a LEAF (no children at all). The conflict flow's
     * double guard (safety-slice gate: the host itself deleted a non-terminal root through
     * {@code removeNodeAt}): only a leaf conflict may be offered for removal, and the resolver
     * re-checks IMMEDIATELY before the commit — the card may have grown children between
     * question and answer. Unresolvable paths report {@code false} (nothing removable there).
     */
    public synchronized boolean isLeafAt(List<String> names) {
        Located located = locateForEdit(names);
        return located.error == null && located.children.size() == 0;
    }

    /** One located, edit-ready node inside a deep-copied candidate document. */
    private static final class Located {
        JsonElement candidate;
        JsonBranchPath resolutionPath;
        JsonArray parentArray;
        JsonObject owner;
        String property;
        JsonArray children;
        EditResult error;
    }

    private Located locateForEdit(List<String> names) {
        Located located = new Located();
        if (names == null || names.isEmpty()) {
            located.error = editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "Name the card to edit (e.g. [\"<card>\",\"<child card>\"]).").build());
            return located;
        }
        String document = store.effectiveContent();
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            located.error = editError(parsed.getDiagnostic());
            return located;
        }
        Resolution resolution = resolve(parsed.getElement(), names);
        if (resolution.diagnostic != null) {
            located.error = editError(resolution.diagnostic);
            return located;
        }
        located.candidate = parsed.getElement().deepCopy();
        located.resolutionPath = resolution.path;
        JsonObject container = located.candidate.getAsJsonObject();
        JsonArray parentArray = null;
        List<JsonBranchPath.Step> steps = resolution.path.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            JsonBranchPath.Step step = steps.get(i);
            JsonElement value = container.get(step.getProperty());
            if (value == null || !value.isJsonArray()) {
                located.error = editError(JsonTreeDiagnostic.of(
                        JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "Concept node \"" + step.getProperty() + "\" no longer exists.").build());
                return located;
            }
            if (i == steps.size() - 1) {
                located.parentArray = parentArray;
                located.owner = container;
                located.property = step.getProperty();
                located.children = value.getAsJsonArray();
                if (located.parentArray == null) {
                    // The target is the concept surface itself — never editable this way.
                    located.error = editError(JsonTreeDiagnostic.of(
                            JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                            "The concept's working surface itself cannot be edited — name a "
                                    + "card.").build());
                }
                return located;
            }
            JsonArray array = value.getAsJsonArray();
            if (step.getElementIndex() < 0 || step.getElementIndex() >= array.size()
                    || !array.get(step.getElementIndex()).isJsonObject()) {
                located.error = editError(JsonTreeDiagnostic.of(
                        JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "The path into \"" + step.getProperty() + "\" no longer matches.")
                        .build());
                return located;
            }
            parentArray = array;
            container = array.get(step.getElementIndex()).getAsJsonObject();
        }
        throw new IllegalStateException("unreachable: loop returns on the last step");
    }

    /** TERMINAL test (three-node rule): every child of every container is an EMPTY array. */
    private static boolean allChildrenAreLeaves(JsonArray children) {
        for (JsonElement element : children) {
            if (!element.isJsonObject()) {
                continue; // sealed/value leaves are not structure — they never block
            }
            for (Map.Entry<String, JsonElement> child : element.getAsJsonObject().entrySet()) {
                JsonElement value = child.getValue();
                if (value.isJsonArray() && value.getAsJsonArray().size() > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Full-candidate validation + atomic CO-commit (document + identity as one pair) — the
     * shared tail of every atomic operation. The no-op rule for normal operations compares
     * document hash + epoch only: their identity delta is derivative of the document delta, so
     * an unchanged document means freshly minted no-op UUIDs are DISCARDED, never committed.
     */
    private EditResult commitCandidate(JsonElement candidate, ConceptIdentity identityAfter,
                                       String boundaryReason) {
        if (failClosed) {
            return failClosedError();
        }
        String candidateJson = GSON.toJson(candidate);
        JsonTreeParseResult validated = JsonTreeParser.parse(candidateJson);
        if (!validated.isOk()) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                    "The changed document failed re-validation and was discarded. Underlying "
                            + "problem: " + validated.getDiagnostic().getMessage())
                    .build());
        }
        String documentHash = FileConceptStore.sha256(candidateJson);
        boolean sameEpoch = identity != null && identityAfter.epoch().equals(identity.epoch());
        if (boundaryReason == null && sameEpoch && documentHash.equals(confirmedDocumentHash)) {
            return new EditResult(true, store.workingRevision(), null);
        }
        long committed = store.commitPair(candidateJson,
                identityAfter.stampedJson(store.workingRevision() + 1, documentHash),
                FileConceptStore.sha256(identityAfter.coreJson()), identityAfter.epoch(),
                System.currentTimeMillis());
        identity = identityAfter;
        confirmedDocumentHash = documentHash;
        if (boundaryReason != null) {
            transientBoundary(boundaryReason, identityAfter.epoch());
        }
        notifyChanged();
        return new EditResult(true, committed, null);
    }

    /** Walk a resolved path to its target ARRAY inside {@code root} (a deep copy), or null. */
    private static JsonArray arrayAt(JsonElement root, JsonBranchPath path) {
        JsonObject container = root.getAsJsonObject();
        List<JsonBranchPath.Step> steps = path.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            JsonBranchPath.Step step = steps.get(i);
            JsonElement value = container.get(step.getProperty());
            if (value == null || !value.isJsonArray()) {
                return null;
            }
            JsonArray array = value.getAsJsonArray();
            if (i == steps.size() - 1) {
                return array;
            }
            if (step.getElementIndex() < 0 || step.getElementIndex() >= array.size()
                    || !array.get(step.getElementIndex()).isJsonObject()) {
                return null;
            }
            container = array.get(step.getElementIndex()).getAsJsonObject();
        }
        return null;
    }

    // ------------------------------------------------------------------ remove

    /** DELIBERATELY destructive: removes the addressed node with its whole subtree. */
    public synchronized EditResult removeBranch(String handleId) {
        Handle handle = handles.get(handleId);
        if (handle == null) {
            return editError(unknownHandle(handleId));
        }
        if (handle.path.getSteps().size() <= 1) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.BRANCH_GRAFT_FAILED,
                    "The concept's working surface itself cannot be removed.")
                    .path(handle.path.describe())
                    .build());
        }
        String document = store.effectiveContent();
        long currentRevision = store.workingRevision();
        if (handle.baseRevision != currentRevision) {
            return editError(stale(handle.baseRevision, currentRevision));
        }
        StrictJsonParseResult parsed = StrictJsonParser.parse(document);
        if (!parsed.isOk()) {
            return editError(parsed.getDiagnostic());
        }
        JsonElement candidate = parsed.getElement().deepCopy();
        JsonTreeDiagnostic removal = removeAt(candidate, handle.path);
        if (removal != null) {
            return editError(removal);
        }
        String candidateJson = GSON.toJson(candidate);
        JsonTreeParseResult validated = JsonTreeParser.parse(candidateJson);
        if (!validated.isOk()) {
            return editError(JsonTreeDiagnostic.of(JsonTreeErrorCode.CANDIDATE_DOCUMENT_INVALID,
                    "The document after the removal failed re-validation and was discarded. "
                            + "Underlying problem: " + validated.getDiagnostic().getMessage())
                    .build());
        }
        long newRevision = store.commitWorking(candidateJson, System.currentTimeMillis());
        notifyChanged();
        return new EditResult(true, newRevision, null);
    }

    // ------------------------------------------------------------------ name-chain resolution

    private static final class Resolution {
        JsonBranchPath path;
        String parentName;
        List<String> siblingNames = new ArrayList<String>();
        JsonTreeDiagnostic diagnostic;
    }

    /**
     * Walk the name chain from the envelope root. Unlike the index-based {@link JsonBranchPath}
     * steps, the service finds the container ELEMENT for each hop by scanning for the first
     * array element that carries the next name — the model addresses concepts by name only.
     */
    private static Resolution resolve(JsonElement documentRoot, List<String> names) {
        Resolution resolution = new Resolution();
        if (!documentRoot.isJsonObject()
                || !documentRoot.getAsJsonObject().has(CONCEPT_PROPERTY)
                || !documentRoot.getAsJsonObject().get(CONCEPT_PROPERTY).isJsonArray()) {
            resolution.diagnostic = JsonTreeDiagnostic
                    .of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                            "The concept document has no \"" + CONCEPT_PROPERTY
                                    + "\" array — the working surface is missing.")
                    .path("$").build();
            return resolution;
        }
        List<String> fullNames = new ArrayList<String>();
        fullNames.add(CONCEPT_PROPERTY);
        if (names != null) {
            fullNames.addAll(names);
        }
        List<JsonBranchPath.Step> steps = new ArrayList<JsonBranchPath.Step>();
        JsonObject container = documentRoot.getAsJsonObject();
        JsonArray parentArray = null;
        for (int i = 0; i < fullNames.size(); i++) {
            String name = fullNames.get(i);
            JsonElement value = container.get(name);
            if (value == null || !value.isJsonArray()) {
                resolution.diagnostic = JsonTreeDiagnostic
                        .of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                                "Concept node \"" + name + "\" does not exist"
                                        + (i > 1 ? " under \"" + fullNames.get(i - 1) + "\""
                                                : "") + ".")
                        .path(describe(fullNames, i))
                        .hint("Read the parent branch first to see the existing node names.")
                        .build();
                return resolution;
            }
            JsonArray array = value.getAsJsonArray();
            if (i == fullNames.size() - 1) {
                steps.add(new JsonBranchPath.Step(name, 0));
                resolution.path = JsonBranchPath.ofSteps(
                        steps.toArray(new JsonBranchPath.Step[0]));
                if (i > 1) {
                    resolution.parentName = fullNames.get(i - 1);
                }
                if (parentArray != null) {
                    collectStructuralNames(parentArray, name, resolution.siblingNames);
                }
                return resolution;
            }
            String next = fullNames.get(i + 1);
            int elementIndex = -1;
            for (int e = 0; e < array.size(); e++) {
                JsonElement element = array.get(e);
                if (element.isJsonObject() && element.getAsJsonObject().has(next)) {
                    elementIndex = e;
                    break;
                }
            }
            if (elementIndex < 0) {
                resolution.diagnostic = JsonTreeDiagnostic
                        .of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                                "Concept node \"" + next + "\" does not exist under \""
                                        + name + "\".")
                        .path(describe(fullNames, i + 1))
                        .hint("Read the parent branch first to see the existing node names.")
                        .build();
                return resolution;
            }
            steps.add(new JsonBranchPath.Step(name, elementIndex));
            parentArray = array;
            container = array.get(elementIndex).getAsJsonObject();
        }
        throw new IllegalStateException("unreachable: loop returns on the last name");
    }

    /** All array-valued property names across the parent array's containers, minus the target. */
    private static void collectStructuralNames(JsonArray parentArray, String except,
            List<String> into) {
        for (JsonElement element : parentArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> property
                    : element.getAsJsonObject().entrySet()) {
                if (property.getValue().isJsonArray() && !property.getKey().equals(except)) {
                    into.add(property.getKey());
                }
            }
        }
    }

    private static String describe(List<String> names, int upToExclusive) {
        StringBuilder sb = new StringBuilder("$");
        for (int i = 0; i <= upToExclusive && i < names.size(); i++) {
            sb.append('.').append(names.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ structure-loss guard

    /**
     * Compare the CURRENT branch with the proposed one on structural names (all levels, root
     * name excluded — renaming the edited node itself is a visible, intentional act). A name
     * that reappears anywhere in the new branch counts as MOVED, not lost — regrouping is
     * exactly what refinement is for.
     */
    private static JsonTreeDiagnostic detectStructureLoss(String document, JsonBranchPath path,
            String proposedBranchJson) {
        JsonBranchCompiler.Result proposed = JsonBranchCompiler.compile(proposedBranchJson);
        if (!proposed.isOk()) {
            return null; // the replacer reports the compile problem with full position info
        }
        JsonBranchExporter.Result current = JsonBranchExporter.exportBranch(document, path);
        if (!current.isOk()) {
            return null; // the replacer reports the path problem
        }
        JsonBranchCompiler.Result old = JsonBranchCompiler.compile(current.getBranchJson());
        if (!old.isOk()) {
            return null;
        }
        Map<String, Integer> before = new LinkedHashMap<String, Integer>();
        Map<String, Integer> after = new LinkedHashMap<String, Integer>();
        countStructuralNames(old.getBranch().getValue(), before);
        countStructuralNames(proposed.getBranch().getValue(), after);
        List<String> lost = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            Integer remaining = after.get(entry.getKey());
            if (remaining == null || remaining < entry.getValue()) {
                lost.add(entry.getKey());
            }
        }
        if (lost.isEmpty()) {
            return null;
        }
        StringBuilder message = new StringBuilder(
                "The proposed refinement silently removes existing concept nodes:");
        for (String name : lost) {
            message.append(" \"").append(name).append('"');
        }
        message.append(". A refinement must preserve existing concepts.");
        return JsonTreeDiagnostic.of(JsonTreeErrorCode.STRUCTURE_LOSS_DETECTED,
                message.toString())
                .path(path.describe())
                .hint("Keep every existing node (moving it elsewhere in the branch is fine), "
                        + "or use the explicit remove operation if removal is intended.")
                .build();
    }

    private static void countStructuralNames(JsonArray array, Map<String, Integer> counts) {
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> property
                        : element.getAsJsonObject().entrySet()) {
                    if (property.getValue().isJsonArray()) {
                        Integer count = counts.get(property.getKey());
                        counts.put(property.getKey(), count == null ? 1 : count + 1);
                        countStructuralNames(property.getValue().getAsJsonArray(), counts);
                    }
                }
            } else if (element.isJsonArray()) {
                countStructuralNames(element.getAsJsonArray(), counts);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Prune structural children below {@code depth} to {@code []} — orientation reads only. */
    private static String pruneToDepth(String branchJson, int depth) {
        StrictJsonParseResult parsed = StrictJsonParser.parse(branchJson);
        JsonObject branch = parsed.getElement().getAsJsonObject();
        Map.Entry<String, JsonElement> only = branch.entrySet().iterator().next();
        JsonObject pruned = new JsonObject();
        pruned.add(only.getKey(), pruneArray(only.getValue().getAsJsonArray(), depth));
        return GSON.toJson(pruned);
    }

    private static JsonArray pruneArray(JsonArray array, int depth) {
        JsonArray out = new JsonArray();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                JsonObject copy = new JsonObject();
                for (Map.Entry<String, JsonElement> property
                        : element.getAsJsonObject().entrySet()) {
                    if (property.getValue().isJsonArray()) {
                        copy.add(property.getKey(), depth <= 1 ? new JsonArray()
                                : pruneArray(property.getValue().getAsJsonArray(), depth - 1));
                    } else {
                        copy.add(property.getKey(), property.getValue().deepCopy());
                    }
                }
                out.add(copy);
            } else if (element.isJsonArray()) {
                out.add(depth <= 1 ? new JsonArray()
                        : pruneArray(element.getAsJsonArray(), depth - 1));
            } else {
                out.add(element.deepCopy());
            }
        }
        return out;
    }

    /** Remove the path's final property from its container (and a container left empty). */
    private static JsonTreeDiagnostic removeAt(JsonElement documentRoot, JsonBranchPath path) {
        JsonObject container = documentRoot.getAsJsonObject();
        JsonArray parentArray = null;
        int parentIndex = -1;
        List<JsonBranchPath.Step> steps = path.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            JsonBranchPath.Step step = steps.get(i);
            JsonElement value = container.get(step.getProperty());
            if (value == null || !value.isJsonArray()) {
                return JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "Concept node \"" + step.getProperty() + "\" no longer exists.")
                        .path(path.describePrefix(i + 1)).build();
            }
            if (i == steps.size() - 1) {
                container.remove(step.getProperty());
                if (container.entrySet().isEmpty() && parentArray != null) {
                    parentArray.remove(parentIndex); // an empty container carries nothing
                }
                return null;
            }
            JsonArray array = value.getAsJsonArray();
            if (step.getElementIndex() < 0 || step.getElementIndex() >= array.size()
                    || !array.get(step.getElementIndex()).isJsonObject()) {
                return JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                        "The path into \"" + step.getProperty() + "\" no longer matches.")
                        .path(path.describePrefix(i + 1)).build();
            }
            parentArray = array;
            parentIndex = step.getElementIndex();
            container = array.get(step.getElementIndex()).getAsJsonObject();
        }
        throw new IllegalStateException("unreachable: loop returns on the last step");
    }

    /** A handle behind the working revision can never be applied again — drop it. */
    private void pruneExpiredHandles() {
        long current = store.workingRevision();
        java.util.Iterator<Map.Entry<String, Handle>> it = handles.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().baseRevision != current) {
                it.remove();
            }
        }
    }

    private static JsonTreeDiagnostic unknownHandle(String handleId) {
        return JsonTreeDiagnostic.of(JsonTreeErrorCode.TARGET_NODE_NOT_FOUND,
                "Unknown or expired branch handle \"" + handleId + "\".")
                .hint("Read the branch again to obtain a fresh handle.")
                .build();
    }

    private static JsonTreeDiagnostic stale(long expected, long current) {
        return JsonTreeDiagnostic.of(JsonTreeErrorCode.STALE_DOCUMENT_REVISION,
                "The concept changed while this branch was being edited. Expected revision: "
                        + expected + ". Current revision: " + current
                        + ". The branch was not applied.")
                .hint("Read the branch again before applying changes.")
                .build();
    }

    private static ReadResult error(JsonTreeDiagnostic diagnostic) {
        return new ReadResult(null, -1L, null, null, new ArrayList<String>(), false, diagnostic);
    }

    private static EditResult editError(JsonTreeDiagnostic diagnostic) {
        return new EditResult(false, -1L, diagnostic);
    }
}

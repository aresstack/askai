package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The research agent's artifact catalog — only artifacts with a CLEAR user-facing responsibility (issue #32):
 * the research brief (scoping truth), its derived visualization, the derived outline, ONE working document and
 * the structured sources/state views. The former per-processing-stage Markdown artifacts (concept,
 * research-notes, findings, draft, final) are legacy: their files may still exist on disk in old projects but
 * they are no longer part of the catalog, get no tabs and are never required by the active workflow.
 *
 * <p>Type ids: {@code "markdown"} (host default view), {@code "research.sources"} and {@code "research.state"}
 * (plugin views). This is the single source of the artifact list in the new agent model.</p>
 */
public final class ResearchArtifacts {

    public static final String TYPE_MARKDOWN = "markdown";
    public static final String TYPE_SOURCES = "research.sources";
    public static final String TYPE_STATE = "research.state";
    public static final String TYPE_BRIEF = "research.brief";
    public static final String TYPE_OUTLINE = "research.outline";
    public static final String TYPE_DOCUMENT = "research.document";
    public static final String TYPE_RUNTIME = "research.runtime";
    public static final String TYPE_SEARCH_SETTINGS = "research.search.settings";

    private ResearchArtifacts() {
    }

    public static List<AgentArtifact> all() {
        List<AgentArtifact> list = new ArrayList<AgentArtifact>();
        // The research brief ("Konzept" tab, formerly "Fragestellung") is the scoping phase's primary
        // artifact — first tab.
        // The former "Visualisierung" tab is GONE: the sources mindmap lives behind the square
        // toolbar button next to the Websuche (and /map) as a transcript overlay instead.
        list.add(new Artifact("research-brief", "Concept", TYPE_BRIEF, ""));
        // Sources sit BEFORE the outline: the concept already yields the first sources, and the outline is
        // (going to be) GENERATED FROM the sources — the tab order mirrors that logical flow.
        list.add(new Artifact("sources", "Sources", TYPE_SOURCES, ""));
        // Outline + Document are ONE tab: the outline (issue #29: persisted derived projection, stale marker,
        // explicit generate action) is the Document tab's DRAWER, the document itself shows in the main area.
        // Plan PR 21: the "outline" artifact slot (outline.md) is rendered from the outline store only, never
        // from the live projection; outline_save is gone. It has no tab of its own until PR 23.
        // ONE canonical working document (issue #32): DRAFT and FINALIZATION both work on it. The legacy
        // concept/research-notes/findings/draft/final artifacts are deliberately NOT listed anymore — old
        // files stay untouched on disk, but they get no tabs and no active-workflow writes.
        // #43 slice 9: the document gets its own page view (chapter hierarchy + clickable
        // [n] source badges); the plain editor stays one toggle away inside that view.
        list.add(new Artifact("document", "Document", TYPE_DOCUMENT, "document.md"));
        list.add(new Artifact("state", "Revisions", TYPE_STATE, "")); // id stays "state" (persisted tab selection, /open)
        // Runtime + search settings are deliberately NOT artifacts anymore: they live as the plugin's
        // settings pages in the host's gear menu (session-based), the artifact area holds work products.
        return Collections.unmodifiableList(list);
    }

    private static AgentArtifact markdown(String id, String displayName, String relativePath) {
        return new Artifact(id, displayName, TYPE_MARKDOWN, relativePath);
    }

    /** Immutable {@link AgentArtifact}. Revision is a placeholder until the artifact store lands (Commit 13). */
    private static final class Artifact implements AgentArtifact {
        private final String id;
        private final String displayName;
        private final String typeId;
        private final String relativePath;

        private Artifact(String id, String displayName, String typeId, String relativePath) {
            this.id = id;
            this.displayName = displayName;
            this.typeId = typeId;
            this.relativePath = relativePath;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getArtifactTypeId() {
            return typeId;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public long getRevision() {
            return 0L;
        }
    }
}

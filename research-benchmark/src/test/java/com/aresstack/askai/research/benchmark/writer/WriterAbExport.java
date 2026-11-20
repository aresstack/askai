package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.drafting.EvidenceAssignments;
import com.aresstack.askai.research.drafting.ProductiveSectionContexts;
import com.aresstack.askai.research.drafting.SectionWriteRequest;
import com.aresstack.askai.research.knowledge.lucene.CompositeSemanticKnowledgeIndex;
import com.aresstack.askai.research.knowledge.processing.FilePassageVectorStore;
import com.aresstack.askai.research.knowledge.processing.FileResearchProjectRepository;
import com.aresstack.askai.research.knowledge.processing.acronym.FileSourceAcronymStore;
import com.aresstack.askai.research.knowledge.processing.acronym.SentenceSegmenter;
import com.aresstack.askai.research.knowledge.processing.acronym.SourceAcronymService;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.plan.SourcePreference;
import com.aresstack.askai.research.runtime.rerank.SourcePreferenceRanking;
import com.aresstack.askai.research.store.FileManuscriptStore;
import com.aresstack.askai.research.store.FileOutlineStore;
import com.aresstack.askai.research.store.FileResearchSourceRepository;
import com.aresstack.askai.research.writer.AcronymGlossarySource;
import com.aresstack.askai.research.writer.EvidencePack;
import com.aresstack.askai.research.writer.SectionContextBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Exports one chapter of a real project as the writer A/B input with its complete evidence packs (stage 0 of the
 * writer proposal). The packs come from the app's own pack builder ({@link SectionContextBuilder}, wired as the app
 * wires it: the project's semantic index, its active passages, the accepted sources, the acronym glossary) with the
 * pack settings the app derives from its writer targets ({@link EvidenceAssignments#packSettings}). It reads the
 * project's stores and never writes them; the caller passes a copy when in doubt.
 *
 * <p>The mission's source preference is applied as the app applies it (mission of the newest pin in
 * {@code book.json}, the given preference settings). Not reproduced: settings other than the given ones (the
 * defaults otherwise), and the sections of earlier chapters the app has in its manuscript when it writes a later
 * chapter (the A/B starts every chapter on an empty manuscript).</p>
 */
public final class WriterAbExport {

    private final File project;
    private final String ollama;

    public WriterAbExport(File project, String ollama) {
        this.project = project;
        this.ollama = ollama.replaceAll("/+$", "");
    }

    /** The project id the knowledge stores were written under. */
    String projectId() throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(new File(project, "knowledge/project.properties"))) {
            p.load(in);
        }
        return p.getProperty("projectId", "");
    }

    /** The book language as the concept pins it ({@code book.json}), "" when unknown. */
    String bookLanguage() {
        try {
            JsonObject book = JsonParser.parseString(new String(Files.readAllBytes(
                    new File(project, "book.json").toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            String found = find(book, "language");
            return found == null ? "" : found;
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private static String find(JsonObject object, String key) {
        if (object.has(key) && object.get(key).isJsonPrimitive()) {
            return object.get(key).getAsString();
        }
        for (String name : object.keySet()) {
            if (object.get(name).isJsonObject()) {
                String found = find(object.getAsJsonObject(name), key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The mission the concept was frozen with: the newest pin of {@code book.json} that carries one, "" when none
     * does. The app reads the committed pin; the export cannot see the commits and takes the newest.
     */
    String mission() {
        try {
            JsonObject book = JsonParser.parseString(new String(Files.readAllBytes(
                    new File(project, "book.json").toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!book.has("pins") || !book.get("pins").isJsonArray()) {
                return "";
            }
            com.google.gson.JsonArray pins = book.getAsJsonArray("pins");
            for (int i = pins.size() - 1; i >= 0; i--) {
                JsonObject pin = pins.get(i).getAsJsonObject();
                if (pin.has("mission") && pin.get("mission").isJsonPrimitive()
                        && !pin.get("mission").getAsString().trim().isEmpty()) {
                    return pin.get("mission").getAsString();
                }
            }
            return "";
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /** The ranking the app's section contexts apply for the mission's source preference under {@code settings}. */
    static SourcePreferenceRanking ranking(String mission, SourcePreference.Settings settings) {
        SourcePreference preference = SourcePreference.of(mission, settings);
        return new SourcePreferenceRanking(preference.getPreferredHosts(), preference.getDemotedMarkers(),
                preference.getRankShift());
    }

    /** The highest approved outline revision of the project. */
    int latestApprovedOutline() {
        File[] files = new File(project, "outline/revisions").listFiles();
        int latest = 0;
        if (files != null) {
            for (File f : files) {
                if (f.getName().matches("\\d{4}\\.json")) {
                    latest = Math.max(latest, Integer.parseInt(f.getName().substring(0, 4)));
                }
            }
        }
        return latest;
    }

    /**
     * The embedding world of the project's passages: the first of the candidate models whose document fingerprint
     * finds persisted passage vectors.
     */
    EmbeddingEndpointDescriptor embeddingWorld(List<String> candidates, String projectId) {
        StringBuilder tried = new StringBuilder();
        for (String model : candidates) {
            try {
                EmbeddingEndpointDescriptor descriptor = WriterAbEmbeddings.descriptor(ollama, model, 600_000L);
                String fingerprint = HttpEmbeddingPortAdapter.documentFingerprint(descriptor);
                int passages = corpus(projectId, fingerprint).read(ActiveKnowledgeCorpusReader.SourceFilter.ALL)
                        .getPassages().size();
                tried.append(model).append(": ").append(passages).append(" passages; ");
                if (passages > 0) {
                    return descriptor;
                }
            } catch (Exception unusable) {
                tried.append(model).append(": ").append(unusable.getMessage()).append("; ");
            }
        }
        throw new IllegalStateException("no candidate embedding model finds the project's passage vectors ("
                + tried + "); name it with -Daskai.benchmark.writerAb.export.embeddingModel");
    }

    private ActiveKnowledgeCorpusReader corpus(String projectId, String fingerprint) {
        return new ActiveKnowledgeCorpusReader(new FileResearchProjectRepository(project),
                new FilePassageVectorStore(project), projectId, fingerprint);
    }

    /**
     * One section of the input: its brief and the pack the app's builder makes for it with the app's pack settings.
     * The digest and the synopsis of the pack are not exported: the A/B's pipeline derives them per run like the app.
     */
    static WriterAbInput.Section section(SectionContextBuilder packs, ProductiveSectionContexts contexts,
                                         OutlineChapter chapter, OutlineSection section,
                                         EvidenceAssignments.Targets targets) {
        com.aresstack.askai.research.writer.WriterPackSettings settings =
                EvidenceAssignments.packSettings(section, targets);
        EvidencePack pack = packs.build(contexts.requestFor(SectionWriteRequest.draft(chapter, section)), settings);
        return WriterAbInput.section(section.getId(), section.getTitle(), section.getBrief().getPurpose(),
                section.getBrief().getKeyPoints(), section.getBrief().getWordTarget(), pack.getPassages(),
                pack.getGlossary(), settings.toString());
    }

    /**
     * The A/B input of one chapter.
     *
     * @param outlineRevision the approved outline revision (0 = the latest)
     * @param chapterNumber   the chapter, from 1
     * @param maxSections     sections taken from the chapter's start (the A/B takes three to four)
     * @param targets         the writer targets whose pack settings apply
     * @param language        the book language; "" = the concept's
     * @param preference      the settings of the mission's source preference (the app's are on by default)
     */
    public WriterAbInput export(int outlineRevision, int chapterNumber, int maxSections,
                                EvidenceAssignments.Targets targets, String language,
                                EmbeddingEndpointDescriptor world, SourcePreference.Settings preference)
            throws IOException {
        String projectId = projectId();
        int revision = outlineRevision > 0 ? outlineRevision : latestApprovedOutline();
        Outline outline = new FileOutlineStore(new File(project, "outline")).approved(revision);
        OutlineChapter full = outline.getChapters().get(chapterNumber - 1);
        List<OutlineSection> taken = new ArrayList<OutlineSection>(full.getSections().subList(0,
                Math.min(maxSections, full.getSections().size())));
        OutlineChapter chapter = new OutlineChapter(full.getId(), full.getOrder(), full.getTitle(), full.getBrief(),
                full.getProvenance(), full.isUserEdited(), full.getStaleReason(), taken);
        final String bookLanguage = language == null || language.isEmpty() ? bookLanguage() : language;
        String fingerprint = HttpEmbeddingPortAdapter.documentFingerprint(world);
        final String mission = mission();
        final SourcePreferenceRanking ranking = ranking(mission, preference);
        ProductiveSectionContexts contexts = new ProductiveSectionContexts(projectId, fingerprint,
                corpus(projectId, fingerprint),
                ProductiveSectionContexts.of(new FileResearchSourceRepository(new File(project, "sources"))),
                new FileManuscriptStore(Files.createTempDirectory("writer-ab-export").toFile()),
                () -> bookLanguage, () -> ranking);
        SourceAcronymService acronyms = new SourceAcronymService(SentenceSegmenter.splitter(),
                new FileSourceAcronymStore(new File(project, "acronyms").toPath()));
        SectionContextBuilder packs = new SectionContextBuilder(WriterAbEmbeddings.port(world),
                new CompositeSemanticKnowledgeIndex(project, projectId), contexts, AcronymGlossarySource.of(acronyms));
        List<WriterAbInput.Section> sections = new ArrayList<WriterAbInput.Section>();
        for (OutlineSection section : taken) {
            sections.add(section(packs, contexts, chapter, section, targets));
        }
        String origin = "exported from " + project.getName() + ", outline revision " + revision + ", chapter "
                + chapterNumber + " (" + taken.size() + " of " + full.getSections().size() + " sections), packs by "
                + "the app's pack builder, embedding " + world.modelId + ", source preference "
                + (preference != null && preference.isEnabled() ? (mission.isEmpty() ? "on, no mission found"
                : "on") : "off");
        return WriterAbInput.of(bookLanguage, chapter.getId(), chapter.getTitle(), chapter.getBrief().getPurpose(),
                sections, origin);
    }
}

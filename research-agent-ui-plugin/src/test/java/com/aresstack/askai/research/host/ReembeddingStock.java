package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationException;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshot;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue;
import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingSettings;
import com.aresstack.askai.research.knowledge.processing.QueueBackedKnowledgeProcessingScheduler;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.store.FileResearchSourceRepository;
import com.aresstack.askai.research.store.MetadataLoadResult;
import com.aresstack.askai.research.store.ResearchProjectContext;
import com.aresstack.askai.research.store.ResearchProjectMetadata;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Point R without the app (architect, 2026-11-21): opens a research workspace the way a session start opens its
 * knowledge store, twice, and records what each opening did. One opening runs the session start's own steps in its
 * order: queue recovery, {@link KnowledgeProcessingSessionFactory#buildRunner},
 * {@link ProductiveResearchBackendFactory#reconcileProcessing}, then the worker, projection and analysis runners as
 * {@code attachKnowledge} starts them, until no job is queued or running. No UI, browser, chat or agent session. The
 * embedding world is resolved like the app's Ollama provider (installed digest, probed dimension, normalization
 * "none"), so the fingerprint is the one the app computes for the same model.
 */
final class ReembeddingStock {

    static final String SEGMENTATION = KnowledgeProcessingSettings.defaults().segmentationPipelineVersion;
    static final String MANUSCRIPT = "manuskript";
    static final String SOURCES = "quellen";
    static final String CAPTURES = "captures";

    private static final int LIST_LIMIT = 200;
    private static final int FILE_LIST_LIMIT = 2000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern WORKSPACE_FOLDER =
            Pattern.compile("(.+)_([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");

    private ReembeddingStock() {
    }

    // ------------------------------------------------------------------ the embedding world, as the app resolves it

    /**
     * The app's Ollama arm ({@code OllamaEmbeddingConfigurationSnapshotProvider} with {@code
     * AskAiOllamaModelDigestLookup} and {@code HttpEmbeddingDimensionProbe}, which live in the app module): the
     * installed model's digest from {@code /api/tags}, the dimension from one raw {@code /api/embed} probe, the
     * normalization "none". Model id, digest, dimension and normalization make the world fingerprint.
     */
    static EmbeddingEndpointDescriptor ollamaWorld(String baseUrl, String modelId, long timeoutMillis)
            throws IOException {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String model = modelId.trim();
        String digest = null;
        JsonObject tags = JsonParser.parseString(http("GET", base + "/api/tags", null, (int) timeoutMillis))
                .getAsJsonObject();
        JsonArray models = tags.has("models") ? tags.getAsJsonArray("models") : new JsonArray();
        for (JsonElement element : models) {
            JsonObject installed = element.getAsJsonObject();
            String name = text(installed, "name");
            String field = text(installed, "model");
            if (model.equals(name.isEmpty() ? field : name) || model.equals(field.isEmpty() ? name : field)) {
                digest = text(installed, "digest");
                break;
            }
        }
        if (digest == null) {
            throw new IllegalStateException("embedding model '" + model + "' is not an installed Ollama model at "
                    + base);
        }
        String probe = http("POST", base + "/api/embed", "{\"model\":" + new Gson().toJson(model)
                + ",\"input\":[\"probe\"],\"input_type\":\"raw\"}", (int) timeoutMillis);
        JsonArray rows = JsonParser.parseString(probe).getAsJsonObject().getAsJsonArray("embeddings");
        if (rows == null || rows.size() != 1 || rows.get(0).getAsJsonArray().size() == 0) {
            throw new IllegalStateException("invalid embedding probe response from " + base + ": " + probe);
        }
        return new EmbeddingEndpointDescriptor(model, base, "/api/embed", rows.get(0).getAsJsonArray().size(),
                "none", digest, timeoutMillis);
    }

    /**
     * The app's sentence models as its NLP provider hands them over: {@code <root>/apache-opennlp_sentence-<lang>/
     * <lang>-sent.bin}, where root is the app's {@code models/nlp/opennlp} folder. A language without a model falls
     * back to the regex segmenter, as in the app; {@code null} (no root) means the regex segmenter everywhere.
     */
    static NlpConfigurationSnapshotProvider openNlp(final File root) {
        if (root == null) {
            return null;
        }
        return (capability, languageCode) -> {
            File artifact = sentenceModel(root, languageCode);
            if (!artifact.isFile()) {
                throw new NlpConfigurationException(NlpConfigurationException.Reason.MODEL_NOT_INSTALLED,
                        "no sentence model at " + artifact);
            }
            String lang = artifact.getParentFile().getName().substring("apache-opennlp_sentence-".length());
            return new NlpConfigurationSnapshot(new NlpModelDescriptor("apache-opennlp/sentence-" + lang, capability,
                    lang, "opennlp", "", "", artifact.getAbsolutePath(), ""));
        };
    }

    static File sentenceModel(File root, String languageCode) {
        String lang = languageCode == null ? "" : languageCode.trim().toLowerCase(Locale.ROOT);
        return new File(new File(root, "apache-opennlp_sentence-" + lang), lang + "-sent.bin");
    }

    /** The id the app wrote into the workspace ({@code knowledge/project.properties}), else the folder's name. */
    static String projectIdOf(File workspace) throws IOException {
        File meta = new File(new File(workspace, "knowledge"), "project.properties");
        if (meta.isFile()) {
            String stored = properties(meta).getProperty("projectId", "").trim();
            if (!stored.isEmpty()) {
                return stored;
            }
        }
        Matcher folder = WORKSPACE_FOLDER.matcher(workspace.getName());
        if (folder.matches()) {
            return folder.group(1) + "#" + folder.group(2);
        }
        throw new IllegalStateException("cannot tell the project id of " + workspace);
    }

    // ------------------------------------------------------------------ one opening

    /** What one opening did. */
    static final class Opening {
        int recovered;
        /** {missing, stale} jobs the reconciliation enqueued; {@code null} when it failed. */
        int[] reconciled;
        boolean drained;
        long millis;
    }

    /** One session start's knowledge path on {@code workspace}, run until no job is queued or running. */
    static Opening open(File workspace, String projectId, EmbeddingEndpointDescriptor descriptor, String language,
                        final NlpConfigurationSnapshotProvider nlp, long timeoutMillis, String label,
                        PrintStream log) throws InterruptedException {
        long started = System.currentTimeMillis();
        Opening opening = new Opening();
        ResearchProjectContext context = ResearchProjectContext.open(projectId, workspace);
        MetadataLoadResult metadata = context.getMetadataStore().load(projectId);
        if (!metadata.isUsableForStart()) {
            throw new IllegalStateException("the stored project metadata is unusable for a start ("
                    + metadata.getStatus() + "): " + metadata.getReason());
        }
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(new File(workspace, "processing"));
        CaptureStore captures = new CaptureStore(200, -1L,
                CaptureStore.highestCaptureNumber(queue.knownCaptureIds()));
        FileResearchSourceRepository repository = context.getFileSourceRepository();
        KnowledgeProcessingSettings settings = KnowledgeProcessingSettings.defaults();
        opening.recovered = queue.recoverStrandedJobs().size();
        KnowledgeProcessingSessionFactory.KnowledgeSession knowledge = KnowledgeProcessingSessionFactory.buildRunner(
                workspace, projectId, descriptor, language,
                languageCode -> SessionSentenceSegmenter.resolve(nlp, languageCode),
                captures, repository, queue, settings, briefQuestions(metadata),
                KnowledgeProcessingSessionFactory.ProjectionListener.NONE, null);
        QueueBackedKnowledgeProcessingScheduler base = new QueueBackedKnowledgeProcessingScheduler(queue, settings,
                HttpEmbeddingPortAdapter.documentFingerprint(descriptor));
        opening.reconciled = ProductiveResearchBackendFactory.reconcileProcessing(workspace, projectId, repository,
                queue, settings, descriptor, base, language);
        // attachKnowledge: the worker, the projection runner (it waits for an explicit trigger) and the analysis
        // runner with its one check at session open
        knowledge.worker.start();
        knowledge.projection.start();
        knowledge.analysis.start();
        knowledge.analysis.knowledgeChanged();
        try {
            opening.drained = drain(queue, new File(workspace, "processing"), timeoutMillis, label, log);
            // the session stays open a moment, as in the app: a debounced run gets its chance to start
            Thread.sleep(Math.max(2000L, settings.projectionDebounceMillis + 1000L));
        } finally {
            knowledge.worker.stop();
            knowledge.projection.stop();
            knowledge.analysis.stop();
        }
        opening.millis = System.currentTimeMillis() - started;
        log.println("[reembed] " + label + " done in " + opening.millis / 1000L + " s: recovered "
                + opening.recovered + ", reconciliation " + Arrays.toString(opening.reconciled)
                + ", queue empty " + opening.drained);
        return opening;
    }

    private static List<String> briefQuestions(MetadataLoadResult metadata) {
        List<String> questions = new ArrayList<String>();
        if (metadata.getStatus() == MetadataLoadResult.Status.LOADED) {
            ResearchProjectMetadata scope = metadata.getMetadata();
            if (scope.hasResearchQuestion()) {
                questions.add(scope.getResearchQuestion());
            }
            questions.addAll(scope.getConfirmedFocusAreas());
        }
        return questions;
    }

    private static boolean drain(FileSourceProcessingQueue queue, File processing, long timeoutMillis, String label,
                                 PrintStream log) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        long report = 0L;
        while (true) {
            Map<String, Integer> states = new TreeMap<String, Integer>();
            synchronized (queue) { // the queue's own lock: no job file is replaced while it is counted
                for (String state : jobStates(processing).values()) {
                    states.merge(state, 1, Integer::sum);
                }
            }
            int open = states.getOrDefault("QUEUED", 0) + states.getOrDefault("PROCESSING", 0);
            long now = System.currentTimeMillis();
            if (open == 0) {
                return true;
            }
            if (now >= deadline) {
                log.println("[reembed] " + label + ": gave up after " + timeoutMillis / 60_000L + " min, " + open
                        + " job(s) still open " + states);
                return false;
            }
            if (now >= report) {
                log.println("[reembed] " + label + ": " + open + " job(s) open " + states);
                report = now + 30_000L;
            }
            Thread.sleep(1000L);
        }
    }

    // ------------------------------------------------------------------ the whole measurement of one workspace

    /** Both openings of one workspace with everything the architect asked for, criteria included. */
    static JsonObject run(File workspace, String projectId, EmbeddingEndpointDescriptor descriptor, String language,
                          NlpConfigurationSnapshotProvider nlp, long timeoutMillis, PrintStream log)
            throws Exception {
        String world = HttpEmbeddingPortAdapter.documentFingerprint(descriptor);
        JsonObject result = new JsonObject();
        result.addProperty("kopie", workspace.getAbsolutePath());
        result.addProperty("projekt", projectId);
        JsonObject worldJson = new JsonObject();
        worldJson.addProperty("modell", descriptor.modelId);
        worldJson.addProperty("digest", descriptor.modelVersionFingerprint);
        worldJson.addProperty("dimension", descriptor.embeddingDimension);
        worldJson.addProperty("fingerabdruck", world);
        worldJson.addProperty("segmentierung", SEGMENTATION);
        worldJson.addProperty("sprache", language);
        worldJson.addProperty("satzzerlegung", SessionSentenceSegmenter.resolve(nlp, language).description);
        result.add("welt", worldJson);

        Map<String, FileState> s0 = snapshot(workspace);
        Map<String, String> jobs0 = jobStates(new File(workspace, "processing"));
        result.add("vorher", state(workspace, world));

        Opening first = open(workspace, projectId, descriptor, language, nlp, timeoutMillis, "erste Oeffnung", log);
        Map<String, FileState> s1 = snapshot(workspace);
        Map<String, String> jobs1 = jobStates(new File(workspace, "processing"));
        JsonObject firstJson = opening(first, jobs0, jobs1, world, new File(workspace, "processing"));
        firstJson.add("aenderungenNachOrdner", byFolder(changes(s0, s1, Integer.MAX_VALUE)));
        result.add("ersteOeffnung", firstJson);
        JsonObject afterFirst = state(workspace, world);
        result.add("nachErsterOeffnung", afterFirst);

        Opening second = open(workspace, projectId, descriptor, language, nlp, timeoutMillis, "zweite Oeffnung", log);
        Map<String, FileState> s2 = snapshot(workspace);
        Map<String, String> jobs2 = jobStates(new File(workspace, "processing"));
        JsonObject secondJson = opening(second, jobs1, jobs2, world, new File(workspace, "processing"));
        JsonArray secondChanges = changes(s1, s2, FILE_LIST_LIMIT);
        secondJson.add("dateien", secondChanges);
        result.add("zweiteOeffnung", secondJson);
        JsonObject afterSecond = state(workspace, world);
        result.add("nachZweiterOeffnung", afterSecond);

        JsonObject stock = new JsonObject();
        stock.add(MANUSCRIPT, group(s0, s1, s2, path -> path.startsWith("manuscript/") || path.equals("book.json")));
        stock.add(SOURCES, group(s0, s1, s2, path -> path.startsWith("sources/")));
        stock.add(CAPTURES, group(s0, s1, s2, path -> path.startsWith("knowledge/captures/")));
        result.add("bestand", stock);

        result.add("kriterien", criteria(first, second, firstJson, secondJson, afterFirst, afterSecond,
                secondChanges, stock));
        JsonArray notes = new JsonArray();
        notes.add("Die Konfigurationsdateien des Sitzungsstarts (z. B. service-endpoint.json, inference-config.json,"
                + " artifacts/document.md) schreibt dieser Test nicht; er geht nur den Wissenspfad.");
        notes.add("Die Themenanalyse laeuft hier nicht: sie wird erst an das freigegebene Konzept gebunden, wenn die"
                + " Agentensitzung sie anfordert (Phase 2 oder Gliederung).");
        notes.add("Zeiger von Captures, deren Quelle ausgeschlossen, doppelt, ersetzt oder ohne Text ist, bleiben alt:"
                + " der Abgleich bettet sie absichtlich nicht neu ein (altNichtZugelassen).");
        result.add("hinweise", notes);
        return result;
    }

    /** The names of the criteria a result does not meet (empty when all are met). */
    static List<String> unmet(JsonObject result) {
        List<String> unmet = new ArrayList<String>();
        for (Map.Entry<String, JsonElement> criterion : result.getAsJsonObject("kriterien").entrySet()) {
            if (!criterion.getValue().getAsJsonObject().get("erfuellt").getAsBoolean()) {
                unmet.add(criterion.getKey() + ": " + criterion.getValue().getAsJsonObject().get("befund")
                        .getAsString());
            }
        }
        return unmet;
    }

    static String toJson(JsonElement element) {
        return GSON.toJson(element);
    }

    private static JsonObject criteria(Opening first, Opening second, JsonObject firstJson, JsonObject secondJson,
                                       JsonObject afterFirst, JsonObject afterSecond, JsonArray secondChanges,
                                       JsonObject stock) {
        JsonObject criteria = new JsonObject();
        boolean ran = first.reconciled != null && second.reconciled != null && first.drained && second.drained;
        criterion(criteria, "K0 beide Oeffnungen liefen durch", ran,
                "Abgleich " + (first.reconciled != null) + "/" + (second.reconciled != null)
                        + ", Warteschlange leer " + first.drained + "/" + second.drained);
        int failedFirst = firstJson.getAsJsonArray("neuFehlgeschlagen").size();
        int failedSecond = secondJson.getAsJsonArray("neuFehlgeschlagen").size();
        criterion(criteria, "K1 keine fehlgeschlagenen Jobs", failedFirst == 0 && failedSecond == 0,
                failedFirst + " in der ersten, " + failedSecond + " in der zweiten Oeffnung neu fehlgeschlagen");
        int orphansFirst = afterFirst.getAsJsonObject("waisen").get("anzahl").getAsInt();
        int orphansSecond = afterSecond.getAsJsonObject("waisen").get("anzahl").getAsInt();
        criterion(criteria, "K2 keine Waisen danach", orphansFirst == 0 && orphansSecond == 0,
                orphansFirst + " nach der ersten, " + orphansSecond + " nach der zweiten Oeffnung");
        JsonObject pointers = afterSecond.getAsJsonObject("zeiger");
        int stale = afterFirst.getAsJsonObject("zeiger").get("altZugelassen").getAsInt()
                + afterFirst.getAsJsonObject("zeiger").get("ohneManifest").getAsInt()
                + pointers.get("altZugelassen").getAsInt() + pointers.get("ohneManifest").getAsInt();
        criterion(criteria, "K3 alle aktiven Zeiger auf der neuen Ableitung", stale == 0,
                pointers.get("neu").getAsInt() + " von " + pointers.get("gesamt").getAsInt() + " neu, "
                        + pointers.get("altZugelassen").getAsInt() + " alt bei zugelassener Quelle, "
                        + pointers.get("ohneManifest").getAsInt() + " ohne Manifest, "
                        + pointers.get("altNichtZugelassen").getAsInt() + " alt bei nicht zugelassener Quelle");
        int changed = 0;
        int written = 0;
        for (JsonElement change : secondChanges) {
            if ("geschrieben".equals(change.getAsJsonObject().get("aenderung").getAsString())) {
                written++;
            } else {
                changed++;
            }
        }
        criterion(criteria, "K4 zweite Oeffnung aendert keine Datei", changed == 0,
                changed + " Datei(en) neu, entfernt oder geaendert, " + written + " gleich neu geschrieben");
        List<String> touched = new ArrayList<String>();
        for (Map.Entry<String, JsonElement> group : stock.entrySet()) {
            if (!group.getValue().getAsJsonObject().get("unveraendert").getAsBoolean()) {
                touched.add(group.getKey());
            }
        }
        criterion(criteria, "K5 Manuskript, Quellen und Captures inhaltlich unveraendert", touched.isEmpty(),
                touched.isEmpty() ? "alle drei gleich" : "geaendert: " + touched);
        return criteria;
    }

    private static void criterion(JsonObject criteria, String name, boolean met, String finding) {
        JsonObject json = new JsonObject();
        json.addProperty("erfuellt", met);
        json.addProperty("befund", finding);
        criteria.add(name, json);
    }

    private static JsonObject opening(Opening opening, Map<String, String> before, Map<String, String> after,
                                      String world, File processing) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("dauerSekunden", opening.millis / 1000L);
        json.addProperty("wiederaufgenommen", opening.recovered);
        json.addProperty("abgleichFehlgeschlagen", opening.reconciled == null);
        json.addProperty("fehlendeJobs", opening.reconciled == null ? -1 : opening.reconciled[0]);
        json.addProperty("neuEinzubetten", opening.reconciled == null ? -1 : opening.reconciled[1]);
        json.addProperty("warteschlangeLeer", opening.drained);
        Map<String, Integer> transitions = new TreeMap<String, Integer>();
        for (Map.Entry<String, String> job : after.entrySet()) {
            String was = before.get(job.getKey());
            if (!job.getValue().equals(was)) {
                transitions.merge((was == null ? "neu" : was) + " -> " + job.getValue(), 1, Integer::sum);
            }
        }
        json.add("jobUebergaenge", GSON.toJsonTree(transitions));
        JsonArray newlyFailed = new JsonArray();
        for (Map.Entry<String, String> job : after.entrySet()) {
            if (job.getValue().startsWith("FAILED") && !job.getValue().equals(before.get(job.getKey()))) {
                newlyFailed.add(jobJson(properties(new File(processing, job.getKey() + ".properties")), world));
            }
        }
        json.add("neuFehlgeschlagen", newlyFailed);
        return json;
    }

    // ------------------------------------------------------------------ jobs, orphans, pointers

    private static JsonObject state(File workspace, String world) throws IOException {
        JsonObject json = new JsonObject();
        json.add("jobs", jobs(new File(workspace, "processing"), world));
        List<String> orphans = orphans(workspace);
        JsonObject orphanJson = new JsonObject();
        orphanJson.addProperty("anzahl", orphans.size());
        orphanJson.add("pfade", GSON.toJsonTree(orphans.subList(0, Math.min(LIST_LIMIT, orphans.size()))));
        json.add("waisen", orphanJson);
        json.add("zeiger", pointers(workspace, world, eligibleSources(workspace)));
        return json;
    }

    /** Job id -> state of every persisted job. */
    static Map<String, String> jobStates(File processing) {
        Map<String, String> states = new TreeMap<String, String>();
        File[] files = processing.listFiles();
        if (files == null) {
            return states;
        }
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("job-") && name.endsWith(".properties")) {
                try {
                    states.put(name.substring(0, name.length() - ".properties".length()),
                            properties(file).getProperty("state", ""));
                } catch (IOException unreadable) {
                    states.put(name.substring(0, name.length() - ".properties".length()), "UNREADABLE");
                }
            }
        }
        return states;
    }

    private static JsonObject jobs(File processing, String world) throws IOException {
        Map<String, Integer> all = new TreeMap<String, Integer>();
        Map<String, Integer> current = new TreeMap<String, Integer>();
        JsonArray failed = new JsonArray();
        int recoveredAfterFailure = 0;
        int total = 0;
        for (String jobId : jobStates(processing).keySet()) {
            Properties job = properties(new File(processing, jobId + ".properties"));
            String state = job.getProperty("state", "");
            boolean thisWorld = isWorld(job, world);
            total++;
            all.merge(state, 1, Integer::sum);
            if (thisWorld) {
                current.merge(state, 1, Integer::sum);
            }
            if (state.startsWith("FAILED") && failed.size() < LIST_LIMIT) {
                failed.add(jobJson(job, world));
            } else if (thisWorld && "COMPLETED".equals(state) && !job.getProperty("failureStage", "").isEmpty()) {
                recoveredAfterFailure++;
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("gesamt", total);
        json.add("nachZustand", GSON.toJsonTree(all));
        json.add("aktuelleWelt", GSON.toJsonTree(current));
        json.addProperty("abgeschlossenNachFehlversuch", recoveredAfterFailure);
        json.add("fehlgeschlagen", failed);
        return json;
    }

    private static boolean isWorld(Properties job, String world) {
        return world.equals(job.getProperty("embeddingModelFingerprint", ""))
                && SEGMENTATION.equals(job.getProperty("segmentationPipelineVersion", ""));
    }

    private static JsonObject jobJson(Properties job, String world) {
        JsonObject json = new JsonObject();
        json.addProperty("job", job.getProperty("jobId", ""));
        json.addProperty("capture", job.getProperty("captureId", ""));
        json.addProperty("zustand", job.getProperty("state", ""));
        json.addProperty("stufe", job.getProperty("failureStage", ""));
        String reason = job.getProperty("failureReason", "");
        json.addProperty("grund", reason.length() > 300 ? reason.substring(0, 300) + "..." : reason);
        json.addProperty("versuche", job.getProperty("attempts", ""));
        json.addProperty("welt", isWorld(job, world) ? "aktuell" : "andere");
        return json;
    }

    /** Generation folders that hold nothing but vectors: no manifest, no sentences, no passages. */
    static List<String> orphans(File workspace) {
        List<String> orphans = new ArrayList<String>();
        File[] captures = new File(new File(workspace, "knowledge"), "derived").listFiles();
        if (captures == null) {
            return orphans;
        }
        for (File capture : captures) {
            File[] generations = capture.listFiles();
            if (generations == null) {
                continue;
            }
            for (File generation : generations) {
                File[] files = generation.listFiles();
                if (files == null || files.length == 0) {
                    continue;
                }
                boolean vectorsOnly = true;
                for (File file : files) {
                    if (!file.isFile() || !(file.getName().equals("vectors.bin")
                            || file.getName().equals("vectors.bin.tmp"))) {
                        vectorsOnly = false;
                    }
                }
                if (vectorsOnly) {
                    orphans.add("knowledge/derived/" + capture.getName() + "/" + generation.getName());
                }
            }
        }
        java.util.Collections.sort(orphans);
        return orphans;
    }

    /** Source ids the reconciliation re-derives: corpus-eligible status and a full text (its own rule). */
    static Set<String> eligibleSources(File workspace) {
        Set<String> eligible = new HashSet<String>();
        for (ResearchSourceRecord record : new FileResearchSourceRepository(new File(workspace, "sources"))
                .find(SourceQuery.all())) {
            SourceStatus status = record.getStatus();
            if (status != SourceStatus.EXCLUDED && status != SourceStatus.DUPLICATE
                    && status != SourceStatus.SUPERSEDED && record.getFullText() != null
                    && !record.getFullText().trim().isEmpty()) {
                eligible.add(record.getSourceId());
            }
        }
        return eligible;
    }

    /** Where every active pointer leads: to a generation of the active world, to an older one, or nowhere. */
    static JsonObject pointers(File workspace, String world, Set<String> eligible) throws IOException {
        File knowledge = new File(workspace, "knowledge");
        File[] files = new File(knowledge, "active").listFiles();
        List<File> pointers = new ArrayList<File>();
        for (File file : files == null ? new File[0] : files) {
            if (file.isFile() && file.getName().endsWith(".properties")) {
                pointers.add(file);
            }
        }
        java.util.Collections.sort(pointers);
        int fresh = 0;
        int staleIneligible = 0;
        List<String> staleEligible = new ArrayList<String>();
        List<String> withoutManifest = new ArrayList<String>();
        for (File pointer : pointers) {
            Properties p = properties(pointer);
            String hash = pointer.getName().substring(0, pointer.getName().length() - ".properties".length());
            String captureId = p.getProperty("captureId", "");
            File manifest = new File(new File(new File(new File(knowledge, "derived"), hash),
                    p.getProperty("processingKey", "")), "manifest.properties");
            if (!manifest.isFile()) {
                withoutManifest.add(captureId);
                continue;
            }
            Properties m = properties(manifest);
            if (world.equals(m.getProperty("embeddingFingerprint", ""))
                    && SEGMENTATION.equals(m.getProperty("segmentationPipelineVersion", ""))) {
                fresh++;
            } else if (eligible.contains(sourceOf(knowledge, hash, p))) {
                staleEligible.add(captureId);
            } else {
                staleIneligible++;
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("gesamt", pointers.size());
        json.addProperty("neu", fresh);
        json.addProperty("altZugelassen", staleEligible.size());
        json.addProperty("altNichtZugelassen", staleIneligible);
        json.addProperty("ohneManifest", withoutManifest.size());
        json.add("listeAltZugelassen", GSON.toJsonTree(staleEligible.subList(0,
                Math.min(LIST_LIMIT, staleEligible.size()))));
        json.add("listeOhneManifest", GSON.toJsonTree(withoutManifest.subList(0,
                Math.min(LIST_LIMIT, withoutManifest.size()))));
        return json;
    }

    /** Pointers written before the one-capture-per-source rule carry no source id; their capture file does. */
    private static String sourceOf(File knowledge, String hash, Properties pointer) throws IOException {
        String sourceId = pointer.getProperty("sourceId", "");
        if (sourceId.isEmpty()) {
            File capture = new File(new File(knowledge, "captures"), hash + ".properties");
            if (capture.isFile()) {
                sourceId = properties(capture).getProperty("sourceId", "");
            }
        }
        return sourceId;
    }

    // ------------------------------------------------------------------ files

    /** Size, checksum and modification time of one file. */
    static final class FileState {
        final long size;
        final String sha256;
        final long modified;

        FileState(long size, String sha256, long modified) {
            this.size = size;
            this.sha256 = sha256;
            this.modified = modified;
        }
    }

    /** Every regular file under {@code root}, by its path relative to it (forward slashes). */
    static Map<String, FileState> snapshot(File root) throws IOException {
        Map<String, FileState> files = new TreeMap<String, FileState>();
        Path base = root.toPath();
        List<Path> paths = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile).forEach(paths::add);
        }
        for (Path path : paths) {
            files.put(base.relativize(path).toString().replace('\\', '/'), new FileState(Files.size(path),
                    sha256(path), Files.getLastModifiedTime(path).toMillis()));
        }
        return files;
    }

    /**
     * Files that appeared ("neu"), disappeared ("entfernt"), changed content ("geaendert") or were written again
     * with the same content ("geschrieben"), each with size and checksum before and after.
     */
    static JsonArray changes(Map<String, FileState> before, Map<String, FileState> after, int limit) {
        JsonArray changes = new JsonArray();
        Set<String> paths = new TreeSet<String>(before.keySet());
        paths.addAll(after.keySet());
        for (String path : paths) {
            FileState was = before.get(path);
            FileState is = after.get(path);
            String kind;
            if (was == null) {
                kind = "neu";
            } else if (is == null) {
                kind = "entfernt";
            } else if (!was.sha256.equals(is.sha256)) {
                kind = "geaendert";
            } else if (was.modified != is.modified) {
                kind = "geschrieben";
            } else {
                continue;
            }
            if (changes.size() >= limit) {
                JsonObject cut = new JsonObject();
                cut.addProperty("pfad", "...");
                cut.addProperty("aenderung", "Liste nach " + limit + " Eintraegen gekuerzt");
                changes.add(cut);
                break;
            }
            JsonObject change = new JsonObject();
            change.addProperty("pfad", path);
            change.addProperty("aenderung", kind);
            change.addProperty("groesseVorher", was == null ? -1L : was.size);
            change.addProperty("groesseNachher", is == null ? -1L : is.size);
            change.addProperty("pruefsummeVorher", was == null ? "" : was.sha256);
            change.addProperty("pruefsummeNachher", is == null ? "" : is.sha256);
            changes.add(change);
        }
        return changes;
    }

    /** The changes counted by folder (two levels) and kind. */
    private static JsonObject byFolder(JsonArray changes) {
        Map<String, Map<String, Integer>> folders = new TreeMap<String, Map<String, Integer>>();
        for (JsonElement element : changes) {
            JsonObject change = element.getAsJsonObject();
            String[] parts = change.get("pfad").getAsString().split("/");
            String folder = parts.length >= 3 ? parts[0] + "/" + parts[1] : parts[0];
            folders.computeIfAbsent(folder, f -> new TreeMap<String, Integer>())
                    .merge(change.get("aenderung").getAsString(), 1, Integer::sum);
        }
        return GSON.toJsonTree(folders).getAsJsonObject();
    }

    /** File count and one checksum over a group's paths and checksums, before, after the first and the second. */
    private static JsonObject group(Map<String, FileState> s0, Map<String, FileState> s1, Map<String, FileState> s2,
                                    Predicate<String> member) {
        JsonObject json = new JsonObject();
        String d0 = groupDigest(s0, member);
        String d1 = groupDigest(s1, member);
        String d2 = groupDigest(s2, member);
        json.add("vorher", groupState(s0, member, d0));
        json.add("nachErster", groupState(s1, member, d1));
        json.add("nachZweiter", groupState(s2, member, d2));
        json.addProperty("unveraendert", d0.equals(d1) && d1.equals(d2));
        Map<String, FileState> before = filter(s0, member);
        Map<String, FileState> afterFirst = filter(s1, member);
        Map<String, FileState> afterSecond = filter(s2, member);
        JsonArray changed = new JsonArray();
        for (JsonElement change : changes(before, afterFirst, LIST_LIMIT)) {
            if (!"geschrieben".equals(change.getAsJsonObject().get("aenderung").getAsString())) {
                change.getAsJsonObject().addProperty("oeffnung", 1);
                changed.add(change);
            }
        }
        for (JsonElement change : changes(afterFirst, afterSecond, LIST_LIMIT)) {
            if (!"geschrieben".equals(change.getAsJsonObject().get("aenderung").getAsString())) {
                change.getAsJsonObject().addProperty("oeffnung", 2);
                changed.add(change);
            }
        }
        json.add("geaendert", changed);
        return json;
    }

    private static JsonObject groupState(Map<String, FileState> files, Predicate<String> member, String digest) {
        JsonObject json = new JsonObject();
        json.addProperty("dateien", filter(files, member).size());
        json.addProperty("pruefsumme", digest);
        return json;
    }

    private static Map<String, FileState> filter(Map<String, FileState> files, Predicate<String> member) {
        Map<String, FileState> filtered = new LinkedHashMap<String, FileState>();
        for (Map.Entry<String, FileState> file : files.entrySet()) {
            if (member.test(file.getKey())) {
                filtered.put(file.getKey(), file.getValue());
            }
        }
        return filtered;
    }

    private static String groupDigest(Map<String, FileState> files, Predicate<String> member) {
        StringBuilder lines = new StringBuilder();
        for (Map.Entry<String, FileState> file : filter(files, member).entrySet()) {
            lines.append(file.getKey()).append('\t').append(file.getValue().sha256).append('\n');
        }
        return sha256(lines.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ small helpers

    static Properties properties(File file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static String text(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private static String http(String method, String url, String body, int timeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (status != 200) {
            throw new IOException(method + " " + url + " returned HTTP " + status + ": " + response);
        }
        return response;
    }
}

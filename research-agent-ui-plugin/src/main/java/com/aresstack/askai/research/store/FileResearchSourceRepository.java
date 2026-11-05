package com.aresstack.askai.research.store;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceRelevance;
import com.aresstack.askai.research.sources.SourceReliability;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.sources.SourceUpdate;
import com.aresstack.askai.research.sources.SourceUpdateResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * File-backed {@link ResearchSourceRepository}: one UTF-8 {@code .properties} file per source under
 * {@code sources/}. No Lucene, no JSON library. Writes are atomic and guarded by optimistic locking on the
 * revision; a corrupt source file is isolated (skipped in {@code find}, {@code null} from {@code get}) and
 * never silently deleted. Records survive a restart. A Lucene index, if introduced later, is a derived view
 * rebuildable from these files.
 */
public final class FileResearchSourceRepository implements ResearchSourceRepository {

    /** One lock per source file, shared by every repository instance on the same directory. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> SOURCE_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    private final File dir;

    public FileResearchSourceRepository(File sourcesDir) {
        this.dir = sourcesDir;
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
    }

    private File file(String sourceId) {
        return new File(dir, sourceId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".properties");
    }

    @Override
    public List<ResearchSourceRecord> find(SourceQuery query) {
        SourceQuery effective = query == null ? SourceQuery.all() : query;
        List<ResearchSourceRecord> result = new ArrayList<ResearchSourceRecord>();
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".properties")) {
                continue;
            }
            ResearchSourceRecord record = readFile(f);
            if (record != null && effective.matches(record)) {
                result.add(record);
            }
        }
        return result;
    }

    @Override
    public ResearchSourceRecord get(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        File f = file(sourceId);
        return f.isFile() ? readFile(f) : null;
    }

    @Override
    public SourceUpdateResult update(String sourceId, long expectedRevision, SourceUpdate update) {
        if (sourceId == null) {
            return SourceUpdateResult.notFound(null);
        }
        synchronized (lockFor(sourceId)) {
            return updateLocked(sourceId, expectedRevision, update);
        }
    }

    private Object lockFor(String sourceId) {
        String key = file(sourceId).getAbsolutePath();
        Object fresh = new Object();
        Object existing = SOURCE_LOCKS.putIfAbsent(key, fresh);
        return existing == null ? fresh : existing;
    }

    private SourceUpdateResult updateLocked(String sourceId, long expectedRevision, SourceUpdate update) {
        ResearchSourceRecord current = get(sourceId);
        if (current == null) {
            return SourceUpdateResult.notFound(sourceId);
        }
        if (current.getRevision() != expectedRevision) {
            return SourceUpdateResult.conflict(current);
        }
        ResearchSourceRecord next = current.toBuilder()
                .title(update.getTitle())
                .origin(update.getOrigin())
                .url(update.getUrl())
                .sourceType(update.getSourceType())
                .author(update.getAuthor())
                .linkedSectionIds(update.getLinkedSectionIds())
                .comment(update.getComment())
                .relevance(update.getRelevance())
                .reliability(update.getReliability())
                .status(update.getStatus())
                .userRelevant(update.isUserRelevant())
                .revision(current.getRevision() + 1L)
                .build();
        try {
            write(next);
            return SourceUpdateResult.updated(next);
        } catch (IOException ex) {
            return SourceUpdateResult.failed(current, String.valueOf(ex.getMessage()));
        }
    }

    /** Add or overwrite a record verbatim (used to seed a fresh project). */
    public void put(ResearchSourceRecord record) throws IOException {
        synchronized (lockFor(record.getSourceId())) {
            write(record);
        }
    }

    private void write(ResearchSourceRecord r) throws IOException {
        StringBuilder sb = new StringBuilder();
        line(sb, "sourceId", r.getSourceId());
        line(sb, "title", r.getTitle());
        line(sb, "origin", r.getOrigin());
        line(sb, "url", r.getUrl());
        line(sb, "sourceType", r.getSourceType());
        line(sb, "capturedAt", Long.toString(r.getCapturedAt()));
        line(sb, "author", r.getAuthor());
        line(sb, "linkedSectionIds", join(r.getLinkedSectionIds()));
        line(sb, "comment", r.getComment());
        line(sb, "relevance", r.getRelevance().name());
        line(sb, "reliability", r.getReliability().name());
        line(sb, "status", r.getStatus().name());
        line(sb, "snapshotReference", r.getSnapshotReference());
        line(sb, "checksum", r.getChecksum());
        line(sb, "revision", Long.toString(r.getRevision()));
        line(sb, "searchQuery", r.getSearchQuery());
        line(sb, "searchRequestId", r.getSearchRequestId());
        line(sb, "excerpt", r.getExcerpt());
        line(sb, "fullText", r.getFullText());
        // A NaN score is written as "" so an old file (no key) and an unscored source read back the same.
        line(sb, "rerankScore", r.hasRerankScore() ? Double.toString(r.getRerankScore()) : "");
        line(sb, "userRelevant", Boolean.toString(r.isUserRelevant()));
        StoreIo.atomicWrite(file(r.getSourceId()), sb.toString());
    }

    private ResearchSourceRecord readFile(File f) {
        try {
            Properties p = new Properties();
            p.putAll(StoreIo.loadUtf8Properties(f));
            String id = p.getProperty("sourceId");
            if (id == null || id.trim().isEmpty()) {
                return null; // corrupt: no id
            }
            return ResearchSourceRecord.builder(id)
                    .title(p.getProperty("title", ""))
                    .origin(p.getProperty("origin", ""))
                    .url(p.getProperty("url", ""))
                    .sourceType(p.getProperty("sourceType", ""))
                    .capturedAt(parseLong(p.getProperty("capturedAt")))
                    .author(p.getProperty("author", ""))
                    .linkedSectionIds(split(p.getProperty("linkedSectionIds", "")))
                    .comment(p.getProperty("comment", ""))
                    .relevance(parseEnum(SourceRelevance.class, p.getProperty("relevance"), SourceRelevance.UNKNOWN))
                    .reliability(parseEnum(SourceReliability.class, p.getProperty("reliability"),
                            SourceReliability.UNKNOWN))
                    .status(parseEnum(SourceStatus.class, p.getProperty("status"), SourceStatus.NEW))
                    .snapshotReference(p.getProperty("snapshotReference", ""))
                    .checksum(p.getProperty("checksum", ""))
                    .revision(parseLong(p.getProperty("revision")))
                    .searchQuery(p.getProperty("searchQuery", "")) // "" for old files / agent-accepted sources
                    .searchRequestId(p.getProperty("searchRequestId", ""))
                    .excerpt(p.getProperty("excerpt", ""))
                    .fullText(p.getProperty("fullText", "")) // "" for old files / parked (unread) sources
                    .rerankScore(parseScore(p.getProperty("rerankScore")))
                    .userRelevant(Boolean.parseBoolean(p.getProperty("userRelevant", "false"))) // false for old files
                    .build();
        } catch (Exception corrupt) {
            return null; // isolate a corrupt source file
        }
    }

    private static void line(StringBuilder sb, String key, String value) {
        // Properties format: escape backslash, then CR and LF (full page text may contain either, and a raw
        // CR/LF would be read back as a line terminator and truncate the value). Keep it simple and robust.
        String v = value == null ? "" : value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
        sb.append(key).append('=').append(v).append('\n');
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0L : Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** Missing key (old file) or empty value → NaN ("no score"); everything else parses as a double. */
    private static double parseScore(String v) {
        if (v == null || v.trim().isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v, E fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v.trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}

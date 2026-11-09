package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.store.FileResearchTaskStore;
import com.aresstack.askai.research.transition.AtomicFiles;
import com.aresstack.askai.research.transition.CorruptProjectFileException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistence of the search plan (plan PR 14, D5): one task per entry in the project's {@link FileResearchTaskStore}
 * and the plan record in {@code search-plan/plan.json} next to them. Both are written atomically as UTF-8. A damaged
 * plan record fails closed ({@link CorruptProjectFileException}); it is never repaired silently.
 */
public final class SearchPlanStore {

    private final FileResearchTaskStore tasks;
    private final File recordFile;

    public SearchPlanStore(FileResearchTaskStore tasks, File projectDirectory) {
        this.tasks = tasks;
        this.recordFile = new File(new File(projectDirectory, "search-plan"), "plan.json");
    }

    /** Plan PR 14: written before the plan build starts, removed once the plan is stored (crash recovery). */
    public File buildPendingFile() {
        return new File(recordFile.getParentFile(), "build-pending");
    }

    public synchronized void markBuildPending() throws IOException {
        AtomicFiles.write(buildPendingFile(), "pending\n");
    }

    public synchronized void clearBuildPending() {
        File marker = buildPendingFile();
        if (marker.isFile() && !marker.delete()) {
            System.err.println("[search-plan] could not remove " + marker);
        }
    }

    public synchronized boolean isBuildPending() {
        return buildPendingFile().isFile();
    }

    public File recordFile() {
        return recordFile;
    }

    /** The gap report of the last plan end ({@code search-plan/outcome.md}); rewritten at every end. */
    public File outcomeFile() {
        return new File(recordFile.getParentFile(), "outcome.md");
    }

    public synchronized void saveOutcome(String report) throws IOException {
        AtomicFiles.write(outcomeFile(), report == null ? "" : report);
    }

    /** The plan record, or {@code null} before the first plan. */
    public synchronized SearchPlanRecord loadRecord() throws CorruptProjectFileException {
        if (!recordFile.isFile()) {
            return null;
        }
        try {
            return SearchPlanRecord.fromJson(new String(Files.readAllBytes(recordFile.toPath()), AtomicFiles.UTF8));
        } catch (IOException | RuntimeException unreadable) {
            throw new CorruptProjectFileException(recordFile, "The search plan cannot be read: "
                    + unreadable.getMessage());
        }
    }

    public synchronized void saveRecord(SearchPlanRecord record) throws IOException {
        AtomicFiles.write(recordFile, record.toJson());
    }

    /** Every plan entry by id (other tasks of the store are left out). */
    public synchronized Map<String, SearchPlanEntry> loadEntries() {
        Map<String, SearchPlanEntry> entries = new LinkedHashMap<String, SearchPlanEntry>();
        for (ResearchTask task : tasks.loadAll().getTasks()) {
            if (SearchPlanEntry.isPlanTask(task)) {
                entries.put(task.getTaskId(), new SearchPlanEntry(task));
            }
        }
        return entries;
    }

    public synchronized void save(SearchPlanEntry entry) throws IOException {
        tasks.save(entry.task());
    }

    public synchronized void saveAll(List<SearchPlanEntry> entries) throws IOException {
        List<ResearchTask> list = new ArrayList<ResearchTask>();
        for (SearchPlanEntry entry : entries) {
            list.add(entry.task());
        }
        tasks.saveAll(list);
    }

    /**
     * The resume pass on session open (before the queue can start): entries left running go back to pending, or
     * fail once their retries are spent. It runs the task store's own pass.
     */
    public synchronized void resumeAfterInterruption(long nowMillis) throws IOException {
        tasks.resumeAfterInterruption(nowMillis);
    }
}

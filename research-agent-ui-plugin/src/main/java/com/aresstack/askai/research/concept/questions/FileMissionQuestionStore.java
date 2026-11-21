package com.aresstack.askai.research.concept.questions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Repair plan step 2a, part A: the working copy of the question list in the project ({@code concept/questions.json}).
 * The approved copy lives in the Phase-1 record; this file is only the draft the user edits in Phase 1.
 */
public final class FileMissionQuestionStore {

    public static final String FILE = "concept" + File.separator + "questions.json";

    private final File file;

    public FileMissionQuestionStore(File projectDir) {
        this.file = new File(projectDir, FILE);
    }

    /** The stored list; the empty list when there is none or it cannot be read. */
    public synchronized MissionQuestionList load() {
        if (!file.isFile()) {
            return MissionQuestionList.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            return MissionQuestionList.fromJson(json.get("questions"));
        } catch (IOException | RuntimeException unreadable) {
            System.err.println("[mission-questions] " + file + " unreadable: " + unreadable.getMessage());
            return MissionQuestionList.empty();
        }
    }

    /** Replaces the stored list (written to a temporary file first). */
    public synchronized void save(MissionQuestionList list) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        JsonObject json = new JsonObject();
        json.add("questions", list.toJson());
        File temp = new File(file.getPath() + ".tmp");
        Files.write(temp.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}

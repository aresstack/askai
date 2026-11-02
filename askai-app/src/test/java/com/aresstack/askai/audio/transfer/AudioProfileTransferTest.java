package com.aresstack.askai.audio.transfer;

import com.aresstack.askai.audio.AudioProfileRepository;
import com.aresstack.askai.audio.FileAudioProfileRepository;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** JSON import/export of audio profiles: versioned envelope, validation, collision handling, protection. */
public class AudioProfileTransferTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final AudioProfileExportService exportService =
            new AudioProfileExportService(new Supplier<String>() {
                public String get() {
                    return "2026-07-26T12:00:00Z";
                }
            });

    // ------------------------------------------------------------------ export

    @Test
    public void exportsSingleUserProfileWithFormatAndSchema() throws Exception {
        File file = folder.newFile("one.json");
        exportService.export(Collections.singletonList(user("p1", "Office microphone")), file);
        String json = read(file);
        assertTrue(json.contains("\"format\": \"" + AudioProfileTransferFormat.FORMAT + "\""));
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("Office microphone"));
        assertTrue("no leftover temp file", noTempFiles(file.getParentFile()));
    }

    @Test
    public void exportsAllUserProfilesButNeverTheBuiltInDefault() throws Exception {
        List<AudioProcessingProfile> all = new ArrayList<AudioProcessingProfile>();
        all.add(AudioProcessingProfiles.defaultSpeech());
        all.add(user("p1", "A"));
        all.add(user("p2", "B"));
        File file = folder.newFile("all.json");
        exportService.export(all, file);
        String json = read(file);
        assertFalse("default profile id must not appear",
                json.contains(AudioProcessingProfiles.DEFAULT_PROFILE_ID));
        assertTrue(json.contains("\"A\""));
        assertTrue(json.contains("\"B\""));
    }

    @Test
    public void tamperedBuiltInOrReservedIdCannotBypassTheExportFilter() {
        AudioProcessingProfile tamperedReservedId =
                new AudioProcessingProfile(AudioProcessingProfiles.DEFAULT_PROFILE_ID, "Sneaky", false, blocks());
        AudioProcessingProfile tamperedBuiltIn =
                new AudioProcessingProfile("x1", "AlsoSneaky", true, blocks());
        List<AudioProcessingProfile> exportable = AudioProfileExportService.exportable(
                java.util.Arrays.asList(tamperedReservedId, tamperedBuiltIn));
        assertTrue("both the reserved id and the built-in flag must be filtered out", exportable.isEmpty());
    }

    @Test
    public void exportWithoutUserProfilesIsRejected() throws Exception {
        File file = folder.newFile("empty.json");
        try {
            exportService.export(Collections.singletonList(AudioProcessingProfiles.defaultSpeech()), file);
            fail("expected a controlled rejection");
        } catch (AudioProfileTransferException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("no user profiles"));
        }
    }

    // ------------------------------------------------------------------ import

    @Test
    public void importsValidProfileAsUserProfile() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("valid.json", envelope(1, profileJson("p1", "Office microphone", false,
                blockJson("b1", "LOW_PASS", true))));

        AudioProfileImportPreview preview = service.preview(file);
        assertEquals(1, preview.getValidCount());
        AudioProfileImportResult result = service.commit(preview);
        assertEquals(1, result.getImportedCount());
        AudioProcessingProfile imported = repo.userProfiles.get(0);
        assertFalse("imported profiles are never built-in", imported.isBuiltIn());
        assertEquals("Office microphone", imported.getName());
    }

    @Test
    public void importedProfileIsAlwaysBuiltInFalseEvenIfJsonSaysTrue() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("bi.json", envelope(1, profileJson("p9", "Trusted?", true,
                blockJson("b1", "HIGH_PASS", true))));
        service.commit(service.preview(file));
        assertFalse(repo.userProfiles.get(0).isBuiltIn());
    }

    @Test
    public void defaultSpeechIsNeverAdoptedAndTheBuiltInStaysUnchanged() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("ds.json", envelope(1, profileJson(
                AudioProcessingProfiles.DEFAULT_PROFILE_ID, "Fake default", true,
                blockJson("b1", "LIMITER", true))));

        AudioProfileImportPreview preview = service.preview(file);
        assertEquals(1, preview.getValidCount());
        assertTrue("reserved id is reassigned", preview.getImportable().get(0).isIdReassigned());
        assertFalse(AudioProcessingProfiles.DEFAULT_PROFILE_ID
                .equals(preview.getImportable().get(0).getFinalId()));

        service.commit(preview);
        AudioProcessingProfile builtIn = repo.findById(AudioProcessingProfiles.DEFAULT_PROFILE_ID);
        assertTrue("the built-in default remains built-in and untouched", builtIn.isBuiltIn());
    }

    @Test
    public void idCollisionGetsANewUuid() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        repo.userProfiles.add(user("dup-id", "Existing"));
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("idc.json", envelope(1, profileJson("dup-id", "Fresh name", false,
                blockJson("b1", "COMPRESSOR", true))));

        AudioProfileImportPreview preview = service.preview(file);
        assertTrue(preview.getImportable().get(0).isIdReassigned());
        assertFalse("dup-id".equals(preview.getImportable().get(0).getFinalId()));
    }

    @Test
    public void nameCollisionIsResolvedDeterministically() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        repo.userProfiles.add(user("e1", "Office microphone"));
        AudioProfileImportService service = new AudioProfileImportService(repo);
        // Two incoming profiles with the same existing name → (2) then (3).
        File file = write("nc.json", envelope(1,
                profileJson("i1", "Office microphone", false, blockJson("b1", "LOW_PASS", true))
                        + "," + profileJson("i2", "Office microphone", false, blockJson("b2", "LOW_PASS", true))));

        AudioProfileImportPreview preview = service.preview(file);
        assertEquals("Office microphone (2)", preview.getImportable().get(0).getFinalName());
        assertEquals("Office microphone (3)", preview.getImportable().get(1).getFinalName());
    }

    @Test
    public void invalidJsonLeavesTheRepositoryUnchanged() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("broken.json", "{ this is not json ");
        try {
            service.preview(file);
            fail("expected controlled rejection");
        } catch (AudioProfileTransferException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("json"));
        }
        assertTrue(repo.userProfiles.isEmpty());
    }

    @Test
    public void unsupportedNewerSchemaVersionIsRejected() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("future.json", envelope(999, profileJson("p1", "Future", false,
                blockJson("b1", "LOW_PASS", true))));
        try {
            service.preview(file);
            fail("expected controlled rejection");
        } catch (AudioProfileTransferException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("newer schema"));
        }
    }

    @Test
    public void unknownExtraFieldsAreTolerated() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        String json = "{\"schemaVersion\":1,\"format\":\"" + AudioProfileTransferFormat.FORMAT + "\","
                + "\"exportedAt\":\"x\",\"somethingNew\":42,\"profiles\":["
                + "{\"id\":\"p1\",\"name\":\"Kept\",\"builtIn\":false,\"extra\":true,\"blocks\":["
                + "{\"id\":\"b1\",\"type\":\"LOW_PASS\",\"enabled\":true,\"parameters\":{},\"weird\":1}]}]}";
        File file = write("extra.json", json);
        AudioProfileImportPreview preview = service.preview(file);
        assertEquals(1, preview.getValidCount());
    }

    @Test
    public void unknownBlockTypeIsNotSilentlyDroppedAndProfileIsNotImportable() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("unknown.json", envelope(1, profileJson("p1", "Weird", false,
                blockJson("b1", "TELEPORTER_9000", true))));

        AudioProfileImportPreview preview = service.preview(file);
        assertEquals(0, preview.getValidCount());
        assertEquals(1, preview.getInvalidCount());
        assertTrue(preview.getRejected().get(0).getReasons().toString().contains("TELEPORTER_9000"));

        service.commit(preview);
        assertTrue("nothing persisted for an unimportable profile", repo.userProfiles.isEmpty());
    }

    @Test
    public void cancelledPreviewPersistsNothing() throws Exception {
        InMemoryRepository repo = new InMemoryRepository();
        AudioProfileImportService service = new AudioProfileImportService(repo);
        File file = write("prev.json", envelope(1, profileJson("p1", "A", false,
                blockJson("b1", "LOW_PASS", true))));
        service.preview(file); // no commit
        assertTrue(repo.userProfiles.isEmpty());
    }

    // ------------------------------------------------------------------ round-trip against the real repo

    @Test
    public void exportThenImportSurvivesRepositoryReloadAndIsEquivalent() throws Exception {
        File dir = folder.newFolder("profiles");
        FileAudioProfileRepository repo = new FileAudioProfileRepository(dir);
        AudioProcessingProfile original = user("orig", "Round trip");
        repo.save(original);

        File exportFile = folder.newFile("rt.json");
        exportService.export(repo.findAll(), exportFile);

        // Fresh repo over the same directory (simulates a restart); delete the source first.
        repo.delete("orig");
        assertFalse(containsName(new FileAudioProfileRepository(dir).findAll(), "Round trip"));

        AudioProfileImportService importService = new AudioProfileImportService(new FileAudioProfileRepository(dir));
        AudioProfileImportResult result = importService.commit(importService.preview(exportFile));
        assertEquals(1, result.getImportedCount());

        List<AudioProcessingProfile> afterReload = new FileAudioProfileRepository(dir).findAll();
        assertTrue("imported profile survives a repository reload", containsName(afterReload, "Round trip"));
        AudioProcessingProfile reloaded = byName(afterReload, "Round trip");
        assertEquals(original.getBlocks().size(), reloaded.getBlocks().size());
        assertEquals(original.getBlocks().get(0).getType(), reloaded.getBlocks().get(0).getType());
    }

    // ------------------------------------------------------------------ helpers

    private static AudioProcessingProfile user(String id, String name) {
        return new AudioProcessingProfile(id, name, false, blocks());
    }

    private static List<AudioBlockDefinition> blocks() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("cutoffHz", "8000");
        List<AudioBlockDefinition> list = new ArrayList<AudioBlockDefinition>();
        list.add(new AudioBlockDefinition("b1", AudioBlockType.LOW_PASS, true, params));
        return list;
    }

    private static String envelope(int schema, String profilesCsv) {
        return "{\"schemaVersion\":" + schema + ",\"format\":\"" + AudioProfileTransferFormat.FORMAT + "\","
                + "\"exportedAt\":\"2026-07-26T12:00:00Z\",\"profiles\":[" + profilesCsv + "]}";
    }

    private static String profileJson(String id, String name, boolean builtIn, String blocksCsv) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"builtIn\":" + builtIn
                + ",\"blocks\":[" + blocksCsv + "]}";
    }

    private static String blockJson(String id, String type, boolean enabled) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"enabled\":" + enabled
                + ",\"parameters\":{\"k\":\"v\"}}";
    }

    private File write(String name, String content) throws Exception {
        File file = folder.newFile(name);
        Files.write(file.toPath(), content.getBytes(Charset.forName("UTF-8")));
        return file;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    private static boolean noTempFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return true;
        }
        for (File file : files) {
            if (file.getName().contains(".tmp-")) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsName(List<AudioProcessingProfile> profiles, String name) {
        return byName(profiles, name) != null;
    }

    private static AudioProcessingProfile byName(List<AudioProcessingProfile> profiles, String name) {
        for (AudioProcessingProfile profile : profiles) {
            if (profile.getName().equals(name)) {
                return profile;
            }
        }
        return null;
    }

    /** In-memory repository mirroring FileAudioProfileRepository semantics (default first, reject built-in). */
    private static final class InMemoryRepository implements AudioProfileRepository {
        final List<AudioProcessingProfile> userProfiles = new ArrayList<AudioProcessingProfile>();

        public List<AudioProcessingProfile> findAll() {
            List<AudioProcessingProfile> all = new ArrayList<AudioProcessingProfile>();
            all.add(AudioProcessingProfiles.defaultSpeech());
            all.addAll(userProfiles);
            return all;
        }

        public AudioProcessingProfile findById(String profileId) {
            if (profileId == null || AudioProcessingProfiles.DEFAULT_PROFILE_ID.equals(profileId)) {
                return AudioProcessingProfiles.defaultSpeech();
            }
            for (AudioProcessingProfile profile : userProfiles) {
                if (profile.getId().equals(profileId)) {
                    return profile;
                }
            }
            return null;
        }

        public AudioProcessingProfile saveAs(AudioProcessingProfile source, String newName) {
            AudioProcessingProfile profile = source.asUserProfile(
                    java.util.UUID.randomUUID().toString(), newName);
            userProfiles.add(profile);
            return profile;
        }

        public void save(AudioProcessingProfile profile) {
            if (profile.isBuiltIn()
                    || AudioProcessingProfiles.DEFAULT_PROFILE_ID.equals(profile.getId())) {
                throw new IllegalArgumentException("The built-in default profile cannot be saved.");
            }
            for (int i = 0; i < userProfiles.size(); i++) {
                if (userProfiles.get(i).getId().equals(profile.getId())) {
                    userProfiles.set(i, profile);
                    return;
                }
            }
            userProfiles.add(profile);
        }

        public void delete(String profileId) {
            for (int i = 0; i < userProfiles.size(); i++) {
                if (userProfiles.get(i).getId().equals(profileId)) {
                    userProfiles.remove(i);
                    return;
                }
            }
        }
    }
}

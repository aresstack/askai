package com.aresstack.askai.audio;

import com.aresstack.askai.settings.AskAiPaths;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Store user-created audio profiles as independent, atomically replaced property files. */
public final class FileAudioProfileRepository implements AudioProfileRepository {

    private static final String FILE_SUFFIX = ".properties";

    private final File directory;

    public FileAudioProfileRepository() {
        this(AskAiPaths.appDirectory().resolve("audio-profiles").toFile());
    }

    public FileAudioProfileRepository(File directory) {
        this.directory = directory;
    }

    @Override
    public List<AudioProcessingProfile> findAll() {
        List<AudioProcessingProfile> profiles = new ArrayList<AudioProcessingProfile>();
        profiles.addAll(AudioProcessingProfiles.builtIns());
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File file = files[i];
                    if (file.isFile() && file.getName().endsWith(FILE_SUFFIX)) {
                        AudioProcessingProfile profile = read(file);
                        if (profile != null) {
                            profiles.add(profile);
                        }
                    }
                }
            }
        }
        Collections.sort(profiles, new Comparator<AudioProcessingProfile>() {
            public int compare(AudioProcessingProfile left, AudioProcessingProfile right) {
                if (left.isBuiltIn() != right.isBuiltIn()) {
                    return left.isBuiltIn() ? -1 : 1;
                }
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return profiles;
    }

    @Override
    public AudioProcessingProfile findById(String profileId) {
        if (profileId == null || profileId.trim().isEmpty()) {
            return AudioProcessingProfiles.defaultSpeech();
        }
        AudioProcessingProfile builtIn = AudioProcessingProfiles.builtInById(profileId.trim());
        if (builtIn != null) {
            return builtIn;
        }
        File file = profileFile(profileId.trim());
        AudioProcessingProfile profile = read(file);
        return profile == null ? AudioProcessingProfiles.defaultSpeech() : profile;
    }

    @Override
    public AudioProcessingProfile saveAs(AudioProcessingProfile source, String newName) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Source profile must not be null.");
        }
        String name = requireName(newName);
        AudioProcessingProfile profile = source.asUserProfile(UUID.randomUUID().toString(), name);
        save(profile);
        return profile;
    }

    @Override
    public void save(AudioProcessingProfile profile) throws IOException {
        if (profile == null) {
            throw new IllegalArgumentException("Profile must not be null.");
        }
        if (profile.isBuiltIn() || AudioProcessingProfiles.isBuiltInId(profile.getId())) {
            throw new IllegalArgumentException("A built-in profile cannot be overwritten.");
        }
        ensureDirectory();
        File target = profileFile(profile.getId());
        File temp = new File(directory, target.getName() + ".tmp-" + System.nanoTime());
        write(profile, temp);
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(String profileId) throws IOException {
        if (profileId == null || AudioProcessingProfiles.isBuiltInId(profileId)) {
            throw new IllegalArgumentException("A built-in profile cannot be deleted.");
        }
        File target = profileFile(profileId);
        if (target.isFile() && !target.delete()) {
            throw new IOException("Could not delete audio profile " + target.getAbsolutePath());
        }
    }

    private void write(AudioProcessingProfile profile, File file) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("profile.id", profile.getId());
        properties.setProperty("profile.name", profile.getName());
        properties.setProperty("block.count", String.valueOf(profile.getBlocks().size()));
        for (int i = 0; i < profile.getBlocks().size(); i++) {
            AudioBlockDefinition block = profile.getBlocks().get(i);
            String prefix = "block." + i + ".";
            properties.setProperty(prefix + "id", block.getId());
            properties.setProperty(prefix + "type", block.getType().name());
            properties.setProperty(prefix + "enabled", String.valueOf(block.isEnabled()));
            for (Map.Entry<String, String> entry : block.getParameters().entrySet()) {
                properties.setProperty(prefix + "param." + entry.getKey(), entry.getValue());
            }
        }
        FileOutputStream output = new FileOutputStream(file);
        try {
            properties.store(output, "AskAI audio-processing profile");
        } finally {
            output.close();
        }
    }

    private AudioProcessingProfile read(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try {
            FileInputStream input = new FileInputStream(file);
            try {
                properties.load(input);
            } finally {
                input.close();
            }
            String id = properties.getProperty("profile.id", stripSuffix(file.getName()));
            String name = properties.getProperty("profile.name", id);
            int count = parseInt(properties.getProperty("block.count"), 0);
            List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
            for (int i = 0; i < count; i++) {
                String prefix = "block." + i + ".";
                String blockId = properties.getProperty(prefix + "id", "block-" + i);
                AudioBlockType type = AudioBlockType.valueOf(properties.getProperty(prefix + "type"));
                boolean enabled = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "true"));
                Map<String, String> parameters = new LinkedHashMap<String, String>();
                String paramPrefix = prefix + "param.";
                for (String key : properties.stringPropertyNames()) {
                    if (key.startsWith(paramPrefix)) {
                        parameters.put(key.substring(paramPrefix.length()), properties.getProperty(key));
                    }
                }
                blocks.add(new AudioBlockDefinition(blockId, type, enabled, parameters));
            }
            return new AudioProcessingProfile(id, name, false, blocks);
        } catch (Exception ex) {
            return null;
        }
    }

    private File profileFile(String id) {
        return new File(directory, safeId(id) + FILE_SUFFIX);
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create audio profile directory " + directory.getAbsolutePath());
        }
    }

    private static String safeId(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stripSuffix(String value) {
        return value.endsWith(FILE_SUFFIX) ? value.substring(0, value.length() - FILE_SUFFIX.length()) : value;
    }

    private static String requireName(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Profile name must not be empty.");
        }
        return value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

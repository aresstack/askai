package com.aresstack.askai.audio.transfer;

import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioProcessingProfile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exports user audio profiles as a versioned JSON envelope. The built-in default profile is never exported:
 * the filter drops {@code builtIn == true} and the reserved id, defensively, so a tampered {@code builtIn}
 * flag or a reserved id cannot slip through. Writing is temp-then-atomic-move, so a failure leaves no
 * partial target file. (Overwrite confirmation for an existing target is the UI's concern.)
 */
public final class AudioProfileExportService {

    private final Supplier<String> exportedAtSupplier;

    public AudioProfileExportService() {
        this(new Supplier<String>() {
            public String get() {
                return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            }
        });
    }

    AudioProfileExportService(Supplier<String> exportedAtSupplier) {
        this.exportedAtSupplier = exportedAtSupplier;
    }

    /** Export exactly the given profiles (after the built-in/reserved filter) to {@code target}. */
    public void export(List<AudioProcessingProfile> profiles, File target)
            throws IOException, AudioProfileTransferException {
        List<AudioProcessingProfile> exportable = exportable(profiles);
        if (exportable.isEmpty()) {
            throw new AudioProfileTransferException(
                    "There are no user profiles to export — the built-in default profile cannot be exported.");
        }
        AudioProfileTransferDocument document = buildDocument(exportable);
        writeAtomically(gson().toJson(document), target);
    }

    /** Keep only real, exportable user profiles: never the built-in default, even if flags were tampered. */
    public static List<AudioProcessingProfile> exportable(List<AudioProcessingProfile> profiles) {
        List<AudioProcessingProfile> result = new ArrayList<AudioProcessingProfile>();
        if (profiles == null) {
            return result;
        }
        for (AudioProcessingProfile profile : profiles) {
            if (profile == null) {
                continue;
            }
            if (profile.isBuiltIn()) {
                continue;
            }
            if (AudioProcessingProfiles.DEFAULT_PROFILE_ID.equals(profile.getId())) {
                continue;
            }
            result.add(profile);
        }
        return result;
    }

    private AudioProfileTransferDocument buildDocument(List<AudioProcessingProfile> profiles) {
        List<TransferProfile> transferProfiles = new ArrayList<TransferProfile>();
        for (AudioProcessingProfile profile : profiles) {
            transferProfiles.add(toTransfer(profile));
        }
        return new AudioProfileTransferDocument(AudioProfileTransferFormat.CURRENT_SCHEMA_VERSION,
                AudioProfileTransferFormat.FORMAT, exportedAtSupplier.get(), transferProfiles);
    }

    private static TransferProfile toTransfer(AudioProcessingProfile profile) {
        List<TransferBlock> blocks = new ArrayList<TransferBlock>();
        for (AudioBlockDefinition block : profile.getBlocks()) {
            Map<String, String> parameters = new LinkedHashMap<String, String>(block.getParameters());
            blocks.add(new TransferBlock(block.getId(), block.getType().name(), block.isEnabled(), parameters));
        }
        // Exported profiles are user profiles: builtIn is always false in the envelope.
        return new TransferProfile(profile.getId(), profile.getName(), false, blocks);
    }

    private void writeAtomically(String json, File target) throws IOException {
        File directory = target.getAbsoluteFile().getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create the export directory " + directory.getAbsolutePath());
        }
        File temp = new File(directory, target.getName() + ".tmp-" + System.nanoTime());
        try {
            Writer writer = new OutputStreamWriter(new java.io.FileOutputStream(temp),
                    Charset.forName("UTF-8"));
            try {
                writer.write(json);
            } finally {
                writer.close();
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }
}

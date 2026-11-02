package com.aresstack.askai.audio.transfer;

import com.aresstack.askai.audio.AudioProfileRepository;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;
import com.aresstack.audio.profile.AudioProcessingProfile;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Imports audio profiles from a versioned JSON envelope. Nothing is written during {@link #preview(File)} —
 * it parses, validates the envelope, migrates if needed, resolves id/name collisions and returns a full
 * plan. {@link #commit(AudioProfileImportPreview)} then persists the planned profiles atomically. Hard
 * protection rules (enforced here, not just in the UI): imports are always {@code builtIn=false}; a reserved
 * or colliding id is replaced with a fresh UUID; the built-in {@code default-speech} is never adopted or
 * overwritten; unknown block types make a profile non-importable rather than being silently dropped.
 */
public final class AudioProfileImportService {

    private final AudioProfileRepository repository;
    private final AudioProfileTransferRepository transferRepository;

    public AudioProfileImportService(AudioProfileRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository must not be null.");
        }
        this.repository = repository;
        this.transferRepository = new AudioProfileTransferRepository(repository);
    }

    public AudioProfileImportPreview preview(File source) throws IOException, AudioProfileTransferException {
        AudioProfileTransferDocument document = migrate(validateEnvelope(parse(readText(source))));
        return buildPreview(document);
    }

    public AudioProfileImportResult commit(AudioProfileImportPreview preview) {
        List<String> importedNames = new ArrayList<String>();
        List<String> importedIds = new ArrayList<String>();
        List<String> failures = new ArrayList<String>();
        for (PlannedProfileImport planned : preview.getImportable()) {
            try {
                transferRepository.commit(planned.getResolvedProfile());
                importedNames.add(planned.getFinalName());
                importedIds.add(planned.getFinalId());
            } catch (Exception ex) {
                failures.add(planned.getFinalName() + ": "
                        + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        return new AudioProfileImportResult(importedNames, importedIds, failures);
    }

    // ------------------------------------------------------------------ parsing & validation

    private static String readText(File source) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("Import file does not exist: " + source);
        }
        return new String(Files.readAllBytes(source.toPath()), Charset.forName("UTF-8"));
    }

    private static AudioProfileTransferDocument parse(String json) throws AudioProfileTransferException {
        try {
            AudioProfileTransferDocument document =
                    new Gson().fromJson(json, AudioProfileTransferDocument.class);
            if (document == null) {
                throw new AudioProfileTransferException("The file is empty or not a valid profile export.");
            }
            return document;
        } catch (JsonParseException ex) {
            throw new AudioProfileTransferException("The file is not valid JSON.", ex);
        }
    }

    private static AudioProfileTransferDocument validateEnvelope(AudioProfileTransferDocument document)
            throws AudioProfileTransferException {
        if (!AudioProfileTransferFormat.FORMAT.equals(document.format)) {
            throw new AudioProfileTransferException(
                    "This is not an AskAI audio-processing-profiles export.");
        }
        if (document.schemaVersion < 1) {
            throw new AudioProfileTransferException("The export is missing a valid schema version.");
        }
        if (document.schemaVersion > AudioProfileTransferFormat.CURRENT_SCHEMA_VERSION) {
            throw new AudioProfileTransferException("This export uses a newer schema version ("
                    + document.schemaVersion + ") than this AskAI build supports ("
                    + AudioProfileTransferFormat.CURRENT_SCHEMA_VERSION + "). Please update AskAI.");
        }
        return document;
    }

    /** Migration seam: bring older supported schema versions up to the current one. No-op for v1. */
    private static AudioProfileTransferDocument migrate(AudioProfileTransferDocument document) {
        // Future: if document.schemaVersion < CURRENT_SCHEMA_VERSION, transform in place and bump.
        return document;
    }

    // ------------------------------------------------------------------ preview building

    private AudioProfileImportPreview buildPreview(AudioProfileTransferDocument document) {
        boolean migrated = document.schemaVersion < AudioProfileTransferFormat.CURRENT_SCHEMA_VERSION;

        Set<String> takenIds = new HashSet<String>();
        Set<String> takenNames = new HashSet<String>();
        for (AudioProcessingProfile existing : repository.findAll()) {
            takenIds.add(existing.getId());
            takenNames.add(existing.getName());
        }
        for (AudioProcessingProfile builtIn : AudioProcessingProfiles.builtIns()) {
            takenIds.add(builtIn.getId()); // reserved: built-in ids are never adopted
        }

        List<PlannedProfileImport> importable = new ArrayList<PlannedProfileImport>();
        List<RejectedProfileImport> rejected = new ArrayList<RejectedProfileImport>();
        Set<String> unknownTypes = new HashSet<String>();

        List<TransferProfile> profiles = document.profiles == null
                ? new ArrayList<TransferProfile>() : document.profiles;
        for (TransferProfile profile : profiles) {
            planOne(profile, takenIds, takenNames, unknownTypes, importable, rejected);
        }

        List<String> warnings = new ArrayList<String>();
        if (migrated) {
            warnings.add("Profiles migrated from schema version " + document.schemaVersion + ".");
        }
        if (!unknownTypes.isEmpty()) {
            warnings.add("Unknown block types present: " + join(unknownTypes)
                    + " — affected profiles cannot be imported.");
        }
        return new AudioProfileImportPreview(document.schemaVersion, migrated, importable, rejected, warnings);
    }

    private void planOne(TransferProfile profile, Set<String> takenIds, Set<String> takenNames,
                         Set<String> unknownTypes, List<PlannedProfileImport> importable,
                         List<RejectedProfileImport> rejected) {
        String displayName = profile.name == null || profile.name.trim().length() == 0
                ? (profile.id == null ? "(unnamed)" : profile.id) : profile.name.trim();
        List<String> reasons = new ArrayList<String>();

        if (profile.name == null || profile.name.trim().length() == 0) {
            reasons.add("Profile has no name.");
        }

        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        List<TransferBlock> transferBlocks = profile.blocks == null
                ? new ArrayList<TransferBlock>() : profile.blocks;
        for (int i = 0; i < transferBlocks.size(); i++) {
            TransferBlock block = transferBlocks.get(i);
            AudioBlockType type = knownType(block == null ? null : block.type);
            if (type == null) {
                String raw = block == null || block.type == null ? "(missing)" : block.type;
                unknownTypes.add(raw);
                reasons.add("Unknown block type: " + raw);
                continue;
            }
            String blockId = block.id == null || block.id.trim().length() == 0
                    ? "block-" + i : block.id.trim();
            Map<String, String> parameters = block.parameters == null
                    ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(block.parameters);
            blocks.add(new AudioBlockDefinition(blockId, type, block.enabled, parameters));
        }

        if (!reasons.isEmpty()) {
            rejected.add(new RejectedProfileImport(displayName, reasons));
            return;
        }

        List<String> profileWarnings = new ArrayList<String>();
        String requestedId = profile.id == null ? "" : profile.id.trim();
        boolean idReassigned = requestedId.length() == 0
                || AudioProcessingProfiles.isBuiltInId(requestedId)
                || takenIds.contains(requestedId);
        String finalId = idReassigned ? UUID.randomUUID().toString() : requestedId;
        takenIds.add(finalId);
        if (idReassigned) {
            profileWarnings.add("Assigned a new id" + (requestedId.length() == 0 ? "" : " (was " + requestedId + ")") + ".");
        }

        String baseName = profile.name.trim();
        String finalName = baseName;
        boolean nameReassigned = false;
        if (takenNames.contains(finalName)) {
            int suffix = 2;
            while (takenNames.contains(baseName + " (" + suffix + ")")) {
                suffix++;
            }
            finalName = baseName + " (" + suffix + ")";
            nameReassigned = true;
            profileWarnings.add("Renamed to \"" + finalName + "\" (name already existed).");
        }
        takenNames.add(finalName);

        AudioProcessingProfile resolved = new AudioProcessingProfile(finalId, finalName, false, blocks);
        importable.add(new PlannedProfileImport(profile.id, profile.name, resolved,
                idReassigned, nameReassigned, profileWarnings));
    }

    private static AudioBlockType knownType(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return null;
        }
        try {
            return AudioBlockType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }
}

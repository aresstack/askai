package com.aresstack.askai.batch.service;

import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioProcessingProfile;

/**
 * The contract for persisted audio-profile identities in batch Markdown documents.
 *
 * <p>A persisted profile id must never be Java {@code null}, empty, whitespace-only, or the literal
 * string {@code "null"} (older writer versions string-concatenated a missing id, producing exactly these
 * corrupt values). {@link #isValidId} is that rule in one place: the writer refuses invalid ids and the
 * parser treats them as absent.
 *
 * <p>{@link #resolveLegacyProfileId} migrates legacy sections that carry no usable id: the visible heading
 * name is resolved against the built-in profiles (Off → {@code off}, Default speech →
 * {@code default-speech}, Crystal voice → {@code crystal-voice}), which are unique by construction. A user
 * profile without a safe id is NOT guessed — it stays id-less and is matched by its exact visible heading
 * on the next upsert (gaining the entry's real id then), so no random or wrong identity is ever invented.
 */
final class AudioProfileIdentityResolver {

    private AudioProfileIdentityResolver() {
    }

    /** @return true only for a usable persisted id: non-null, non-blank, and not the literal "null". */
    static boolean isValidId(String id) {
        if (id == null) {
            return false;
        }
        String trimmed = id.trim();
        return !trimmed.isEmpty() && !"null".equalsIgnoreCase(trimmed);
    }

    /**
     * @return the stable built-in id for a legacy section's visible profile name, or {@code null} when the
     *         name does not unambiguously identify a built-in profile (no guessing for user profiles).
     */
    static String resolveLegacyProfileId(String profileName) {
        if (profileName == null) {
            return null;
        }
        String name = profileName.trim();
        for (AudioProcessingProfile builtIn : AudioProcessingProfiles.builtIns()) {
            if (builtIn.getName().equals(name)) {
                return builtIn.getId();
            }
        }
        return null;
    }
}

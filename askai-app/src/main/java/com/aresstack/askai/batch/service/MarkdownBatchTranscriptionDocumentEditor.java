package com.aresstack.askai.batch.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link BatchTranscriptionDocumentEditor} that parses the document into model/profile sections, upserts by
 * the stable {@code modelId + profileId} key and re-renders. Sections carry hidden id comments
 * ({@code <!-- askai:model-id=... -->} / {@code <!-- askai:profile-id=... -->}) so a renamed profile is
 * still matched; a legacy document without those comments is matched by its exact visible heading and gains
 * the comments on the next upsert. Order is preserved: models and profiles keep first-appearance order and
 * an update never moves a section.
 *
 * <p>Headings inside fenced code blocks ({@code ``` } / {@code ~~~}) are ignored, so Markdown in a
 * transcription is preserved and never mistaken for a section boundary.</p>
 */
public final class MarkdownBatchTranscriptionDocumentEditor implements BatchTranscriptionDocumentEditor {

    private static final String MODEL_HEADING_PREFIX = "# ";
    private static final String PROFILE_HEADING_PREFIX = "## Audio profile:";
    private static final Pattern MODEL_ID = Pattern.compile("^<!--\\s*askai:model-id=(.*?)\\s*-->$");
    private static final Pattern PROFILE_ID = Pattern.compile("^<!--\\s*askai:profile-id=(.*?)\\s*-->$");

    public String upsertTranscription(String markdown, TranscriptionDocumentEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        Document document = parse(markdown == null ? "" : markdown);
        document.normalize();
        document.upsert(entry);
        return document.render();
    }

    // ------------------------------------------------------------------ parsing

    private static Document parse(String markdown) {
        List<String> lines = splitLines(markdown.replace("\r\n", "\n").replace('\r', '\n'));
        List<Integer> modelHeadings = headingIndices(lines, true);
        Document document = new Document();
        int firstModel = modelHeadings.isEmpty() ? lines.size() : modelHeadings.get(0);
        document.preamble = joinTrimmed(sublist(lines, 0, firstModel));
        for (int m = 0; m < modelHeadings.size(); m++) {
            int start = modelHeadings.get(m);
            int end = m + 1 < modelHeadings.size() ? modelHeadings.get(m + 1) : lines.size();
            document.models.add(parseModel(sublist(lines, start, end)));
        }
        return document;
    }

    private static ModelSection parseModel(List<String> block) {
        ModelSection model = new ModelSection();
        model.modelName = block.get(0).substring(MODEL_HEADING_PREFIX.length()).trim();
        List<Integer> profileHeadings = headingIndices(block, false);
        int firstProfile = profileHeadings.isEmpty() ? block.size() : profileHeadings.get(0);
        String id = firstMatch(sublist(block, 1, firstProfile), MODEL_ID);
        if (id != null) {
            model.modelId = id;
            model.hasIdComment = true;
        } else {
            model.modelId = model.modelName;
            model.hasIdComment = false;
        }
        for (int p = 0; p < profileHeadings.size(); p++) {
            int start = profileHeadings.get(p);
            int end = p + 1 < profileHeadings.size() ? profileHeadings.get(p + 1) : block.size();
            model.profiles.add(parseProfile(sublist(block, start, end)));
        }
        return model;
    }

    private static ProfileSection parseProfile(List<String> block) {
        ProfileSection profile = new ProfileSection();
        profile.profileName = block.get(0).substring(PROFILE_HEADING_PREFIX.length()).trim();
        List<String> rest = new ArrayList<String>(sublist(block, 1, block.size()));
        for (int i = 0; i < rest.size(); i++) {
            String id = match(rest.get(i), PROFILE_ID);
            if (id != null) {
                if (AudioProfileIdentityResolver.isValidId(id)) {
                    profile.profileId = id;
                    profile.hasIdComment = true;
                }
                // an invalid id (empty / "null") is dropped here and migrated below
                rest.remove(i);
                break;
            }
        }
        if (!profile.hasIdComment) {
            // Legacy migration: resolve built-in profiles unambiguously by their visible heading
            // (Off -> off, Default speech -> default-speech, ...). User profiles are never guessed;
            // they stay id-less and are matched by their exact heading on the next upsert.
            String resolved = AudioProfileIdentityResolver.resolveLegacyProfileId(profile.profileName);
            if (resolved != null) {
                profile.profileId = resolved;
                profile.hasIdComment = true;
            }
        }
        profile.body = joinTrimmed(rest);
        return profile;
    }

    /** Line indices of model ({@code isModel}) or profile headings, ignoring lines inside fenced code. */
    private static List<Integer> headingIndices(List<String> lines, boolean isModel) {
        List<Integer> result = new ArrayList<Integer>();
        boolean inFence = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            if (isModel ? isModelHeading(lines.get(i)) : isProfileHeading(lines.get(i))) {
                result.add(i);
            }
        }
        return result;
    }

    private static boolean isModelHeading(String line) {
        return line.startsWith(MODEL_HEADING_PREFIX);
    }

    private static boolean isProfileHeading(String line) {
        return line.startsWith(PROFILE_HEADING_PREFIX);
    }

    private static String firstMatch(List<String> lines, Pattern pattern) {
        for (String line : lines) {
            String value = match(line, pattern);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String match(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line.trim());
        return matcher.matches() ? matcher.group(1).trim() : null;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(text.substring(start));
        return lines;
    }

    private static List<String> sublist(List<String> lines, int from, int to) {
        return new ArrayList<String>(lines.subList(Math.min(from, lines.size()), Math.min(to, lines.size())));
    }

    /** Drop leading/trailing blank lines and join with {@code \n}, preserving inner content verbatim. */
    private static String joinTrimmed(List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).trim().isEmpty()) {
            start++;
        }
        while (end > start && lines.get(end - 1).trim().isEmpty()) {
            end--;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ document model

    private static final class Document {
        private String preamble = "";
        private final List<ModelSection> models = new ArrayList<ModelSection>();

        /**
         * Bring a parsed (possibly legacy-corrupted) document into canonical shape:
         * per stable model id exactly one model section — duplicates created by the old append-only writer
         * are merged into the first occurrence, their profiles carried over in order. Within a model,
         * duplicate profiles collapse deterministically: the LAST fully read section wins (its name and
         * body), while keeping the FIRST occurrence's position; distinct profiles keep their order.
         * Merging without an id comment happens only via the parse fallback (visible heading), which is
         * exact and therefore unambiguous. No transcription body of a surviving section is dropped.
         */
        private void normalize() {
            List<ModelSection> canonical = new ArrayList<ModelSection>();
            for (ModelSection model : models) {
                ModelSection existing = null;
                for (ModelSection candidate : canonical) {
                    if (candidate.modelId.equals(model.modelId)) {
                        existing = candidate;
                        break;
                    }
                }
                if (existing == null) {
                    canonical.add(model);
                } else {
                    existing.hasIdComment |= model.hasIdComment;
                    existing.profiles.addAll(model.profiles);
                }
            }
            for (ModelSection model : canonical) {
                model.dedupProfiles();
            }
            models.clear();
            models.addAll(canonical);
        }

        private void upsert(TranscriptionDocumentEntry entry) {
            ModelSection model = findModel(entry.getModelId());
            if (model == null) {
                model = new ModelSection();
                model.modelName = entry.getModelId(); // a model tag is its own display name
                models.add(model);
            }
            model.modelId = entry.getModelId();
            model.hasIdComment = true;
            model.upsert(entry);
        }

        private ModelSection findModel(String modelId) {
            for (ModelSection model : models) {
                if (model.modelId.equals(modelId)) { // parsed id = comment value, or the visible tag (legacy)
                    return model;
                }
            }
            return null;
        }

        private String render() {
            List<String> parts = new ArrayList<String>();
            if (!preamble.isEmpty()) {
                parts.add(preamble);
            }
            for (ModelSection model : models) {
                parts.add(model.render());
            }
            if (parts.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    builder.append("\n\n");
                }
                builder.append(parts.get(i));
            }
            return builder.append('\n').toString();
        }
    }

    private static final class ModelSection {
        private String modelId = "";
        private String modelName = "";
        private boolean hasIdComment;
        private final List<ProfileSection> profiles = new ArrayList<ProfileSection>();

        private void upsert(TranscriptionDocumentEntry entry) {
            ProfileSection profile = findProfile(entry);
            if (profile == null) {
                profile = new ProfileSection();
                profiles.add(profile);
            }
            profile.profileId = entry.getProfileId();
            profile.profileName = entry.getProfileName();
            profile.hasIdComment = true;
            profile.body = entry.getTranscription().trim();
        }

        /** Collapse duplicate profiles: same identity → last section wins, first position kept. */
        private void dedupProfiles() {
            List<ProfileSection> canonical = new ArrayList<ProfileSection>();
            for (ProfileSection profile : profiles) {
                ProfileSection existing = null;
                for (ProfileSection candidate : canonical) {
                    if (sameIdentity(candidate, profile)) {
                        existing = candidate;
                        break;
                    }
                }
                if (existing == null) {
                    canonical.add(profile);
                } else {
                    existing.profileId = profile.profileId;
                    existing.profileName = profile.profileName;
                    existing.hasIdComment = profile.hasIdComment;
                    existing.body = profile.body;
                }
            }
            profiles.clear();
            profiles.addAll(canonical);
        }

        /** Same stable id, or — for id-less legacy sections — the same exact visible heading. */
        private static boolean sameIdentity(ProfileSection a, ProfileSection b) {
            if (a.hasIdComment && b.hasIdComment) {
                return a.profileId.equals(b.profileId);
            }
            if (!a.hasIdComment && !b.hasIdComment) {
                return a.profileName.equals(b.profileName);
            }
            return false;
        }

        private ProfileSection findProfile(TranscriptionDocumentEntry entry) {
            for (ProfileSection profile : profiles) { // stable id first
                if (profile.hasIdComment && profile.profileId != null
                        && profile.profileId.equals(entry.getProfileId())) {
                    return profile;
                }
            }
            for (ProfileSection profile : profiles) { // legacy: exact visible heading
                if (!profile.hasIdComment && profile.profileName.equals(entry.getProfileName())) {
                    return profile;
                }
            }
            return null;
        }

        private String render() {
            StringBuilder builder = new StringBuilder();
            builder.append(MODEL_HEADING_PREFIX).append(modelName);
            if (AudioProfileIdentityResolver.isValidId(modelId)) {
                builder.append("\n\n<!-- askai:model-id=").append(modelId.trim()).append(" -->");
            }
            for (ProfileSection profile : profiles) {
                builder.append("\n\n").append(profile.render());
            }
            return builder.toString();
        }
    }

    private static final class ProfileSection {
        private String profileId;
        private String profileName = "";
        private boolean hasIdComment;
        private String body = "";

        private String render() {
            StringBuilder builder = new StringBuilder();
            builder.append(PROFILE_HEADING_PREFIX).append(' ').append(profileName);
            // Only a valid stable id is ever serialized. An id-less legacy section keeps no comment (it
            // is matched by its heading on the next upsert) — previously a null id was string-concatenated
            // here, writing the literal "profile-id=null" into the document.
            if (AudioProfileIdentityResolver.isValidId(profileId)) {
                builder.append("\n\n<!-- askai:profile-id=").append(profileId.trim()).append(" -->");
            }
            if (!body.isEmpty()) {
                builder.append("\n\n").append(body);
            }
            return builder.toString();
        }
    }
}

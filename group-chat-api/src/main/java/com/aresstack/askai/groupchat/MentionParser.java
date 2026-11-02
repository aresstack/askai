package com.aresstack.askai.groupchat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses {@code @Handle} mentions from a Markdown message body.
 *
 * <p>A mention is a word that starts with {@code @} and is followed by one or more non-whitespace
 * characters.  Trailing punctuation adjacent to the token is stripped when matching against
 * participant handles.  Matching is first attempted against the participant's
 * {@link Participant#getMentionHandle() mentionHandle} (case-insensitive), then against the
 * {@link Participant#getDisplayName() displayName} for single-word names (backward compat).</p>
 *
 * <p>The special token {@code @AskAI} (case-insensitive) refers to the room bot and must not be
 * resolved to a human participant.</p>
 */
public final class MentionParser {

    /** The canonical bot mention token (case-insensitive match applies). */
    public static final String BOT_MENTION = "@AskAI";

    /** The reserved mention handle for the logical bot — never assigned to a human participant. */
    public static final String BOT_HANDLE = "AskAI";

    private MentionParser() {
    }

    /**
     * Extract the participant IDs that are mentioned in {@code markdown}.
     *
     * <p>Resolution order for each {@code @token}: first try the participant's
     * {@link Participant#getMentionHandle() mentionHandle} (case-insensitive), then fall back to
     * the {@link Participant#getDisplayName() displayName} for single-word names.</p>
     *
     * @param markdown    the raw message text
     * @param participants the participants currently in the room
     * @return an unmodifiable list of participant IDs that are mentioned; never {@code null}
     */
    public static List<String> extractMentionedIds(String markdown, List<Participant> participants) {
        if (markdown == null || markdown.isEmpty() || participants == null || participants.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> found = new ArrayList<String>();
        for (String token : mentionTokens(markdown)) {
            String name = token.substring(1); // strip leading '@'
            for (Participant p : participants) {
                if (found.contains(p.getParticipantId())) {
                    continue;
                }
                // Primary: match against mentionHandle
                if (p.getMentionHandle().equalsIgnoreCase(name)) {
                    found.add(p.getParticipantId());
                    continue;
                }
                // Fallback: match against display name for single-word names
                if (!p.getDisplayName().contains(" ")
                        && p.getDisplayName().equalsIgnoreCase(name)) {
                    found.add(p.getParticipantId());
                }
            }
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * Returns {@code true} when the message contains an explicit {@code @AskAI} mention
     * (case-insensitive).
     */
    public static boolean mentionsBot(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return false;
        }
        for (String token : mentionTokens(markdown)) {
            if (BOT_MENTION.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns all raw {@code @Token} strings found in the text (including the leading {@code @}).
     */
    public static List<String> mentionTokens(String text) {
        List<String> tokens = new ArrayList<String>();
        String[] words = text.split("\\s+");
        for (String word : words) {
            if (word.startsWith("@") && word.length() > 1) {
                // Strip trailing punctuation so "@Maria," matches "@Maria".
                String stripped = stripTrailingPunctuation(word);
                if (stripped.length() > 1) {
                    tokens.add(stripped);
                }
            }
        }
        return Collections.unmodifiableList(tokens);
    }

    /**
     * Compute a mention handle that is unique within the given set of already-assigned handles.
     *
     * <p>The base handle is derived from {@code displayName} by stripping non-alphanumeric
     * characters.  If the result collides with an existing handle (case-insensitive) a numeric
     * suffix is appended until a unique value is found.  The reserved {@link #BOT_HANDLE}
     * ({@code "AskAI"}, case-insensitive) is never returned.</p>
     *
     * @param displayName    the participant's display name
     * @param existingHandles handles already in use in the room (may include the bot handle)
     * @return a non-null, non-empty handle that is unique among {@code existingHandles}
     */
    public static String computeUniqueHandle(String displayName, List<String> existingHandles) {
        String base = (displayName != null ? displayName : "User")
                .replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty()) {
            base = "User";
        }
        String candidate = base;
        int suffix = 2;
        while (isReservedOrTaken(candidate, existingHandles)) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private static boolean isReservedOrTaken(String handle, List<String> existingHandles) {
        if (BOT_HANDLE.equalsIgnoreCase(handle)) {
            return true;
        }
        for (String existing : existingHandles) {
            if (existing.equalsIgnoreCase(handle)) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingPunctuation(String word) {
        int end = word.length();
        while (end > 1 && isPunctuation(word.charAt(end - 1))) {
            end--;
        }
        return word.substring(0, end);
    }

    private static boolean isPunctuation(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ':' || c == ';';
    }
}

